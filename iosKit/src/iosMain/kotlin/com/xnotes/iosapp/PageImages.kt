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
import com.xnotes.storage.lite.LiteNoteSetup
import platform.UIKit.UIImage
import kotlin.math.roundToInt

internal val black = Rgba(0, 0, 0)
internal val white = Rgba(255, 255, 255)

internal fun Page.paperRect(doc: Document) = insets(doc).let {
    Rect(-it.left, -it.top, width + it.left + it.right, height + it.top + it.bottom)
}

/** Render the new-note form's selected sheet with the same paper and templates as an opened note. */
fun previewNotePage(setup: LiteNoteSetup, catalog: TemplateCatalog, cover: Boolean, edge: Int, dark: Boolean): UIImage {
    val doc = setup.newDocument { _, _ -> }
    val index = if (!cover && setup.cover != null) 1 else 0
    return PageImages(doc, dark, catalog).use { it.page(index, edge) }
}

internal class PageImages(private val doc: Document, private val dark: Boolean, private val catalog: TemplateCatalog? = null) : AutoCloseable {
    private val assets = lazy { (IosStorageLocations.cache / Uuids.random()).also { KFiles.system.createDirectory(it, mustCreate = true) } }
    private val layout = TemplateLayoutCache(TemplateEval(CgTemplateGlyphs))
    private val templates = mutableMapOf<String, Template>()
    private val pictures = mutableMapOf<Pair<String, String>, ImageData>()
    private val frames = mutableMapOf<Boolean, FlowFrame>()
    private val textLayout = FlowLayout(CgTextMeasurer)
    val slotHeight get() = textLayout.defaultSlotHeight(doc.flow)
    private var previewPages = emptyList<Page>()
    val displayPages get() = if (previewPages.isEmpty()) doc.pages else doc.pages + previewPages
    fun invalidateFlow() = frames.clear()
    fun flow(night: Boolean) = frames.getOrPut(night) {
        val pages = displayPages
        val (width, height) = doc.blankPageSize(doc.pages.last())
        textLayout.autoColor = { if (night) white else black }
        textLayout.layout(doc.flow,
            pages.map { PageBox(it.sourceWidth, it.sourceHeight) }, doc.dpi,
            firstPage = if (pages.size > 1 && pages[0].cover) 1 else 0, addedPage = PageBox(width, height))
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

    private fun background(page: Page, renderer: Renderer, cover: Rect) {
        val key = page.resolvedTemplate(doc)
        if (key == PageTemplates.NONE) return
        val pattern = PagePattern.fromId(key)
        if (pattern != null) {
            val areas = if (page.pdfPage == null) listOf(cover) else Rooms.paper(cover, page.rooms, page.sourceWidth, page.sourceHeight)
            for (paper in areas) paintPagePattern(renderer, pattern, page.resolvedPatternColor(doc), page.resolvedSpacing(doc) ?: PageStyle.DEFAULT_SPACING, paper, paper)
            return
        }
        val entry = catalog?.entry(key)
        val embedded = doc.templatePackages[key]
        val template = templates.getOrPut(key) {
            val text = doc.templates[key] ?: embedded?.let(TemplatePackage::readJson)
            text?.let(TemplateReader::read) ?: entry?.template ?: return
        }
        val pkg = embedded ?: entry?.file
        PageTemplatePainter.paint(renderer, doc, page, template, cover, cover, layout) { src ->
            if (pkg == null) null else pictures.getOrPut(key to src) {
                val file = assets.value / Uuids.random()
                check(TemplatePackage.extract(pkg, src, file))
                val size = checkNotNull(CgImageCodec.probeFile(file.toString()))
                ImageData(file, size.width, size.height)
            }
        }
    }

    override fun close() { pdf?.close(); pdf = null; if (assets.isInitialized()) KFiles.system.deleteRecursively(assets.value) }
}
