package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.xnotes.R
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class MapWorkspaceFailure {UNAVAILABLE,CHANGED}

/** Request-scoped Native preparation; its owner keeps all decoding and adoption details. */
internal class PreparedColdMixedNative(
    private val check:()->Boolean,
    private val verifyPrepared:suspend ()->Boolean,
    private val adopt:()->Boolean,
    private val release:suspend ()->Unit,
) {
    fun current()=check()
    suspend fun verify()=current() && verifyPrepared() && current()
    fun commit()=current() && adopt()
    suspend fun close()=release()
}

/** Main-window document presentation. The existing broker remains the only map editing owner. */
@MainThread
internal class MapWorkspace(context:Context,private val scope:CoroutineScope) {
    val splitPresentation=MapSplitPresentation()
    internal class Entry internal constructor(val uri:Uri,val title:String,val lease:MapDocumentLease,
        val presentation:MapPresentationState=MapPresentationState(),internal var parkingCheck:()->Boolean={true}) {
        val session get()=lease.session
        // Runtime map data only. A retained Entry must never keep an old native Editor/Activity.
        internal var sourceReturn by mutableStateOf<MapSourceReturnBookmark?>(null)
        internal var flushNativeOnPresent by mutableStateOf(true)
        // A Canvas source return parks its dirty model for this presentation only.
        internal var skipNativePersistOnPresent by mutableStateOf(false)
    }
    private val app=context.applicationContext
    private var active=true
    private var generation=0L
    private var work:Job?=null
    private var openOperation:Any?=null
    private val acceptedOpens=mutableSetOf<Job>()
    private var hostBinding:Any?=null
    internal var onPresentationIntent:(()->Unit)?=null
    internal var prepareHostDeparture:(suspend (()->Boolean)->com.xnotes.ui.PreparedHomeGate?)?=null
    private var disposal:Job?=null
    private val visited=linkedMapOf<String,Entry>()
    private var sourceFrames by mutableStateOf<List<MapSourceNavigationFrame>>(emptyList())
    private val releases=mutableSetOf<Job>()
    internal val sourceNavigationGeneration:Long get()=generation
    internal val sourceReturnToken:Any? get()=sourceFrames.lastOrNull()?.token
    val canReturnSource:Boolean get()=active && visible && sourceFrames.lastOrNull()?.destination===current
    val sourceParentTitle:String? get()=sourceFrames.lastOrNull()?.origin?.title
    private fun ownedEntries()=(visited.values+listOfNotNull(current)).distinctBy {it.lease}
    private fun trackRelease(job:Job):Job {
        releases+=job;job.invokeOnCompletion {releases-=job};return job
    }
    private fun retire(entries:List<Entry>,preserveRecoveryFor:Entry?=null):List<Job> = entries.distinctBy {it.lease}.map {entry->
        entry.presentation.closeSources();entry.presentation.visible=false;entry.parkingCheck={false}
        trackRelease(if(entry===preserveRecoveryFor) entry.lease.closeUnadoptedSource() else entry.lease.close())
    }
    var current by mutableStateOf<Entry?>(null);private set
    var visible by mutableStateOf(false);private set
    var opening by mutableStateOf(false);private set
    internal var nativeSplitOperation by mutableStateOf(false);private set
    var failure by mutableStateOf<MapWorkspaceFailure?>(null);private set

    /** Cold restore uses the actual Map load proof and a protected local Native cache together. */
    internal suspend fun restoreColdMixed(value:com.xnotes.ui.ColdMixedPresentation,
        prepareNative:suspend (()->Boolean)->PreparedColdMixedNative?,stillCurrent:()->Boolean={true},
        beforeAdopt:()->Boolean={true}):Boolean =
        restoreCold(value.map,value.mapCamera,MapOpenPresentation.Split(value.layout),value.nativeHint,
            prepareNative,stillCurrent,beforeAdopt)

    /** A Home slot is explicit; neither a parked NOTE nor a Canvas cache is read here. */
    internal suspend fun restoreColdMapOnly(value:com.xnotes.ui.LastPresentation.MapOnly,
        stillCurrent:()->Boolean={true},beforeAdopt:()->Boolean={true}):Boolean {
        if(value.homeLayout?.nativeHome==false) return false
        return restoreCold(value.map,value.camera,value.homeLayout?.let {MapOpenPresentation.Split(it)}
            ?: MapOpenPresentation.Single,null,null,stillCurrent,beforeAdopt)
    }

