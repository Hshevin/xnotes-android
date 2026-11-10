package com.xnotes.mindmap

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.xnotes.R
import com.xnotes.ui.Editor
import com.xnotes.ui.theme.*

/** Pickers belong to the reading host, so a retired page cannot clear/rebind an old system result. */
@Composable
internal fun PageToMapFlowHost(editor:Editor,onSave:(Editor)->Unit,onOpenMap:(Uri,Boolean)->Unit,visible:Boolean=true,
    preferredMap:MapWorkspace.Entry?=null,preferredMapCurrent:(MapWorkspace.Entry)->Boolean={false},
    visibleSourcePane:com.xnotes.ui.Pane?=null) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val request:com.xnotes.ui.SourceCreationRequest?=editor.openPanes.filter {visibleSourcePane==null || it.pane==visibleSourcePane}.firstNotNullOfOrNull {
        it.pageReferenceRequest ?: it.canvasRegionReferenceRequest
    }
    val controller=remember(request?.id) {request?.let {PageToMapController(context,it,scope)}}
    val latest by rememberUpdatedState(controller)
    val sourceCopyPicker=com.xnotes.ui.rememberNativeDestinationPicker()
    var pickerRequest by rememberSaveable {mutableStateOf<String?>(null)}
    var pickerKind by rememberSaveable {mutableStateOf<String?>(null)}
    var pickerAccept by remember {mutableStateOf<((Uri)->Boolean)?>(null)}
    fun receive(uri:Uri?,kind:String) {
        val owner=pickerRequest;val expected=pickerKind;val accept=pickerAccept
        pickerRequest=null;pickerKind=null;pickerAccept=null
        val current=latest
        if(uri==null || expected!=kind || owner==null || current?.request?.id!=owner || !current.request.current()) return
        val resolver=context.contentResolver
        runCatching {resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
            .onFailure {runCatching {resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}}
        accept?.invoke(uri)
    }
    val choose=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {receive(it,"OPEN")}
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {receive(it,"NEW")}
    DisposableEffect(controller) {onDispose {controller?.close()}}
    LaunchedEffect(controller) {controller?.start()}
    SideEffect {
        controller?.takeIf {!it.request.current()}?.close()
    }
    controller?.takeIf {visible}?.let {flow->
        val openUri=flow.targetUri;val openContext=flow.targetContext
        PageToMapDialog(flow,pickerRequest!=null || sourceCopyPicker.busy,
            chooseOpen=preferredMap?.takeIf {it.lease.alive && preferredMapCurrent(it)}?.let {entry->{
                flow.selectOpenMap(entry) {preferredMapCurrent(entry)};Unit
            }},
            choose={if(pickerRequest==null && flow.request.current()) {
                val accept=flow.mapDestination(false)
                if(accept!=null) {pickerAccept=accept;pickerRequest=flow.request.id;pickerKind="OPEN"
                try {choose.launch(arrayOf("application/json","application/octet-stream","*/*"))}
                catch(_:Exception) {pickerRequest=null;pickerKind=null;pickerAccept=null}
                }
            }},
            create={if(pickerRequest==null && flow.request.current()) {
                val accept=flow.mapDestination(true)
                if(accept!=null) {pickerAccept=accept;pickerRequest=flow.request.id;pickerKind="NEW"
                try {create.launch(context.getString(R.string.map_title)+".xmap")}
                catch(_:Exception) {pickerRequest=null;pickerKind=null;pickerAccept=null}
                }
            }},
            save={
                if(!flow.canSaveSourceInPlace && flow.canSaveSourceCopy) {
                    if(!launchSourceCopy(flow,sourceCopyPicker,context.getString(R.string.graph_region_copy_file_name)) && flow.request.current())
                        flow.request.pane.message=context.getString(R.string.native_save_picker_busy)
                } else flow.saveSource {pane->pane.message=context.getString(if(flow.sourceNeedsIndependentSave) R.string.graph_ref_save_copy_then_retry else R.string.graph_ref_save_then_retry);onSave(pane)}
            },
            reopen={flow.reopen {flow.request.pane.message=context.getString(R.string.graph_ref_reopened_retry)}},
            open={flow.openMap(openUri,openContext) {uri->onOpenMap(uri,flow.request.flushNativeOnPresent)}},close={flow.close()})
    }
}

