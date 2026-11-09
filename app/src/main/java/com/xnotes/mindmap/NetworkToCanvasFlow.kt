package com.xnotes.mindmap

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.xnotes.R
import com.xnotes.core.util.DocumentKind
import com.xnotes.ui.AlertDialog
import com.xnotes.ui.theme.*
import java.util.UUID
import com.xnotes.storage.NotebookStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Two target contracts share one original slot; retired payloads never move to a later flow. */
internal class NetworkCanvasPicker(private val registry:ActivityResultRegistry) {
    private class Cell(var callback:((Uri?)->Unit)?) {var unregister:(()->Unit)?=null}
    private var cell:Cell?=null
    private var disposed=false
    var busy by mutableStateOf(false);private set
    fun launch(create:Boolean,name:String,onResult:(Uri?)->Unit):Boolean {
        if(disposed || cell!=null) return false
        val request=Cell(onResult);cell=request;busy=true
        fun receive(uri:Uri?) {if(!disposed && cell===request) {val callback=request.callback;release(request);callback?.invoke(uri)}}
        return try {
            val key="network-canvas-${UUID.randomUUID()}"
            if(create) {
                val launcher=registry.register(key,ActivityResultContracts.CreateDocument("application/octet-stream"),::receive)
                request.unregister=launcher::unregister
                if(disposed || cell!==request) {release(request);false} else {launcher.launch(name);true}
            } else {
                val launcher=registry.register(key,ActivityResultContracts.OpenDocument(),::receive)
                request.unregister=launcher::unregister
                if(disposed || cell!==request) {release(request);false} else {launcher.launch(arrayOf("application/json","application/octet-stream","*/*"));true}
            }
        } catch(_:Exception) {release(request);false}
    }
    fun retire() {cell?.callback=null}
    fun dispose() {disposed=true;cell?.let(::release)}
    private fun release(request:Cell) {
        if(cell===request) {cell=null;busy=false}
        request.callback=null;val unregister=request.unregister;request.unregister=null;unregister?.invoke()
    }
}

private data class NetworkCanvasTarget(val uri:Uri,val title:String,val key:String=MapSaves.key(uri))

