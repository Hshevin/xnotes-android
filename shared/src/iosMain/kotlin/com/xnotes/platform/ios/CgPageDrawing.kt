package com.xnotes.platform.ios

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.*
import com.xnotes.core.pal.Renderer
import com.xnotes.core.template.*
import com.xnotes.core.text.*
import com.xnotes.format.TemplatePackage
import com.xnotes.format.TemplateReader
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import com.xnotes.storage.ios.IosStorageLocations

fun Page.paperRect(doc: Document) = insets(doc).let {
    Rect(-it.left, -it.top, width + it.left + it.right, height + it.top + it.bottom)
}

/** Page decoration and flow layout shared by the iPad viewer and its vector PDF export. */
class CgPageDrawing(private val doc: Document) : AutoCloseable {
    private val assets = lazy { (IosStorageLocations.cache / Uuids.random()).also { KFiles.system.createDirectory(it, mustCreate = true) } }
    private val layout = TemplateLayoutCache(TemplateEval())
    private val templates = mutableMapOf<String, Template>()
    private val pictures = mutableMapOf<Pair<String, String>, ImageData>()
    private val textLayout = FlowLayout(CgTextMeasurer)
    val slotHeight get() = textLayout.defaultSlotHeight(doc.flow)

    fun layoutFlow(pages: List<Page>, ink: Rgba): FlowFrame {
        val (width, height) = doc.blankPageSize(doc.pages.last())
        textLayout.autoColor = { ink }
        return textLayout.layout(doc.flow,
            pages.map { PageBox(it.sourceWidth, it.sourceHeight) }, doc.dpi,
            firstPage = if (pages.size > 1 && pages[0].cover) 1 else 0, addedPage = PageBox(width, height))
    }

    fun paintTemplate(page: Page, renderer: Renderer, cover: Rect) {
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

    override fun close() {
        if (assets.isInitialized()) KFiles.system.deleteRecursively(assets.value)
    }
}
