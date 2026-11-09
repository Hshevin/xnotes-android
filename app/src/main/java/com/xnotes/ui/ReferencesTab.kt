package com.xnotes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.xnotes.R
import com.xnotes.mindmap.BacklinkGroup
import com.xnotes.mindmap.BacklinkHostHooks
import com.xnotes.mindmap.BacklinkOrigin
import com.xnotes.mindmap.BacklinkProblem
import com.xnotes.mindmap.BacklinkRemovalState
import com.xnotes.mindmap.MapBacklinkController
import com.xnotes.mindmap.MapBacklinkRow
import com.xnotes.mindmap.MapButton
import com.xnotes.mindmap.NodeKind
import com.xnotes.mindmap.SourcePreviewImage
import com.xnotes.mindmap.SourceScope
import com.xnotes.mindmap.SourceStatus
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize
import com.xnotes.ui.theme.toComposeColor

/** The current reading pane owns the lookup; closing this tab retires all of its delayed callbacks. */
@Composable
internal fun ReferencesTab(editor:Editor,hooks:BacklinkHostHooks?) {
    val version=editor.backlinkContextVersion
    val context=remember(editor,version) {editor.captureBacklinkContext()}
    val palette=LocalPalette.current
    if(context==null) {
        Text(stringResource(R.string.graph_backlinks_context_unavailable),Modifier.padding(Space.s12),
            color=palette.text.toComposeColor(),fontSize=TextSize.body)
        return
    }
    val scope=rememberCoroutineScope()
    val controller=remember(context) {MapBacklinkController(editor.view.context.applicationContext,scope,context)}
    DisposableEffect(controller) {controller.refresh();onDispose {controller.close()}}
    val report=controller.report
    val busy=hooks?.busy?.invoke()==true
    val problem=hooks?.problem?.invoke(context) ?: controller.problem
    Column(Modifier.fillMaxSize().padding(Space.s8),verticalArrangement=Arrangement.spacedBy(Space.s4)) {
        Text(stringResource(R.string.graph_backlinks_title),fontSize=TextSize.title,fontWeight=FontWeight.SemiBold,color=palette.text.toComposeColor())
        Text(stringResource(R.string.graph_backlinks_scope_notice),fontSize=TextSize.caption,color=palette.textDim.toComposeColor())
        if(context.pageIdentity!=BacklinkPageIdentity.AVAILABLE) Text(stringResource(when(context.pageIdentity) {
            BacklinkPageIdentity.NOT_ENABLED->R.string.graph_backlinks_page_not_enabled
            else->R.string.graph_backlinks_page_invalid
        }),fontSize=TextSize.caption,color=palette.textDim.toComposeColor())
        Row(horizontalArrangement=Arrangement.spacedBy(Space.s4)) {
            MapButton(R.string.graph_backlinks_refresh,enabled=!busy && !controller.loading) {controller.refresh()}
            if(controller.loading) MapButton(R.string.graph_backlinks_cancel_query) {controller.cancel()}
            if(busy && hooks?.canCancel?.invoke(context)==true) MapButton(R.string.cancel) {hooks.cancel(context)}
        }
        if(controller.loading || busy) Text(stringResource(if(busy) R.string.graph_backlinks_opening else R.string.graph_backlinks_loading),fontSize=TextSize.caption)
        problem?.let {Text(stringResource(backlinkProblemLabel(it)),fontSize=TextSize.body)}
        report?.let {
            Text(stringResource(R.string.graph_backlinks_checked,it.checkedMaps),fontSize=TextSize.caption)
            if(!it.complete) Text(stringResource(R.string.graph_backlinks_partial),fontSize=TextSize.body)
            if(it.rows.isEmpty() && !controller.loading) Text(stringResource(if(it.complete) R.string.graph_backlinks_empty_complete else R.string.graph_backlinks_empty_partial),fontSize=TextSize.body)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f),verticalArrangement=Arrangement.spacedBy(Space.s8)) {
            report?.issues?.forEachIndexed {index,issue->item("issue:$index") {
                Column {
                    issue.displayName?.let {Text(it,fontSize=TextSize.caption,maxLines=2,overflow=TextOverflow.Ellipsis)}
                    Text(stringResource(backlinkProblemLabel(issue.problem)),fontSize=TextSize.caption)
                }
            }}
            report?.takeIf {it.omittedIssues>0}?.let {item("omitted") {Text(stringResource(R.string.graph_backlinks_omitted_issues,it.omittedIssues),fontSize=TextSize.caption)}}
            report?.takeIf {it.excludedMaps>0}?.let {item("excluded") {Text(stringResource(R.string.graph_backlinks_excluded_maps,it.excludedMaps),fontSize=TextSize.caption)}}
            val groups=report?.rows.orEmpty().groupBy {row->when {
                row.target.scope==SourceScope.FILE->BacklinkGroup.WHOLE_FILE
                context.pageId!=null && row.target.pageId==context.pageId->BacklinkGroup.CURRENT_PAGE
                else->BacklinkGroup.OTHER_PAGE
            }}
            for(group in BacklinkGroup.entries) {
                val rows=groups[group].orEmpty()
                if(rows.isEmpty()) continue
                item("group:$group") {Text(stringResource(when(group) {
                    BacklinkGroup.CURRENT_PAGE->R.string.graph_backlinks_current_page
                    BacklinkGroup.OTHER_PAGE->R.string.graph_backlinks_other_pages
                    BacklinkGroup.WHOLE_FILE->R.string.graph_backlinks_whole_file
                }),fontSize=TextSize.title,fontWeight=FontWeight.SemiBold)}
                items(rows,key={it.key}) {row->BacklinkRow(row,controller,hooks,enabled=!busy && !controller.loading)}
            }
        }
    }
    controller.removal?.let {state->
        val row=state.row;val token=state.token
        val saving=state is BacklinkRemovalState.Saving
        AlertDialog(onDismissRequest={if(!saving) controller.dismissRemove(token)},title={Text(stringResource(R.string.graph_backlinks_remove_title),fontSize=TextSize.heading)},
            text={Column(verticalArrangement=Arrangement.spacedBy(Space.s8)) {
                when(state) {
                    is BacklinkRemovalState.Preparing->Text(stringResource(R.string.graph_backlinks_loading),fontSize=TextSize.body)
                    is BacklinkRemovalState.Confirm->Text(stringResource(R.string.graph_backlinks_remove_confirm,row.location.title,backlinkNodeTitle(row),row.sourceFileName,backlinkRange(row)),fontSize=TextSize.body)
                    is BacklinkRemovalState.Saving->Text(stringResource(R.string.graph_backlinks_remove_saving),fontSize=TextSize.body)
                    is BacklinkRemovalState.Removed->Text(stringResource(if(state.newerChanges) R.string.graph_backlinks_removed_newer else R.string.graph_backlinks_removed),fontSize=TextSize.body)
                    is BacklinkRemovalState.SavedChanged->Text(stringResource(if(state.newerChanges) R.string.graph_backlinks_saved_changed_newer else R.string.graph_backlinks_saved_changed),fontSize=TextSize.body)
                    is BacklinkRemovalState.Failed->Text(stringResource(backlinkProblemLabel(state.problem)),fontSize=TextSize.body)
                }
                if(state.canReview && hooks!=null) MapButton(R.string.graph_backlinks_view_node,enabled=!busy) {
                    controller.captureRemovedOpen(token)?.let {hooks.open(it)}
                }
            }},confirmButton={when(state) {
                is BacklinkRemovalState.Confirm->MapButton(R.string.remove,enabled=!busy) {controller.confirmRemove(token)}
                is BacklinkRemovalState.Failed->if(state.canRetrySave) MapButton(R.string.graph_backlinks_retry_save,enabled=!busy) {controller.retryRemoveSave(token)}
                else->Unit
            }},dismissButton={if(!saving) MapButton(if(state is BacklinkRemovalState.Preparing || state is BacklinkRemovalState.Confirm) R.string.cancel else R.string.map_done) {controller.dismissRemove(token)}})
    }
}

