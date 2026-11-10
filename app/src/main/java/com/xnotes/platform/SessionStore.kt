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

    init {runCatching {LegacySessionBodyCandidates.cleanup(dir)}}

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
    internal fun prepareMutation(document:Document?,metadata:Metadata):PreparedLegacySessionMutation=
        PreparedLegacySessionMutation.prepareNote(dir,codec,document,metadata)

    /** A null body only refreshes metadata; the caller must supply a fixed body after NEEDS_BODY. */
    fun saveChecked(document:Document?,metadata:Metadata):LegacySessionWrite=
        publishLegacyMutation(dir) {PreparedLegacySessionMutation.prepareNote(dir,codec,document,metadata,clearCanvas=false)}.legacyWrite()

    /** Discard any saved session (document + sidecar), so the next launch restores nothing.
     *  Used when the app is left with no note open, so a closed note isn't kept on disk. */
    fun clear() {
        clearChecked()
    }

    /** Keep the sidecar when the body cannot be removed, so a failed clear does not detach it. */
    fun clearChecked():Boolean=publishLegacyMutation(dir) {PreparedLegacySessionMutation.prepareNoteClear(dir)} is LegacyMutationResult.Published

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

/** Shared by every legacy store instance, including aliases of the same private directory. */
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
