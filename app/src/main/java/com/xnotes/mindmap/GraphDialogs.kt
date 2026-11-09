package com.xnotes.mindmap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
internal fun relationNodeTitle(doc:MindMapDocument,id:String):String {
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
                                  change:(MapRelation?,MapRelation?)->RelationEditResult,newRelation:MapRelation?=null,
                                  existingRelationId:String?=null,onSelectExisting:(String)->Unit={},close:()->Unit) {
    var draft by remember {mutableStateOf(existingRelationId?.let {id->doc.relations.firstOrNull {it.id==id}?.let {RelationDraft(it,it)}}
        ?: newRelation?.let {RelationDraft(null,it)})}
    var creatingFrom by remember {mutableStateOf<String?>(null)}
    var choosing by remember {mutableStateOf<RelationEndpoint?>(null)}
    var message by remember {mutableStateOf<Int?>(if(existingRelationId!=null && doc.relations.none {it.id==existingRelationId}) R.string.graph_relation_changed else null)}
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
                    RelationChoice(relationSentence(doc,r)) {onSelectExisting(r.id);draft=RelationDraft(r,r);message=null}
                }
            }
        }
    },confirmButton={MapButton(if(choosing!=null || creatingFrom!=null) R.string.graph_back else R.string.map_done,enabled=valid || choosing!=null || creatingFrom!=null) {
        if(alive.get()) when {choosing!=null->choosing=null;creatingFrom!=null->creatingFrom=null
            edit==null->dismiss();draft?.token===edit.token->accept(change(edit.initial,draft!!.edge))}
    }},dismissButton={MapButton(R.string.cancel,onClick=::dismiss)})
}
@Composable
internal fun GraphSourcesDialog(snapshot:SourceEditSnapshot,editor:SourceListEditor,add:()->Unit,addEnabled:Boolean=true,
                                open:((MapSourceNavigationTicket)->Unit)?=null,focus:MapSourcePanelFocus?=null,
                                focusHandled:(Any)->Unit={},feedback:Int?=null,close:()->Unit) {
    val node=snapshot.node
    val scroll=rememberLazyListState()
    val sourceIds=node.sources.map {it.id}
    LaunchedEffect(focus?.token,sourceIds,editor.conflict,feedback) {
        val pending=focus ?: return@LaunchedEffect
        if(pending.nodeId==node.id) {
            val at=sourceIds.indexOf(pending.sourceId)
            if(at>=0) scroll.scrollToItem(2+(if(editor.conflict) 1 else 0)+(if(feedback!=null) 1 else 0)+at)
        }
        focusHandled(pending.token)
    }
    val alive=remember(editor) {java.util.concurrent.atomic.AtomicBoolean(true)}
    DisposableEffect(editor) {onDispose {alive.set(false);editor.close()}}
    SideEffect {editor.sync(snapshot)}
    fun dismiss() {if(alive.get()) {alive.set(false);editor.close();close()}}
    AlertDialog(onDismissRequest=::dismiss,title={Column {
        if(node.text.isNotBlank()) Text(node.text.take(256),fontSize=TextSize.heading)
        Text(stringResource(R.string.graph_sources_count,node.sources.size),fontSize=TextSize.heading)
    }},text={
        LazyColumn(Modifier.heightIn(max=MindMapNodeListHeight),state=scroll) {
            item {
                Text(stringResource(if(node.fileCardId!=null) R.string.graph_file_card_notice else R.string.graph_sources_notice),fontSize=TextSize.body)
                if(node.fileCardId!=null && node.sources.singleOrNull()?.scope==SourceScope.PDF_ORIGINAL)
                    Text(stringResource(R.string.network_original_card_notice),fontSize=TextSize.caption)
            }
            item {
                Text(stringResource(R.string.graph_preview_backup_notice),fontSize=TextSize.caption)
                Row(horizontalArrangement=Arrangement.spacedBy(Space.s4)) {
                    MapButton(R.string.graph_preview_check_all) {if(alive.get()) editor.checkGraph()}
                    MapButton(R.string.graph_preview_refresh_all) {if(alive.get()) editor.refreshGraph()}
                }
                if(editor.busySourceIds.isNotEmpty()) MapButton(R.string.cancel) {if(alive.get()) editor.cancel()}
            }
            if(editor.conflict) item {Text(stringResource(R.string.graph_sources_changed),fontSize=TextSize.body)}
            feedback?.let {message->item {Text(stringResource(message),fontSize=TextSize.body)}}
            items(node.sources.size,key={node.sources[it].id}) { index ->
                val s=node.sources[index]
                // Bind the visible row before click; remove/undo must not let an old UI mint a new lifetime.
                val navigation=if(open!=null) editor.captureNavigation(s.id,s.target) else null
                LaunchedEffect(editor,s.id,s.target) {if(alive.get()) editor.showPreview(s.id,s.target)}
                Text(s.fileName,fontSize=TextSize.title)
                Text(sourceScopeLabel(s),fontSize=TextSize.caption)
                SourcePreviewImage(editor.previewAsset(s.id))
                if(s.hasHistoricalPreview) Text(stringResource(R.string.graph_preview_historical),fontSize=TextSize.caption)
                if(s.id in editor.busySourceIds) Text(stringResource(R.string.graph_preview_working),fontSize=TextSize.caption)
                if(s.id in editor.failedSourceIds) Text(stringResource(R.string.graph_preview_failed),fontSize=TextSize.caption)
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
                Row(horizontalArrangement=Arrangement.spacedBy(Space.s4)) {
                    if(open!=null) MapButton(R.string.open,enabled=navigation!=null) {
                        if(alive.get() && navigation!=null) open(navigation)
                    }
                    MapButton(R.string.graph_preview_check,enabled=s.id !in editor.busySourceIds) {if(alive.get()) editor.check(s.id,s.target)}
                    MapButton(R.string.graph_preview_refresh,enabled=s.id !in editor.busySourceIds) {if(alive.get()) editor.refresh(s.id,s.target)}
                }
                OutlinedTextField(s.note,{ note -> if(alive.get()) editor.note(s.id,note) },enabled=!LocalInkBusy.current,
                    label={Text(stringResource(R.string.graph_note),fontSize=TextSize.body)},textStyle=androidx.compose.ui.text.TextStyle(fontSize=TextSize.body))
                Row {
                    MapButton(R.string.graph_primary,selected=node.primarySource==s.id){if(alive.get()) editor.primary(s.id)}
                    MapButton(R.string.graph_up,enabled=index>0){
                        if(alive.get()) editor.move(s.id,-1)
                    }
                    MapButton(R.string.graph_down,enabled=index<node.sources.lastIndex){
                        if(alive.get()) editor.move(s.id,1)
                    }
                    if(node.fileCardId==null) MapButton(R.string.delete){if(alive.get()) editor.remove(s.id)}
                }
                HorizontalDivider()
            }
        }
    },confirmButton={if(node.fileCardId==null) MapButton(R.string.graph_add_source,enabled=addEnabled){if(alive.get()) add()}},dismissButton={MapButton(R.string.map_done,onClick=::dismiss)})
}

