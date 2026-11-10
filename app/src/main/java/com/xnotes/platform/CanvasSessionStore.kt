package com.xnotes.platform

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.format.CanvasCodec
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * The working session for an infinite canvas: the open document, including edits never written to
 * its file, so relaunching reopens where the user left off rather than on a blank canvas.
 *
 * A sibling of [SessionStore] rather than a mode of it, for the same reason the codecs are
 * siblings: the two document models share no shape, and the restore path has to know which editor
 * to hand its result to. The view is not stored here at all, because a canvas carries its own last
 * view inside the document.
 *
 * Legacy callers remain best-effort; checked writes report failures. The body and sidecar are
 * individually replaced, not one crash-atomic publication. Missing or corrupt sessions restore nothing.
 */
class CanvasSessionStore(private val dir: File, private val codec: CanvasCodec, private val imageDir: File) {

    private val docFile = File(dir, "canvas.xcanvas")
    private val meta = JsonStore(File(dir, "canvas-session.json"))

    init {runCatching {LegacySessionBodyCandidates.cleanup(dir)}}

    data class Metadata(val path:String?,val displayName:String?,val dirty:Boolean)

    /** Whether a canvas session exists, so the host knows which editor to restore into. */
    fun exists(): Boolean = docFile.exists()

    fun save(document: InfiniteDocument, writeDocument: Boolean) {
        saveChecked(if(writeDocument || !docFile.exists()) document else null,
            Metadata(document.path,document.displayName,document.dirty))
    }

    /** A null body only refreshes metadata; canvas view changes still require a new body. */
    internal fun prepareMutation(document:InfiniteDocument?,metadata:Metadata):PreparedLegacySessionMutation=
        PreparedLegacySessionMutation.prepareCanvas(dir,codec,document,metadata)

    /** A null body only refreshes metadata; canvas view changes still require a new body. */
    fun saveChecked(document:InfiniteDocument?,metadata:Metadata):LegacySessionWrite=
        publishLegacyMutation(dir) {PreparedLegacySessionMutation.prepareCanvas(dir,codec,document,metadata,clearNote=false)}.legacyWrite()

    /** The stored canvas, or null when there is none or it will not read. */
    fun load(): InfiniteDocument? = runCatching {
        if (!docFile.exists()) return null
        val document = FileInputStream(docFile).use { codec.read(it, imageDir) }
        val json = meta.read()
        document.path = json.optString("path", "").ifEmpty { null }
        document.displayName = json.optString("display_name", "").ifEmpty { null }
        document.dirty = json.optBoolean("dirty", false)
        document
    }.getOrNull()

    fun clear() {
        clearChecked()
    }

    /** Preserve the legacy empty sidecar, but never detach it from a body that failed to clear. */
    fun clearChecked():Boolean=publishLegacyMutation(dir) {PreparedLegacySessionMutation.prepareCanvasClear(dir)} is LegacyMutationResult.Published
}
