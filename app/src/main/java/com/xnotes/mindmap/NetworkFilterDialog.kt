package com.xnotes.mindmap

import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.xnotes.R
import com.xnotes.ui.AlertDialog
import com.xnotes.ui.theme.*
import java.util.UUID
import kotlinx.coroutines.*

private data class NetworkFilterDraft(val id:String,val revision:Long,val fileIds:Set<String>?,
    val kinds:Set<NetworkFileKind>?,val folder:NetworkFolder?) {
    val filter get()=NetworkFilter(fileIds,kinds,folder)
    companion object {
        fun from(filter:NetworkFilter,revision:Long)=NetworkFilterDraft(UUID.randomUUID().toString(),revision,
            filter.fileIds,filter.kinds,filter.folder)
        val Saver=listSaver<NetworkFilterDraft?,Any>(save={draft->
            // The accepted filter lives in the retained controller. Very large unfinished dialogs
            // may close on recreation instead of exceeding Android's shared saved-state budget.
            val chars=draft?.let {it.fileIds.orEmpty().sumOf(String::length)+(it.folder?.uri?.length ?: 0)+(it.folder?.title?.length ?: 0)} ?: 0
            if(draft==null || chars>65536) emptyList() else listOf(draft.id,draft.revision,draft.fileIds!=null,ArrayList(draft.fileIds.orEmpty()),
                draft.kinds!=null,ArrayList(draft.kinds.orEmpty().map {it.name}),draft.folder?.uri.orEmpty(),draft.folder?.title.orEmpty())
        },restore={values->
            if(values.isEmpty()) null else runCatching {
                val files=if(values[2] as Boolean) (values[3] as List<*>).map {it as String}.toSet() else null
                val kinds=if(values[4] as Boolean) (values[5] as List<*>).map {NetworkFileKind.valueOf(it as String)}.toSet() else null
                val folder=(values[6] as String).takeIf {it.isNotBlank()}?.let {NetworkFolder(it,values[7] as String)}
                NetworkFilter(files,kinds,folder)
                NetworkFilterDraft(values[0] as String,values[1] as Long,files,kinds,folder)
            }.getOrNull()
        })
    }
}