/** Only validated immutable PNG assets enter Android's decoder; late unpublished bitmaps are ours. */
@Composable
internal fun SourcePreviewImage(asset:MapPreviewAsset?) {
    val image by produceState<ImageBitmap?>(null,asset?.key) {
        value=null
        if(asset!=null) {
            val owned=java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap?>()
            try {
                val decoded=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val bytes=asset.pngBytes()
                    requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)).also(owned::set).asImageBitmap()
                }
                value=decoded;owned.set(null) // Compose owns the published image while a frame may use it.
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:Exception) {value=null}
            finally {owned.getAndSet(null)?.recycle()}
        }
    }
    image?.let {
        Image(it,stringResource(R.string.graph_preview_image),Modifier.fillMaxWidth().heightIn(max=MindMapSourcePreviewHeight),contentScale=ContentScale.Fit)
        Text(stringResource(R.string.graph_preview_image),fontSize=TextSize.caption)
    }
}

@Composable
internal fun sourceScopeLabel(source:MapSource):String {
    val range=sourceRangeLabel(source.scope,source.lastKnownPage)
    return if(source.region!=null || source.originalPdf?.userRegion!=null)
        stringResource(R.string.graph_backlinks_range_region,range) else range
}

@Composable
internal fun sourceRangeLabel(scope:SourceScope,lastKnownPage:Int?):String=when(scope) {
    SourceScope.FILE->stringResource(R.string.graph_source_whole_file)
    SourceScope.CANVAS_REGION->stringResource(R.string.graph_source_canvas_region)
    SourceScope.PDF_ORIGINAL->lastKnownPage?.let {stringResource(R.string.original_pdf_page_last_known,it)}
        ?: stringResource(R.string.original_pdf_readonly)
    SourceScope.PAGE->lastKnownPage?.let {stringResource(R.string.graph_source_page_last_known,it)}
        ?: stringResource(R.string.graph_source_page_unknown)
}

