package com.xnotes.mindmap

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.xnotes.R
import com.xnotes.ui.theme.*

/** Actions stay with the current Main composition; the retained workspace stores no such callbacks. */
internal class FileNetworkHostActions(
    val open:(FileNetworkWorkspace.Entry,LocalNetworkEvidence,LocalNetworkEvidence)->Boolean,
    val close:(FileNetworkWorkspace.Entry)->Unit,
    val busy:()->Boolean,
    val cancel:(FileNetworkWorkspace.Entry)->Unit,
    val feedback:(FileNetworkWorkspace.Entry)->BacklinkProblem?,
)

private data class NetworkOpeningVisual(val report:LocalFileNetworkReport,val scene:LocalNetworkScene)

@Composable
internal fun FileNetworkWorkspaceHost(workspace:FileNetworkWorkspace,actions:FileNetworkHostActions,
    nativeContent:@Composable ()->Unit) {
    if(!workspace.visible) nativeContent()
    workspace.current?.takeIf {workspace.visible}?.let {entry->key(entry) {FileNetworkScreen(entry,actions)}}
}

@Composable
private fun FileNetworkScreen(entry:FileNetworkWorkspace.Entry,actions:FileNetworkHostActions) {
    val controller=entry.controller;val currentReport=controller.report;val presentation=entry.presentation
    val controls=controller.filterRevision
    val observedControls=remember(entry) {longArrayOf(controls)}
    val labels=LocalNetworkLabels(stringResource(R.string.network_file),stringResource(R.string.network_concept),
        stringResource(R.string.map_ink_node),stringResource(R.string.graph_reference),
        stringResource(R.string.network_reference_count),stringResource(R.string.graph_belongs),stringResource(R.string.network_empty_overview))
    val busy=actions.busy()
    var openingVisual by remember(entry) {mutableStateOf<NetworkOpeningVisual?>(null)}
    val held=openingVisual.takeIf {busy}
    val report=held?.report ?: currentReport
    // Do not feed an undisplayed LOADING report into the stable-position builder either.
    val scene=held?.scene ?: remember(report,labels) {report?.let {entry.builder.build(it,labels)}}
    var localProblem by remember(entry) {mutableStateOf<BacklinkProblem?>(null)}
    BackHandler {if(busy) actions.cancel(entry) else actions.close(entry)}
    val dark=LocalPalette.current.isDark
    XnotesTheme(monochromePalette(dark)) {
        SideEffect {
            if(observedControls[0]!=controls) {observedControls[0]=controls;presentation.invalidateInput()}
            scene?.let(presentation::reconcile);if(!busy) openingVisual=null
        }
        Surface(Modifier.fillMaxSize(),color=LocalPalette.current.panel.toComposeColor()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=Space.s8)) {
                    MapButton(R.string.map_close) {actions.close(entry)}
                    Text(stringResource(if(controller.browseMode==NetworkBrowseMode.LOCAL) R.string.network_title else R.string.network_library_title),
                        fontSize=TextSize.title,modifier=Modifier.padding(Space.s12))
                    NetworkScopeControls(entry,enabled=!busy && !controller.loading && !controller.filtering)
                    NetworkFilterControls(entry,enabled=!busy && !controller.loading && !controller.filtering)
                    if(controller.browseMode!=NetworkBrowseMode.LIBRARY) MapButton(R.string.network_direct_only,enabled=!busy && !controller.loading && !controller.filtering && controller.expandedFiles.isNotEmpty()) {
                        if(controller.collapseAll(controls)) presentation.invalidateInput()
                    }
                    MapButton(R.string.network_refresh,enabled=!busy && !controller.loading && !controller.filtering) {localProblem=null;controller.refresh()}
                    MapButton(R.string.network_fit,enabled=presentation.idle && !busy && scene!=null) {presentation.requestFit()}
                    if(controller.loading || controller.filtering) MapButton(R.string.graph_backlinks_cancel_query) {controller.cancel()}
                    if(busy) MapButton(R.string.cancel) {actions.cancel(entry)}
                }
                Text(when(controller.browseMode) {
                    NetworkBrowseMode.LOCAL->entry.query.title
                    NetworkBrowseMode.FILES->stringResource(R.string.network_scope_centres,controller.centreFiles.size)
                    NetworkBrowseMode.LIBRARY->stringResource(R.string.network_scope_inventory)
                },Modifier.padding(horizontal=Space.s12),fontSize=TextSize.body,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(stringResource(R.string.network_semantics),Modifier.padding(Space.s12),fontSize=TextSize.caption)
                HorizontalDivider()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val canvas:@Composable (Modifier)->Unit={modifier->
                        scene?.let {LocalNetworkCanvas(it,presentation,true,modifier)} ?: Box(modifier)
                    }
                    val details:@Composable (Modifier)->Unit={modifier->NetworkDetails(entry,report,modifier,controls,
                        enabled=!busy && !controller.loading && !controller.filtering && presentation.idle && presentation.cameraReady,
                        open={anchor,target->
                            // Acquiring a stored map creates a live LOADING actor. Hold only the visible
                            // diagram; the controller and final source proofs continue checking live data.
                            openingVisual=if(report!=null && scene!=null) NetworkOpeningVisual(report,scene) else null
                            if(actions.open(entry,anchor,target)) localProblem=null
                            else {openingVisual=null;localProblem=BacklinkProblem.CHANGED}
                        })}
                    if(maxWidth>=LayerSidebarWidth*2) Row(Modifier.fillMaxSize()) {
                        canvas(Modifier.weight(1f).fillMaxHeight())
                        details(Modifier.width(LayerSidebarWidth).fillMaxHeight())
                    } else Column(Modifier.fillMaxSize()) {
                        canvas(Modifier.weight(1f).fillMaxWidth())
                        details(Modifier.fillMaxWidth().heightIn(max=MindMapNodeListHeight))
                    }
                    // Progress and errors must not resize the canvas and retire an in-flight input proof.
                    val problem=actions.feedback(entry) ?: localProblem ?: controller.problem
                    if(currentReport!=null || controller.loading || controller.filtering || busy || problem!=null) Surface(
                        Modifier.align(Alignment.TopStart).padding(Space.s8),color=LocalPalette.current.panel.toComposeColor()) {
                        Column(Modifier.padding(Space.s8)) {
                            currentReport?.let {data->
                                Text(stringResource(R.string.network_counts,data.files.size,data.concepts.size,data.checkedMaps),fontSize=TextSize.caption)
                                if(!data.complete) Text(stringResource(R.string.graph_backlinks_partial),fontSize=TextSize.body)
                                if(data.limited) Text(stringResource(R.string.graph_backlinks_problem_limit),fontSize=TextSize.body)
                                if(data.filterActive) Text(stringResource(R.string.network_filter_summary,data.filteredConcepts),fontSize=TextSize.caption)
                                if(data.unknownFilterFiles.isNotEmpty()) Text(stringResource(R.string.network_filter_unknown,data.unknownFilterFiles.size),fontSize=TextSize.caption)
                            }
                            if(controller.filtering) Text(stringResource(R.string.network_filter_checking),fontSize=TextSize.body)
                            if(controller.loading || busy) Text(stringResource(if(busy) R.string.network_opening_snapshot else R.string.graph_backlinks_loading),fontSize=TextSize.body)
                            problem?.let {Text(stringResource(networkProblemText(it)),fontSize=TextSize.body)}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkDetails(entry:FileNetworkWorkspace.Entry,report:LocalFileNetworkReport?,modifier:Modifier,
    controls:Long,enabled:Boolean,open:(LocalNetworkEvidence,LocalNetworkEvidence)->Unit) {
    val p=entry.presentation
    val selectedNode=p.selectedNode;val selectedEdge=p.selectedEdge
    val concepts=report?.concepts.orEmpty()
    val selected=when {
        selectedEdge is NetworkSceneEdgeKey.References->concepts.filter {it.key==selectedEdge.concept}
        selectedEdge is NetworkSceneEdgeKey.Belongs->concepts.filter {it.key==selectedEdge.concept}
        selectedNode is NetworkSceneNodeKey.Concept->concepts.filter {it.key==selectedNode.key}
        selectedNode is NetworkSceneNodeKey.File->concepts.filter {c->c.location.graphId==selectedNode.documentId || c.members.any {it.fileId==selectedNode.documentId}}
        else->emptyList()
    }
    LazyColumn(modifier.padding(Space.s12),verticalArrangement=Arrangement.spacedBy(Space.s8)) {
        item {
            Text(stringResource(R.string.network_explanation),fontSize=TextSize.heading)
            if(selectedEdge is NetworkSceneEdgeKey.Belongs) Text(stringResource(R.string.network_ownership),fontSize=TextSize.body)
            else Text(stringResource(R.string.network_content_evidence),fontSize=TextSize.caption)
            val file=when {
                selectedEdge is NetworkSceneEdgeKey.References->selectedEdge.fileId
                selectedNode is NetworkSceneNodeKey.File->selectedNode.documentId
                else->null
            }
            if(report?.filterActive==true && file!=null && file !in report.matchedFiles)
                Text(stringResource(R.string.network_filter_context),fontSize=TextSize.caption)
            if(selectedNode is NetworkSceneNodeKey.File) {
                val id=selectedNode.documentId
                val expanded=id in entry.controller.expandedFiles
                Text(report?.files?.firstOrNull {it.documentId==id}?.observedNames?.firstOrNull().orEmpty()
                    .ifBlank {stringResource(R.string.network_file)},fontSize=TextSize.body)
                if(selected.isEmpty()) Text(stringResource(R.string.network_file_no_displayed_refs),fontSize=TextSize.caption)
                if(entry.controller.browseMode!=NetworkBrowseMode.LIBRARY &&
                    (entry.controller.browseMode==NetworkBrowseMode.LOCAL && id==entry.query.focusId ||
                        entry.controller.browseMode==NetworkBrowseMode.FILES && id in entry.controller.centreFiles))
                    Text(stringResource(R.string.network_expansion_root),fontSize=TextSize.caption)
                else if(entry.controller.browseMode!=NetworkBrowseMode.LIBRARY) MapButton(if(expanded) R.string.network_collapse_file else R.string.network_expand_file,enabled=enabled) {
                    if(entry.controller.setExpanded(id,!expanded,controls)) p.invalidateInput()
                }
            }
        }
        if(p.candidates.size>1) item {Column {
            Text(stringResource(R.string.network_overlapping),fontSize=TextSize.body)
            for(candidate in p.candidates) {
                val c=concepts.firstOrNull {it.key==when(candidate) {
                    is NetworkSceneEdgeKey.References->candidate.concept;is NetworkSceneEdgeKey.Belongs->candidate.concept
                }}
                NetworkSelectButton(stringResource(R.string.network_edge_choice,c?.title.orEmpty(),
                    stringResource(if(candidate is NetworkSceneEdgeKey.References) R.string.graph_referenced else R.string.graph_belongs))) {
                    p.selectEdge(candidate,p.candidates)
                }
            }
        }}
        report?.issues?.forEachIndexed {index,issue->item("issue:$index") {
            issue.displayName?.let {Text(it,fontSize=TextSize.caption)}
            Text(stringResource(networkProblemText(issue.problem)),fontSize=TextSize.caption)
        }}
        report?.takeIf {it.omittedIssues>0}?.let {item("omitted") {Text(stringResource(R.string.graph_backlinks_omitted_issues,it.omittedIssues),fontSize=TextSize.caption)}}
        if(report!=null && concepts.isEmpty()) item {Text(stringResource(when {
            !report.complete->R.string.graph_backlinks_empty_partial
            report.filterActive->R.string.network_filter_empty
            report.browseMode!=NetworkBrowseMode.LOCAL && report.files.isNotEmpty()->R.string.network_no_displayed_refs
            report.browseMode!=NetworkBrowseMode.LOCAL->R.string.network_empty_overview
            else->R.string.network_empty
        }),fontSize=TextSize.body)}
        for(c in selected) {
            item("concept:${c.key.stableKey}") {Column(verticalArrangement=Arrangement.spacedBy(Space.s4)) {
                Text(c.location.title,fontSize=TextSize.body,fontWeight=FontWeight.SemiBold)
                NetworkSelectButton(c.title.ifBlank {stringResource(R.string.network_unnamed_concept)}) {
                    p.selectNode(NetworkSceneNodeKey.Concept(c.key))
                }
                if(c.key in report?.contextConcepts.orEmpty()) Text(stringResource(R.string.network_expansion_context),fontSize=TextSize.caption)
                c.origin?.let {origin->
                    val file=report?.files?.firstOrNull {it.documentId==origin.fileId}?.observedNames?.firstOrNull().orEmpty()
                        .ifBlank {stringResource(R.string.network_file)}
                    Text(stringResource(if(origin.kind==NetworkOriginKind.SOURCE) R.string.network_origin_source else R.string.network_origin_owner,
                        file,origin.depth),fontSize=TextSize.caption)
                    if(origin.path.isNotEmpty()) {
                        Text(stringResource(R.string.network_expansion_path),fontSize=TextSize.caption)
                        for(step in origin.path) Text(stringResource(R.string.network_expansion_path_step,step.location.title,
                            step.nodeTitle.ifBlank {stringResource(R.string.network_unnamed_concept)},
                            step.sourceFileName.ifBlank {stringResource(R.string.network_file)}),fontSize=TextSize.caption)
                    }
                }
            }}
            val sourceFile=when {
                selectedEdge is NetworkSceneEdgeKey.References->selectedEdge.fileId
                selectedNode is NetworkSceneNodeKey.File && c.members.any {it.fileId==selectedNode.documentId}->selectedNode.documentId
                else->null
            }
            for(evidence in c.members.filter {sourceFile==null || it.fileId==sourceFile}) {
                val anchor=if(c.origin?.kind==NetworkOriginKind.OWNER) evidence else
                    c.members.firstOrNull {it.fileId==(c.origin?.fileId ?: entry.query.focusId)}
                val row=evidence.row
                item("source:${row.key}") {Column(verticalArrangement=Arrangement.spacedBy(Space.s4)) {
                    Text(row.sourceFileName.ifBlank {stringResource(R.string.network_file)},fontSize=TextSize.body)
                    if(report?.filterActive==true && evidence.fileId !in report.matchedFiles)
                        Text(stringResource(R.string.network_filter_context),fontSize=TextSize.caption)
                    val range=if(row.target.scope==SourceScope.FILE) stringResource(R.string.graph_source_whole_file)
                        else row.lastKnownPage?.let {stringResource(R.string.graph_source_page_last_known,it)} ?: stringResource(R.string.graph_source_page_unknown)
                    Text(if(row.target.region!=null) stringResource(R.string.graph_backlinks_range_region,range) else range,fontSize=TextSize.caption)
                    if(row.sourceNote.isNotBlank()) Text(row.sourceNote,fontSize=TextSize.body)
                    Text(stringResource(networkStatusText(row.status)),fontSize=TextSize.caption)
                    row.blocked?.let {Text(stringResource(networkProblemText(it)),fontSize=TextSize.caption)}
                    MapButton(R.string.network_open_reference,enabled=enabled && anchor!=null && row.blocked==null && anchor.row.blocked==null) {
                        if(anchor!=null) open(anchor,evidence)
                    }
                    HorizontalDivider()
                }}
            }
        }
        if(report?.files?.isNotEmpty()==true) item {Text(stringResource(R.string.network_files),fontSize=TextSize.heading)}
        for(file in report?.files.orEmpty()) item("file:${file.documentId}") {
            NetworkSelectButton(file.observedNames.firstOrNull().orEmpty().ifBlank {stringResource(R.string.network_file)},enabled) {
                p.selectNode(NetworkSceneNodeKey.File(file.documentId))
            }
        }
        if(concepts.isNotEmpty()) item {Text(stringResource(R.string.network_concepts),fontSize=TextSize.heading)}
        for(c in concepts) item("outline:${c.key.stableKey}") {
            NetworkSelectButton(c.title.ifBlank {stringResource(R.string.network_unnamed_concept)}) {
                p.selectNode(NetworkSceneNodeKey.Concept(c.key))
            }
        }
    }
}

internal fun networkStatusText(status:SourceStatus)=when(status) {
    SourceStatus.UNVERIFIED->R.string.graph_status_unverified;SourceStatus.CURRENT->R.string.graph_status_current
    SourceStatus.STALE->R.string.graph_status_stale;SourceStatus.UNAVAILABLE->R.string.graph_status_unavailable
    SourceStatus.PARTIALLY_DELETED->R.string.graph_status_partial;SourceStatus.DELETED->R.string.graph_status_deleted
    SourceStatus.EMPTY->R.string.graph_status_empty;SourceStatus.TRASHED->R.string.graph_status_trashed
}
internal fun networkProblemText(problem:BacklinkProblem)=when(problem) {
    BacklinkProblem.UNAVAILABLE->R.string.graph_backlinks_problem_unavailable;BacklinkProblem.CORRUPT->R.string.graph_backlinks_problem_corrupt
    BacklinkProblem.RECOVERY_CONFLICT->R.string.graph_backlinks_problem_recovery_conflict;BacklinkProblem.DUPLICATE->R.string.graph_backlinks_problem_duplicate
    BacklinkProblem.IDENTITY->R.string.graph_backlinks_problem_identity;BacklinkProblem.LOADING->R.string.graph_backlinks_problem_loading
    BacklinkProblem.LIMIT->R.string.graph_backlinks_problem_limit;BacklinkProblem.CHANGED->R.string.graph_backlinks_problem_changed
    BacklinkProblem.BUSY->R.string.graph_backlinks_problem_busy;BacklinkProblem.SAVE_FAILED->R.string.graph_backlinks_problem_save_failed
    BacklinkProblem.TRASHED->R.string.graph_backlinks_problem_trashed;BacklinkProblem.UNKNOWN_SCOPE->R.string.graph_backlinks_problem_unknown_scope
    BacklinkProblem.CANCELLED->R.string.graph_backlinks_problem_cancelled
}

@Composable
private fun NetworkSelectButton(text:String,enabled:Boolean=true,onClick:()->Unit) {
    androidx.compose.material3.TextButton(onClick=onClick,enabled=enabled,modifier=Modifier.heightIn(min=MinTouchTarget)) {
        Text(text,fontSize=TextSize.body)
    }
}
