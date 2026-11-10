package com.xnotes.mindmap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.R
import com.xnotes.ui.theme.*

/** A separate, explicitly confirmed target transaction. The ordinary source list stays its owner. */
@Composable
internal fun SourceTargetEditDialog(controller:SourceTargetEditController,shown:SourceTargetEditState,
    filePickerAvailable:Boolean=true,launchFile:(SourceTargetPickerSlot)->Unit) {
    if(controller.state?.id!=shown.id) return
    var choosePage by remember(shown.id) {mutableStateOf(false)}
    var activate by remember(shown.id) {mutableStateOf(false)}
    val id=shown.id
    val accepted=shown.ticket.accepted
    val title=if(shown.ticket.mode==SourceTargetEditMode.RANGE) R.string.source_target_adjust else R.string.source_target_replace
    Dialog(onDismissRequest={controller.cancel(id)},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(color=LocalPalette.current.menuBg.toComposeColor(),contentColor=LocalPalette.current.text.toComposeColor()) {
            Column(Modifier.fillMaxSize().padding(Space.s16),verticalArrangement=Arrangement.spacedBy(Space.s8)) {
                Row(horizontalArrangement=Arrangement.spacedBy(Space.s8)) {
                    Text(stringResource(title),fontSize=TextSize.heading,modifier=Modifier.weight(1f))
                    MapButton(if(accepted) R.string.source_target_return else R.string.cancel) {controller.cancel(id)}
                }
                if(shown.displayName.isNotBlank()) Text(shown.displayName.take(4096),fontSize=TextSize.title,maxLines=2,overflow=TextOverflow.Ellipsis)
                controller.displayedProblem(id)?.let {Text(stringResource(sourceTargetProblemText(it)),fontSize=TextSize.body)}
                when(shown.step) {
                    SourceTargetEditStep.CHOOSE_SOURCE->{
                        Text(stringResource(R.string.source_target_replace_notice),fontSize=TextSize.body)
                        Text(stringResource(R.string.source_target_pdf_notice),fontSize=TextSize.caption)
                        Row(horizontalArrangement=Arrangement.spacedBy(Space.s8)) {
                            MapButton(R.string.source_target_choose_file,enabled=filePickerAvailable && controller.pendingPicker==null) {
                                controller.capturePicker(id)?.let(launchFile)
                            }
                            MapButton(R.string.source_target_choose_original,enabled=filePickerAvailable && controller.pendingPicker==null) {controller.loadOriginals(expectedId=id)}
                        }
                        shown.originalPage?.let {OriginalTargetChoices(controller,shown,it,Modifier.weight(1f))}
                    }
                    SourceTargetEditStep.LOADING->Text(stringResource(R.string.source_target_loading),fontSize=TextSize.body)
                    SourceTargetEditStep.RETAIN_REQUIRED->{
                        Text(stringResource(R.string.source_target_retain_notice),fontSize=TextSize.body)
                        MapButton(R.string.source_target_retain) {controller.retainPdf(id)}
                        MapButton(R.string.source_target_choose_another) {controller.chooseAnotherSource(id)}
                    }
                    SourceTargetEditStep.RESTORE_REQUIRED->{
                        Text(stringResource(R.string.source_target_restore_notice),fontSize=TextSize.body)
                        MapButton(R.string.source_target_restore) {controller.restorePdf(id)}
                        MapButton(R.string.source_target_choose_another) {controller.chooseAnotherSource(id)}
                    }
                    SourceTargetEditStep.VISUAL->{
                        Text(stringResource(R.string.source_target_saved_content),fontSize=TextSize.caption)
                        if(shown.kind==SourceRangeKind.PDF_ORIGINAL)
                            Text(stringResource(R.string.source_target_original_page_notice),fontSize=TextSize.caption)
                        Row(horizontalArrangement=Arrangement.spacedBy(Space.s4)) {
                            if(shown.kind!=SourceRangeKind.PDF_ORIGINAL) MapButton(R.string.graph_source_whole_file,
                                selected=shown.selectionScope==SourceRangeScope.WHOLE_FILE) {controller.chooseScope(SourceRangeScope.WHOLE_FILE,id)}
                            if(shown.kind in setOf(SourceRangeKind.NOTE,SourceRangeKind.PDF_ORIGINAL)) MapButton(R.string.source_target_whole_page,
                                enabled=shown.frame?.pageId!=null,selected=shown.selectionScope==SourceRangeScope.WHOLE_PAGE) {controller.chooseScope(SourceRangeScope.WHOLE_PAGE,id)}
                            if(shown.frame?.regionAllowed==true) MapButton(R.string.source_target_region,
                                selected=shown.selectionScope==SourceRangeScope.REGION) {controller.chooseScope(SourceRangeScope.REGION,id)}
                        }
                        if(shown.kind in setOf(SourceRangeKind.NOTE,SourceRangeKind.PDF_ORIGINAL)) Row(horizontalArrangement=Arrangement.spacedBy(Space.s8)) {
                            MapButton(R.string.source_target_pages,enabled=shown.pages.isNotEmpty()) {choosePage=true}
                            shown.pages.firstOrNull {it.id==shown.frame?.pageId}?.let {Text(stringResource(R.string.graph_source_page_number,it.number),fontSize=TextSize.body)}
                            if(shown.kind==SourceRangeKind.PDF_ORIGINAL && shown.frame?.pageId!=null)
                                MapButton(R.string.source_target_rotate) {controller.choosePage(shown.frame.pageId,(shown.frame.viewTurns+1)%4,id)}
                            if(shown.needsPageActivation) MapButton(R.string.graph_source_enable_pages) {activate=true}
                        }
                        if(shown.canResumeActivation) MapButton(R.string.graph_source_complete_activation) {activate=true}
                        if(shown.kind==SourceRangeKind.MAP) Text(stringResource(R.string.source_target_map_whole_only),fontSize=TextSize.body)
                        else SourceTargetVisualSurface(controller,shown,Modifier.weight(1f))
                        Row(horizontalArrangement=Arrangement.spacedBy(Space.s8)) {
                            MapButton(if(shown.ticket.mode==SourceTargetEditMode.RANGE) R.string.source_target_confirm_range else R.string.source_target_confirm_replace,
                                enabled=controller.canConfirm(id)) {controller.confirm(id)}
                            MapButton(R.string.source_target_reload,enabled=!controller.visualBusy) {controller.reload(id)}
                            if(shown.ticket.mode==SourceTargetEditMode.REPLACE) MapButton(R.string.source_target_choose_another,enabled=!controller.visualBusy) {controller.chooseAnotherSource(id)}
                        }
                    }
                    SourceTargetEditStep.CONFIRMING->Text(stringResource(R.string.source_target_confirming),fontSize=TextSize.body)
                    SourceTargetEditStep.SAVING->Text(stringResource(R.string.source_target_saving),fontSize=TextSize.body)
                    SourceTargetEditStep.SAVED->{
                        val outcome=shown.saveOutcome as? MapSaveOutcome.Written
                        Text(stringResource(if(outcome==null) R.string.source_target_unchanged else if(outcome.latest)
                            R.string.source_target_saved else R.string.source_target_saved_later_edits),fontSize=TextSize.body)
                        MapButton(R.string.source_target_return) {controller.cancel(id)}
                    }
                    SourceTargetEditStep.SAVE_FAILED->{
                        Text(stringResource(R.string.source_target_save_failed),fontSize=TextSize.body)
                        MapButton(R.string.source_target_retry_save) {controller.retrySave(id)}
                        MapButton(R.string.source_target_return) {controller.cancel(id)}
                    }
                    SourceTargetEditStep.FAILED,SourceTargetEditStep.CHANGED->{
                        MapButton(R.string.source_target_reload) {controller.reload(id)}
                        if(shown.canResumeActivation) MapButton(R.string.graph_source_complete_activation) {activate=true}
                        MapButton(R.string.source_target_return) {controller.cancel(id)}
                    }
                }
            }
        }
    }
    if(choosePage && controller.state?.id==id) AlertDialog(onDismissRequest={choosePage=false},
        title={Text(stringResource(R.string.source_target_pages),fontSize=TextSize.heading)},text={
            LazyColumn(Modifier.heightIn(max=MindMapNodeListHeight)) {
                items(shown.pages.size,key={shown.pages[it].id}) {index->val page=shown.pages[index]
                    TextButton(onClick={choosePage=false;controller.choosePage(page.id,expectedId=id)},modifier=Modifier.fillMaxWidth().heightIn(min=MinTouchTarget)) {
                        Text(stringResource(R.string.graph_source_page_number,page.number),fontSize=TextSize.body)
                    }
                }
            }
        },confirmButton={MapButton(R.string.cancel) {choosePage=false}})
    if(activate && controller.state?.id==id) AlertDialog(onDismissRequest={activate=false},
        title={Text(stringResource(R.string.graph_source_enable_pages),fontSize=TextSize.heading)},
        text={Text(stringResource(R.string.graph_source_enable_notice),fontSize=TextSize.body)},
        confirmButton={MapButton(if(shown.canResumeActivation) R.string.graph_source_complete_activation else R.string.graph_source_enable_confirm) {
            activate=false;if(shown.canResumeActivation) controller.completeActivation(id) else controller.activatePages(id)
        }},dismissButton={MapButton(R.string.cancel) {activate=false}})
}