/** A text-only actual page directory; rows never decode or read their source independently. */
@Composable
internal fun GraphSourceChoiceDialog(choice:MapSourceChoice,controller:MapSourcePickerController) {
    var confirming by remember(choice.requestId) {mutableStateOf<MapSourceChoice?>(null)}
    CompositionLocalProvider(LocalInkBusy provides false) {
        val consent=confirming
        if(consent!=null) {
            AlertDialog(onDismissRequest={confirming=null},title={Text(stringResource(R.string.graph_source_enable_pages),fontSize=TextSize.heading)},
                text={Text(stringResource(R.string.graph_source_enable_notice),fontSize=TextSize.body)},
                confirmButton={MapButton(R.string.graph_source_enable_confirm) {controller.activatePages(consent);confirming=null}},
                dismissButton={MapButton(R.string.cancel) {confirming=null}})
        } else AlertDialog(onDismissRequest={controller.cancel(choice)},title={Text(stringResource(R.string.graph_source_choose),fontSize=TextSize.heading)},
            text={LazyColumn(Modifier.heightIn(max=MindMapNodeListHeight),verticalArrangement=Arrangement.spacedBy(Space.s8)) {
                choice.offer?.let {offer->item {Text(offer.name,fontSize=TextSize.title)}}
                if(choice.busy) item {Text(stringResource(R.string.graph_source_loading),fontSize=TextSize.body)}
                if(choice.problem!=null) item {Text(stringResource(when(choice.problem) {
                    MapSourceChoiceProblem.READ->R.string.graph_source_error
                    MapSourceChoiceProblem.CHANGED->R.string.graph_source_choice_changed
                    MapSourceChoiceProblem.ACTIVATION->R.string.graph_source_activation_failed
                }),fontSize=TextSize.body)}
                if(choice.canResume) item {MapButton(R.string.graph_source_complete_activation,enabled=!choice.busy) {controller.completeActivation(choice)}}
                choice.offer?.let {offer->
                    item {MapButton(R.string.graph_source_whole_file,enabled=!choice.busy) {controller.chooseFile(choice)}}
                    when(val pages=offer.catalog?.pages) {
                        com.xnotes.storage.NativeSourceSnapshot.ReferenceCatalog.Pages.NeedsActivation -> item {
                            MapButton(R.string.graph_source_enable_pages,enabled=!choice.busy) {confirming=choice}
                        }
                        is com.xnotes.storage.NativeSourceSnapshot.ReferenceCatalog.Pages.Available -> {
                            item {Text(stringResource(R.string.graph_source_page_directory),fontSize=TextSize.body)}
                            items(pages.orderedIds.size,key={pages.orderedIds[it]}) {index->
                                TextButton(onClick={controller.choosePage(choice,pages.orderedIds[index])},enabled=!choice.busy,
                                    modifier=Modifier.fillMaxWidth().heightIn(min=MinTouchTarget)) {
                                    Text(stringResource(R.string.graph_source_page_number,index+1),fontSize=TextSize.body)
                                }
                            }
                        }
                        else -> Unit // Canvas/map sources deliberately offer the whole document only.
                    }
                }
            }},confirmButton={},dismissButton={MapButton(R.string.cancel) {controller.cancel(choice)}})
    }
}
