package com.xnotes.mindmap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xnotes.R
import com.xnotes.ui.theme.*

internal fun relationLabel(kind: RelationKind) = when(kind) {
    RelationKind.RELATED -> R.string.graph_related
    RelationKind.REFERENCED_FROM -> R.string.graph_referenced
    RelationKind.BELONGS_TO -> R.string.graph_belongs
    RelationKind.DEPENDS_ON -> R.string.graph_depends
}
@Composable
internal fun GraphRelationsDialog(doc: MindMapDocument, selected: String?, change: (MindMapDocument)->Boolean, close:()->Unit) {
    var editing by remember { mutableStateOf<MapRelation?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val edge=editing
    var label by remember(edge?.id) { mutableStateOf(edge?.label.orEmpty()) }
    var kind by remember(edge?.id) { mutableStateOf(edge?.kind ?: RelationKind.RELATED) }
    AlertDialog(onDismissRequest=close,title={Text(stringResource(R.string.graph_relations),fontSize=TextSize.heading)},text={
        LazyColumn(Modifier.heightIn(max=MindMapNodeListHeight)) {
            if(edge != null) {
                item { Text(doc.node(edge.from).text+" → "+doc.node(edge.to).text,fontSize=TextSize.body) }
                item { OutlinedTextField(label,{label=it},label={Text(stringResource(R.string.graph_description),fontSize=TextSize.body)},textStyle=androidx.compose.ui.text.TextStyle(fontSize=TextSize.body)) }
                items(RelationKind.entries.size) { i -> val k=RelationKind.entries[i];MapButton(relationLabel(k),selected=k==kind){kind=k} }
                item { MapButton(R.string.graph_reverse) { editing=edge.copy(from=edge.to,to=edge.from,label=label,kind=kind) } }
                item { MapButton(R.string.delete) { if(change(doc.copy(relations=doc.relations.filterNot { it.id==edge.id }))) editing=null } }
            } else if(creating && selected != null) {
                items(doc.nodes.size) { i -> val n=doc.nodes[i];if(n.id!=selected) TextButton(onClick={
                    val(next,id)=doc.connect(selected,n.id,RelationKind.RELATED)
                    if(change(next)) { editing=next.relations.first { it.id==id };creating=false }
                },modifier=Modifier.heightIn(min=MinTouchTarget)){Text(n.text,fontSize=TextSize.body)} }
            } else {
                item { MapButton(R.string.graph_connect,enabled=selected!=null){creating=true} }
                items(doc.relations.size) { i -> val r=doc.relations[i];TextButton(onClick={editing=r},modifier=Modifier.heightIn(min=MinTouchTarget)) {
                    Text(doc.node(r.from).text+(if(r.directed)" → " else " — ")+doc.node(r.to).text+" · "+stringResource(relationLabel(r.kind)),fontSize=TextSize.body)
                } }
            }
            if(error) item { Text(stringResource(R.string.graph_invalid),fontSize=TextSize.body) }
        }
    },confirmButton={MapButton(R.string.map_done){if(edge==null) close() else {
        val next=doc.copy(relations=doc.relations.map { if(it.id==edge.id) edge.copy(label=label,kind=kind) else it })
        if(change(next)) editing=null else error=true
    }}},dismissButton={MapButton(R.string.cancel,onClick=close)})
}
@Composable
internal fun GraphSourcesDialog(node: MapNode, change:(MapNode)->Boolean, add:()->Unit, close:()->Unit) {
    AlertDialog(onDismissRequest=close,title={Text(stringResource(R.string.graph_sources_count,node.sources.size),fontSize=TextSize.heading)},text={
        LazyColumn(Modifier.heightIn(max=MindMapNodeListHeight)) {
            item { Text(stringResource(R.string.graph_sources_notice),fontSize=TextSize.body) }
            items(node.sources.size,key={node.sources[it].id}) { index ->
                val s=node.sources[index]
                Text(s.fileName,fontSize=TextSize.title)
                Text(stringResource(when(s.status) {
                    SourceStatus.UNVERIFIED->R.string.graph_status_unverified
                    SourceStatus.CURRENT->R.string.graph_status_current
                    SourceStatus.STALE->R.string.graph_status_stale
                    SourceStatus.UNAVAILABLE->R.string.graph_status_unavailable
                    SourceStatus.PARTIALLY_DELETED->R.string.graph_status_partial
                    SourceStatus.DELETED->R.string.graph_status_deleted
                    SourceStatus.EMPTY->R.string.graph_status_empty
                    SourceStatus.TRASHED->R.string.graph_status_trashed
                }),fontSize=TextSize.caption)
                OutlinedTextField(s.note,{ note -> change(node.copy(sources=node.sources.map { if(it.id==s.id) it.copy(note=note) else it })) },
                    label={Text(stringResource(R.string.graph_note),fontSize=TextSize.body)},textStyle=androidx.compose.ui.text.TextStyle(fontSize=TextSize.body))
                Row {
                    MapButton(R.string.graph_primary,selected=node.primarySource==s.id){change(node.copy(primarySource=s.id))}
                    MapButton(R.string.graph_up,enabled=index>0){
                        val list=node.sources.toMutableList();java.util.Collections.swap(list,index,index-1);change(node.copy(sources=list))
                    }
                    MapButton(R.string.graph_down,enabled=index<node.sources.lastIndex){
                        val list=node.sources.toMutableList();java.util.Collections.swap(list,index,index+1);change(node.copy(sources=list))
                    }
                    MapButton(R.string.delete){change(node.copy(sources=node.sources.filterNot { it.id==s.id },primarySource=node.primarySource.takeUnless { it==s.id }))}
                }
                HorizontalDivider()
            }
        }
    },confirmButton={MapButton(R.string.graph_add_source,onClick=add)},dismissButton={MapButton(R.string.map_done,onClick=close)})
}
