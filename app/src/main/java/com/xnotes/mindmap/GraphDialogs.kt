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
private data class RelationDraft(val initial:MapRelation?,val edge:MapRelation,val token:Any=Any())
private enum class RelationEndpoint { FROM,TO }

@Composable
private fun relationNodeTitle(doc:MindMapDocument,id:String):String {
    val index=doc.nodes.indexOfFirst {it.id==id}
    if(index<0) return stringResource(R.string.graph_missing_node)
    val node=doc.nodes[index]
    val title=node.text.ifBlank {stringResource(when(node.kind) {
        NodeKind.TEXT->R.string.map_text_node;NodeKind.INK->R.string.map_ink_node;NodeKind.REFERENCE->R.string.graph_reference
    })}
    return stringResource(R.string.graph_numbered_node,index+1,title)
}
@Composable
private fun relationSentence(doc:MindMapDocument,edge:MapRelation)=stringResource(when(edge.kind) {
    RelationKind.RELATED->R.string.graph_sentence_related
    RelationKind.REFERENCED_FROM->R.string.graph_sentence_referenced
    RelationKind.BELONGS_TO->R.string.graph_sentence_belongs
    RelationKind.DEPENDS_ON->R.string.graph_sentence_depends
},relationNodeTitle(doc,edge.from),relationNodeTitle(doc,edge.to))