@Composable
private fun BacklinkRow(row:MapBacklinkRow,controller:MapBacklinkController,hooks:BacklinkHostHooks?,enabled:Boolean) {
    DisposableEffect(controller,row.key,row.target,row.preview) {
        controller.showPreview(row,true)
        onDispose {controller.showPreview(row,false)}
    }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(Space.s4)) {
        Text(row.location.title,fontSize=TextSize.body,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
        Text(backlinkNodeTitle(row),fontSize=TextSize.body,maxLines=3,overflow=TextOverflow.Ellipsis)
        if(row.sourceFileName.isNotBlank()) Text(row.sourceFileName,fontSize=TextSize.caption,maxLines=2,overflow=TextOverflow.Ellipsis)
        Text(backlinkRange(row),fontSize=TextSize.caption)
        if(row.sourceNote.isNotBlank()) Text(row.sourceNote,fontSize=TextSize.body,maxLines=4,overflow=TextOverflow.Ellipsis)
        Text(stringResource(when(row.status) {
            SourceStatus.UNVERIFIED->R.string.graph_status_unverified;SourceStatus.CURRENT->R.string.graph_status_current
            SourceStatus.STALE->R.string.graph_status_stale;SourceStatus.UNAVAILABLE->R.string.graph_status_unavailable
            SourceStatus.PARTIALLY_DELETED->R.string.graph_status_partial;SourceStatus.DELETED->R.string.graph_status_deleted
            SourceStatus.EMPTY->R.string.graph_status_empty;SourceStatus.TRASHED->R.string.graph_status_trashed
        }),fontSize=TextSize.caption)
        when(row.origin) {
            BacklinkOrigin.LIVE->Text(stringResource(R.string.graph_backlinks_origin_live),fontSize=TextSize.caption)
            BacklinkOrigin.RECOVERY->Text(stringResource(R.string.graph_backlinks_origin_recovery),fontSize=TextSize.caption)
            else->Unit
        }
        row.blocked?.let {Text(stringResource(backlinkProblemLabel(it)),fontSize=TextSize.caption)}
        if(row.preview?.depictedTarget?.let {it!=row.target}==true)
            Text(stringResource(R.string.graph_preview_old_target),fontSize=TextSize.caption)
        SourcePreviewImage(controller.previewAssets[row.key])
        when {
            row.key in controller.previewBusy->Text(stringResource(R.string.graph_backlinks_loading),fontSize=TextSize.caption)
            row.key in controller.previewFailed->Text(stringResource(R.string.graph_backlinks_preview_failed),fontSize=TextSize.caption)
            row.preview==null->Text(stringResource(R.string.graph_backlinks_preview_missing),fontSize=TextSize.caption)
        }
        Row(horizontalArrangement=Arrangement.spacedBy(Space.s4)) {
            if(hooks!=null) MapButton(R.string.open,enabled=enabled && row.blocked==null) {controller.captureOpen(row)?.let {hooks.open(it)}}
            MapButton(R.string.remove,enabled=enabled && row.blocked==null) {controller.prepareRemove(row)}
        }
        HorizontalDivider()
    }
}

