@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.xnotes.platform.ios

import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.BackgroundPattern
import com.xnotes.core.infinite.CanvasPdfLayout
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.isFinite
import com.xnotes.core.model.*
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pdf.MarkupPainter
import com.xnotes.core.text.FlowPainter
import com.xnotes.kio.fileIdentity
import com.xnotes.map.ui.MapPaint
import com.xnotes.storage.lite.LiteSession
import kotlinx.cinterop.*
import okio.Path
import platform.CoreGraphics.*
import platform.Foundation.NSURL
import platform.PDFKit.PDFAnnotation
import platform.PDFKit.PDFDocument
import platform.PDFKit.kPDFDisplayBoxCropBox

/** Exports a privately opened, stable session. The caller keeps it open until this call returns. */
object PdfExport {
    private val white = Rgba(255, 255, 255)
    private val black = Rgba(0, 0, 0)

    /**
     * Reports completed pages for a note and completed items for a canvas, starting at zero.
     * Returns false on cancellation. A rendering or file error propagates with the old destination intact.
     */
    fun export(
        session: LiteSession,
        out: Path,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        if (isCancelled()) return false
        val note = session.note
        fileIdentity(out)?.let { target ->
            require(target != fileIdentity(session.path) && target != note?.pdfFile?.let(::fileIdentity))
        }
        return writePdf(out, isCancelled) { context ->
            if (note != null) paintNote(context, note, onProgress, isCancelled)
            else paintCanvas(context, checkNotNull(session.canvas), onProgress, isCancelled)
        }
    }

    private fun paintNote(
        context: OwnedContext, doc: Document, progress: (Int, Int) -> Unit, cancelled: () -> Boolean,
    ): Boolean {
        require(doc.pages.isNotEmpty() && doc.dpi > 0)
        val total = doc.pages.size
        progress(0, total)
        if (cancelled()) return false
        val source = if (doc.pages.any { it.pdfPage != null }) {
            PDFDocument(NSURL.fileURLWithPath(checkNotNull(doc.pdfFile).toString())).also { check(!it.isLocked()) }
        } else null
        CgPageDrawing(doc).use { drawing ->
            val flow = if (doc.flow.isEmpty) null else drawing.layoutFlow(doc.pages, black)
            doc.pages.forEachIndexed { index, page ->
                if (cancelled()) return false
                autoreleasepool {
                    val cover = page.paperRect(doc)
                    context.page(cover, 72.0 / doc.dpi) { renderer ->
                        val paper = page.resolvedPageColor(doc, white) ?: white
                        renderer.fillRect(cover, paper)
                        paintPaperStrips(renderer, page.paperStrips(doc, paper))
                        paintSourceAndMarkups(context.ref, renderer, page, source, doc.dpi)
                        drawing.paintTemplate(page, renderer, cover)
                        PageLayers.paint(page.visibleItems.forExport(), {
                            if (flow != null) FlowPainter.paintPage(renderer, flow, index, cover)
                        }) { item -> paintItem(renderer, item) }
                    }
                }
                progress(index + 1, total)
            }
        }
        return true
    }

    private fun paintSourceAndMarkups(
        cg: CGContextRef, renderer: Renderer, page: Page, source: PDFDocument?, dpi: Int,
    ) {
        val index = page.pdfPage
        if (index == null) {
            MarkupPainter.paint(renderer, page.markups, dpi / 72.0)
            return
        }
        require(source != null && index >= 0 && index.toULong() < source.pageCount)
        val sourcePage = checkNotNull(source.pageAtIndex(index.toULong()))
        val pdfPage = checkNotNull(sourcePage.pageRef)
        val annotations = sourcePage.annotations.filterIsInstance<PDFAnnotation>().filter { it.shouldDisplay }
        val box = Rect(0.0, 0.0, page.sourceWidth, page.sourceHeight)
        // Quartz centers a small source in a larger destination without upscaling it.
        // Map its crop/rotation into a unit square, then scale that square to the note in pixels.
        val transform = CGPDFPageGetDrawingTransform(pdfPage, kCGPDFCropBox,
            CGRectMake(0.0, 0.0, 1.0, 1.0), PageTurn.normalize(page.pdfTurn) * 90, false)
        page.paintInBands(renderer) {
            renderer.fillRect(box, white)
            cg.saved {
                CGContextClipToRect(cg, box.cg())
                CGContextTranslateCTM(cg, 0.0, box.h)
                CGContextScaleCTM(cg, box.w, -box.h)
                CGContextConcatCTM(cg, transform)
                CGContextDrawPDFPage(cg, pdfPage)
                if (annotations.isNotEmpty()) {
                    // PDFKit applies the page's display transform again when drawing its annotations.
                    CGContextConcatCTM(cg, CGAffineTransformInvert(sourcePage.transformForBox(kPDFDisplayBoxCropBox)))
                    for (annotation in annotations) cg.saved { annotation.drawWithBox(kPDFDisplayBoxCropBox, cg) }
                }
            }
            MarkupPainter.paint(renderer, page.markups, dpi / 72.0)
        }
    }

    private fun paintCanvas(
        context: OwnedContext, doc: InfiniteDocument, progress: (Int, Int) -> Unit, cancelled: () -> Boolean,
    ): Boolean {
        val items = MapPaint.ordered(doc, CgTextMeasurer).forExport().filter { it.paintBounds().isFinite() }
        val layout = CanvasPdfLayout.of(items.map { it.paintBounds() }.reduceOrNull(Rect::union), doc.dpi)
        progress(0, items.size)
        if (cancelled()) return false
        context.page(layout.cover, layout.scale) { renderer ->
            val bg = doc.background
            renderer.fillRect(layout.cover, bg.paperColor ?: white)
            val period = bg.clampedSpacing * BackgroundPattern.levelMultiplier(bg.clampedSpacing, layout.zoomEquivalent(doc.dpi))
            paintPagePattern(renderer, bg.pattern, bg.patternColor, period, layout.cover, layout.cover)
            items.forEachIndexed { i, item ->
                if (cancelled()) return false
                autoreleasepool { paintItem(renderer, item) }
                progress(i + 1, items.size)
            }
        }
        return true
    }

    private fun paintItem(renderer: Renderer, item: CanvasItem) {
        item.paint(renderer)
        if (item is Stroke) item.releaseGeometry()
    }

    /** A PDF page uses points and a bottom-left origin. Present the renderer with document coordinates. */
    private inline fun OwnedContext.page(cover: Rect, scale: Double, paint: (Renderer) -> Unit) {
        require(cover.isFinite() && cover.w > 0 && cover.h > 0 && scale.isFinite() && scale > 0)
        val width = (cover.w * scale).coerceAtLeast(1.0)
        val height = (cover.h * scale).coerceAtLeast(1.0)
        CGContextBeginPage(ref, CGRectMake(0.0, 0.0, width, height))
        try {
            CGContextTranslateCTM(ref, 0.0, height)
            CGContextScaleCTM(ref, scale, -scale)
            CGContextTranslateCTM(ref, -cover.left, -cover.top)
            paint(CgRenderer(null, this, nightInk = false))
        } finally { CGContextEndPage(ref) }
    }
}