@Composable
private fun OriginalTargetChoices(controller:SourceTargetEditController,shown:SourceTargetEditState,page:OriginalPickerPage,modifier:Modifier) {
    val id=shown.id
    Column(modifier,verticalArrangement=Arrangement.spacedBy(Space.s8)) {
        HorizontalDivider()
        if(!page.supported) Text(stringResource(R.string.source_target_original_unsupported),fontSize=TextSize.body)
        if(page.entries.isEmpty()) Text(stringResource(R.string.source_target_original_empty),fontSize=TextSize.body)
        LazyColumn(Modifier.weight(1f)) {
            items(page.entries.size) {index->val row=page.entries[index]
                TextButton(onClick={controller.chooseOriginal(row,id)},enabled=row.state==OriginalPickerState.ROW_AVAILABLE && row.current(),
                    modifier=Modifier.fillMaxWidth().heightIn(min=MinTouchTarget)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(row.title?.take(4096) ?: stringResource(R.string.source_target_original_unavailable),fontSize=TextSize.body,maxLines=2,overflow=TextOverflow.Ellipsis)
                        if(row.state==OriginalPickerState.ROW_DELETED) Text(stringResource(R.string.source_target_original_deleted),fontSize=TextSize.caption)
                        else if(row.state!=OriginalPickerState.ROW_AVAILABLE) Text(stringResource(R.string.source_target_original_unavailable),fontSize=TextSize.caption)
                    }
                }
            }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(Space.s8)) {
            MapButton(R.string.source_target_refresh_list) {controller.loadOriginals(expectedId=id)}
            page.next?.let {next->MapButton(R.string.source_target_more,enabled=next.current()) {controller.loadOriginals(next,id)}}
        }
    }
}
internal fun sourceTargetProblemText(problem:SourceTargetEditProblem)=when(problem) {
    SourceTargetEditProblem.READ,SourceTargetEditProblem.RETAIN->R.string.source_target_read_failed
    SourceTargetEditProblem.CHANGED->R.string.source_target_changed
    SourceTargetEditProblem.INVALID_RANGE->R.string.source_target_invalid_range
    SourceTargetEditProblem.PAGE_REQUIRED->R.string.source_target_page_required
    SourceTargetEditProblem.ACTIVATION->R.string.source_target_activation_failed
    SourceTargetEditProblem.SAVE->R.string.source_target_save_failed
}
