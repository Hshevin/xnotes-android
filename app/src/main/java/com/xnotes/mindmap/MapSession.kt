package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal object MapSaves {
    // Writes survive an Activity being stopped or destroyed. Each document owns its serialization lock.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    fun key(uri:Uri):String {
        if(uri.scheme=="file") return Uri.fromFile(File(requireNotNull(uri.path)).canonicalFile).toString()
        return runCatching {"${uri.authority}|${android.provider.DocumentsContract.getDocumentId(uri)}"}.getOrDefault(uri.toString())
    }
    fun lockFor(uri: Uri) = locks.getOrPut(key(uri)) { Mutex() }
}

class MapSession() : ViewModel() {
    private var connectedSourceReader:(Context,Uri,()->Unit)->MapSource=MapSourceAccess::inspect
    private var targetOverride:((Uri)->MapTarget)?=null
    internal constructor(reader:(Context,Uri,()->Unit)->MapSource,target:((Uri)->MapTarget)?=null):this() {
        connectedSourceReader=reader;targetOverride=target
    }
    internal var replacingDocument by mutableStateOf(false);private set
    internal var connectedDraft by mutableStateOf<ConnectedNodeDraft?>(null);private set
    internal var connectedSourceRequest by mutableStateOf<ConnectedSourceRequest?>(null);private set
    private var connectedEpoch=0L
    internal val interactionEpoch:Long get()=connectedEpoch
    private var connectedImport:Job?=null
    private var connectedImportId:String?=null
    private data class ExistingSourceRequest(val id:String=java.util.UUID.randomUUID().toString(),val epoch:Long,val documentId:String,val nodeId:String)
    private var existingSourceRequest:ExistingSourceRequest?=null
    private val existingSourceJobs=mutableSetOf<Job>()
    private data class SourceLife(val nodeId:String,val target:MapSourceTarget,val token:Any=Any())
    private val sourceLifetimes=mutableMapOf<String,SourceLife>()
    private var previewController:MapSourcePreviewController?=null
    internal val sourcePreviews:MapSourcePreviewController get()=previewController ?:
        MapSourcePreviewController(this,app,viewModelScope).also {previewController=it}
    private fun reconcileSourceLifetimes() {
        val present=hashSetOf<String>()
        document?.nodes?.forEach {node->node.sources.forEach {source->
            present.add(source.id)
            val previous=sourceLifetimes[source.id]
            if(previous==null || previous.nodeId!=node.id || previous.target!=source.target) sourceLifetimes[source.id]=SourceLife(node.id,source.target)
        }}
        sourceLifetimes.keys.retainAll(present)
        previewController?.documentChanged()
    }
    internal fun captureSourcePreview(nodeId:String,sourceId:String):MapSourcePreviewTicket? {
        if(closing || replacingDocument) return null
        val source=document?.nodes?.firstOrNull {it.id==nodeId}?.sources?.firstOrNull {it.id==sourceId} ?: return null
        val life=sourceLifetimes[sourceId] ?: return null
        return MapSourcePreviewTicket(nodeId,sourceId,source.target,connectedEpoch,life.token)
    }
    internal fun currentSourcePreview(ticket:MapSourcePreviewTicket):MapSource? {
        if(closing || replacingDocument || connectedEpoch!=ticket.epoch || sourceLifetimes[ticket.sourceId]?.token!==ticket.lifetime) return null
        return document?.nodes?.firstOrNull {it.id==ticket.nodeId}?.sources?.firstOrNull {it.id==ticket.sourceId && it.target==ticket.target}
    }
    /** Prepare every history frame off Main; retry a changed history with the same rendered values. */
    internal suspend fun mergeSourceDerived(patches:List<MapSourceDerivedPatch>,current:(MapSourceDerivedPatch)->Boolean):Boolean {
        while(!closing && !replacingDocument) {
            currentCoroutineContext().ensureActive()
            val h=history ?: return false
            val valid=patches.filter(current)
            if(valid.isEmpty()) return false
            val capture=h.captureDerived()
            val coroutine=currentCoroutineContext()
            val prepared=withContext(Dispatchers.IO) {MapHistory.prepareDerived(capture,valid) {coroutine.ensureActive()}}
            androidx.compose.runtime.snapshotFlow {inkContact}.first {!it}
            if(history!==h || closing || replacingDocument) return false
            if(valid.any {!current(it)}) continue
            val before=document
            if(!h.acceptDerived(prepared)) continue
            document=h.document
            if(document!=before) {
                dirty=true;copySaved=false;revision++
                debounce?.cancel();debounce=viewModelScope.launch {delay(350);save()}
            }
            return true
        }
        return false
    }
    private var sourcePickerInstance:MapSourcePickerController?=null
    internal val sourcePicker:MapSourcePickerController get()=sourcePickerInstance ?: MapSourcePickerController(app,viewModelScope,
        ::sourceRequestCurrent,::acceptSourceChoice,::cancelSourceRequest,{inkContact}).also {sourcePickerInstance=it}
    private fun sourceRequestCurrent(id:String):Boolean {
        existingSourceRequest?.takeIf {it.id==id}?.let {return sourceCurrent(it)}
        val request=connectedSourceRequest?.takeIf {it.id==id} ?: return false
        return !closing && !replacingDocument && connected(request.token)?.let {it.epoch==request.epoch && it.node?.kind==NodeKind.REFERENCE &&
            document?.nodes?.any {node->node.id==it.from}==true}==true
    }
    private fun retireInvalidSourceRequests() {
        listOfNotNull(existingSourceRequest?.id,connectedSourceRequest?.id).forEach {if(!sourceRequestCurrent(it)) cancelSourceRequest(it)}
    }
    internal fun cancelSourceRequest(id:String) {
        sourcePickerInstance?.retireRequest(id)
        if(existingSourceRequest?.id==id) existingSourceRequest=null
        if(connectedSourceRequest?.id==id) connectedSourceRequest=null
    }
    private fun acceptSourceChoice(id:String,source:MapSource):Boolean {
        if(inkContact || !sourceRequestCurrent(id)) return false
        source.validate()
        existingSourceRequest?.takeIf {it.id==id}?.let {request->
            val applied=operate {it.update(request.nodeId) {node->node.copy(sources=node.sources+source)}}
            if(applied) {existingSourceRequest=null;sourceFailed=false};return applied
        }
        val request=connectedSourceRequest?.takeIf {it.id==id} ?: return false
        val draft=connected(request.token) ?: return false;val node=draft.node ?: return false
        val next=node.copy(sources=node.sources+source,primarySource=node.primarySource ?: source.id)
        if(document?.connectNewNode(draft.from,next) !is ConnectedNodeResult.Applied) return false
        connectedDraft=draft.copy(node=next,problem=null)
        connectedSourceRequest=null;return true
    }
    internal fun beginConnectedNode(from:String,center:com.xnotes.core.geometry.Pt):Boolean {
        if(closing || replacingDocument || inkContact || textDraft!=null || textRestore!=null || connectedDraft!=null) return false
        val doc=document ?: return false
        if(doc.nodes.none {it.id==from}) return false
        if(!center.x.isFinite() || !center.y.isFinite() || kotlin.math.abs(center.x)>MindMapDocument.MAX_COORD || kotlin.math.abs(center.y)>MindMapDocument.MAX_COORD) {
            limitError=true;return false
        }
        connectedDraft=ConnectedNodeDraft(epoch=connectedEpoch,documentId=doc.id,from=from,center=center)
        return true
    }
    private fun connected(token:String)=connectedDraft?.takeIf {it.token==token && it.epoch==connectedEpoch && it.documentId==document?.id}
    internal fun chooseConnectedKind(token:String,kind:NodeKind):Boolean {
        val draft=connected(token)?.takeIf {it.node==null && !closing && !inkContact} ?: return false
        val node=MapNode(id=draft.nodeId,parent=null,free=true,kind=kind)
        connectedDraft=draft.copy(node=node.copy(x=draft.center.x-node.width/2,y=draft.center.y-node.height/2))
        return true
    }
    /** Merge only body properties; a late input callback cannot erase an inspected source. */
    internal fun editConnectedNode(token:String,proposed:MapNode):Boolean {
        val draft=connected(token)?.takeIf {!closing} ?: return false
        val node=draft.node?.takeIf {it.id==proposed.id} ?: return false
        val next=node.copy(text=proposed.text,kind=proposed.kind,width=proposed.width,height=proposed.height)
        return try {
            MindMapDocument(nodes=listOf(next.copy(parent=null,free=false))).validate()
            connectedDraft=draft.copy(node=next,problem=null);retireInvalidSourceRequests();true
        } catch(_:IllegalArgumentException) {connectedDraft=draft.copy(problem=ConnectedDraftProblem.LIMIT);false}
    }
    internal fun cancelConnectedNode(token:String) {
        if(connectedDraft?.token!=token) return
        connectedSourceRequest?.let {sourcePickerInstance?.retireRequest(it.id)}
        connectedImport?.cancel();connectedImport=null;connectedImportId=null;connectedSourceRequest=null;connectedDraft=null
    }
    private fun retireConnectedDraft() {
        previewController?.retireAll();sourceLifetimes.clear()
        sourcePickerInstance?.retire()
        selectedRelation=null
        nodeSelectionMode=false
        replaceNodeSelection(emptySet())
        selectionRevision++
        connectedDraft?.let {cancelConnectedNode(it.token)};connectedEpoch++
        existingSourceRequest=null;existingSourceJobs.toList().forEach {it.cancel()};existingSourceJobs.clear()
    }
    internal fun finishConnectedNode(token:String,proposed:MapNode?=null):Boolean {
        val draft=connected(token)?.takeIf {!closing && !inkContact && !it.loadingSource && connectedSourceRequest==null} ?: return false
        val node=draft.node ?: return false
        if(proposed!=null && proposed.id!=node.id) return false
        val body=if(proposed==null) node else node.copy(text=proposed.text,kind=proposed.kind,width=proposed.width,height=proposed.height)
        if(body.kind==NodeKind.REFERENCE && body.sources.isEmpty()) return false
        val centered=body.copy(x=draft.center.x-body.width/2,y=draft.center.y-body.height/2)
        val result=requireNotNull(document).connectNewNode(draft.from,centered)
        if(result is ConnectedNodeResult.Applied && commit(result.document)) {
            selected=centered.id;cancelConnectedNode(token);return true
        }
        connectedDraft=draft.copy(problem=if(result==ConnectedNodeResult.Conflict) ConnectedDraftProblem.CHANGED else ConnectedDraftProblem.LIMIT)
        return false
    }
    internal fun beginConnectedSource(token:String):ConnectedSourceRequest? {
        val draft=connected(token)?.takeIf {!closing && !it.loadingSource && it.node?.kind==NodeKind.REFERENCE && connectedSourceRequest==null &&
            existingSourceRequest==null && sourcePickerInstance?.state==null} ?: return null
        return ConnectedSourceRequest(token=token,epoch=draft.epoch).also {connectedSourceRequest=it}
    }
    internal fun receiveConnectedSource(requestId:String,uri:Uri?):Boolean {
        val request=connectedSourceRequest?.takeIf {it.id==requestId} ?: return false
        connectedSourceRequest=null
        val draft=connected(request.token)?.takeIf {it.epoch==request.epoch && !closing} ?: return false
        if(uri==null) return true
        connectedDraft=draft.copy(loadingSource=true,problem=null);connectedImportId=request.id
        connectedImport=viewModelScope.launch {
            val result=withContext(Dispatchers.IO) {
                val coroutine=currentCoroutineContext()
                try {Result.success(connectedSourceReader(app,uri) {coroutine.ensureActive()}.also {it.validate()})}
                catch(error:java.util.concurrent.CancellationException) {throw error}
                catch(error:Exception) {Result.failure(error)}
            }
            val current=connected(request.token)?.takeIf {it.epoch==request.epoch && connectedImportId==request.id && !closing} ?: return@launch
            connectedImportId=null;connectedImport=null
            connectedDraft=result.fold({source->current.copy(loadingSource=false,node=current.node?.copy(sources=listOf(source),primarySource=source.id),problem=null)},
                {current.copy(loadingSource=false,problem=ConnectedDraftProblem.SOURCE)})
        }
        return true
    }
    internal fun failConnectedSource(requestId:String) {
        val request=connectedSourceRequest?.takeIf {it.id==requestId} ?: return
        if(receiveConnectedSource(requestId,null)) connected(request.token)?.let {connectedDraft=it.copy(problem=ConnectedDraftProblem.SOURCE)}
    }
    override fun onCleared() {retireConnectedDraft();super.onCleared()}
    var textDraft by mutableStateOf<MapTextDraft?>(null); private set
    var textRestore by mutableStateOf<MapTextRestore?>(null); private set
    var textEditConflict by mutableStateOf(false); private set
    fun beginText(id:String,viewport:MindMapView.Viewport,width:Int,height:Int,density:Float=1f):Boolean {
        if(closing || replacingDocument || inkContact || connectedDraft!=null || textDraft!=null || textRestore!=null || width<=0 || height<=0 || !density.isFinite() || density<=0) return false
        val node=document?.nodes?.firstOrNull {it.id==id && it.kind==NodeKind.TEXT} ?: return false
        textEditConflict=false
        textDraft=MapTextDraft(nodeId=id,initialText=node.text,viewport=viewport,viewWidth=width,viewHeight=height,previousSelection=selected,viewDensity=density)
        selected=id
        return true
    }
    fun updateTextDraft(token:String,value:androidx.compose.ui.text.input.TextFieldValue):Boolean {
        val draft=textDraft?.takeIf {it.token==token} ?: return false
        if(value.text.length>20_000) {limitError=true;return false}
        textDraft=draft.copy(value=value);limitError=false
        return true
    }
    fun finishTextDraft(token:String):Boolean {
        val draft=textDraft?.takeIf {it.token==token} ?: return false
        val doc=document ?: return false
        val n=doc.nodes.firstOrNull {it.id==draft.nodeId}
        if(n==null || n.kind!=NodeKind.TEXT || n.text!=draft.initialText) {textEditConflict=true;return false}
        if(draft.value.text!=draft.initialText) {
            val height=MapPainter().measuredHeight(draft.value.text,n.width)
            val updated=doc.update(n.id) {it.copy(text=draft.value.text,height=height)}
            // Layout keeps free topics in place, so their branches reflow like the main tree's.
            val next=try {MapLayout.arrange(updated)} catch(_:IllegalArgumentException) {limitError=true;return false}
            if(!commit(next)) return false
        }
        endText(draft)
        return true
    }
    fun cancelTextDraft(token:String) {textDraft?.takeIf {it.token==token}?.let(::endText)}
    private fun endText(draft:MapTextDraft) {
        textDraft=null;textEditConflict=false
        textRestore=MapTextRestore(draft.token,draft.viewport,draft.viewWidth,draft.viewHeight,draft.previousSelection,draft.viewDensity)
    }
    fun completeTextRestore(token:String) {
        val restore=textRestore?.takeIf {it.token==token} ?: return
        selected=restore.selection?.takeIf {id->document?.nodes?.any {it.id==id}==true} ?: document?.root?.id
        textRestore=null
    }
    var inkContact by mutableStateOf(false)
    var inkMode by mutableStateOf(InkMode.PEN)
    var inkWidth by mutableStateOf(3.0)
    fun commitInk(id:String, expected:List<MapInk>, next:List<MapInk>):Boolean {
        val doc=document ?: return false
        val n=doc.nodes.firstOrNull {it.id==id} ?: return false
        if(n.kind!=NodeKind.INK || n.ink!=expected) return false
        return commit(doc.update(id) {it.copy(ink=next)})
    }
    fun editNodeProperties(id:String, proposed:MapNode):Boolean = operate { doc ->
        if(doc.nodes.none {it.id==id}) return@operate doc
        val updated=doc.update(id) { current -> current.copy(text=proposed.text,kind=proposed.kind,width=proposed.width,height=proposed.height) }
        if(updated==doc || proposed.kind==NodeKind.INK) updated else MapLayout.arrange(updated)
    }
    fun reportInkLimit() {limitError=true}
    var document by mutableStateOf<MindMapDocument?>(null); private set
    var selectedNodes by mutableStateOf<Set<String>>(emptySet());private set
    var nodeSelectionMode by mutableStateOf(false);private set
    private var selectionRevision=0L
    internal val contentRevision:Long get()=revision
    var selected:String?
        get()=selectedNodes.singleOrNull()
        set(value) {replaceNodeSelection(value?.let(::setOf) ?: emptySet());selectedRelation=null}
    private fun replaceNodeSelection(ids:Set<String>) {
        if(selectedNodes!=ids) {selectedNodes=ids.toSet();selectionRevision++}
    }
    private fun selectionReady() = !closing && !replacingDocument && !inkContact &&
        textDraft==null && textRestore==null && connectedDraft==null
    fun setNodeSelectionMode(enabled:Boolean):Boolean {
        if(!selectionReady()) return false
        if(nodeSelectionMode==enabled) return true
        nodeSelectionMode=enabled;selectedRelation=null;selectionRevision++
        if(!enabled && selectedNodes.size>1) selected=document?.nodes?.firstOrNull {it.id in selectedNodes}?.id
        return true
    }
    fun selectNodes(ids:Set<String>):Boolean {
        if(!selectionReady() || !nodeSelectionMode && ids.size>1) return false
        val visible=document?.visible()?.mapTo(hashSetOf()) {it.id} ?: return false
        if(!visible.containsAll(ids)) return false
        replaceNodeSelection(ids);selectedRelation=null
        return true
    }
    internal fun selectNodes(capture:MapNodeSelectionCapture,ids:Set<String>):Boolean =
        accepts(capture) && selectNodes(ids)
    internal fun captureNodes(expected:MindMapDocument,ids:Set<String>,expectedSelection:Set<String> = selectedNodes):MapNodeSelectionCapture? {
        if(!selectionReady() || expected!==document || expectedSelection!=selectedNodes) return null
        val visible=expected.visible().mapTo(hashSetOf()) {it.id}
        if(!visible.containsAll(ids)) return null
        return MapNodeSelectionCapture(expected,ids,this,connectedEpoch,revision,selectionRevision)
    }
    private fun accepts(capture:MapNodeSelectionCapture):Boolean = selectionReady() &&
        capture.owner===this && capture.document===document && capture.epoch==connectedEpoch &&
        capture.revision==revision && capture.selectionRevision==selectionRevision
    internal fun moveNodes(capture:MapNodeSelectionCapture,dx:Double,dy:Double):Boolean {
        if(!accepts(capture)) return false
        return operate {it.translateNodes(capture.ids,dx,dy)}.also {if(it) selectNodes(capture.ids)}
    }
    internal fun deleteNodes(capture:MapNodeSelectionCapture):Boolean {
        if(!accepts(capture)) return false
        return operate {it.deleteNodes(capture.ids)}
    }
    internal fun alignNodes(capture:MapNodeSelectionCapture,alignment:MapAlignment):Boolean {
        if(!accepts(capture)) return false
        return operate {it.alignNodes(capture.ids,alignment)}
    }
    var selectedRelation by mutableStateOf<String?>(null);private set
    fun selectRelation(id:String?):Boolean {
        if(closing || replacingDocument || inkContact || textDraft!=null || textRestore!=null) return false
        if(id==null) {selectedRelation=null;return true}
        val doc=document ?: return false
        val visible=doc.visible().mapTo(hashSetOf()) {it.id}
        if(doc.relations.none {it.id==id && it.from in visible && it.to in visible}) return false
        nodeSelectionMode=false;selectionRevision++
        replaceNodeSelection(emptySet());selectedRelation=id
        return true
    }
    var loading by mutableStateOf(true); private set
    var loadFailed by mutableStateOf(false); private set
    var saveFailed by mutableStateOf(false); private set
    var conflict by mutableStateOf(false); private set
    var recovered by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var closing by mutableStateOf(false); private set
    var dirty by mutableStateOf(false); private set
    var limitError by mutableStateOf(false); private set
    var sourceFailed by mutableStateOf(false); private set
    var copySaved by mutableStateOf(false); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    private var opened = false
    private var history: MapHistory? = null
    private lateinit var store: MapStore
    private lateinit var app: Context
    private lateinit var lock: Mutex
    @Volatile private var revision = 0L
    @Volatile private var durableRevision = -1L
    private var debounce: Job? = null
    val recoverySafe get() = ::store.isInitialized && store.journalReady && durableRevision == revision

