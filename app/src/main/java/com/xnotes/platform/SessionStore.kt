package com.xnotes.platform

import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.model.Document
import com.xnotes.format.DocumentCodec
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

enum class LegacySessionWrite { SAVED,NEEDS_BODY,FAILED }

/**
 * Persists the working session — the open document (including unsaved edits) plus
 * the view state (zoom/scroll) — under a private directory, so relaunching reopens
 * the last note where the user left off instead of a blank one.
 *
 * The document is written in the normal `.xnote` format via [DocumentCodec]; the
 * file's identity ([Document.path]/[Document.displayName]/[Document.dirty], which
 * the codec doesn't carry) and the view state go in a small sidecar JSON.
 * Legacy callers remain best-effort; checked writes report failures. The body and sidecar are
 * individually replaced, not one crash-atomic publication. Missing or corrupt sessions restore nothing.
 */
class SessionStore(private val dir: File, private val codec: DocumentCodec, private val pdfDir: File, private val imageDir: File) {

    private val docFile = File(dir, "document.xnote")
    private val metaFile = File(dir, "session.json")
    private val meta = JsonStore(metaFile)

    /** Captured separately from the body, so a view-only refresh never needs a live model. */
    data class Metadata(
        val path:String?,
        val displayName:String?,
        val dirty:Boolean,
        val zoom:Double,
        val scrollX:Double,
        val scrollY:Double,
        val zoomLocked:Boolean,
        val viewOverrides:ViewOverrides,
    )

    /** A restored session: the document plus the view state to reapply. */
    class Snapshot(
        val document: Document,
        val zoom: Double,
        val scrollX: Double,
        val scrollY: Double,
        val zoomLocked: Boolean,
        val viewOverrides: ViewOverrides,
    )

    /**
     * Save [document] and view state. Pass [writeDocument] = false to refresh only
     * the (cheap) view-state sidecar when the document content hasn't changed since
     * the last save — avoiding re-serializing a large note on every pause.
     */
    fun save(
        document: Document,
        zoom: Double,
        scrollX: Double,
        scrollY: Double,
        zoomLocked: Boolean,
        viewOverrides: ViewOverrides,
        writeDocument: Boolean,
    ) {
        saveChecked(if(writeDocument || !docFile.exists()) document else null,
            Metadata(document.path,document.displayName,document.dirty,zoom,scrollX,scrollY,zoomLocked,viewOverrides))
    }

    /** A null body only refreshes metadata; the caller must supply a fixed body after NEEDS_BODY. */
    fun saveChecked(document:Document?,metadata:Metadata):LegacySessionWrite=runCatching {
        if(document==null) {
            if(!docFile.exists()) return@runCatching LegacySessionWrite.NEEDS_BODY
            if(!docFile.isFile) return@runCatching LegacySessionWrite.FAILED
        } else {
            check(dir.isDirectory || dir.mkdirs())
            val tmp=File.createTempFile("document-",".xnote.tmp",dir)
            try {
                FileOutputStream(tmp).use {output->
                    codec.write(document,object:FilterOutputStream(output) {
                        override fun write(bytes:ByteArray,offset:Int,length:Int)=output.write(bytes,offset,length)
                        override fun close() {flush()}
                    })
                    output.fd.sync()
                }
                Files.move(tmp.toPath(),docFile.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
            } finally {tmp.delete()}
        }
        val m=with(metadata) {
            val m = JSONObject()
                .put("dirty", dirty)
                .put("zoom", zoom)
                .put("scrollX", scrollX)
                .put("scrollY", scrollY)
                .put("zoomLocked", zoomLocked)
            path?.let {m.put("path",it)}
            displayName?.let {m.put("displayName",it)}
            ViewOverridesJson.write(m,viewOverrides)
        }
        if(meta.writeChecked(m)) LegacySessionWrite.SAVED else LegacySessionWrite.FAILED
    }.getOrDefault(LegacySessionWrite.FAILED)

    /** Discard any saved session (document + sidecar), so the next launch restores nothing.
     *  Used when the app is left with no note open, so a closed note isn't kept on disk. */
    fun clear() {
        clearChecked()
    }

    /** Keep the sidecar when the body cannot be removed, so a failed clear does not detach it. */
    fun clearChecked():Boolean=runCatching {
        Files.deleteIfExists(docFile.toPath())
        Files.deleteIfExists(metaFile.toPath())
        true
    }.getOrDefault(false)

    /** Load the saved session, or null when there is none or it can't be read. */
    fun load(): Snapshot? {
        if (!docFile.exists()) return null
        val doc = runCatching { FileInputStream(docFile).use { codec.read(it, pdfDir, imageDir) } }.getOrNull() ?: return null
        val m = meta.read()
        doc.path = m.optString("path", "").ifEmpty { null }
        doc.displayName = m.optString("displayName", "").ifEmpty { null }
        doc.dirty = m.optBoolean("dirty", false)
        return Snapshot(
            doc,
            m.optDouble("zoom", 0.0),
            m.optDouble("scrollX", 0.0),
            m.optDouble("scrollY", 0.0),
            m.optBoolean("zoomLocked", false),
            ViewOverridesJson.read(m),
        )
    }
}
