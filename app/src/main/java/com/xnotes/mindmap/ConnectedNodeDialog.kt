package com.xnotes.mindmap

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xnotes.R
import com.xnotes.ui.theme.*

@Composable
internal fun ConnectedNodeDialog(session:MapSession,draft:ConnectedNodeDraft,pickerBusy:Boolean,chooseSource:()->Unit) {
    val alive=remember(draft.token) {java.util.concurrent.atomic.AtomicBoolean(true)}
    DisposableEffect(draft.token) {onDispose {alive.set(false)}}
    val cancel={if(alive.get()) session.cancelConnectedNode(draft.token)}
    val problem=when(draft.problem) {
        ConnectedDraftProblem.CHANGED->R.string.graph_connected_changed
        ConnectedDraftProblem.LIMIT->R.string.map_limit
        ConnectedDraftProblem.SOURCE->R.string.graph_source_error
        null->null
    }
    val node=draft.node
    val from=session.document?.let {relationNodeTitle(it,draft.from)} ?: stringResource(R.string.graph_missing_node)
    val notice=stringResource(R.string.graph_create_connected_notice,from)
    if(node!=null && node.kind!=NodeKind.REFERENCE) {
        MapNodeDialog(node,cancel,{session.finishConnectedNode(draft.token,it)},title=R.string.graph_create_connected,
            notice=notice,problemLabel=problem ?: R.string.map_limit,
            onDraftChange={session.editConnectedNode(draft.token,it)})
        return
    }
    AlertDialog(onDismissRequest=cancel,title={Text(stringResource(R.string.graph_create_connected),fontSize=TextSize.heading)},text={
        Column(verticalArrangement=Arrangement.spacedBy(Space.s8)) {
            Text(notice,fontSize=TextSize.body,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            problem?.let {Text(stringResource(it),fontSize=TextSize.body)}
            if(node==null) for(kind in NodeKind.entries) MapButton(when(kind) {
                NodeKind.TEXT->R.string.map_text_node;NodeKind.INK->R.string.map_ink_node;NodeKind.REFERENCE->R.string.graph_reference
            }) {if(alive.get()) session.chooseConnectedKind(draft.token,kind)}
            else {
                OutlinedTextField(node.text,{value->if(alive.get()) session.editConnectedNode(draft.token,node.copy(text=value))},
                    label={Text(stringResource(R.string.graph_display_title),fontSize=TextSize.body)},
                    textStyle=androidx.compose.ui.text.TextStyle(fontSize=TextSize.bodyLarge),modifier=Modifier.fillMaxWidth())
                node.sources.forEach {Text(it.fileName,fontSize=TextSize.title)}
                Text(stringResource(R.string.graph_reference_file_notice),fontSize=TextSize.body)
                if(draft.loadingSource) Text(stringResource(R.string.graph_source_loading),fontSize=TextSize.body)
                MapButton(R.string.graph_add_source,enabled=!draft.loadingSource && !pickerBusy) {if(alive.get()) chooseSource()}
            }
        }
    },confirmButton={if(node!=null) MapButton(R.string.map_done,enabled=node.sources.isNotEmpty() && !draft.loadingSource && !pickerBusy) {
        if(alive.get()) session.finishConnectedNode(draft.token)
    }},dismissButton={MapButton(R.string.cancel,onClick=cancel)})
}