    private suspend fun restoreCold(ref:com.xnotes.ui.SplitSourceRef,camera:MapPresentationCamera?,
        presentation:MapOpenPresentation,hint:MapNativeRestoreHint?,
        prepareNative:(suspend (()->Boolean)->PreparedColdMixedNative?)?,stillCurrent:()->Boolean,
        beforeAdopt:()->Boolean):Boolean = coroutineScope {
        if(!active || current!=null || visible || splitPresentation.layout!=null || splitPresentation.returnLayout!=null)
            return@coroutineScope false
        val layoutGeneration=splitPresentation.generation
        val request=++generation
        val previous=acceptedOpens.toList();work?.cancel();openOperation=null;opening=true;failure=null;nativeSplitOperation=false
        fun valid()=active && generation==request && current==null && !visible &&
            splitPresentation.generation==layoutGeneration && stillCurrent()
        val pending=async(start=CoroutineStart.LAZY) {
            var map:MapColdAccess.Held?=null
            var native:PreparedColdMixedNative?=null
            var adopted=false
            try {
                previous.forEach {it.join()}
                if(!valid()) return@async false
                map=MapColdAccess(app).prepare(ref,::valid) ?: return@async false
                val held=requireNotNull(map)
                if(!valid() || !held.current()) return@async false
                if(prepareNative!=null) {
                    native=prepareNative(::valid) ?: return@async false
                    if(!requireNotNull(native).verify()) return@async false
                }
                if(!valid() || !held.current() || native?.current()==false) return@async false
                val next=Entry(held.uri,held.title,held.lease)
                // The camera only touches this still-private presentation before either model is adopted.
                if(!next.presentation.applyArrangementCamera(next.session,camera)) return@async false
                held.finish {lease->
                    if(!valid() || lease!==next.lease || native?.current()==false || !beforeAdopt()) false
                    else if(native?.commit()==false) false
                    else {
                        visited.clear();sourceFrames=emptyList();visited[MapSaves.key(next.uri)]=next
                        next.parkingCheck={false};next.flushNativeOnPresent=false;next.skipNativePersistOnPresent=false
                        next.presentation.visible=true
                        splitPresentation.present(presentation)
                        splitPresentation.rememberNativeHint(hint)
                        current=next;visible=true;adopted=true
                        true
                    }
                }
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:Exception) {false}
            finally {
                withContext(NonCancellable+Dispatchers.Main.immediate) {
                    try {native?.close()} finally {map?.closeUnadopted()?.join()}
                }
                if(generation==request) {
                    opening=false;openOperation=null
                    if(!adopted) failure=MapWorkspaceFailure.UNAVAILABLE
                }
            }
        }
        work=pending;acceptedOpens+=pending
        pending.invokeOnCompletion {acceptedOpens-=pending;if(work===pending) work=null}
        pending.start();pending.await()
    }

    fun open(uri:Uri,title:String,preparePresentation:suspend ()->(()->Boolean)?={ {true} },stillCurrent:()->Boolean={true},
        operation:Any?=null,onPresented:(Entry)->Unit={},onFinished:(Boolean)->Unit={},flushNativeOnPresent:Boolean=true,
        presentation:MapOpenPresentation=MapOpenPresentation.Single,
        prepareDeparture:(suspend (()->Boolean)->com.xnotes.ui.PreparedHomeGate?)?=prepareHostDeparture,
        commitPresentation:((()->Boolean)->Boolean)?=null,intentAlreadyAccepted:Boolean=false):Boolean {
        if(!active) return false
        if(!intentAlreadyAccepted) onPresentationIntent?.invoke()
        val request=++generation
        val previous=acceptedOpens.toList();work?.cancel();openOperation=operation;opening=true;failure=null;nativeSplitOperation=false
        fun valid()=active && generation==request && stillCurrent()
        val pending=scope.launch(start=CoroutineStart.LAZY) {
            var incoming:MapDocumentLease?=null
            var departure:com.xnotes.ui.PreparedHomeGate?=null
            var adopted=false
            try {
                previous.forEach {it.join()}
                if(!valid()) {if(active && generation==request) failure=MapWorkspaceFailure.CHANGED;return@launch}
                val prior=current
                val reused=prior?.takeIf {it.lease.alive && MapSaves.key(it.uri)==MapSaves.key(uri)}
                val lease=reused?.lease ?: MapDocuments.acquire(app,uri).also {incoming=it}
                snapshotFlow {lease.session.loading to lease.alive}.first {!it.first || !it.second}
                if(!valid()) {if(active && generation==request) failure=MapWorkspaceFailure.CHANGED;return@launch}
                if(!lease.alive || lease.session.loadFailed || lease.session.document==null) {
                    failure=MapWorkspaceFailure.UNAVAILABLE;return@launch
                }
                val ready=preparePresentation()
                if(reused==null && visible && prior!=null && current===prior && prior.session.document!=null && !prior.session.canParkSourceNavigation) {
                    failure=MapWorkspaceFailure.CHANGED;return@launch
                }
                if(prepareDeparture!=null) {
                    departure=prepareDeparture(::valid)
                    if(departure==null) {if(active && generation==request) failure=MapWorkspaceFailure.CHANGED;return@launch}
                }
                // No suspension between the final physical-input proof and changing presentation.
                if(!valid() || ready?.invoke()!=true) {
                    if(active && generation==request) failure=MapWorkspaceFailure.CHANGED
                    return@launch
                }
                val beforeAdopt={departure?.commit(::valid)!=false}
                if((commitPresentation?.invoke(beforeAdopt) ?: beforeAdopt())!=true) {
                    if(active && generation==request) failure=MapWorkspaceFailure.CHANGED
                    return@launch
                }
                val next=reused ?: Entry(lease.uri,title,lease)
                val replaced=if(reused==null) ownedEntries() else emptyList()
                if(reused==null) {visited.clear();sourceFrames=emptyList()}
                visited[MapSaves.key(next.uri)]=next
                next.parkingCheck=ready;next.flushNativeOnPresent=flushNativeOnPresent;next.skipNativePersistOnPresent=false
                next.presentation.visible=true
                splitPresentation.present(presentation)
                current=next;visible=true;adopted=true
                retire(replaced)
                onPresented(next)
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:Exception) {if(active && generation==request) failure=MapWorkspaceFailure.UNAVAILABLE}
            finally {
                withContext(NonCancellable+Dispatchers.Main.immediate) {
                    try {departure?.close()} finally {if(!adopted) incoming?.closeUnadoptedSource()?.join()}
                }
                if(generation==request) {opening=false;openOperation=null}
                onFinished(adopted)
            }
        }
        work=pending;acceptedOpens+=pending
        pending.invokeOnCompletion {acceptedOpens-=pending;if(work===pending) work=null}
        pending.start()
        return true
    }
    /** One final Main stack adopts Native, Map and layout after both independent preparations. */
    internal fun openPair(uri:Uri,title:String,mapIsFirst:Boolean,
        prepareNative:suspend ((()->Boolean))->com.xnotes.ui.PreparedPagedPairOpen?,
        stillCurrent:()->Boolean={true},operation:Any?=null,
        onPresented:(Entry,com.xnotes.ui.PreparedPagedPairOpen)->Unit={_,_->},onFinished:(Boolean)->Unit={}):Boolean =
        openPairPrepared(uri,title,mapIsFirst,{_,valid->prepareNative(valid)},stillCurrent,operation,onPresented,onFinished)

