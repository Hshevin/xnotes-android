package com.xnotes.platform

import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.model.Document
import com.xnotes.format.DocumentCodec
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
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

    init { LegacySessionBodyCandidates.cleanup(dir) }
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

    fun saveChecked(document: Document?, metadata: Metadata): LegacySessionWrite = try {
        LegacySessionBodyCandidates.cleanup(dir)
        if (document != null) writeSessionBody(docFile) { codec.write(document, it, embedPdf = !document.identity.externalPdf) }
        if (!docFile.isFile) LegacySessionWrite.NEEDS_BODY else {
            val json = JSONObject().put("dirty", metadata.dirty).put("zoom", metadata.zoom)
                .put("scrollX", metadata.scrollX).put("scrollY", metadata.scrollY).put("zoomLocked", metadata.zoomLocked)
            metadata.path?.let { json.put("path", it) }
            metadata.displayName?.let { json.put("displayName", it) }
            if (meta.writeChecked(ViewOverridesJson.write(json, metadata.viewOverrides))) LegacySessionWrite.SAVED else LegacySessionWrite.FAILED
        }
    } catch (_: Exception) { LegacySessionWrite.FAILED }

    fun deleteBody() { Files.deleteIfExists(docFile.toPath()) }
    fun clear() { clearChecked() }
    fun clearChecked(): Boolean = runCatching { deleteBody(); Files.deleteIfExists(metaFile.toPath()); true }.getOrDefault(false)

    /** Load the saved session, or null when there is none or it can't be read. */
    fun load(): Snapshot? {
        if (!docFile.exists()) return null
        val doc = runCatching { FileInputStream(docFile).use { codec.readSession(it, pdfDir, imageDir) } }.getOrNull() ?: return null
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

internal fun writeSessionBody(file: File, encode: (java.io.OutputStream) -> Unit) {
    file.parentFile?.mkdirs()
    val candidate = LegacySessionBodyCandidates.create(file.parentFile!!, file.nameWithoutExtension + "-", "." + file.extension + ".tmp")
    val tmp = candidate.file
    try {
        tmp.outputStream().use(encode)
        Files.move(tmp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally { candidate.close() }
}

internal object LegacySessionBodyCandidates {
    private val gate=Any()
    private val active=hashSetOf<String>()

    fun cleanup(directory:File)=synchronized(gate) {
        for(file in directory.listFiles().orEmpty()) {
            val candidate=file.name.startsWith("document-") && file.name.endsWith(".xnote.tmp") ||
                file.name.startsWith("canvas-") && file.name.endsWith(".xcanvas.tmp")
            if(candidate && file.isFile && file.canonicalPath !in active) file.delete()
        }
    }

    // Register before cleanup can observe the new file; encoding itself never holds this gate.
    fun create(directory:File,prefix:String,suffix:String):Candidate=synchronized(gate) {
        val file=File.createTempFile(prefix,suffix,directory)
        val key=file.canonicalPath
        active.add(key)
        Candidate(file,key)
    }

    class Candidate internal constructor(val file:File,private val key:String):AutoCloseable {
        private val identity=Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey()
        private var closed=false
        internal fun ownsFile():Boolean=runCatching {
            val actual=Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)
            actual.isRegularFile && identity!=null && actual.fileKey()==identity
        }.getOrDefault(false)
        /** Releasing registration must never delete a foreign replacement at this pathname. */
        internal fun unregister()=synchronized(gate) {if(!closed) {closed=true;active.remove(key)};Unit}
        override fun close()=synchronized(gate) {
            if(!closed) {
                closed=true
                try {if(ownsFile()) Files.deleteIfExists(file.toPath())} finally {active.remove(key)}
            }
            Unit
        }
    }
}
