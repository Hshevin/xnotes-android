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
        val doc = document ?: return false
        return try { commit(block(doc)) } catch (_: IllegalArgumentException) { limitError = true; false }
    }
    fun undo() { if (!closing) history?.let { it.undo(); if (it.document != document) publish(it) } }
    fun redo() { if (!closing) history?.let { it.redo(); if (it.document != document) publish(it) } }
    private fun publish(h: MapHistory) {
        document = h.document
        if (h.document.nodes.none { it.id == selected }) selected = h.document.root.id
        canUndo = h.canUndo; canRedo = h.canRedo; dirty = true; copySaved = false; revision++
        debounce?.cancel()
        debounce = viewModelScope.launch { delay(350); save() }
    }
    fun save(onClosed: (() -> Unit)? = null) {
        val doc = document ?: return
        debounce?.cancel()
        if (onClosed != null) closing = true
        val version = revision
        saving = true
        MapSaves.scope.launch {
            val result = withContext(Dispatchers.IO) {
                lock.withLock {
                    if (version != revision) null else runCatching { store.save(doc) }.also {
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
    fun saveCopy(uri: Uri, onComplete: () -> Unit) {
        val doc = document ?: return
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
