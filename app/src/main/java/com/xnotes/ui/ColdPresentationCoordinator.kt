package com.xnotes.ui

import androidx.annotation.MainThread
import com.xnotes.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One UI host's finite publication and bootstrap work. The retained owner stores only routing facts. */
@MainThread
internal class ColdPresentationCoordinator(
    private val editor:Editor,
    private val bootstrap:ColdPresentationBootstrap,
    private val store:LastPresentationStore=LastPresentationStore(editor.view.context.filesDir),
) {
    internal data class Frame(val homePanes:Set<Pane> = emptySet(),val parked:Boolean=false)
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
        bootstrap.retire();host=bootstrap.bind()
        publication=Any();store.reserve()
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
                val retained=bootstrap.presentation
                val priorProblem=bootstrap.problem
                val success=when {
                    retained is LastPresentation.Native->restoreNative(retained,::current) {true}
                    retained==null && bootstrap.permitsLegacySession && priorProblem==null &&
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
                is LastPresentation.Native->restoreNative(value,::current,::finalProof)
            }
            if(current()) {
                bootstrap.finish(request,value.takeIf {adopted},if(adopted) null else ColdPresentationProblem.UNAVAILABLE)
                if(adopted) try {onRestored?.invoke(value)} catch(error:Exception) {
                    android.util.Log.w("xnotes.presentation","Restored workspace UI notification failed",error)
                }
            }
            return adopted && current()
        } catch(cancelled:CancellationException) {
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
            else {cap.applyAdoption();true}
        } finally {cap.close()}
    }

    internal class OutgoingCheckpoint internal constructor(private val check:()->Boolean) {
        fun current()=check()
    }

    /** Parked buffers still get local durability; the returned Native description is never published. */
    private suspend fun checkpointOutgoing(stillCurrent:()->Boolean):OutgoingCheckpoint? {
        if(!hostCurrent() || !stillCurrent()) return null
        val nativeIdentity=editor.captureNativePresentationIdentity()
        fun current()=hostCurrent() && stillCurrent() && editor.matchesNativePresentationIdentity(nativeIdentity)
        val native=if(editor.openPanes.any {it.noteOpen}) {
            native(emptySet(),::current) ?: return null
        } else null
        val result=OutgoingCheckpoint {current() && native?.current()!=false}
        return result.takeIf {it.current()}
    }

    private suspend fun native(home:Set<Pane>,current:()->Boolean):Editor.PreparedNativePresentationCheckpoint? =
        when(val result=editor.prepareNativePresentationCheckpointResult(home,current)) {
            is NativeCheckpointResult.Ready->result.checkpoint
            NativeCheckpointResult.Superseded->null
            NativeCheckpointResult.Deferred->null
            is NativeCheckpointResult.Failed->{failedCheckpoint(current);null}
        }
    suspend fun checkpoint():Boolean {
        if(!hostCurrent() || !bootstrap.completed || bootstrap.problem!=null) return false
        if(bootstrap.observation==null && bootstrap.presentation==null && editor.openPanes.none {it.noteOpen})
            return false
        val epoch=Any().also {publication=it}
        val ticket=store.reserve()
        val frame=frameProvider()
        val nativeIdentity=editor.captureNativePresentationIdentity()
        val nativeVersion=editor.presentationVersion
        val secondVersion=editor.secondary?.presentationVersion
        val nativeLayout=listOf(editor.splitRatio,editor.splitSideBySide,editor.splitReversed,editor.focusedPane)
        fun current()=hostCurrent() && publication===epoch && frameProvider()==frame &&
            editor.presentationVersion==nativeVersion && editor.secondary?.presentationVersion==secondVersion &&
            listOf(editor.splitRatio,editor.splitSideBySide,editor.splitReversed,editor.focusedPane)==nativeLayout &&
            editor.matchesNativePresentationIdentity(nativeIdentity)
        val parked=frame.parked || frame.homePanes.isNotEmpty()
        val outgoing=if(parked) checkpointOutgoing(::current) ?: return false else null
        var proof:()->Boolean=::current
        val value=when {
            frame.parked->{proof={current() && outgoing?.current()!=false};LastPresentation.Home}
            editor.openPanes.any {it.noteOpen && it.pane !in frame.homePanes}->{
                val native=native(frame.homePanes,::current) ?: return false
                proof={current() && native.current() && outgoing?.current()!=false}
                native.value
            }
            else->{proof={current() && outgoing?.current()!=false};LastPresentation.Home}
        }
        if(!proof()) return false
        val attempt=store.begin(ticket,value)
        return try {
            when(attempt.prepare()) {
                LastPresentationPrepare.Failed->return failedCheckpoint(::current)
                LastPresentationPrepare.Changed->return false
                LastPresentationPrepare.PreservedInvalid->return failedCheckpoint(::current)
                LastPresentationPrepare.Ready->Unit
            }
            when(attempt.commit(proof)) {
                LastPresentationWrite.Written->{bootstrap.presented(value);true}
                LastPresentationWrite.Changed->false
                LastPresentationWrite.PreservedInvalid->failedCheckpoint(::current)
                LastPresentationWrite.Failed->failedCheckpoint(::current)
            }
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
            else (attempt.commit {active && bootstrap.current(request) && store.matches(observed) && editor.openPanes.none {it.noteOpen}}==
                LastPresentationWrite.Written).also {if(it) bootstrap.finish(request,LastPresentation.Home)}
        } finally {attempt.close()}
    }

    fun close() {active=false;publication=Any()}
}