    private fun openPairPrepared(requestedUri:Uri,requestedTitle:String,mapIsFirst:Boolean,
        prepareNative:suspend (com.xnotes.ui.MixedSplitRequest?,()->Boolean)->com.xnotes.ui.PreparedPagedPairOpen?,
        stillCurrent:()->Boolean,operation:Any?,
        onPresented:(Entry,com.xnotes.ui.PreparedPagedPairOpen)->Unit,onFinished:(Boolean)->Unit,
        savedRequest:com.xnotes.ui.MixedSplitRequest?=null):Boolean {
        if(!active) return false
        onPresentationIntent?.invoke()
        val origin=current;val originVisible=visible;val layoutGeneration=splitPresentation.generation
        val request=++generation
        val previous=acceptedOpens.toList();work?.cancel();openOperation=operation;opening=true;failure=null;nativeSplitOperation=false
        fun valid()=active && generation==request && current===origin && visible==originVisible &&
            splitPresentation.generation==layoutGeneration && stillCurrent()
        val pending=scope.launch(start=CoroutineStart.LAZY) {
            var incoming:MapDocumentLease?=null
            var native:com.xnotes.ui.PreparedPagedPairOpen?=null
            var departure:com.xnotes.ui.PreparedHomeGate?=null
            var adopted=false
            var adoptedLease:MapDocumentLease?=null
            try {
                previous.forEach {it.join()}
                if(!valid()) throw MapPairChanged()
                val restored=savedRequest?.let {SavedMixedSplitResolver(app).resolve(it)}
                if(!valid()) throw MapPairChanged()
                val uri=restored?.map?.uri?.let(Uri::parse) ?: requestedUri
                val title=restored?.map?.title ?: requestedTitle
                val existing=MapDocuments.observations(app).singleOrNull {it.key==MapSaves.key(uri)}
                    ?.takeUnless {it.failed && it.document==null && !it.loading && !it.creating}
                if(existing?.creating==true || existing?.closing==true || existing?.replacing==true) throw MapPairChanged()
                val access=MapPairAccess(app)
                val initial=access.initial(uri)
                if(restored!=null && initial.id!=restored.map.documentId) throw MapPairChanged()
                if(!valid()) throw MapPairChanged()
                val reused=ownedEntries().firstOrNull {it.lease.alive && MapSaves.key(it.uri)==MapSaves.key(uri)}
                val lease=reused?.lease ?: MapDocuments.acquire(app,uri).also {incoming=it}
                if(existing!=null && existing.session!==lease.session) throw MapPairChanged()
                val loadEpoch=lease.session.interactionEpoch
                snapshotFlow {lease.session.loading to lease.alive}.first {!it.first || !it.second}
                if(!valid() || lease.session.interactionEpoch!=loadEpoch) throw MapPairChanged()
                if(!lease.alive || lease.session.loadFailed || lease.session.document==null) {
                    failure=MapWorkspaceFailure.UNAVAILABLE;return@launch
                }
                val requireLoadBaseline=existing?.document==null
                if(existing!=null && existing.session===lease.session && existing.epoch!=lease.session.interactionEpoch)
                    throw MapPairChanged()
                val source=access.prepare(uri,lease,initial,requireLoadBaseline)
                val next=reused ?: Entry(lease.uri,title,lease)
                // Initial reads precede every blur/save guard in the Native preparation.
                native=prepareNative(restored,::valid) ?: run {failure=MapWorkspaceFailure.CHANGED;return@launch}
                val candidate=requireNotNull(native)
                if(!valid() || !source.current() || !candidate.current()) throw MapPairChanged()
                val replaced=ownedEntries().filter {it!==next}
                val leavesReady=settleMapRetirement(replaced,::valid)
                val departureProvider=prepareHostDeparture
                if(departureProvider!=null) {
                    departure=departureProvider {valid() && leavesReady() && candidate.current()}
                    if(departure==null) throw MapPairChanged()
                }
                val applied=source.finish {
                    if(!valid() || !leavesReady() || !candidate.current()) false
                    else {
                        val mapPane=if(candidate.nativePane==com.xnotes.ui.Pane.PRIMARY) com.xnotes.ui.Pane.SECONDARY else com.xnotes.ui.Pane.PRIMARY
                        val saved=restored?.layout
                        val focus=if(saved==null) {
                            if(mapIsFirst) mapPane else candidate.nativePane
                        } else if(saved.focusedPane==saved.mapPane) mapPane else candidate.nativePane
                        val mapFirst=if(saved==null) mapIsFirst else (saved.mapPane==com.xnotes.ui.Pane.PRIMARY)!=saved.reversed
                        val layout=MapSplitLayout(mapPane,focus,saved?.ratio ?: .5f,saved?.sideBySide,
                            (mapPane==com.xnotes.ui.Pane.PRIMARY)!=mapFirst)
                        // commit's only false result is pre-mutation. No business veto follows it.
                        if(!candidate.commit(beforeAdopt={departure?.commit {valid() && leavesReady() && candidate.current()}!=false})) false else {
                            visited.clear();sourceFrames=emptyList()
                            visited[MapSaves.key(next.uri)]=next
                            next.parkingCheck={false};next.sourceReturn=null
                            next.flushNativeOnPresent=false;next.skipNativePersistOnPresent=false;next.presentation.visible=true
                            if(restored!=null) next.presentation.applyArrangementCamera(next.session,restored.mapCamera)
                            splitPresentation.present(MapOpenPresentation.Split(layout))
                            splitPresentation.rememberNativeHint(candidate.target.nativeRestoreHint())
                            current=next;visible=true;adopted=true;adoptedLease=next.lease
                            retire(replaced)
                            onPresented(next,candidate)
                            true
                        }
                    }
                }
                if(!applied && active && generation==request) failure=MapWorkspaceFailure.CHANGED
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:MapPairChanged) {if(!adopted && active && generation==request) failure=MapWorkspaceFailure.CHANGED}
            catch(_:Exception) {if(!adopted && active && generation==request) failure=MapWorkspaceFailure.UNAVAILABLE}
            finally {
                try {
                    withContext(NonCancellable+Dispatchers.Main.immediate) {
                        try {native?.close()} finally {try {departure?.close()} finally {
                            incoming?.takeIf {it!==adoptedLease}?.closeUnadoptedSource()?.join()
                        }}
                    }
                } catch(error:Exception) {
                    android.util.Log.w("xnotes.map.pair","Pair preparation cleanup failed",error)
                } finally {
                    if(generation==request) {opening=false;openOperation=null}
                    onFinished(adopted)
                }
            }
        }
        work=pending;acceptedOpens+=pending
        pending.invokeOnCompletion {acceptedOpens-=pending;if(work===pending) work=null}
        pending.start();return true
    }

