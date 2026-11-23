package com.xnotes.iosapp

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.*
import com.xnotes.core.pal.*
import com.xnotes.core.pdf.MarkupPainter
import com.xnotes.core.template.*
import com.xnotes.core.text.*
import com.xnotes.format.TemplatePackage
import com.xnotes.format.TemplateReader
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import com.xnotes.nativelibs.Pdfium
import com.xnotes.platform.ios.*
import com.xnotes.storage.ios.IosStorageLocations
import platform.UIKit.UIImage
import kotlin.math.roundToInt

internal val black = Rgba(0, 0, 0)
internal val white = Rgba(255, 255, 255)

internal fun Page.paperRect(doc: Document) = insets(doc).let {
    Rect(-it.left, -it.top, width + it.left + it.right, height + it.top + it.bottom)
}

internal class PageImages(private val doc: Document, private val dark: Boolean) : AutoCloseable {
    private val assets = lazy { (IosStorageLocations.cache / Uuids.random()).also { KFiles.system.createDirectory(it, mustCreate = true) } }
    private val layout = TemplateLayoutCache(TemplateEval())
    private val templates = mutableMapOf<String, Template>()
    private val pictures = mutableMapOf<Pair<String, String>, ImageData>()
    private val frames = mutableMapOf<Boolean, FlowFrame>()
    private fun flow(night: Boolean) = if (doc.flow.isEmpty) null else frames.getOrPut(night) {
        FlowLayout(CgTextMeasurer).apply { autoColor = { if (night) white else black } }.layout(doc.flow,
            doc.pages.map { PageBox(it.sourceWidth, it.sourceHeight) }, doc.dpi,
            firstPage = if (doc.pages.size > 1 && doc.pages[0].cover) 1 else 0)
    }
    private var pdf: com.xnotes.nativelibs.PdfiumDocument? = null

    fun page(index: Int, edge: Int): UIImage {
        val page = doc.pages[index]
        val cover = page.paperRect(doc)
        val scale = edge / maxOf(cover.w, cover.h)
        val surface = CgSurfaceFactory.create((cover.w * scale).roundToInt().coerceAtLeast(1), (cover.h * scale).roundToInt().coerceAtLeast(1))
        return try {
            val paper = page.resolvedPageColor(doc, if (dark && page.pdfPage == null) black else white) ?: white
            surface.fill(paper)
            val raw = surface.renderer()
            val night = NightInk.enabled(dark, paper, page.pdfPage != null)
            val renderer = if (night) NightInkRenderer(raw) else raw
            renderer.scale(scale, scale)
            renderer.translate(-cover.x, -cover.y)
            background(page, renderer, cover)
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
            PageLayers.paint(page.visibleItems, {
                val text = flow(night) ?: return@paint
                val flowRenderer = if (!night) renderer else object : Renderer by renderer {
                    override fun drawTextRun(text: String, x: Double, baseline: Double, font: FontSpec, color: Rgba) =
                        renderer.drawTextRun(text, x, baseline, font, NightInk.color(color))
                }
                FlowPainter.paintPage(flowRenderer, text, index, cover)
            }) { it.paint(renderer) }
            surface.image()
        } finally { surface.recycle() }
    }

    private fun background(page: Page, renderer: Renderer, cover: Rect) {
        val key = page.resolvedTemplate(doc)
        if (key == PageTemplates.NONE) return
        val areas = if (page.pdfPage == null) listOf(cover) else Rooms.paper(cover, page.rooms, page.sourceWidth, page.sourceHeight)
        val ink = page.resolvedPatternColor(doc)
        val pattern = PagePattern.fromId(key)
        if (pattern != null) {
            for (paper in areas) paintPagePattern(renderer, pattern, ink, page.resolvedSpacing(doc) ?: PageStyle.DEFAULT_SPACING, paper, paper)
            return
        }
        val pkg = doc.templatePackages[key]
        val template = templates.getOrPut(key) {
            TemplateReader.read(doc.templates[key] ?: pkg?.let(TemplatePackage::readJson) ?: return)
        }
        val scale = doc.dpi / 25.4
        val numbers = page.resolvedParams(doc).toMutableMap()
        page.resolvedSpacing(doc)?.let { spacing -> template.spacingParam?.let { numbers[it.name] = spacing / scale } }
        val values = TemplateValues(numbers, page.resolvedColors(doc))
        for (paper in areas) {
            val definition = TemplatePage(page.width / scale, page.height / scale,
                Rect(paper.x / scale, paper.y / scale, paper.w / scale, paper.h / scale), ink, page.resolvedAccentColor(doc), values)
            TemplatePainter.paint(renderer, layout.layout(template, definition), scale, paper) { src ->
                if (pkg == null) null else pictures.getOrPut(key to src) {
                    val file = assets.value / Uuids.random()
                    check(TemplatePackage.extract(pkg, src, file))
                    val size = checkNotNull(CgImageCodec.probeFile(file.toString()))
                    ImageData(file, size.width, size.height)
                }
            }
        }
    }

    override fun close() { pdf?.close(); pdf = null; if (assets.isInitialized()) KFiles.system.deleteRecursively(assets.value) }
}