@Composable
private fun backlinkNodeTitle(row:MapBacklinkRow)=row.nodeTitle.ifBlank {stringResource(when(row.kind) {
    NodeKind.TEXT->R.string.map_text_node;NodeKind.INK->R.string.map_ink_node;NodeKind.REFERENCE->R.string.graph_reference
})}
@Composable
private fun backlinkRange(row:MapBacklinkRow):String {
    val range=if(row.target.scope==SourceScope.FILE) stringResource(R.string.graph_source_whole_file)
        else row.lastKnownPage?.let {stringResource(R.string.graph_source_page_last_known,it)} ?: stringResource(R.string.graph_source_page_unknown)
    return if(row.target.region!=null) stringResource(R.string.graph_backlinks_range_region,range) else range
}

private fun backlinkProblemLabel(problem:BacklinkProblem)=when(problem) {
    BacklinkProblem.UNAVAILABLE->R.string.graph_backlinks_problem_unavailable
    BacklinkProblem.CORRUPT->R.string.graph_backlinks_problem_corrupt
    BacklinkProblem.RECOVERY_CONFLICT->R.string.graph_backlinks_problem_recovery_conflict
    BacklinkProblem.DUPLICATE->R.string.graph_backlinks_problem_duplicate
    BacklinkProblem.IDENTITY->R.string.graph_backlinks_problem_identity
    BacklinkProblem.LOADING->R.string.graph_backlinks_problem_loading
    BacklinkProblem.LIMIT->R.string.graph_backlinks_problem_limit
    BacklinkProblem.CHANGED->R.string.graph_backlinks_problem_changed
    BacklinkProblem.BUSY->R.string.graph_backlinks_problem_busy
    BacklinkProblem.SAVE_FAILED->R.string.graph_backlinks_problem_save_failed
    BacklinkProblem.TRASHED->R.string.graph_backlinks_problem_trashed
    BacklinkProblem.UNKNOWN_SCOPE->R.string.graph_backlinks_problem_unknown_scope
    BacklinkProblem.CANCELLED->R.string.graph_backlinks_problem_cancelled
}