    internal fun openSavedPair(request:com.xnotes.ui.MixedSplitRequest,
        prepareNative:suspend (com.xnotes.ui.NativeSplitSource,com.xnotes.ui.Editor.ReadingPosition,()->Boolean)->com.xnotes.ui.PreparedPagedPairOpen?,
        stillCurrent:()->Boolean={true},operation:Any?=null,
        onPresented:(Entry,com.xnotes.ui.PreparedPagedPairOpen)->Unit={_,_->},onFinished:(Boolean)->Unit={}):Boolean =
        openPairPrepared(Uri.parse(request.map.uri),request.map.title.orEmpty(),
            (request.layout.mapPane==com.xnotes.ui.Pane.PRIMARY)!=request.layout.reversed,
            prepareNative={resolved,valid->
                val saved=requireNotNull(resolved)
                prepareNative(com.xnotes.ui.NativeSplitSource(saved.native.uri,saved.native.title,saved.native.documentId),saved.nativePosition,valid)
            },stillCurrent=stillCurrent,operation=operation,onPresented=onPresented,onFinished=onFinished,savedRequest=request)

    internal suspend fun prepareMixedArrangementSave(
        prepareNative:suspend (com.xnotes.ui.Pane,()->Boolean)->com.xnotes.ui.PreparedMixedNoteArrangementSave?,
        stillCurrent:()->Boolean={true},
    ):PreparedMixedArrangementSave? {
        val entry=current ?: return null
        val layout=splitPresentation.layout ?: return null
        val epoch=entry.session.interactionEpoch
        val routeGeneration=generation;val layoutGeneration=splitPresentation.generation
        val documentId=entry.session.document?.id ?: return null
        fun actorReady()=MapDocuments.observations(app).any {it.session===entry.session && it.key==MapSaves.key(entry.uri) &&
            !it.creating && !it.failed && !it.closing && !it.replacing && !it.loading}
        fun routeCurrent()=active && current===entry && visible && !opening && generation==routeGeneration &&
            entry.lease.alive && entry.session.interactionEpoch==epoch && entry.session.document?.id==documentId &&
            splitPresentation.layout==layout && splitPresentation.generation==layoutGeneration && !layout.nativeHome &&
            stillCurrent() && actorReady() && entry.session.canParkSourceNavigation
        if(!routeCurrent()) return null
        try {
            val native=prepareNative(layout.nativePane,::routeCurrent) ?: return null
            if(!routeCurrent() || !native.current() || native.nativePane!=layout.nativePane) return null
            // Even a shared actor needs this operation's actual successful save, not the lease retirement waiver.
            val written=entry.session.saveAttempt().await() as? MapSaveOutcome.Written ?: return null
            val sha=written.sourceSha256 ?: return null
            if(!written.latest || !routeCurrent() || !native.current() || entry.session.dirty || entry.session.saving) return null
            val document=entry.session.document ?: return null
            val revision=entry.session.contentRevision
            if(written.capturedRevision!=revision) return null
            val access=MapPairAccess(app)
            val initial=access.initial(entry.uri)
            if(!routeCurrent() || !native.current() || initial.id!=documentId || initial.sha256!=sha) return null
            val source=access.prepare(entry.uri,entry.lease,initial,requireLoadBaseline=false,expectedPublicationSha256=sha)
            val request=com.xnotes.ui.MixedSplitRequest(
                com.xnotes.ui.SplitSourceRef(documentId,entry.uri.toString(),entry.title),native.source,layout,
                entry.presentation.arrangementCamera(entry.session),native.position)
            // Encoding here also rejects unrepresentable current view settings before a stage is created.
            com.xnotes.ui.SplitOpenCodec.encodeMixed(request)
            fun current()=routeCurrent() && native.current() && source.current() && entry.session.document===document &&
                entry.session.contentRevision==revision && !entry.session.dirty && !entry.session.saving
            if(!current()) return null
            return PreparedMixedArrangementSave(request,listOfNotNull(entry.title,native.source.title).joinToString(" + "),::current) {commit->
                if(!current()) null else {
                    val nativeSource=native.verifySource()
                    if(nativeSource==null || !current() || !nativeSource()) null else {
                        var published:java.io.File?=null
                        source.finish {
                            if(!current() || !nativeSource()) false
                            else {published=commit();published!=null}
                        }
                        published
                    }
                }
            }
        } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
        catch(_:Exception) {return null}
    }

