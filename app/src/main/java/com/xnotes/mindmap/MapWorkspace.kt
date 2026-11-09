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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
    var current by mutableStateOf<Entry?>(null);private set
    var visible by mutableStateOf(false);private set
    var opening by mutableStateOf(false);private set
    var failure by mutableStateOf<MapWorkspaceFailure?>(null);private set

    fun open(uri:Uri,title:String,preparePresentation:suspend ()->(()->Boolean)?={ {true} },stillCurrent:()->Boolean={true},
        operation:Any?=null,onPresented:(Entry)->Unit={},onFinished:(Boolean)->Unit={},flushNativeOnPresent:Boolean=true):Boolean {
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
                // No suspension between the final physical-input proof and changing presentation.
                if(!valid() || ready?.invoke()!=true) {
                    if(active && generation==request) failure=MapWorkspaceFailure.CHANGED
                    return@launch
                }
                val next=reused ?: Entry(lease.uri,title,lease)
                next.parkingCheck=ready;next.flushNativeOnPresent=flushNativeOnPresent;next.skipNativePersistOnPresent=false
                next.presentation.visible=true
                current=next;visible=true;adopted=true
                if(prior!=null && prior!==next) {prior.presentation.visible=false;prior.parkingCheck={false};prior.lease.close()}
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
    fun clearFailure() {failure=null}
    fun cancelOpen() {generation++;val pending=work;work=null;openOperation=null;pending?.cancel();opening=false;failure=null}
    internal fun cancelOpen(operation:Any):Boolean {
        if(openOperation!==operation) return false
        cancelOpen();return true
    }
    fun bindHost(binding:Any) {
        if(hostBinding===binding) return
        cancelOpen();current?.parkingCheck={false};hostBinding=binding
    }
    fun isHostCurrent(binding:Any)=active && hostBinding===binding
    fun unbindHost(binding:Any):Boolean {
        if(hostBinding!==binding) return false
        cancelOpen();current?.parkingCheck={false};hostBinding=null;return true
    }
    internal fun canHide(expected:Entry)=active && current===expected && expected.lease.alive &&
        !expected.session.hasUnconfirmedContent && !expected.session.closing && !expected.session.replacingDocument
    fun hide(expected:Entry):Boolean {
        if(!canHide(expected)) return false
        cancelOpen();expected.presentation.visible=false;visible=false;return true
    }
    /** Real native callers re-prepare via open(); this fast path still checks its original proof. */
    fun show(expected:Entry):Boolean {
        if(!active || current!==expected || !expected.lease.alive) return false
        if(!visible && !expected.parkingCheck()) {failure=MapWorkspaceFailure.CHANGED;return false}
        cancelOpen();expected.presentation.visible=true;visible=true;return true
    }
    fun close(expected:Entry):Job? {
        if(!active || current!==expected) return null
        cancelOpen();current=null;visible=false;expected.presentation.visible=false;expected.parkingCheck={false}
        return expected.lease.close()
    }
    fun persist() {current?.takeIf {it.lease.alive && it.session.dirty}?.session?.save()}
    fun dispose():Job {
        disposal?.let {return it}
        active=false;generation++;opening=false
        hostBinding=null
        val pending=acceptedOpens.toList();work=null;pending.forEach {it.cancel()}
        val entry=current;current=null;visible=false;entry?.presentation?.visible=false;entry?.parkingCheck={false}
        val released=entry?.lease?.close()
        return MapSaves.scope.launch {pending.forEach {it.cancelAndJoin()};released?.join()}.also {disposal=it}
    }
}

/** Keep map pickers registered while its body is parked; never cover an on-top ink Surface. */
@Composable
internal fun MapWorkspaceHost(workspace:MapWorkspace,onClosed:(MapWorkspace.Entry)->Unit,
    hooks:((MapWorkspace.Entry)->MapHostHooks)?=null,nativeContent:@Composable ()->Unit) {
    Box(Modifier.fillMaxSize()) {
        if(!workspace.visible) nativeContent()
        workspace.current?.let {entry->key(entry) {
            MindMapScreen(entry.session,entry.title,onClose={onClosed(entry)},presentation=entry.presentation,hostHooks=hooks?.invoke(entry))
        }}
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
