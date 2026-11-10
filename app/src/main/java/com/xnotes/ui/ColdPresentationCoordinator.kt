package com.xnotes.ui

import androidx.annotation.MainThread
import androidx.compose.runtime.snapshotFlow
import com.xnotes.R
import com.xnotes.core.util.DocumentKind
import com.xnotes.mindmap.MapWorkspace
import com.xnotes.mindmap.MapRecoveryCheckpoint
import com.xnotes.mindmap.PreparedColdMixedNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One UI host's finite publication and bootstrap work. The retained owner stores only routing facts. */
@MainThread
internal class ColdPresentationCoordinator(
    private val editor:Editor,
    private val maps:MapWorkspace,
    private val bootstrap:ColdPresentationBootstrap,
    private val store:LastPresentationStore=LastPresentationStore(editor.view.context.filesDir),
) {
    internal data class Frame(val homePanes:Set<Pane> = emptySet(),val parked:Boolean=false,val foreignForeground:Boolean=false)
    private var host=bootstrap.bind()
    private var active=true
    private var publication=Any()
    private var frameProvider:()->Frame={Frame()}
    internal var onRestored:((LastPresentation)->Unit)?=null

    fun bindFrame(provider:()->Frame) {frameProvider=provider}
    private fun hostCurrent()=active && bootstrap.current(host)

    /** Every accepted front-door intent retires a cold preparation before the new IO starts. */
    fun retireIntent() {
        if(!active) return
        val preparing=!bootstrap.completed
        bootstrap.retire();host=bootstrap.bind()
        publication=Any();store.reserve()
        if(preparing) maps.cancelOpen()
    }

    suspend fun prepareDeparture(current:()->Boolean):PreparedHomeGate? {
        if(!hostCurrent() || !current()) return null
        val epoch=Any().also {publication=it}
        val ticket=store.reserve()
        val gate=PreparedHomeGate.prepare(store,ticket) {bootstrap.presented(LastPresentation.Home)} ?: return null
        if(!hostCurrent() || publication!==epoch || !current()) {gate.close();return null}
        return gate
    }

    suspend fun bootstrap(retry:Boolean=false):Boolean {
        if(retry) {host=bootstrap.bind(retry=true);publication=Any();store.reserve()}
        val request=host
        fun current()=active && bootstrap.current(request)
        try {
            if(request.retained) {
                val layout=maps.splitPresentation.layout ?: maps.splitPresentation.returnLayout
                val retained=bootstrap.presentation
                val priorProblem=bootstrap.problem
                val success=when {
                    layout!=null->com.xnotes.restoreMixedNativeSession(editor,maps)
                    maps.current!=null->true
                    retained is LastPresentation.Native->restoreNative(retained,::current) {true}
                    retained==null && bootstrap.permitsLegacyMap && priorProblem==null &&
                        bootstrap.observation?.read===LastPresentationRead.Absent->{
                        editor.restoreSession();if(current()) editor.restoreSplitWorkspace();current()
                    }
                    retained==null && priorProblem!=null->false
                    else->true
                }
                if(current() && !success) bootstrap.finish(request,retained,priorProblem ?: ColdPresentationProblem.UNAVAILABLE)
                return success && current()
            }
            val observed=withContext(Dispatchers.IO) {store.observe()}
            if(!current() || !bootstrap.observed(request,observed)) return false
            val read=observed.read
            if(read===LastPresentationRead.Absent) {
                editor.restoreSession()
                if(!current()) return false
                editor.restoreSplitWorkspace()
                return bootstrap.finish(request,null)
            }
            if(read===LastPresentationRead.Invalid) {
                bootstrap.finish(request,null,ColdPresentationProblem.INVALID);return false
            }
            val value=(read as LastPresentationRead.Ready).value
            fun finalProof()=current() && store.matches(observed)
            val adopted=when(value) {
                LastPresentation.Home->finalProof()
                is LastPresentation.MapOnly->maps.restoreColdMapOnly(value,::current,::finalProof)
                is LastPresentation.Native->restoreNative(value,::current,::finalProof)
                is LastPresentation.Mixed->{
                    val mixed=value.value
                    if(mixed.layout.nativeHome || mixed.nativeHint.kind!=DocumentKind.NOTE) false else
                        maps.restoreColdMixed(mixed,prepareNative={valid->
                            val target=if(mixed.layout.nativePane==Pane.PRIMARY) editor else editor.secondaryPane()
                            val cap=target.prepareNoteSessionRestore(mixed.nativeHint)
                            cap?.let {PreparedColdMixedNative(valid,{it.verify()},{
                                if(!valid() || !it.claimForAdoption()) false else {
                                    it.applyAdoption(mixed.nativeView)
                                    editor.mixedPresentationActive=true
                                    editor.focusMixedNativePane(target.pane)
                                    true
                                }
                            },{it.close()})}
                        },stillCurrent=::current,beforeAdopt=::finalProof)
                }
            }
            if(current()) {
                bootstrap.finish(request,value.takeIf {adopted},if(adopted) null else ColdPresentationProblem.UNAVAILABLE)
                if(adopted) try {onRestored?.invoke(value)} catch(error:Exception) {
                    android.util.Log.w("xnotes.presentation","Restored workspace UI notification failed",error)
                }
            }
            return adopted && current()
        } catch(cancelled:CancellationException) {
            // A later front door cancels this request's Map child without cancelling this UI host.
            currentCoroutineContext().ensureActive()
            return false
        } catch(_:Exception) {
            if(current()) bootstrap.finish(request,null,ColdPresentationProblem.UNAVAILABLE)
            return false
        }
    }

    private suspend fun restoreNative(value:LastPresentation.Native,current:()->Boolean,finalProof:()->Boolean):Boolean {
        val cap=editor.prepareColdNativePresentation(value,current) ?: return false
        return try {
            if(!cap.verify() || !current() || !finalProof() || !cap.claimForAdoption()) false
            else {cap.applyAdoption();editor.mixedPresentationActive=false;true}
        } finally {cap.close()}
    }

    /** Both Main and the cold-host tests use this same actual-presentation producer. */
    fun observe(scope:CoroutineScope):Job=scope.launch {
        snapshotFlow {
            val entry=maps.current
            listOf(frameProvider(),entry,maps.visible,maps.opening,maps.splitPresentation.layout,maps.splitPresentation.generation,
                entry?.session?.interactionEpoch,entry?.session?.contentRevision,entry?.presentation?.ordinaryCameraVersion,
                editor.presentationVersion,editor.secondary?.presentationVersion,
                editor.splitRatio,editor.splitSideBySide,editor.splitReversed,editor.focusedPane)
        }.collectLatest {
            val intent=publication
            delay(CHECKPOINT_SETTLE_MS)
            if(publication===intent) checkpoint()
        }
    }

    internal class OutgoingCheckpoint internal constructor(val map:MapRecoveryCheckpoint?,private val check:()->Boolean) {
        fun current()=check()
    }

    /** Parked buffers still get local durability; the returned Native description is never published. */
    suspend fun checkpointOutgoing(stillCurrent:()->Boolean,localOnly:Boolean=false):OutgoingCheckpoint? {
        if(!hostCurrent() || !stillCurrent()) return null
        val entry=maps.current
        val model=entry?.session?.document
        val revision=entry?.session?.contentRevision
        val epoch=entry?.session?.interactionEpoch
        val nativeIdentity=editor.captureNativePresentationIdentity()
        fun current()=hostCurrent() && stillCurrent() && editor.matchesNativePresentationIdentity(nativeIdentity) &&
            maps.current===entry && (entry==null || entry.lease.alive && entry.session.document===model &&
                entry.session.contentRevision==revision && entry.session.interactionEpoch==epoch)
        val native=if(editor.openPanes.any {it.noteOpen}) {
            editor.prepareNativePresentationCheckpoint(emptySet(),::current) ?: run {failedCheckpoint(::current);return null}
        } else null
        val map=if(entry!=null && model!=null) {
            entry.session.awaitRecoveryCheckpoint(localOnly=localOnly) {current() && native?.current()!=false}
                ?: run {failedCheckpoint(::current);return null}
        } else null
        val result=OutgoingCheckpoint(map) {current() && native?.current()!=false && map?.current()!=false}
        return result.takeIf {it.current()}
    }

    suspend fun checkpoint():Boolean {
        if(!hostCurrent() || !bootstrap.completed || bootstrap.problem!=null || maps.opening) return false
        if(bootstrap.observation==null && bootstrap.presentation==null && maps.current==null && editor.openPanes.none {it.noteOpen})
            return false
        val epoch=Any().also {publication=it}
        val ticket=store.reserve()
        val frame=frameProvider()
        val entry=maps.current
        val shown=maps.visible
        val route=maps.sourceNavigationGeneration
        val layout=maps.splitPresentation.layout
        val layoutGeneration=maps.splitPresentation.generation
        val nativeIdentity=editor.captureNativePresentationIdentity()
        val mapModel=entry?.session?.document
        val mapRevision=entry?.session?.contentRevision
        val mapEpoch=entry?.session?.interactionEpoch
        val cameraVersion=entry?.presentation?.ordinaryCameraVersion
        val nativeVersion=editor.presentationVersion
        val secondVersion=editor.secondary?.presentationVersion
        val nativeLayout=listOf(editor.splitRatio,editor.splitSideBySide,editor.splitReversed,editor.focusedPane)
        fun current()=hostCurrent() && publication===epoch && frameProvider()==frame && maps.current===entry &&
            maps.visible==shown && !maps.opening && maps.sourceNavigationGeneration==route &&
            maps.splitPresentation.layout==layout && maps.splitPresentation.generation==layoutGeneration &&
            editor.presentationVersion==nativeVersion && editor.secondary?.presentationVersion==secondVersion &&
            listOf(editor.splitRatio,editor.splitSideBySide,editor.splitReversed,editor.focusedPane)==nativeLayout &&
            editor.matchesNativePresentationIdentity(nativeIdentity) && (entry==null || entry.lease.alive &&
                entry.session.document===mapModel && entry.session.contentRevision==mapRevision && entry.session.interactionEpoch==mapEpoch &&
                entry.presentation.ordinaryCameraVersion==cameraVersion)
        val parked=frame.parked || frame.foreignForeground || frame.homePanes.isNotEmpty() ||
            shown && entry!=null && (layout==null || layout.nativeHome)
        val outgoing=if(parked) checkpointOutgoing(::current) ?: return false else null
        var proof:()->Boolean=::current
        val value=when {
            frame.parked || frame.foreignForeground->{proof={current() && outgoing?.current()!=false};LastPresentation.Home}
            shown && entry!=null->{
                var note:Editor.WorkingCheckpoint?=null
                if(layout!=null && !layout.nativeHome && layout.nativePane !in frame.homePanes) {
                    val target=if(layout.nativePane==Pane.PRIMARY) editor else editor.secondary ?: return false
                    if(!target.noteOpen || target.canvasOpen || target.missingDocument!=null) return false
                    note=target.checkpointNoteWorkingSession(::current) ?: return failedCheckpoint(::current)
                }
                val native=note
                val map=outgoing?.map ?: entry.session.awaitRecoveryCheckpoint {current() && native?.current()!=false}
                    ?: return failedCheckpoint(::current)
                proof={current() && map.current() && native?.current()!=false && outgoing?.current()!=false}
                val ref=SplitSourceRef(map.documentId,map.uri.toString(),entry.title)
                val camera=entry.presentation.arrangementCamera(entry.session)
                if(native!=null && layout!=null) LastPresentation.Mixed(ColdMixedPresentation(ref,native.hint,layout,camera,
                    nativeView=native.noteView))
                else LastPresentation.MapOnly(ref,camera,layout?.copy(nativeHome=true))
            }
            editor.openPanes.any {it.noteOpen && it.pane !in frame.homePanes}->{
                val homes=frame.homePanes+listOfNotNull(layout?.mapPane?.takeIf {entry==null})
                val native=editor.prepareNativePresentationCheckpoint(homes,::current)
                    ?: return failedCheckpoint(::current)
                proof={current() && native.current() && outgoing?.current()!=false}
                if(layout!=null && entry==null) native.value.copy(layout=ColdNativeLayout(layout.focusedPane,
                    layout.ratio,layout.sideBySide,layout.reversed)) else native.value
            }
            else->{proof={current() && outgoing?.current()!=false};LastPresentation.Home}
        }
        if(!proof()) return false
        val attempt=store.begin(ticket,value)
        return try {
            if(attempt.prepare()!=LastPresentationPrepare.Ready || !proof()) return failedCheckpoint(::current)
            val written=attempt.commit(proof)==LastPresentationWrite.Written
            if(written) {
                if(value is LastPresentation.Mixed) maps.splitPresentation.rememberNativeHint(value.value.nativeHint)
                bootstrap.presented(value)
            }
            else failedCheckpoint(::current)
            written
        } finally {attempt.close()}
    }

    private fun failedCheckpoint(current:()->Boolean):Boolean {
        if(current() && editor.message==null) editor.message=editor.view.context.getString(R.string.err_save_recovery_session)
        return false
    }

    suspend fun resetLayout():Boolean {
        val observed=bootstrap.observation ?: return false
        val request=host
        val ticket=store.reserve()
        val attempt=if(observed.read===LastPresentationRead.Invalid) store.beginReset(ticket,observed)
            else store.begin(ticket,LastPresentation.Home)
        return try {
            if(attempt.prepare()!=LastPresentationPrepare.Ready) false
            else (attempt.commit {active && bootstrap.current(request) && store.matches(observed) && !maps.visible && editor.openPanes.none {it.noteOpen}}==
                LastPresentationWrite.Written).also {if(it) bootstrap.finish(request,LastPresentation.Home)}
        } finally {attempt.close()}
    }

    fun close() {active=false;publication=Any()}
    private companion object {const val CHECKPOINT_SETTLE_MS=180L}
}