    fun open(context: Context, uri: Uri) {
        if (opened) return
        opened = true; app = context.applicationContext
        lock = MapSaves.lockFor(uri)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { lock.withLock { runCatching {
                store=MapFiles.store(app,uri,target(uri));store.open()
            } } }
            result.onSuccess {
                history = MapHistory(it.document); document = it.document; selected = it.document.root.id
                reconcileSourceLifetimes()
                recovered = it.recovered; conflict = it.conflict; dirty = it.recovered
                if (store.journalReady) durableRevision = revision
            }.onFailure { loadFailed = true }
            loading = false
        }
    }
    private fun target(uri: Uri) = targetOverride?.invoke(uri) ?: MapFiles.target(app,uri)
    fun commit(next: MindMapDocument): Boolean {
        if (closing || replacingDocument) return false
        val h = history ?: return false
        val previous=document?.nodes.orEmpty().flatMap {it.sources}.associateBy {it.id}
        val reset=next.nodes.flatMap {it.sources}.filter {source->previous[source.id]?.let {it.target!=source.target}==true &&
            source.status!=if(source.lastPreview!=null) SourceStatus.STALE else SourceStatus.UNVERIFIED}.mapTo(hashSetOf()) {it.id}
        val normalized=if(reset.isEmpty()) next else next.copy(nodes=next.nodes.map {node->
            if(node.sources.none {it.id in reset}) node else node.copy(sources=node.sources.map {source->
                if(source.id in reset) source.copy(status=if(source.lastPreview!=null) SourceStatus.STALE else SourceStatus.UNVERIFIED) else source
            })
        })
        try { h.commit(normalized); limitError = false } catch (_: IllegalArgumentException) { limitError = true; return false }
        if (h.document != document) publish(h)
        return true
    }
    fun operate(block: (MindMapDocument) -> MindMapDocument): Boolean {
        if(inkContact) return false
        val doc = document ?: return false
        return try { commit(block(doc)) } catch (_: IllegalArgumentException) { limitError = true; false }
    }
    /** Dialog drafts may outlive other edits; only the latest owner document is merged. */
    internal fun changeRelation(initial:MapRelation?,proposed:MapRelation?):RelationEditResult {
        if(closing || replacingDocument || inkContact) return RelationEditResult.Conflict
        val doc=document ?: return RelationEditResult.Conflict
        val result=doc.changeRelation(initial,proposed)
        return if(result is RelationEditResult.Applied && !commit(result.document)) RelationEditResult.Invalid else result
    }
    fun undo() { if (!closing && !replacingDocument && !inkContact) history?.let { it.undo(); if (it.document != document) publish(it) } }
    fun redo() { if (!closing && !replacingDocument && !inkContact) history?.let { it.redo(); if (it.document != document) publish(it) } }
    private fun publish(h: MapHistory) {
        limitError=false
        document = h.document
        reconcileSourceLifetimes()
        retireInvalidSourceRequests()
        val visible=h.document.visible().mapTo(hashSetOf()) {it.id}
        if(h.document.relations.none {it.id==selectedRelation && it.from in visible && it.to in visible}) selectedRelation=null
        if(selectedRelation!=null) replaceNodeSelection(emptySet())
        else if(nodeSelectionMode) replaceNodeSelection(selectedNodes.intersect(visible))
        else if (h.document.nodes.none { it.id == selected }) selected = h.document.root.id
        canUndo = h.canUndo; canRedo = h.canRedo; dirty = true; copySaved = false; revision++
        debounce?.cancel()
        debounce = viewModelScope.launch { delay(350); save() }
    }
    /** [overwrite] is the explicit conflict choice to keep this version over the destination's. */
    fun save(onClosed: (() -> Unit)? = null, overwrite: Boolean = false) {
        if(replacingDocument) return
        val doc = document ?: return
        debounce?.cancel()
        if (onClosed != null) {retireConnectedDraft();closing = true}
        val version = revision
        saving = true
        MapSaves.scope.launch {
            val result = withContext(Dispatchers.IO) {
                lock.withLock {
                    if (version != revision) null else runCatching { if (overwrite) store.overwrite(doc) else store.save(doc) }.also {
                        if (store.journalReady) durableRevision = version
                    }
                }
            }
            if (version != revision) return@launch
            saving = false
            result?.onSuccess {
                dirty = false; saveFailed = false; recovered = false; conflict = false
                onClosed?.invoke()
            }?.onFailure {
                saveFailed = true; conflict = it is MapConflict; closing = false
                reconcileSourceLifetimes()
            }
        }
    }
    internal fun beginExistingSource(nodeId:String):String? {
        val doc=document ?: return null
        if(closing || replacingDocument || existingSourceRequest!=null || connectedSourceRequest!=null || sourcePickerInstance?.state!=null || doc.nodes.none {it.id==nodeId}) return null
        return ExistingSourceRequest(epoch=connectedEpoch,documentId=doc.id,nodeId=nodeId).also {existingSourceRequest=it}.id
    }
    private fun sourceCurrent(request:ExistingSourceRequest)=!closing && !replacingDocument && request.epoch==connectedEpoch &&
        request.documentId==document?.id && document?.nodes?.any {it.id==request.nodeId}==true
    internal fun receiveExistingSource(requestId:String,uri:Uri?):Boolean {
        val request=existingSourceRequest?.takeIf {it.id==requestId} ?: return false
        existingSourceRequest=null
        if(!sourceCurrent(request)) return false
        if(uri==null) return true
        lateinit var job:Job
        job=viewModelScope.launch(start=kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val source=withContext(Dispatchers.IO) {
                    val coroutine=currentCoroutineContext()
                    try {Result.success(connectedSourceReader(app,uri) {coroutine.ensureActive()}.also {it.validate()})}
                    catch(error:java.util.concurrent.CancellationException) {throw error}
                    catch(error:Exception) {Result.failure(error)}
                }
                if(!sourceCurrent(request)) return@launch
                sourceFailed=source.isFailure
                source.getOrNull()?.let {sourceValue->
                    androidx.compose.runtime.snapshotFlow {inkContact}.first {!it}
                    if(sourceCurrent(request)) operate {it.update(request.nodeId) {node->node.copy(sources=node.sources+sourceValue)}}
                }
            } finally {existingSourceJobs.remove(job)}
        }
        existingSourceJobs.add(job);job.start();return true
    }
    internal fun failExistingSource(requestId:String) {
        val request=existingSourceRequest?.takeIf {it.id==requestId} ?: return
        if(receiveExistingSource(requestId,null) && sourceCurrent(request)) sourceFailed=true
    }
    /** Source controls never replace a node body, and stale lists cannot erase newly attached data. */
    internal fun sourceEditSnapshot(nodeId:String):SourceEditSnapshot? {
        if(closing || replacingDocument) return null
        val doc=document ?: return null
        return doc.nodes.firstOrNull {it.id==nodeId}?.let {SourceEditSnapshot(connectedEpoch,doc.id,it)}
    }
    internal fun editSources(expected:SourceEditSnapshot,proposed:MapNode):Boolean {
        if(closing || replacingDocument || inkContact || proposed.id!=expected.node.id || expected.epoch!=connectedEpoch || expected.documentId!=document?.id) return false
        val current=document?.nodes?.firstOrNull {it.id==expected.node.id} ?: return false
        if(current.sources.map(::sourceSemantic)!=expected.node.sources.map(::sourceSemantic) || current.primarySource!=expected.node.primarySource) return false
        val latest=current.sources.associateBy {it.id}
        val merged=proposed.sources.map {source->latest[source.id]?.let {actual->
            actual.copy(documentId=source.documentId,scope=source.scope,pageId=source.pageId,region=source.region,note=source.note,
                lastKnownPage=if(source.target==actual.target) actual.lastKnownPage
                    else if(source.scope==SourceScope.FILE) null else source.lastKnownPage,
                status=if(source.target!=actual.target) {
                    if(actual.lastPreview!=null) SourceStatus.STALE else SourceStatus.UNVERIFIED
                } else actual.status)
        } ?: source}
        return operate {it.update(current.id) {node->node.copy(sources=merged,primarySource=proposed.primarySource)}}
    }
    private fun sourceSemantic(source:MapSource)=listOf(source.id,source.documentId,source.scope,source.pageId,source.region,source.note)
    /** Explicit conflict choice: discard the recovered edits and reload the destination's version. */
    fun useFileVersion() {
        if (closing || saving || inkContact || textDraft != null) return
        retireConnectedDraft()
        replacingDocument=true
        debounce?.cancel()
        saving = true
        MapSaves.scope.launch {
            val result = withContext(Dispatchers.IO) { lock.withLock { runCatching { store.discardRecovery() } } }
            saving = false
            result.onSuccess {
                retireConnectedDraft()
                // A new revision drops any save still queued for the discarded edits.
                revision++
                history = MapHistory(it.document); document = it.document; selected = it.document.root.id
                reconcileSourceLifetimes()
                canUndo = false; canRedo = false
                dirty = false; saveFailed = false; recovered = false; conflict = false; copySaved = false
                sourceFailed=false
            }.onFailure { saveFailed = true }
            replacingDocument=false
            if(result.isFailure) reconcileSourceLifetimes()
        }
    }
    /** A copy is a new document: it gets its own id so the registry never sees two files claim one. */
    fun saveCopy(uri: Uri, onComplete: () -> Unit) {
        val doc = document?.copy(id = java.util.UUID.randomUUID().toString()) ?: return
        saving = true;copySaved=false
        MapSaves.scope.launch {
            try {
                val result=try {MapFiles.create(app,uri,doc,targetOverride?.invoke(uri));Result.success(Unit)}
                    catch(error:java.util.concurrent.CancellationException) {throw error}
                    catch(error:Exception) {Result.failure(error)}
                if (result.isSuccess) { copySaved = true; onComplete() } else saveFailed = true
            } finally {saving=false}
        }
    }
}
