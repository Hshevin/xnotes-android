package com.xnotes.storage.lite

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import okio.Path

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
    val note: Document? get() = (original.body as? LiteBody.Note)?.doc
    val canvas: InfiniteDocument? get() = (original.body as? LiteBody.Canvas)?.doc
    private var revision = if (recovery != null) 1L else 0L
    private var savedRevision = 0L
    private var closed = false
    private var writing = false
    val dirty get() = revision != savedRevision
    val registeredRecoveryCopy: Path? get() = recovery?.record?.parent?.recoveryBody(kind)

    init {
        note?.path = path.toString()
        canvas?.path = path.toString()
        setModelDirty(dirty)
    }

    private fun setModelDirty(value: Boolean) {
        note?.dirty = value
        canvas?.dirty = value
    }

    fun markEdited() {
        check(!closed)
        revision++
        setModelDirty(true)
    }

    fun save(beforePublish: ((Path) -> Unit)? = null): LiteSave = transaction {
        if (!dirty) return@transaction LiteSave.Saved
        val version = revision
        val body = original.body.snapshot()
        val receipt = try {
            store.publish(path, base, body, original, beforePublish)
        } catch (failure: Exception) {
            val copy = try {
                protect(body)
            } catch (protectionFailure: Exception) {
                throw LiteProtectionException(failure, protectionFailure)
            }
            copy.problem?.let { failure.addSuppressed(it) }
            return@transaction if (failure is SourceConflict && copy.problem == null) LiteSave.Conflict(copy.body)
                else LiteSave.Failed(failure, copy.body)
        }
        base = receipt
        savedRevision = version
        if (!dirty) setModelDirty(false)
        replaceRecovery(null)
        LiteSave.Saved
    }

    fun writeRecovery(): Boolean = transaction {
        if (!dirty) return@transaction false
        try {
            protect(original.body.snapshot()).recovery != null
        } catch (_: Exception) {
            false
        }
    }

    private fun protect(body: LiteBody): Protected = store.protect(path, base, body, original).also {
        if (it.recovery != null) replaceRecovery(it.recovery)
    }

    private fun replaceRecovery(next: LiteRecovery?) {
        val previous = recovery
        recovery = next
        if (previous != null) runCatching { store.discard(previous) }
    }

    private inline fun <R> transaction(block: () -> R): R {
        check(!closed && !writing)
        writing = true
        try {
            return block()
        } finally {
            writing = false
        }
    }

    override fun close() {
        check(!writing)
        if (!closed) {
            closed = true
            store.release(assets)
        }
    }

    fun abandonForTest() = close()
}