    /** Called after both Native initial reads, before Native guard/save and the last source IO. */
    private suspend fun settleMapRetirement(entries:List<Entry>,valid:()->Boolean):()->Boolean {
        for(entry in entries) {
            while(entry.session.document!=null) {
                if(!valid() || !entry.lease.alive || !entry.session.canParkSourceNavigation) throw MapPairChanged()
                if(entry.lease.hasOtherOwners || !entry.session.dirty && !entry.session.saving) break
                when(val outcome=entry.session.saveAttempt().await()) {
                    is MapSaveOutcome.Written->if(outcome.latest) break
                    else->throw java.io.IOException("The map save was not published")
                }
            }
        }
        val checks=entries.map {entry->
            val document=entry.session.document;val revision=entry.session.contentRevision;val epoch=entry.session.interactionEpoch
            val check:()->Boolean={entry.lease.alive && entry.session.document===document &&
                entry.session.contentRevision==revision && entry.session.interactionEpoch==epoch &&
                (document==null || entry.session.canParkSourceNavigation &&
                    (entry.lease.hasOtherOwners || !entry.session.dirty && !entry.session.saving))}
            check
        }
        return {valid() && checks.all {it()}}
    }

    /** The Native preparation must call settleMaps after its two initial reads. */
    internal fun replaceWithNativeSplit(
        prepareNative:suspend (current:()->Boolean,settleMaps:suspend ()->Boolean)->com.xnotes.ui.PreparedNativeSplitOpen?,
        stillCurrent:()->Boolean={true},operation:Any?=null,
        onPresented:(com.xnotes.ui.PreparedNativeSplitOpen)->Unit={},onFinished:(Boolean)->Unit={}):Boolean {
        if(!active) return false
        onPresentationIntent?.invoke()
        val origin=current;val originVisible=visible;val layoutGeneration=splitPresentation.generation
        val request=++generation
        val previous=acceptedOpens.toList();work?.cancel();openOperation=operation;opening=true;failure=null;nativeSplitOperation=true
        fun valid()=active && generation==request && current===origin && visible==originVisible &&
            splitPresentation.generation==layoutGeneration && stillCurrent()
        val pending=scope.launch(start=CoroutineStart.LAZY) {
            var native:com.xnotes.ui.PreparedNativeSplitOpen?=null
            var departure:com.xnotes.ui.PreparedHomeGate?=null
            var adopted=false
            var mapProof:(()->Boolean)?=null
            fun candidateCurrent()=valid() && mapProof?.invoke()!=false
            try {
                previous.forEach {it.join()}
                if(!valid()) throw MapPairChanged()
                val entries=ownedEntries()
                suspend fun settleMaps():Boolean {
                    if(!valid()) return false
                    val ready=mapProof ?: settleMapRetirement(entries,::valid).also {mapProof=it}
                    return ready()
                }
                native=prepareNative(::candidateCurrent,::settleMaps)
                val candidate=native ?: run {if(valid()) failure=MapWorkspaceFailure.CHANGED;return@launch}
                if(!valid() || mapProof?.invoke()!=true || !candidate.current()) throw MapPairChanged()
                val departureProvider=prepareHostDeparture
                if(departureProvider!=null) {
                    departure=departureProvider {candidateCurrent() && candidate.current()}
                    if(departure==null) throw MapPairChanged()
                }
                // No suspension or additional business veto between the two document adoption and
                // retiring this presentation's own leases. Other Map owners keep their actor.
                if(!candidate.commit(beforeAdopt={departure?.commit {candidateCurrent() && candidate.current()}!=false})) throw MapPairChanged()
                visited.clear();sourceFrames=emptyList();current=null;visible=false
                splitPresentation.clear();adopted=true
                retire(entries)
                onPresented(candidate)
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:MapPairChanged) {if(!adopted && active && generation==request) failure=MapWorkspaceFailure.CHANGED}
            catch(_:Exception) {if(!adopted && active && generation==request) failure=MapWorkspaceFailure.UNAVAILABLE}
            finally {
                try {withContext(NonCancellable+Dispatchers.Main.immediate) {try {native?.close()} finally {departure?.close()}}}
                catch(error:Exception) {android.util.Log.w("xnotes.map.split","Native split preparation cleanup failed",error)}
                finally {
                    if(generation==request) {opening=false;openOperation=null}
                    onFinished(adopted)
                }
            }
        }
        work=pending;acceptedOpens+=pending
        pending.invokeOnCompletion {acceptedOpens-=pending;if(work===pending) work=null}
        pending.start();return true
    }

