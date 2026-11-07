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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private object MapSaves {
    // Writes survive an Activity being stopped or destroyed. Each document owns its serialization lock.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    fun lockFor(uri: Uri) = locks.getOrPut(uri.toString()) { Mutex() }
}

class MapSession : ViewModel() {
    var textDraft by mutableStateOf<MapTextDraft?>(null); private set
    var textRestore by mutableStateOf<MapTextRestore?>(null); private set
    var textEditConflict by mutableStateOf(false); private set
    fun beginText(id:String,viewport:MindMapView.Viewport,width:Int,height:Int,density:Float=1f):Boolean {
        if(closing || inkContact || textDraft!=null || textRestore!=null || width<=0 || height<=0 || !density.isFinite() || density<=0) return false
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
    var selected by mutableStateOf<String?>(null)
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
        store = MapStore(File(app.noBackupFilesDir, "mind-map-recovery"), uri.toString(), target(uri))
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { lock.withLock { runCatching { store.open() } } }
            result.onSuccess {
                history = MapHistory(it.document); document = it.document; selected = it.document.root.id
                recovered = it.recovered; conflict = it.conflict; dirty = it.recovered
                if (store.journalReady) durableRevision = revision
            }.onFailure { loadFailed = true }
            loading = false
        }
    }
    private fun target(uri: Uri) = object : MapTarget {
        override fun read() = checkNotNull(app.contentResolver.openInputStream(uri)).use(MapCodec::readBytes)
        override fun write(bytes: ByteArray) { checkNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) } }
    }
    fun commit(next: MindMapDocument): Boolean {
        if (closing) return false
        val h = history ?: return false
        try { h.commit(next); limitError = false } catch (_: IllegalArgumentException) { limitError = true; return false }
        if (h.document != document) publish(h)
        return true
    }
    fun operate(block: (MindMapDocument) -> MindMapDocument): Boolean {
        if(inkContact) return false
        val doc = document ?: return false
        return try { commit(block(doc)) } catch (_: IllegalArgumentException) { limitError = true; false }
    }
    fun undo() { if (!closing && !inkContact) history?.let { it.undo(); if (it.document != document) publish(it) } }
    fun redo() { if (!closing && !inkContact) history?.let { it.redo(); if (it.document != document) publish(it) } }
    private fun publish(h: MapHistory) {
        limitError=false
        document = h.document
        if (h.document.nodes.none { it.id == selected }) selected = h.document.root.id
        canUndo = h.canUndo; canRedo = h.canRedo; dirty = true; copySaved = false; revision++
        debounce?.cancel()
        debounce = viewModelScope.launch { delay(350); save() }
    }
    /** [overwrite] is the explicit conflict choice to keep this version over the destination's. */
    fun save(onClosed: (() -> Unit)? = null, overwrite: Boolean = false) {
        val doc = document ?: return
        debounce?.cancel()
        if (onClosed != null) closing = true
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
            }
        }
    }
    fun addSource(uri: Uri, nodeId: String) {
        viewModelScope.launch {
            val source=withContext(Dispatchers.IO) { runCatching { MapSourceAccess.inspect(app,uri) } }
            sourceFailed=source.isFailure
            source.getOrNull()?.let { s ->
                androidx.compose.runtime.snapshotFlow {inkContact}.first { !it }
                if(document?.nodes?.any { it.id==nodeId } == true) operate { it.update(nodeId) { n -> n.copy(sources=n.sources+s) } }
            }
        }
    }
    /** Explicit conflict choice: discard the recovered edits and reload the destination's version. */
    fun useFileVersion() {
        if (closing || saving || inkContact || textDraft != null) return
        debounce?.cancel()
        saving = true
        MapSaves.scope.launch {
            val result = withContext(Dispatchers.IO) { lock.withLock { runCatching { store.discardRecovery() } } }
            saving = false
            result.onSuccess {
                // A new revision drops any save still queued for the discarded edits.
                revision++
                history = MapHistory(it.document); document = it.document; selected = it.document.root.id
                canUndo = false; canRedo = false
                dirty = false; saveFailed = false; recovered = false; conflict = false; copySaved = false
            }.onFailure { saveFailed = true }
        }
    }
    /** A copy is a new document: it gets its own id so the registry never sees two files claim one. */
    fun saveCopy(uri: Uri, onComplete: () -> Unit) {
        val doc = document?.copy(id = java.util.UUID.randomUUID().toString()) ?: return
        saving = true
        MapSaves.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val bytes = MapCodec.encode(doc)
                target(uri).write(bytes)
                check(target(uri).read().contentEquals(bytes))
            } }
            saving = false
            if (result.isSuccess) { copySaved = true; onComplete() } else saveFailed = true
        }
    }
}