@Composable
internal fun NetworkToCanvasFlow(entry:FileNetworkWorkspace.Entry,scene:LocalNetworkScene?,enabled:Boolean,
    openCanvas:(FileNetworkWorkspace.Entry,NetworkCanvasOpenRequest)->Boolean) {
    val context=LocalContext.current;val app=context.applicationContext
    val scope=rememberCoroutineScope()
    val registry=requireNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
    val picker=remember(entry,registry) {NetworkCanvasPicker(registry)}
    var flow by remember(entry) {mutableStateOf<NetworkToCanvasController?>(null)}
    var unavailable by remember(entry) {mutableStateOf(false)}
    var pickerProblem by remember(entry) {mutableStateOf(false)}
    val frameRevision=entry.controller.filterRevision
    val frameFiles=entry.controller.centreFiles.toSet()
    val selected=entry.controller.browseMode==NetworkBrowseMode.FILES && entry.controller.centreFiles.isNotEmpty()
    val latestEnabled by rememberUpdatedState(enabled)
    DisposableEffect(entry,picker) {onDispose {picker.dispose();flow?.close()}}
    MapButton(if(selected) R.string.network_canvas_add else R.string.network_canvas_select_first,enabled=enabled && selected) {
        if(latestEnabled && flow==null && entry.alive && entry.controller.filterRevision==frameRevision &&
            entry.controller.centreFiles==frameFiles) {
            val snapshot=scene?.let {NetworkCanvasSelection.capture(entry,it)}
            if(snapshot==null) unavailable=true else {pickerProblem=false;flow=NetworkToCanvasController(app,snapshot,scope)}
        }
    }
    if(unavailable) AlertDialog(onDismissRequest={unavailable=false},title={Text(stringResource(R.string.network_canvas_add),fontSize=TextSize.heading)},
        text={Text(stringResource(R.string.network_canvas_selection_changed),fontSize=TextSize.body)},
        confirmButton={MapButton(R.string.map_done) {unavailable=false}})
    val shown=flow ?: return
    val live=MapDocuments.observations(app)
    var targetsProblem by remember(shown) {mutableStateOf(false)}
    val storedTargets by produceState<List<NetworkCanvasTarget>>(emptyList(),shown) {
        val targets=try {withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            NotebookStorage.get(app).registry.knownMapLocations(4097).filter {it.available}.take(4096).mapNotNull {
                val uri=Uri.parse(it.uri)
                if(uri.scheme !in setOf("file","content")) null else runCatching {NetworkCanvasTarget(uri,it.name)}.getOrNull()
            }.also {currentCoroutineContext().ensureActive()}
        }} catch(cancelled:kotlinx.coroutines.CancellationException) {throw cancelled}
        catch(_:Exception) {if(flow===shown) targetsProblem=true;emptyList()}
        if(flow===shown && shown.current()) value=targets
    }
    val targets=(storedTargets+live.filter {it.document!=null && !it.failed}.mapNotNull {
        runCatching {NetworkCanvasTarget(it.uri,it.uri.lastPathSegment.orEmpty().substringAfterLast('/'))}.getOrNull()
    }).distinctBy {it.key}.take(4096)
    fun close() {if(flow===shown) {picker.retire();shown.close();flow=null}}
    DisposableEffect(shown) {onDispose {shown.close()}}
    if(shown.step==NetworkCanvasStep.CLOSED) {SideEffect {if(flow===shown) {picker.retire();flow=null}};return}
    fun choose(create:Boolean) {
        if(flow!==shown || !shown.current() || shown.busy || picker.busy) return
        pickerProblem=false
        val creation=if(create) shown.prepareNew() ?: return else null
        val name=DocumentKind.withSuffix(context.getString(R.string.network_canvas_new_title),DocumentKind.MIND_MAP)
        if(!picker.launch(create,name) {uri->
            if(uri==null && creation!=null) shown.cancelNew(creation)
            if(uri!=null && flow===shown && shown.current() && (creation==null || shown.creationCurrent(creation))) {
                val read=Intent.FLAG_GRANT_READ_URI_PERMISSION
                runCatching {app.contentResolver.takePersistableUriPermission(uri,read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
                    .onFailure {runCatching {app.contentResolver.takePersistableUriPermission(uri,read)}}
                if(creation!=null) shown.completeNew(creation,uri) else shown.chooseExisting(uri)
            }
        }) {creation?.let(shown::cancelNew);pickerProblem=true}
    }
    AlertDialog(onDismissRequest=::close,title={Text(stringResource(R.string.network_canvas_add),fontSize=TextSize.heading)},text={
        LazyColumn(Modifier.heightIn(max=MindMapNodeListHeight),verticalArrangement=Arrangement.spacedBy(Space.s8)) {
            item("summary") {Text(stringResource(R.string.network_canvas_summary,shown.selection.seeds.size),fontSize=TextSize.body)
                Text(stringResource(R.string.network_canvas_notice),fontSize=TextSize.caption)}
            if(shown.targetUri!=null) item("target") {
                Text(shown.title,fontSize=TextSize.title)
                Text(shown.targetUri.toString(),fontSize=TextSize.caption,maxLines=2,overflow=TextOverflow.Ellipsis)
            }
            when(shown.step) {
                NetworkCanvasStep.DESTINATION->item("destination") {
                    MapButton(R.string.network_canvas_existing,enabled=!picker.busy) {choose(false)}
                    MapButton(R.string.network_canvas_create,enabled=!picker.busy) {choose(true)}
                    Text(stringResource(R.string.network_canvas_create_confirm_notice),fontSize=TextSize.caption)
                }
                NetworkCanvasStep.CONFIRM->item("confirm") {
                    Text(stringResource(R.string.network_canvas_target_chosen),fontSize=TextSize.body)
                    MapButton(R.string.network_canvas_confirm) {if(flow===shown) shown.confirm()}
                    MapButton(R.string.network_canvas_change) {if(flow===shown) shown.changeTarget()}
                }
                NetworkCanvasStep.WORKING->item("working") {
                    Text(stringResource(R.string.network_canvas_working),fontSize=TextSize.body)
                    Text(stringResource(R.string.network_canvas_keep_working),fontSize=TextSize.caption)
                }
                NetworkCanvasStep.ADDED->item("added") {
                    Text(stringResource(R.string.network_canvas_added),fontSize=TextSize.body)
                    if((shown.saveOutcome as? MapSaveOutcome.Written)?.latest==false)
                        Text(stringResource(R.string.network_canvas_newer_changes),fontSize=TextSize.body)
                }
                NetworkCanvasStep.SAVE_FAILED->item("save-failed") {
                    Text(stringResource(R.string.network_canvas_save_failed),fontSize=TextSize.body)
                    MapButton(R.string.network_canvas_retry_save,enabled=shown.canRetrySave) {if(flow===shown) shown.retrySave()}
                }
                NetworkCanvasStep.CREATE_RETAINED->item("retained") {
                    Text(stringResource(R.string.network_canvas_retained),fontSize=TextSize.body)
                    MapButton(R.string.network_canvas_continue) {if(flow===shown) shown.openRetained()}
                }
                NetworkCanvasStep.FAILED->item("failed") {
                    Text(stringResource(R.string.network_canvas_failed),fontSize=TextSize.body)
                    MapButton(R.string.network_canvas_change) {if(flow===shown) shown.changeTarget()}
                }
                NetworkCanvasStep.CLOSED->Unit
            }
            if(shown.step==NetworkCanvasStep.DESTINATION) {
                item("known-targets") {Text(stringResource(R.string.network_canvas_known_targets),fontSize=TextSize.heading)}
                if(targetsProblem) item("targets-problem") {Text(stringResource(R.string.graph_backlinks_problem_unavailable),fontSize=TextSize.body)}
                for(target in targets) item("target:${target.key}") {
                    MapButtonTarget(target.title.ifBlank {stringResource(R.string.map_title)},target.uri.toString(),enabled=!picker.busy) {
                        if(flow===shown && shown.current()) shown.chooseExisting(target.uri,target.title)
                    }
                }
            }
            if(picker.busy) item("picker-busy") {Text(stringResource(R.string.network_canvas_picker_busy),fontSize=TextSize.caption)}
            if(pickerProblem) item("picker-problem") {Text(stringResource(R.string.network_canvas_picker_failed),fontSize=TextSize.body)}
            if(shown.problem && shown.step==NetworkCanvasStep.CONFIRM) item("busy") {
                Text(stringResource(R.string.network_canvas_target_busy),fontSize=TextSize.body)
            }
            if(shown.canView) item("view") {MapButton(R.string.network_canvas_view) {
                if(flow===shown) shown.captureOpen()?.let {request->if(!openCanvas(entry,request)) request.finished(false)}
            }}
            if(shown.opening) item("opening") {Text(stringResource(R.string.network_preparing),fontSize=TextSize.body)}
        }
    },confirmButton={MapButton(when {
        shown.busy->R.string.map_close
        shown.step in setOf(NetworkCanvasStep.DESTINATION,NetworkCanvasStep.CONFIRM,NetworkCanvasStep.FAILED)->R.string.cancel
        else->R.string.map_done
    },onClick=::close)})
}

@Composable
private fun MapButtonTarget(title:String,location:String,enabled:Boolean,onClick:()->Unit) {
    androidx.compose.material3.TextButton(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=MinTouchTarget)) {
        Column(Modifier.fillMaxWidth()) {
            Text(title,fontSize=TextSize.body)
            Text(location,fontSize=TextSize.caption,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}