@Composable
private fun RelationChoice(label:String,onClick:()->Unit) {
    TextButton(onClick=onClick,enabled=!LocalInkBusy.current,modifier=Modifier.fillMaxWidth().heightIn(min=MinTouchTarget)) {
        Text(label,fontSize=TextSize.body,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
internal fun GraphRelationsDialog(doc:MindMapDocument,selected:String?,
                                  change:(MapRelation?,MapRelation?)->RelationEditResult,newRelation:MapRelation?=null,close:()->Unit) {
    var draft by remember {mutableStateOf(newRelation?.let {RelationDraft(null,it)})}
    var creatingFrom by remember {mutableStateOf<String?>(null)}
    var choosing by remember {mutableStateOf<RelationEndpoint?>(null)}
    var message by remember {mutableStateOf<Int?>(null)}
    val alive=remember {java.util.concurrent.atomic.AtomicBoolean(true)}
    DisposableEffect(Unit) {onDispose {alive.set(false)}}
    val edit=draft;val edge=edit?.edge
    val valid=if(edge==null) creatingFrom==null || doc.nodes.any {it.id==creatingFrom}
        else doc.nodes.any {it.id==edge.from} && doc.nodes.any {it.id==edge.to} &&
            (edit.initial==null || doc.relations.firstOrNull {it.id==edit.initial.id}==edit.initial)
    fun dismiss() {alive.set(false);draft=null;creatingFrom=null;choosing=null;close()}
    fun update(expected:RelationDraft,changeEdge:(MapRelation)->MapRelation) {
        val current=draft?.takeIf {alive.get() && it.token===expected.token} ?: return
        draft=current.copy(edge=changeEdge(current.edge));message=null
    }
    fun accept(result:RelationEditResult) {
        when(result) {
            is RelationEditResult.Applied -> {draft=null;creatingFrom=null;choosing=null;message=null}
            is RelationEditResult.Duplicate -> {
                // Read the current existing edge, never carry E1's draft label into E2.
                val existing=result.relation
                draft=RelationDraft(existing,existing);creatingFrom=null;choosing=null
                message=R.string.graph_relation_duplicate
            }
            RelationEditResult.Conflict -> message=R.string.graph_relation_changed
            RelationEditResult.Invalid -> message=R.string.graph_invalid
        }
    }
    AlertDialog(onDismissRequest=::dismiss,title={Text(stringResource(R.string.graph_relations),fontSize=TextSize.heading)},text={
        LazyColumn(Modifier.heightIn(max=MindMapNodeListHeight),verticalArrangement=Arrangement.spacedBy(Space.s4)) {
            if(!valid) item {Text(stringResource(R.string.graph_relation_changed),fontSize=TextSize.body)}
            message?.let {id->item {Text(stringResource(id),fontSize=TextSize.body)}}
            if(choosing!=null && edit!=null) {
                val endpoint=choosing
                item {Text(stringResource(if(choosing==RelationEndpoint.FROM) R.string.graph_choose_from else R.string.graph_choose_to),fontSize=TextSize.title)}
                items(doc.nodes.size,key={doc.nodes[it].id}) {i->
                    val n=doc.nodes[i];val other=if(endpoint==RelationEndpoint.FROM) edit.edge.to else edit.edge.from
                    if(n.id!=other) RelationChoice(relationNodeTitle(doc,n.id)) {
                        update(edit) {if(endpoint==RelationEndpoint.FROM) it.copy(from=n.id) else it.copy(to=n.id)}
                        if(draft?.token===edit.token) choosing=null
                    }
                }
            } else if(edit!=null) {
                item {Text(relationSentence(doc,edit.edge),fontSize=TextSize.body)}
                item {RelationChoice(stringResource(R.string.graph_endpoint_from,relationNodeTitle(doc,edit.edge.from))) {choosing=RelationEndpoint.FROM}}
                item {RelationChoice(stringResource(R.string.graph_endpoint_to,relationNodeTitle(doc,edit.edge.to))) {choosing=RelationEndpoint.TO}}
                item {OutlinedTextField(edit.edge.label,{value->
                    if(value.length<=20_000) update(edit) {it.copy(label=value)}
                    else if(alive.get() && draft?.token===edit.token) message=R.string.graph_invalid
                },label={Text(stringResource(R.string.graph_description),fontSize=TextSize.body)},
                    textStyle=androidx.compose.ui.text.TextStyle(fontSize=TextSize.body),modifier=Modifier.fillMaxWidth())}
                items(RelationKind.entries.size) {i->val kind=RelationKind.entries[i]
                    MapButton(relationLabel(kind),selected=kind==edit.edge.kind) {update(edit) {it.copy(kind=kind)}}
                }
                item {MapButton(R.string.graph_reverse,enabled=edit.edge.directed) {update(edit) {it.copy(from=it.to,to=it.from)}}}
                if(edit.initial!=null) item {MapButton(R.string.delete,enabled=valid) {if(alive.get() && draft?.token===edit.token) accept(change(edit.initial,null))}}
                item {MapButton(R.string.graph_relation_list) {draft=null;message=null}}
            } else if(creatingFrom!=null) {
                val from=creatingFrom!!
                item {Text(stringResource(R.string.graph_choose_to),fontSize=TextSize.title)}
                items(doc.nodes.size,key={doc.nodes[it].id}) {i->val n=doc.nodes[i]
                    if(n.id!=from) RelationChoice(relationNodeTitle(doc,n.id)) {
                        if(alive.get() && creatingFrom==from) {
                            draft=RelationDraft(null,MapRelation(from=from,to=n.id));creatingFrom=null;message=null
                        }
                    }
                }
            } else {
                item {MapButton(R.string.graph_connect,enabled=doc.nodes.size>1 && doc.nodes.any {it.id==selected}) {creatingFrom=selected;message=null}}
                items(doc.relations.size,key={doc.relations[it].id}) {i->val r=doc.relations[i]
                    RelationChoice(relationSentence(doc,r)) {draft=RelationDraft(r,r);message=null}
                }
            }
        }
    },confirmButton={MapButton(if(choosing!=null || creatingFrom!=null) R.string.graph_back else R.string.map_done,enabled=valid || choosing!=null || creatingFrom!=null) {
        if(alive.get()) when {choosing!=null->choosing=null;creatingFrom!=null->creatingFrom=null
            edit==null->dismiss();draft?.token===edit.token->accept(change(edit.initial,draft!!.edge))}
    }},dismissButton={MapButton(R.string.cancel,onClick=::dismiss)})
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
