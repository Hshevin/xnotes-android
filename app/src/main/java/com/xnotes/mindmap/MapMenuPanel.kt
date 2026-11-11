package com.xnotes.mindmap

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.xnotes.R
import com.xnotes.ui.theme.*

/** Title, scrolling body and action row of one editor; only the outer frame differs between dialog and menu. */
internal typealias MapFrame = @Composable (title:@Composable ()->Unit,body:@Composable ()->Unit,actions:@Composable ()->Unit,dismiss:()->Unit)->Unit

internal val DialogFrame:MapFrame = {title,body,actions,dismiss->
    AlertDialog(onDismissRequest=dismiss,title=title,text=body,confirmButton={Row {actions()}})
}

/** In-pane frame: the action row stays reachable above the body, which scrolls in the remaining height. */
internal val PanelFrame:MapFrame = {title,body,actions,_->
    Column(Modifier.fillMaxSize().padding(Space.s8),verticalArrangement=Arrangement.spacedBy(Space.s8)) {
        title()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {actions()}
        Box(Modifier.weight(1f).fillMaxWidth()) {body()}
    }
}

internal enum class MapMenuTab { NODES,SOURCES,RELATIONS,LAYOUT }

/**
 * Keeps [content] composed while hidden, so switching menu pages or folding the menu never commits or
 * drops a page's unconfirmed state. A hidden page is measured but not placed: no touches, no drawing.
 */
@Composable
internal fun MapMenuPage(shown:Boolean,modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit) {
    Box(modifier.then(if(shown) Modifier else Modifier.clearAndSetSemantics {}).layout {measurable,constraints->
        val placeable=measurable.measure(constraints)
        if(shown) layout(placeable.width,placeable.height) {placeable.place(0,0)} else layout(0,0) {}
    },content=content)
}

internal fun defaultNodeTitle(context:android.content.Context,kind:NodeKind)=when(kind) {
    NodeKind.TEXT->context.getString(R.string.map_topic)
    NodeKind.INK->context.getString(R.string.map_ink_node)
    NodeKind.REFERENCE->""
}

/**
 * One creation transaction for child, sibling, free and canvas-connected nodes. Kind, title and sources live
 * only in the session draft; [created] runs after the single history commit of node, hierarchy or relation
 * and sources. Cancel leaves neither a node nor a history step.
 */
@Composable
internal fun NodeCreationContent(session:MapSession,draft:ConnectedNodeDraft,pickerBusy:Boolean,chooseSource:()->Unit,
                                 created:(String,NodeKind)->Unit) {
    val alive=remember(draft.token) {java.util.concurrent.atomic.AtomicBoolean(true)}
    DisposableEffect(draft.token) {onDispose {alive.set(false)}}
    val painter=remember {MapPainter()}
    val cancel={if(alive.get()) session.cancelConnectedNode(draft.token)}
    val problem=when(draft.problem) {
        ConnectedDraftProblem.CHANGED->if(draft.placement==NodePlacement.CONNECTED) R.string.graph_connected_changed else R.string.map_create_parent_changed
        ConnectedDraftProblem.LIMIT->R.string.map_limit
        ConnectedDraftProblem.SOURCE->R.string.graph_source_error
        null->null
    }
    val doc=session.document
    val anchor=if(draft.placement==NodePlacement.FREE || doc==null) null else relationNodeTitle(doc,draft.from)
    val notice=when(draft.placement) {
        NodePlacement.CONNECTED->stringResource(R.string.graph_create_connected_notice,anchor.orEmpty())
        NodePlacement.CHILD,NodePlacement.SIBLING->stringResource(R.string.map_create_under,anchor.orEmpty())
        NodePlacement.FREE->null
    }
    val node=draft.node
    val ready=node!=null && !draft.loadingSource && !pickerBusy && (node.kind!=NodeKind.REFERENCE || node.sources.isNotEmpty())
    PanelFrame({Text(stringResource(when(draft.placement) {
        NodePlacement.CONNECTED->R.string.graph_create_connected
        NodePlacement.CHILD->R.string.map_create_child
        NodePlacement.SIBLING->R.string.map_create_sibling
        NodePlacement.FREE->R.string.map_create_free
    }),fontSize=TextSize.heading,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)},{
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(Space.s8)) {
            notice?.let {Text(it,fontSize=TextSize.body,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)}
            problem?.let {Text(stringResource(it),fontSize=TextSize.body)}
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for(kind in NodeKind.entries) MapButton(when(kind) {
                    NodeKind.TEXT->R.string.map_text_node;NodeKind.INK->R.string.map_ink_node;NodeKind.REFERENCE->R.string.graph_reference
                },selected=node?.kind==kind,enabled=!draft.loadingSource) {if(alive.get()) session.changeConnectedKind(draft.token,kind)}
            }
            if(node!=null) {
                OutlinedTextField(node.text,{value->if(alive.get() && value.length<=20_000) session.editConnectedNode(draft.token,node.copy(text=value))},
                    label={Text(stringResource(R.string.map_node_text),fontSize=TextSize.body)},maxLines=4,
                    textStyle=androidx.compose.ui.text.TextStyle(fontSize=TextSize.bodyLarge),modifier=Modifier.fillMaxWidth())
                HorizontalDivider()
                Text(stringResource(R.string.graph_sources_count,node.sources.size),fontSize=TextSize.title)
                Text(stringResource(if(node.kind==NodeKind.REFERENCE) R.string.graph_reference_file_notice else R.string.map_create_sources_optional),
                    fontSize=TextSize.caption)
                node.sources.forEach {source->
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(source.fileName,fontSize=TextSize.body,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(sourceScopeLabel(source),fontSize=TextSize.caption)
                        }
                        MapButton(R.string.delete,enabled=!draft.loadingSource) {if(alive.get()) session.removeConnectedSource(draft.token,source.id)}
                    }
                }
                if(draft.loadingSource) Text(stringResource(R.string.graph_source_loading),fontSize=TextSize.body)
                MapButton(R.string.graph_add_source,enabled=!draft.loadingSource && !pickerBusy) {if(alive.get()) chooseSource()}
            }
        }
    },{
        MapButton(R.string.cancel,onClick=cancel)
        MapButton(R.string.map_create_confirm,enabled=ready) {
            val body=draft.node
            if(alive.get() && body!=null) {
                val measured=if(body.kind==NodeKind.TEXT) body.copy(height=painter.measuredHeight(body.text,body.width)) else body
                if(session.finishConnectedNode(draft.token,measured)) created(body.id,body.kind)
            }
        }
    },cancel)
}