/** Only its permanent toolbar button participates in layout; both editors cover the existing Canvas. */
@Composable
internal fun NetworkFilterControls(entry:FileNetworkWorkspace.Entry,enabled:Boolean) {
    val context=LocalContext.current
    val app=context.applicationContext
    val registry=requireNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
    val picker=remember(registry,entry) {NetworkFolderPicker(registry)}
    val reader=remember(app) {NetworkFileFactsReader(app)}
    val scope=rememberCoroutineScope()
    val controller=entry.controller
    var draft by rememberSaveable(entry.query.focusId,stateSaver=NetworkFilterDraft.Saver) {mutableStateOf<NetworkFilterDraft?>(null)}
    var attached by remember(entry) {mutableStateOf(true)}
    var folderRead by remember(entry) {mutableStateOf<Job?>(null)}
    var pickerError by remember(entry) {mutableStateOf(false)}
    val latestEnabled by rememberUpdatedState(enabled)
    fun sameDraft(id:String)=attached && entry.alive && draft?.id==id
    fun current(id:String)=sameDraft(id) && draft?.revision==controller.filterRevision
    fun retireFolderResult() {picker.retire();folderRead?.cancel();folderRead=null}
    fun close() {retireFolderResult();draft=null;pickerError=false}
    DisposableEffect(entry,picker) {onDispose {attached=false;picker.dispose();folderRead?.cancel();folderRead=null}}
    MapButton(R.string.network_filter,enabled=enabled) {if(attached && entry.alive && latestEnabled) {
        close();draft=NetworkFilterDraft.from(controller.filter,controller.filterRevision)
    }}
    val shown=draft ?: return
    fun dismissDraft() {if(sameDraft(shown.id)) close()}
    var folderPath by rememberSaveable(shown.id) {mutableStateOf(arrayListOf<String>())}
    var browserRevision by rememberSaveable(shown.id) {mutableStateOf(0L)}
    fun update(change:(NetworkFilterDraft)->NetworkFilterDraft) {
        if(current(shown.id) && latestEnabled) draft=draft?.let(change)
    }
    val canEdit=enabled && folderRead==null && current(shown.id)
    if(folderPath.isNotEmpty()) {
        val path=folderPath.toList()
        val page=browserRevision
        val parent=NetworkFolder(path[path.size-2],path.last())
        fun browserCurrent()=current(shown.id) && folderPath==path && browserRevision==page
        key(shown.id,shown.revision,parent.uri,page) {
            NetworkFolderBrowser(reader,parent,path.filterIndexed {index,_->index%2==1},enabled=canEdit,
                current=::browserCurrent,
                enter={child->if(browserCurrent() && latestEnabled && path.filterIndexed {i,_->i%2==0}.none {it==child.uri}) {
                    browserRevision++;folderPath=ArrayList(path+listOf(child.uri,child.title))}},
                up={if(browserCurrent() && latestEnabled && path.size>2) {browserRevision++;folderPath=ArrayList(path.dropLast(2))}},
                choose={if(browserCurrent() && latestEnabled) {update {it.copy(folder=parent)};browserRevision++;folderPath=arrayListOf()}},
                dismiss={if(sameDraft(shown.id) && browserRevision==page) {browserRevision++;folderPath=arrayListOf()}})
        }
        return
    }
    fun chooseExternal() {
        if(!canEdit || picker.busy || !current(shown.id) || !latestEnabled) return
        pickerError=false
        if(!picker.launch {uri->
            if(uri==null || !current(shown.id)) return@launch
            val fallback=context.getString(R.string.network_filter_selected_folder)
            lateinit var work:Job
            work=scope.launch(start=CoroutineStart.LAZY) {
                try {
                    val folder=readNetworkFolder(app,uri,fallback)
                    if(current(shown.id) && folderRead===work) draft=draft?.copy(folder=folder)
                } catch(cancelled:CancellationException) {throw cancelled}
                catch(_:Exception) {if(current(shown.id) && folderRead===work) pickerError=true}
                finally {if(folderRead===work) folderRead=null}
            }
            folderRead=work;work.start()
        } && current(shown.id)) pickerError=true
    }
    AlertDialog(onDismissRequest=::dismissDraft,title={Text(stringResource(R.string.network_filter_title),fontSize=TextSize.heading)},text={
        LazyColumn(Modifier.heightIn(max=MindMapNodeListHeight),verticalArrangement=Arrangement.spacedBy(Space.s8)) {
            item("notice") {
                Text(stringResource(R.string.network_filter_notice),fontSize=TextSize.caption)
                Text(stringResource(R.string.network_original_folder_notice),fontSize=TextSize.caption)
                if(!current(shown.id)) Text(stringResource(R.string.network_filter_draft_changed),fontSize=TextSize.body)
            }
            item("files") {FilterSection(R.string.network_filter_files,shown.fileIds==null,canEdit,
                all={update {it.copy(fileIds=null)}},none={update {it.copy(fileIds=emptySet())}})}
            for(file in controller.catalog) item("file:${file.documentId}") {
                FilterCheck(networkFileTitle(file),
                    shown.fileIds==null || file.documentId in shown.fileIds,canEdit) {
                    update {latest->val selected=latest.fileIds ?: controller.catalog.mapTo(linkedSetOf()) {it.documentId}
                        latest.copy(fileIds=if(file.documentId in selected) selected-file.documentId else selected+file.documentId)}
                }
            }
            item("kinds") {FilterSection(R.string.network_filter_kinds,shown.kinds==null,canEdit,
                all={update {it.copy(kinds=null)}},none={update {it.copy(kinds=emptySet())}})}
            for(kind in NetworkFileKind.entries) item("kind:${kind.name}") {
                FilterCheck(networkFileKindLabel(kind),shown.kinds==null || kind in shown.kinds,canEdit) {update {latest->
                    val selected=latest.kinds ?: NetworkFileKind.entries.toSet()
                    latest.copy(kinds=if(kind in selected) selected-kind else selected+kind)
                }}
            }
            item("folder") {Column {
                Text(stringResource(R.string.network_filter_folder),fontSize=TextSize.heading)
                Text(shown.folder?.title ?: stringResource(R.string.network_filter_folder_unrestricted),fontSize=TextSize.body)
                MapButton(R.string.network_filter_folder_internal,enabled=canEdit) {
                    if(current(shown.id) && latestEnabled) {
                        retireFolderResult()
                        // URI construction only. The reader proves the actual root exists without mkdir.
                        val uri=DocumentsContract.buildTreeDocumentUri("${context.packageName}.documents","root")
                        browserRevision++
                        folderPath=arrayListOf(uri.toString(),context.getString(R.string.network_filter_folder_internal))
                    }
                }
                MapButton(R.string.network_filter_folder_choose,enabled=canEdit && !picker.busy,onClick=::chooseExternal)
                MapButton(R.string.network_filter_folder_clear,enabled=canEdit && shown.folder!=null) {
                    retireFolderResult();update {it.copy(folder=null)}
                }
                if(picker.busy) Text(stringResource(R.string.network_filter_picker_busy),fontSize=TextSize.caption)
                if(folderRead!=null) Text(stringResource(R.string.network_filter_checking),fontSize=TextSize.caption)
                if(pickerError) Text(stringResource(R.string.network_filter_picker_failed),fontSize=TextSize.caption)
            }}
            item("reset") {MapButton(R.string.network_filter_reset,enabled=canEdit) {
                retireFolderResult();update {it.copy(fileIds=null,kinds=null,folder=null)}
            }}
        }
    },confirmButton={TextButton(onClick={
        if(draft===shown && current(shown.id) && latestEnabled && folderRead==null && controller.applyFilter(shown.filter,shown.revision)) close()
    },enabled=canEdit) {Text(stringResource(R.string.apply),fontSize=TextSize.body)}},
        dismissButton={TextButton(onClick=::dismissDraft) {Text(stringResource(R.string.cancel),fontSize=TextSize.body)}})
}