    /** A successful synchronous handoff consumes incoming; every failure leaves it to its holder. */
    internal fun pushSource(origin:Entry,incoming:MapDocumentLease,title:String,bookmark:MapSourceReturnBookmark,
        departure:MapPresentationDeparture,beforeAdopt:()->Boolean={true},parkingCheck:()->Boolean):SourcePushResult {
        if(!active || !visible || current!==origin || !origin.lease.alive || !incoming.alive ||
            origin.session.currentSourceNavigation(bookmark.source)==null || !departure.current(origin.presentation,origin.session) ||
            bookmark.source.target.scope!=SourceScope.FILE || incoming.session.document?.id!=bookmark.source.target.documentId)
            return SourcePushResult.CHANGED
        if(opening || !origin.session.canParkSourceNavigation || !incoming.session.canParkSourceNavigation) return SourcePushResult.BUSY
        val key=MapSaves.key(incoming.uri)
        val pooled=visited[key]
        if(pooled!=null && (!pooled.lease.alive || pooled.session!==incoming.session)) return SourcePushResult.CHANGED
        val self=key==MapSaves.key(origin.uri)
        if(self && incoming.session!==origin.session) return SourcePushResult.CHANGED
        if(!self && (sourceFrames.size>=64 || pooled==null && visited.size>=16)) return SourcePushResult.LIMIT
        if(!parkingCheck()) return SourcePushResult.CHANGED
        if(!beforeAdopt()) return SourcePushResult.CHANGED
        cancelOpen()
        if(self) {
            origin.presentation.closeSources();origin.parkingCheck=parkingCheck;origin.skipNativePersistOnPresent=true
            if(incoming!==origin.lease) trackRelease(incoming.closeUnadoptedSource())
            return SourcePushResult.SELF
        }
        val next=pooled ?: Entry(incoming.uri,title,incoming).also {it.flushNativeOnPresent=false}
        visited[MapSaves.key(origin.uri)]=origin;visited[key]=next
        sourceFrames=sourceFrames+MapSourceNavigationFrame(origin,next,bookmark,departure)
        origin.presentation.parkSourcePanel();origin.presentation.visible=false;origin.parkingCheck={false}
        next.parkingCheck=parkingCheck;next.skipNativePersistOnPresent=true;next.presentation.visible=true
        current=next;visible=true
        if(incoming!==next.lease) trackRelease(incoming.closeUnadoptedSource())
        return SourcePushResult.APPLIED
    }
    /** Returning parks both actual map actors; it never closes one or forces a save. */
    internal fun returnSource(expected:Entry,beforeAdopt:()->Boolean={true},parkingCheck:()->Boolean):Boolean {
        val frame=sourceFrames.lastOrNull() ?: return false
        val origin=frame.origin
        if(!active || !visible || current!==expected || frame.destination!==expected || !expected.lease.alive || !origin.lease.alive ||
            visited[MapSaves.key(origin.uri)]!==origin || !expected.session.canParkSourceNavigation || !origin.session.canParkSourceNavigation ||
            !parkingCheck()) return false
        if(!beforeAdopt()) return false
        cancelOpen();sourceFrames=sourceFrames.dropLast(1)
        expected.presentation.parkSourcePanel();expected.presentation.visible=false;expected.parkingCheck={false}
        origin.parkingCheck=parkingCheck;origin.skipNativePersistOnPresent=true;origin.presentation.visible=true
        current=origin;visible=true
        origin.presentation.applySourceReturn(origin.session,frame.bookmark,frame.departure)
        return true
    }
    fun clearFailure() {failure=null}
    fun cancelOpen() {generation++;val pending=work;work=null;openOperation=null;pending?.cancel();opening=false;failure=null;nativeSplitOperation=false}
    internal fun cancelOpen(operation:Any):Boolean {
        if(openOperation!==operation) return false
        cancelOpen();return true
    }
    fun bindHost(binding:Any) {
        if(hostBinding===binding) return
        cancelOpen();ownedEntries().forEach {it.parkingCheck={false}};hostBinding=binding
    }
    fun isHostCurrent(binding:Any)=active && hostBinding===binding
    fun unbindHost(binding:Any):Boolean {
        if(hostBinding!==binding) return false
        cancelOpen();ownedEntries().forEach {it.parkingCheck={false}};hostBinding=null;return true
    }
    internal fun canHide(expected:Entry)=active && current===expected && expected.lease.alive &&
        !expected.session.hasUnconfirmedContent && !expected.session.closing && !expected.session.replacingDocument
    fun hide(expected:Entry,beforeAdopt:()->Boolean={true}):Boolean {
        if(!canHide(expected)) return false
        if(!beforeAdopt()) return false
        cancelOpen();splitPresentation.park();expected.presentation.visible=false;visible=false;return true
    }
    /** Real native callers re-prepare via open(); this fast path still checks its original proof. */
    fun show(expected:Entry,beforeAdopt:()->Boolean={true}):Boolean {
        if(!active || current!==expected || !expected.lease.alive) return false
        if(!visible && !expected.parkingCheck()) {failure=MapWorkspaceFailure.CHANGED;return false}
        if(!beforeAdopt()) return false
        cancelOpen();expected.presentation.visible=true;visible=true;return true
    }
    fun showSplit(expected:Entry,layout:MapSplitLayout,beforeAdopt:()->Boolean={true}):Boolean {
        if(!active || current!==expected || !expected.lease.alive || !visible && !expected.parkingCheck() ||
            !expected.session.canParkSourceNavigation) return false
        if(!beforeAdopt()) return false
        cancelOpen();splitPresentation.present(MapOpenPresentation.Split(layout))
        expected.presentation.visible=true;visible=true;return true
    }
    fun focusSplit(pane:com.xnotes.ui.Pane):Boolean {
        val layout=splitPresentation.layout ?: return false
        if(!active || pane!=layout.focusedPane && current?.session?.inkContact==true) return false
        return splitPresentation.updateLayout {it.copy(focusedPane=pane)}
    }
    fun updateSplitLayout(beforeAdopt:()->Boolean={true},transform:(MapSplitLayout)->MapSplitLayout):Boolean =
        active && splitPresentation.updateLayout(beforeAdopt,transform)
    fun removeSplitHome(mapPane:com.xnotes.ui.Pane,beforeAdopt:()->Boolean={true}):Boolean {
        if(!active || current!=null || splitPresentation.layout?.mapPane!=mapPane) return false
        if(!beforeAdopt()) return false
        cancelOpen();splitPresentation.clear();return true
    }
    fun close(expected:Entry,preserveRecovery:Boolean=false,beforeAdopt:()->Boolean={true}):Job? {
        return closeEntry(expected,preserveRecovery,keepSplit=false,beforeAdopt=beforeAdopt)
    }
    fun closeToHome(expected:Entry,preserveRecovery:Boolean=false,beforeAdopt:()->Boolean={true}):Job? {
        if(splitPresentation.layout==null) return close(expected,preserveRecovery,beforeAdopt)
        return closeEntry(expected,preserveRecovery,keepSplit=true,beforeAdopt=beforeAdopt)
    }
    private fun closeEntry(expected:Entry,preserveRecovery:Boolean,keepSplit:Boolean,beforeAdopt:()->Boolean):Job? {
        if(!active || current!==expected) return null
        if(preserveRecovery && !expected.lease.hasOtherOwners &&
            (!expected.session.recoverySafe || !expected.session.canParkSourceNavigation)) return null
        if(!beforeAdopt()) return null
        val pending=acceptedOpens.toList();cancelOpen()
        val entries=ownedEntries();visited.clear();sourceFrames=emptyList();current=null;visible=false
        if(!keepSplit) splitPresentation.clear()
        val released=retire(entries,expected.takeIf {preserveRecovery})+releases.toList()
        return MapSaves.scope.launch {pending.forEach {it.cancelAndJoin()};released.distinct().forEach {it.join()}}
    }
    fun persist() {ownedEntries().filter {it.lease.alive}.map {it.session}.distinct().filter {it.dirty}.forEach {it.save()}}
    fun dispose():Job {
        disposal?.let {return it}
        active=false;generation++;opening=false
        hostBinding=null
        val pending=acceptedOpens.toList();work=null;pending.forEach {it.cancel()}
        val entries=ownedEntries();visited.clear();sourceFrames=emptyList();current=null;visible=false
        splitPresentation.clear()
        val released=retire(entries)+releases.toList()
        return MapSaves.scope.launch {pending.forEach {it.cancelAndJoin()};released.distinct().forEach {it.join()}}.also {disposal=it}
    }
}

