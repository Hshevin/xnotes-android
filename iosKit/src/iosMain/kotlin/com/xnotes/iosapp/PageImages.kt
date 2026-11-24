package com.xnotes.iosapp

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.*
import com.xnotes.core.pal.*
import com.xnotes.core.pdf.MarkupPainter
import com.xnotes.core.template.TemplateCatalog
import com.xnotes.core.text.*
import com.xnotes.kio.KFiles
import com.xnotes.nativelibs.Pdfium
import com.xnotes.platform.ios.*
import com.xnotes.storage.lite.LiteNoteSetup
import platform.UIKit.UIImage
import kotlin.math.roundToInt

internal val black = Rgba(0, 0, 0)
internal val white = Rgba(255, 255, 255)

/** Render the new-note form's selected sheet with the same paper and templates as an opened note. */
fun previewNotePage(setup: LiteNoteSetup, catalog: TemplateCatalog, cover: Boolean, edge: Int, dark: Boolean): UIImage {
    val doc = setup.newDocument { _, _ -> }
    val index = if (!cover && setup.cover != null) 1 else 0
    return PageImages(doc, dark, catalog).use { it.page(index, edge) }
}

internal class PageImages(private val doc: Document, private val dark: Boolean, catalog: TemplateCatalog? = null) : AutoCloseable {
    private val drawing = CgPageDrawing(doc, catalog)
    private val frames = mutableMapOf<Boolean, FlowFrame>()
    val slotHeight get() = drawing.slotHeight
    private var previewPages = emptyList<Page>()
    val displayPages get() = if (previewPages.isEmpty()) doc.pages else doc.pages + previewPages
    fun invalidateFlow() = frames.clear()
    fun flow(night: Boolean) = frames.getOrPut(night) {
        drawing.layoutFlow(displayPages, if (night) white else black)
    }
    fun clearPreviewPages() {
        if (previewPages.isNotEmpty()) { previewPages = emptyList(); invalidateFlow() }
    }
    fun overflowPages(preview: Boolean): List<Page> {
        clearPreviewPages(); invalidateFlow()
        val (width, height) = doc.blankPageSize(doc.pages.last())
        var needed = flow(false).extraPagesNeeded
        while (needed > 0) {
            previewPages = previewPages + List(needed) { Page(width, height) }; invalidateFlow()
            needed = flow(false).extraPagesNeeded
        }
        return previewPages.also { if (!preview) clearPreviewPages() }
    }
    private var pdf: com.xnotes.nativelibs.PdfiumDocument? = null

    fun page(index: Int, edge: Int, upper: Boolean? = null): UIImage {
        val page = displayPages[index]
        val cover = page.paperRect(doc)
        val scale = edge / maxOf(cover.w, cover.h)
        val surface = CgSurfaceFactory.create((cover.w * scale).roundToInt().coerceAtLeast(1), (cover.h * scale).roundToInt().coerceAtLeast(1))
        return try {
            val paper = page.resolvedPageColor(doc, if (dark && page.pdfPage == null) black else white) ?: white
            surface.fill(if (upper == true) Rgba(0, 0, 0, 0) else paper)
            val raw = surface.renderer()
            val night = NightInk.enabled(dark, paper, page.pdfPage != null)
            val renderer = if (night) NightInkRenderer(raw) else raw
            renderer.scale(scale, scale)
            renderer.translate(-cover.x, -cover.y)
            val items = if (upper == null) page.visibleItems else {
                val topIds = page.layers.entries.dropWhile { it.id != page.layers.activeId }.drop(1).map { it.id }.toSet()
                page.visibleItems.filter { ((it.layerId ?: DrawingLayers.DEFAULT) in topIds) == upper }
            }
            if (upper == true) {
                items.forEach { it.paint(renderer) }; return surface.image()
            }
            drawing.paintTemplate(page, renderer, cover)
            val pdfIndex = page.pdfPage
            if (pdfIndex != null) {
                val source = pdf ?: checkNotNull(Pdfium.open(KFiles.system.read(checkNotNull(doc.pdfFile)) { readByteArray() })).also { pdf = it }
                val turn = PageTurn.normalize(page.pdfTurn)
                val wide = (page.sourceWidth * scale).roundToInt().coerceAtLeast(1)
                val high = (page.sourceHeight * scale).roundToInt().coerceAtLeast(1)
                val width = if (turn % 2 == 0) wide else high
                val height = if (turn % 2 == 0) high else wide
                val original = CgSurfaceFactory.opaqueArgb(source.render(pdfIndex, width, height), width, height, turn)
                try {
                    for (tile in Rooms.tiles(page.rooms, page.sourceWidth, page.sourceHeight)) renderer.withSave {
                        renderer.clipRect(tile.display); renderer.translate(tile.dx, tile.dy)
                        renderer.drawRaster(original, Rect(0.0, 0.0, page.sourceWidth, page.sourceHeight))
                        MarkupPainter.paint(renderer, page.markups, doc.dpi / 72.0)
                    }
                } finally { original.recycle() }
            } else MarkupPainter.paint(renderer, page.markups, doc.dpi / 72.0)
            PageLayers.paint(items, {
                if (doc.flow.isEmpty) return@paint
                val text = flow(night)
                val flowRenderer = if (!night) renderer else object : Renderer by renderer {
                    override fun drawTextRun(text: String, x: Double, baseline: Double, font: FontSpec, color: Rgba) =
                        renderer.drawTextRun(text, x, baseline, font, NightInk.color(color))
                }
                FlowPainter.paintPage(flowRenderer, text, index, cover)
            }) { it.paint(renderer) }
            surface.image()
        } finally { surface.recycle() }
    }

    override fun close() { pdf?.close(); pdf = null; drawing.close() }
}
