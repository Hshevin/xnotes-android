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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class MapWorkspaceFailure {UNAVAILABLE,CHANGED}

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
    var failure by mutableStateOf<MapWorkspaceFailure?>(null);private set

    fun open(uri:Uri,title:String,preparePresentation:suspend ()->(()->Boolean)?={ {true} },stillCurrent:()->Boolean={true},
        operation:Any?=null,onPresented:(Entry)->Unit={},onFinished:(Boolean)->Unit={},flushNativeOnPresent:Boolean=true,
        presentation:MapOpenPresentation=MapOpenPresentation.Single):Boolean {
        if(!active) return false
        val request=++generation
        work?.cancel();openOperation=operation;opening=true;failure=null
        fun valid()=active && generation==request && stillCurrent()
        val pending=scope.launch(start=CoroutineStart.LAZY) {
            var incoming:MapDocumentLease?=null
            var adopted=false
            try {
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
                // No suspension between the final physical-input proof and changing presentation.
                if(!valid() || ready?.invoke()!=true) {
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
                if(!adopted) withContext(NonCancellable+Dispatchers.Main.immediate) {incoming?.close()?.join()}
                if(generation==request) {opening=false;openOperation=null}
                onFinished(adopted)
            }
        }
        work=pending;acceptedOpens+=pending
        pending.invokeOnCompletion {acceptedOpens-=pending;if(work===pending) work=null}
        pending.start()
        return true
    }
    /** A successful synchronous handoff consumes incoming; every failure leaves it to its holder. */
    internal fun pushSource(origin:Entry,incoming:MapDocumentLease,title:String,bookmark:MapSourceReturnBookmark,
        departure:MapPresentationDeparture,parkingCheck:()->Boolean):SourcePushResult {
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
    internal fun returnSource(expected:Entry,parkingCheck:()->Boolean):Boolean {
        val frame=sourceFrames.lastOrNull() ?: return false
        val origin=frame.origin
        if(!active || !visible || current!==expected || frame.destination!==expected || !expected.lease.alive || !origin.lease.alive ||
            visited[MapSaves.key(origin.uri)]!==origin || !expected.session.canParkSourceNavigation || !origin.session.canParkSourceNavigation ||
            !parkingCheck()) return false
        cancelOpen();sourceFrames=sourceFrames.dropLast(1)
        expected.presentation.parkSourcePanel();expected.presentation.visible=false;expected.parkingCheck={false}
        origin.parkingCheck=parkingCheck;origin.skipNativePersistOnPresent=true;origin.presentation.visible=true
        current=origin;visible=true
        origin.presentation.applySourceReturn(origin.session,frame.bookmark,frame.departure)
        return true
    }
    fun clearFailure() {failure=null}
    fun cancelOpen() {generation++;val pending=work;work=null;openOperation=null;pending?.cancel();opening=false;failure=null}
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
    fun hide(expected:Entry):Boolean {
        if(!canHide(expected)) return false
        cancelOpen();splitPresentation.park();expected.presentation.visible=false;visible=false;return true
    }
    /** Real native callers re-prepare via open(); this fast path still checks its original proof. */
    fun show(expected:Entry):Boolean {
        if(!active || current!==expected || !expected.lease.alive) return false
        if(!visible && !expected.parkingCheck()) {failure=MapWorkspaceFailure.CHANGED;return false}
        cancelOpen();expected.presentation.visible=true;visible=true;return true
    }
    fun showSplit(expected:Entry,layout:MapSplitLayout):Boolean {
        if(!active || current!==expected || !expected.lease.alive || !visible && !expected.parkingCheck() ||
            !expected.session.canParkSourceNavigation) return false
        cancelOpen();splitPresentation.present(MapOpenPresentation.Split(layout))
        expected.presentation.visible=true;visible=true;return true
    }
    fun focusSplit(pane:com.xnotes.ui.Pane):Boolean {
        val layout=splitPresentation.layout ?: return false
        if(!active || pane!=layout.focusedPane && current?.session?.inkContact==true) return false
        return splitPresentation.updateLayout {it.copy(focusedPane=pane)}
    }
    fun updateSplitLayout(transform:(MapSplitLayout)->MapSplitLayout):Boolean =
        active && splitPresentation.updateLayout(transform)
    fun removeSplitHome(mapPane:com.xnotes.ui.Pane):Boolean {
        if(!active || current!=null || splitPresentation.layout?.mapPane!=mapPane) return false
        cancelOpen();splitPresentation.clear();return true
    }
    fun close(expected:Entry,preserveRecovery:Boolean=false):Job? {
        return closeEntry(expected,preserveRecovery,keepSplit=false)
    }
    fun closeToHome(expected:Entry,preserveRecovery:Boolean=false):Job? {
        if(splitPresentation.layout==null) return close(expected,preserveRecovery)
        return closeEntry(expected,preserveRecovery,keepSplit=true)
    }
    private fun closeEntry(expected:Entry,preserveRecovery:Boolean,keepSplit:Boolean):Job? {
        if(!active || current!==expected) return null
        if(preserveRecovery && !expected.lease.hasOtherOwners &&
            (!expected.session.recoverySafe || !expected.session.canParkSourceNavigation)) return null
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