@Composable
private fun FilterSection(title:Int,unrestricted:Boolean,enabled:Boolean,all:()->Unit,none:()->Unit) {
    Column {
        Text(stringResource(title),fontSize=TextSize.heading)
        Row {
            MapButton(R.string.network_filter_unrestricted,enabled,onClick=all)
            MapButton(R.string.network_filter_none,enabled,onClick=none)
        }
        if(unrestricted) Text(stringResource(R.string.network_filter_unrestricted),fontSize=TextSize.caption)
    }
}

@Composable
private fun FilterCheck(label:String,checked:Boolean,enabled:Boolean,toggle:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=MinTouchTarget).toggleable(value=checked,enabled=enabled,
        role=Role.Checkbox,onValueChange={toggle()}),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(Space.s8)) {
        Checkbox(checked,onCheckedChange=null,enabled=enabled)
        Text(label,fontSize=TextSize.body,modifier=Modifier.weight(1f))
    }
}

@Composable
private fun NetworkFolderBrowser(reader:NetworkFileFactsReader,parent:NetworkFolder,path:List<String>,enabled:Boolean,
    current:()->Boolean,enter:(NetworkFolder)->Unit,up:()->Unit,choose:()->Unit,dismiss:()->Unit) {
    var listing by remember {mutableStateOf<NetworkFolderListing?>(null)}
    LaunchedEffect(reader,parent.uri) {
        val result=try {reader.listFolders(parent)} catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {NetworkFolderListing(emptyList(),BacklinkProblem.UNAVAILABLE)}
        if(current()) listing=result
    }
    AlertDialog(onDismissRequest=dismiss,title={Text(stringResource(R.string.network_filter_folder_internal),fontSize=TextSize.heading)},text={
        LazyColumn(Modifier.heightIn(max=MindMapNodeListHeight),verticalArrangement=Arrangement.spacedBy(Space.s8)) {
            item("path") {
                Text(path.joinToString(" / "),fontSize=TextSize.body)
                MapButton(R.string.network_filter_folder_up,enabled=enabled && path.size>1,onClick=up)
            }
            val result=listing
            if(result==null) item("loading") {Text(stringResource(R.string.loading),fontSize=TextSize.body)}
            result?.problem?.let {item("problem") {Text(stringResource(networkProblemText(it)),fontSize=TextSize.body)}}
            if(result?.limited==true) item("limit") {Text(stringResource(R.string.graph_backlinks_problem_limit),fontSize=TextSize.body)}
            if(result!=null && result.problem==null && result.folders.isEmpty()) item("empty") {
                Text(stringResource(R.string.no_folders_here),fontSize=TextSize.body)
            }
            result?.folders?.forEach {folder->item(folder.uri) {
                TextButton(onClick={if(current()) enter(folder)},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=MinTouchTarget)) {
                    Text(folder.title,fontSize=TextSize.body,modifier=Modifier.fillMaxWidth())
                }
            }}
        }
    },confirmButton={TextButton(onClick={if(current()) choose()},enabled=enabled && listing?.problem==null && listing!=null) {
        Text(stringResource(R.string.network_filter_folder_use),fontSize=TextSize.body)
    }},dismissButton={TextButton(onClick=dismiss) {Text(stringResource(R.string.cancel),fontSize=TextSize.body)}})
}
