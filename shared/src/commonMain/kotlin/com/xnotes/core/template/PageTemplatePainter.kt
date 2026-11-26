package com.xnotes.core.template

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.*
import com.xnotes.core.pal.Renderer

/** Draw a page's template with its resolved document settings, leaving original PDF areas clear. */
object PageTemplatePainter {
    /** [page]'s parameter values from its style chain, lengths converted to template mm. */
    private fun values(t: Template, doc: Document, page: Page): TemplateValues {
        val numbers = HashMap(page.resolvedParams(doc))
        val spacing = t.spacingParam
        page.resolvedSpacing(doc)?.let { px -> if (spacing != null) numbers[spacing.name] = px * 25.4 / doc.dpi }
        return TemplateValues(numbers, page.resolvedColors(doc))
    }

    /**
     * Paint [page]'s template into [r] in page-local content px. [cover] is the page's whole paper,
     * margins included; [region] is the part being painted. A page showing an imported PDF gets its
     * template in the margins and the room inserted into it only, so the PDF itself is never drawn
     * over. [minLine] is the thinnest line in content px, so a thumbnail's ruling stays visible.
     */
    fun paint(r: Renderer, doc: Document, page: Page, t: Template, cover: Rect, region: Rect,
              layouts: TemplateLayoutCache, minLine: Double = 0.0, picture: (String) -> ImageData?) {
        val mm = doc.dpi / 25.4
        val papers = if (page.pdfPage == null) listOf(cover) else Rooms.paper(cover, page.rooms, page.sourceWidth, page.sourceHeight)
        val values = values(t, doc, page)
        val ink = page.resolvedPatternColor(doc)
        val accent = page.resolvedAccentColor(doc)
        for (paper in papers) {
            if (!paper.intersects(region)) continue
            val tp = TemplatePage(
                page.width / mm, page.height / mm,
                Rect(paper.x / mm, paper.y / mm, paper.w / mm, paper.h / mm),
                ink, accent, values,
            )
            TemplatePainter.paint(r, layouts.layout(t, tp), mm, region, minLine, picture)
        }
    }
}