/** The main layout invokes this once, inside the actual map slot. */
internal val LocalMapPaneContent=staticCompositionLocalOf<(@Composable ()->Unit)?> {null}

/** Keep map pickers registered while its body is parked; never cover an on-top ink Surface. */
@Composable
internal fun MapWorkspaceHost(workspace:MapWorkspace,onClosed:(MapWorkspace.Entry)->Unit,
    hooks:((MapWorkspace.Entry)->MapHostHooks)?=null,nativeContent:@Composable ()->Unit) {
    val targetPicker=rememberSourceTargetPickerHost()
    val latestClosed by rememberUpdatedState(onClosed)
    val latestHooks by rememberUpdatedState(hooks)
    val mapContent=remember(workspace) {movableContentOf {
        workspace.current?.let {entry->key(entry) {
            MindMapScreen(entry.session,entry.title,onClose={latestClosed(entry)},presentation=entry.presentation,
                hostHooks=latestHooks?.invoke(entry))
        }}
    }}
    CompositionLocalProvider(LocalSourceTargetPickerHost provides targetPicker) {
    Box(Modifier.fillMaxSize()) {
        val split=workspace.splitPresentation.layout!=null
        CompositionLocalProvider(LocalMapPaneContent provides mapContent) {
            if(split || !workspace.visible) nativeContent()
            if(!split) mapContent()
        }
        if(workspace.opening || workspace.visible && workspace.failure!=null) {
            Surface(modifier=Modifier.align(Alignment.BottomEnd).padding(Space.s8)) {
                Row(Modifier.padding(Space.s8),horizontalArrangement=Arrangement.spacedBy(Space.s8),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(when {
                        workspace.nativeSplitOperation && workspace.opening->R.string.native_split_opening
                        workspace.nativeSplitOperation->R.string.native_split_open_failed
                        workspace.opening->R.string.map_loading
                        workspace.failure==MapWorkspaceFailure.CHANGED->R.string.native_open_changed
                        else->R.string.map_open_error
                    }),fontSize=TextSize.body,modifier=Modifier.weight(1f))
                    MapButton(R.string.cancel) {if(workspace.opening) workspace.cancelOpen() else workspace.clearFailure()}
                }
            }
        }
    }
    }
}
