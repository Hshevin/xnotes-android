package com.xnotes.iosapp.home

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.NightInkRenderer
import com.xnotes.iosapp.PageImages
import com.xnotes.iosapp.bitmap
import com.xnotes.iosapp.black
import com.xnotes.iosapp.store
import com.xnotes.iosapp.white
import com.xnotes.platform.ios.CgSurfaceFactory
import com.xnotes.storage.ios.IosDocumentEntry
import com.xnotes.storage.lite.LiteOpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** What a tile shows of a document once it has been read: its first page or drawing, how many pages, and whether it marks up a PDF. */
internal class DocPreview(val image: ImageBitmap?, val pages: Int, val pdf: Boolean)

/** First-page previews for the home tiles, read one document at a time and kept for as long as the file is unchanged. */
internal object HomeThumbs {
    /** Long side of a rendered preview, in pixels: a medium tile at 2x with room to spare. */
    private const val EDGE = 480
    private val previews = mutableStateMapOf<String, DocPreview>()
    // PDFium and the document reader are not shared between threads; one reader keeps them serial.
    private val reader = Mutex()

    private fun key(entry: IosDocumentEntry, dark: Boolean) = "${entry.path}|${entry.modifiedAtMillis}|$dark"

    fun cached(entry: IosDocumentEntry, dark: Boolean): DocPreview? = previews[key(entry, dark)]

    suspend fun load(entry: IosDocumentEntry, dark: Boolean): DocPreview {
        val key = key(entry, dark)
        previews[key]?.let { return it }
        val made = reader.withLock {
            previews[key] ?: withContext(Dispatchers.Default) { render(entry, dark) }
        }
        previews[key] = made
        return made
    }

    private fun render(entry: IosDocumentEntry, dark: Boolean): DocPreview {
        val opened = store.open(entry.path) as? LiteOpen.Opened ?: return DocPreview(null, 0, false)
        return opened.session.use { session ->
            runCatching {
                session.note?.let { doc ->
                    PageImages(doc, dark).use { images -> DocPreview(images.page(0, EDGE).bitmap(), doc.pages.size, doc.pdfFile != null) }
                } ?: session.canvas?.let { canvas ->
                    val items = canvas.items
                    val bounds = items.map { it.bounds() }.reduceOrNull(Rect::union)
                    val surface = CgSurfaceFactory.create(EDGE, EDGE)
                    try {
                        surface.fill(if (dark) black else white)
                        if (bounds != null && bounds.w > 0 && bounds.h > 0) {
                            val raw = surface.renderer()
                            val renderer = if (dark) NightInkRenderer(raw) else raw
                            val pad = EDGE * 0.06
                            val scale = (EDGE - 2 * pad) / maxOf(bounds.w, bounds.h)
                            renderer.translate(((EDGE - bounds.w * scale) / 2).roundToInt().toDouble(), ((EDGE - bounds.h * scale) / 2).roundToInt().toDouble())
                            renderer.scale(scale, scale)
                            renderer.translate(-bounds.x, -bounds.y)
                            canvas.layers.ordered(items).forEach { it.paint(renderer) }
                        }
                        DocPreview(surface.image().bitmap(), 0, false)
                    } finally { surface.recycle() }
                }
            }.getOrNull() ?: DocPreview(null, 0, false)
        }
    }

}