@Composable
private fun PageToMapDialog(flow:PageToMapController,pickerBusy:Boolean,chooseOpen:(()->Unit)?,choose:()->Unit,create:()->Unit,
                            save:()->Unit,reopen:()->Unit,open:()->Unit,close:()->Unit) {
    val session=flow.targetSession
    val doc=session?.document
    val step=flow.step
    if(step==PageToMapStep.CLOSED) return
    val hasImport=flow.hasImportedReference
    val palette=LocalPalette.current
    val targetContext=flow.targetContext
    val confirmation=flow.confirmationToken
    val modeContext=flow.sourceModeContext
    val restoreOffer=flow.originalRestoreOffer
    val newBranch=(flow.destination as? MapReferenceDestination.New)?.node?.parent
    val branchEditing=newBranch!=null && (session?.textDraft!=null || session?.textRestore!=null)
    CompositionLocalProvider(LocalInkBusy provides false) {
        AlertDialog(onDismissRequest=close,title={Text(stringResource(flow.request.titleRes),fontSize=TextSize.heading)},text={
            LazyColumn(Modifier.heightIn(max=MindMapNodeListHeight),verticalArrangement=Arrangement.spacedBy(Space.s8)) {
                flow.request.contextNoticeRes?.let {notice->item {Text(stringResource(notice),fontSize=TextSize.caption)}}
                flow.source?.let {source->item {
                    Text(source.fileName,fontSize=TextSize.title)
                    Text(sourceScopeLabel(source),fontSize=TextSize.caption)
                }}
                when(step) {
                    PageToMapStep.PREPARING,PageToMapStep.TARGET_LOADING,PageToMapStep.CONFIRMING,PageToMapStep.SAVING->item {
                        Text(stringResource(if(step==PageToMapStep.SAVING) R.string.map_saving else R.string.graph_ref_preparing),fontSize=TextSize.body)
                    }
                    PageToMapStep.SOURCE_MODE->item {
                        Text(stringResource(R.string.graph_source_mode_notice),fontSize=TextSize.body)
                        Text(stringResource(R.string.graph_source_mode_original_retention),fontSize=TextSize.caption)
                        flow.sourceModeIssue?.let {issue->Text(stringResource(when(issue) {
                            com.xnotes.ui.SourceCreationIssue.ORIGINAL_REGION->R.string.graph_source_original_region_unavailable
                            com.xnotes.ui.SourceCreationIssue.ORIGINAL_UNAVAILABLE->R.string.graph_source_original_unavailable
                            com.xnotes.ui.SourceCreationIssue.ORIGINAL_CHANGED->R.string.graph_source_original_changed
                        }),fontSize=TextSize.body)}
                        if(com.xnotes.ui.SourceCreationMode.NOTE_PAGE in flow.sourceModes)
                            MapButton(R.string.graph_source_mode_note) {flow.selectSourceMode(com.xnotes.ui.SourceCreationMode.NOTE_PAGE,modeContext)}
                        if(com.xnotes.ui.SourceCreationMode.PDF_ORIGINAL in flow.sourceModes)
                            MapButton(R.string.graph_source_mode_original) {flow.selectSourceMode(com.xnotes.ui.SourceCreationMode.PDF_ORIGINAL,modeContext)}
                    }
                    PageToMapStep.ORIGINAL_RESTORE_REQUIRED->item {
                        Text(stringResource(R.string.graph_source_original_restore_notice),fontSize=TextSize.body)
                        MapButton(R.string.graph_source_original_restore) {flow.restoreOriginal(restoreOffer)}
                    }
                    PageToMapStep.SAVE_REQUIRED->item {
                        Text(stringResource(when {
                            flow.canSaveSourceInPlace->R.string.graph_ref_save_in_place_notice
                            flow.canSaveSourceCopy->R.string.graph_region_save_copy_notice
                            flow.sourceNeedsIndependentSave->R.string.graph_ref_save_copy_then_retry
                            else->R.string.graph_ref_save_then_retry
                        }),fontSize=TextSize.body)
                        MapButton(when {flow.canSaveSourceInPlace->R.string.save;flow.canSaveSourceCopy->R.string.graph_region_save_copy;else->R.string.save_as_ellipsis},enabled=!pickerBusy,onClick=save)
                    }
                    PageToMapStep.SOURCE_COPY_RETAINED->item {
                        Text(stringResource(R.string.graph_region_copy_retained),fontSize=TextSize.body)
                        MapButton(R.string.graph_region_continue_copy) {flow.continueSourceCopy()}
                    }
                    PageToMapStep.SOURCE_COPY_WRITTEN->item {
                        Text(stringResource(R.string.graph_region_copy_written),fontSize=TextSize.body)
                    }
                    PageToMapStep.ENABLE_REQUIRED->item {
                        Text(stringResource(R.string.graph_ref_enable_notice),fontSize=TextSize.body)
                        MapButton(R.string.graph_source_enable_confirm) {flow.enablePages()}
                    }
                    PageToMapStep.ENABLE_RETAINED->item {
                        Text(stringResource(R.string.graph_source_activation_failed),fontSize=TextSize.body)
                        MapButton(R.string.graph_source_complete_activation) {flow.continueEnable()}
                    }
                    PageToMapStep.REOPEN_REQUIRED->item {
                        Text(stringResource(R.string.graph_ref_reopen_notice),fontSize=TextSize.body)
                        MapButton(R.string.graph_ref_reopen,onClick=reopen)
                    }
                    PageToMapStep.DESTINATION->item {
                        Text(stringResource(R.string.graph_ref_destination_notice),fontSize=TextSize.body)
                        if(pickerBusy) Text(stringResource(R.string.native_save_picker_busy),fontSize=TextSize.caption)
                        chooseOpen?.let {MapButton(R.string.graph_ref_other_pane_map,enabled=!pickerBusy,onClick=it)}
                        MapButton(R.string.graph_ref_choose_map,enabled=!pickerBusy,onClick=choose)
                        MapButton(R.string.map_new,enabled=!pickerBusy,onClick=create)
                    }
                    PageToMapStep.TARGET->{
                        item {
                            Text(stringResource(R.string.graph_ref_choose_node),fontSize=TextSize.body)
                            if(session?.conflict==true || session?.recovered==true)
                                Text(stringResource(R.string.graph_ref_target_recovery),fontSize=TextSize.caption)
                            if(session?.conflict==true || session?.recovered==true) MapButton(R.string.graph_ref_view_map,onClick=open)
                            if(session?.inkContact==true) Text(stringResource(R.string.graph_ref_wait_pen),fontSize=TextSize.caption)
                            if(branchEditing) {
                                Text(stringResource(R.string.graph_ref_finish_edit),fontSize=TextSize.caption)
                                MapButton(R.string.graph_ref_view_map,onClick=open)
                            }
                            MapButton(R.string.graph_ref_new_branch,selected=(flow.destination as? MapReferenceDestination.New)?.node?.parent!=null) {flow.chooseNewReference(true,expectedContext=targetContext)}
                            MapButton(R.string.graph_ref_new_free,selected=(flow.destination as? MapReferenceDestination.New)?.node?.free==true) {flow.chooseNewReference(false,expectedContext=targetContext)}
                            val draft=flow.destination as? MapReferenceDestination.New
                            if(newBranch!=null && doc!=null) Text(stringResource(R.string.graph_ref_branch_parent,relationNodeTitle(doc,newBranch)),fontSize=TextSize.caption)
                            if(draft!=null) OutlinedTextField(flow.newNodeTitle,{flow.renameNewReference(it,draft.node.id,targetContext)},
                                label={Text(stringResource(R.string.graph_display_title),fontSize=TextSize.body)},
                                textStyle=androidx.compose.ui.text.TextStyle(fontSize=TextSize.body))
                        }
                        items(doc?.nodes?.size ?: 0,key={doc!!.nodes[it].id}) {index->
                            val node=doc!!.nodes[index]
                            val selected=(flow.destination as? MapReferenceDestination.Existing)?.nodeId==node.id
                            TextButton(onClick={flow.chooseExistingNode(node.id,targetContext)},modifier=Modifier.fillMaxWidth().heightIn(min=MinTouchTarget)
                                .then(if(selected) Modifier.background(palette.text.toComposeColor()) else Modifier),
                                colors=ButtonDefaults.textButtonColors(contentColor=if(selected) palette.panel.toComposeColor() else palette.text.toComposeColor())) {
                                Text(relationNodeTitle(doc,node.id),fontSize=TextSize.body)
                            }
                        }
                        item {MapButton(R.string.graph_ref_change_map) {flow.changeMap()}}
                    }
                    PageToMapStep.ADDED->item {
                        Text(stringResource(R.string.graph_ref_added),fontSize=TextSize.body)
                        if(session?.dirty==true) Text(stringResource(R.string.graph_ref_newer_changes),fontSize=TextSize.caption)
                        MapButton(R.string.graph_ref_view_map,onClick=open)
                    }
                    PageToMapStep.SAVE_FAILED->item {
                        Text(stringResource(R.string.graph_ref_save_failed),fontSize=TextSize.body)
                        MapButton(R.string.graph_ref_retry_save) {flow.retrySave()}
                        MapButton(R.string.graph_ref_view_map,onClick=open)
                    }
                    PageToMapStep.CREATE_RETAINED->item {
                        Text(stringResource(R.string.graph_ref_create_retained),fontSize=TextSize.body)
                        MapButton(R.string.graph_ref_open_retained) {flow.openRetained()}
                        MapButton(R.string.graph_ref_view_map,onClick=open)
                    }
                    PageToMapStep.SOURCE_ERROR->item {
                        Text(stringResource(R.string.graph_ref_source_changed),fontSize=TextSize.body)
                        MapButton(R.string.graph_ref_retry_source) {flow.start()}
                    }
                    PageToMapStep.TARGET_ERROR->item {
                        Text(stringResource(R.string.graph_ref_target_changed),fontSize=TextSize.body)
                        if(!hasImport) {
                            MapButton(R.string.graph_ref_choose_map,enabled=!pickerBusy,onClick=choose)
                            MapButton(R.string.map_new,enabled=!pickerBusy,onClick=create)
                        }
                        if(flow.targetUri!=null) MapButton(R.string.graph_ref_view_map,onClick=open)
                    }
                    PageToMapStep.CLOSED->Unit
                }
                if(flow.canChangeSourceMode && step!=PageToMapStep.SOURCE_MODE) item {
                    MapButton(R.string.graph_source_mode_change,enabled=!pickerBusy) {flow.changeSourceMode()}
                }
            }
        },confirmButton={
            if(step==PageToMapStep.TARGET) MapButton(R.string.graph_ref_add,enabled=flow.destination!=null && !branchEditing && session?.inkContact!=true && session?.closing!=true && session?.replacingDocument!=true) {flow.confirm(confirmation)}
            else if(step==PageToMapStep.ADDED) MapButton(R.string.map_done,onClick=close)
        },dismissButton={MapButton(if(hasImport) R.string.map_close else R.string.cancel,onClick=close)})
    }
}

/** The real Canvas fallback consumer: its callback belongs to the original flow generation. */
internal fun launchSourceCopy(flow:PageToMapController,picker:com.xnotes.ui.NativeDestinationPicker,name:String):Boolean {
    val accept=flow.sourceCopyDestination(name) ?: return false
    return picker.launch(name) {uri->if(uri!=null) accept(uri)}
}
