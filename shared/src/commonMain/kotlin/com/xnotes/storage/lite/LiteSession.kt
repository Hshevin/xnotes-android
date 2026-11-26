package com.xnotes.storage.lite

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.util.CoreLock
import com.xnotes.core.util.locked
import okio.Path

class LiteSaveJob internal constructor(private val execute: () -> LiteSave) {
    fun run(): LiteSave = execute()
}

class LiteRecoveryJob internal constructor(private val execute: () -> Boolean) {
    fun run(): Boolean = execute()
}

class LiteSession internal constructor(
    private val store: LiteStore,
    val path: Path,
    private val assets: Path,
    private val original: Decoded,
    private var base: Stamp,
    private var recovery: LiteRecovery?,
) : AutoCloseable {
    val kind = original.body.kind
    val documentId = original.documentId
    private val lock = CoreLock()
    private var revision = if (recovery != null) 1L else 0L
    private var savedRevision = 0L
    private var closed = false
    private var writing = false
    // A restored model describes the private copy, not the original file's stored prefix.
    private var savedAssets = if (recovery == null) original.assets else emptyList()
    val dirty get() = lock.locked { refreshDirty() }
    val note: Document? get() = lock.locked { refreshDirty(); (original.body as? LiteBody.Note)?.doc }
    val canvas: InfiniteDocument? get() = lock.locked { refreshDirty(); (original.body as? LiteBody.Canvas)?.doc }
    val registeredRecoveryCopy: Path? get() = lock.locked { recovery?.record?.parent?.recoveryBody(kind) }

    init {
        note?.path = path.toString()
        canvas?.path = path.toString()
    }

    private fun refreshDirty(): Boolean {
        val dirty = revision != savedRevision
        when (val model = original.body) {
            is LiteBody.Note -> model.doc.dirty = dirty
            is LiteBody.Canvas -> model.doc.dirty = dirty
        }
        return dirty
    }

    fun markEdited() = lock.locked {
        check(!closed)
        revision++
        refreshDirty()
        Unit
    }

    /** Capture the model on its owning thread. The returned work is single-use. */
    private fun <T> capture(write: (LiteBody, Long) -> T): (() -> T)? = lock.locked {
        check(!closed && !writing)
        if (!refreshDirty()) return@locked null
        val body = original.body.snapshot()
        val version = revision
        writing = true
        var started = false
        val job = {
            lock.locked { check(!started); started = true }
            try { write(body, version) } finally { lock.locked { writing = false } }
        }
        job
    }

    fun beginSave(): LiteSaveJob? = prepareSave(null)

    private fun prepareSave(beforePublish: ((Path) -> Unit)?): LiteSaveJob? =
        capture { body, version ->
            val receipt = try {
                store.publish(path, base, body, original, savedAssets, beforePublish)
            } catch (failure: Exception) {
                val copy = try { protect(body) } catch (protectionFailure: Exception) {
                    throw LiteProtectionException(failure, protectionFailure)
                }
                copy.problem?.let { failure.addSuppressed(it) }
                return@capture if (failure is SourceConflict && copy.problem == null) LiteSave.Conflict(copy.body)
                    else LiteSave.Failed(failure, copy.body)
            }
            lock.locked {
                base = receipt.first
                savedAssets = receipt.second
                savedRevision = version
            }
            replaceRecovery(null)
            LiteSave.Saved
        }?.let(::LiteSaveJob)

    fun save(beforePublish: ((Path) -> Unit)? = null): LiteSave =
        (prepareSave(beforePublish)?.run() ?: LiteSave.Saved).also { lock.locked { refreshDirty() } }

    fun beginRecovery(): LiteRecoveryJob? = capture { body, _ ->
        try { protect(body).recovery != null } catch (_: Exception) { false }
    }?.let(::LiteRecoveryJob)

    fun writeRecovery(): Boolean = beginRecovery()?.run() ?: false

    private fun protect(body: LiteBody): Protected = store.protect(path, base, body, original).also {
        if (it.recovery != null) replaceRecovery(it.recovery)
    }

    private fun replaceRecovery(next: LiteRecovery?) {
        val previous = lock.locked { recovery.also { recovery = next } }
        if (previous != null) runCatching { store.discard(previous) }
    }

    override fun close() = lock.locked {
        check(!writing)
        if (!closed) {
            closed = true
            store.release(assets)
        }
    }

    fun abandonForTest() = close()
}
