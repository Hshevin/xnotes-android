package com.xnotes.storage.lite

import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.enableReferenceAnchors
import kotlin.time.Clock

data class LiteNoteSetup(val widthMm: Double, val heightMm: Double, val style: PageStyle, val cover: PageStyle? = null) {
    /** Document structure used by both Android's new-note form and LiteStore. */
    fun newDocument(embed: (Document, String?) -> Unit): Document {
        val dpi = PageSize.DEFAULT_DPI
        val w = PageSize.mmToPx(widthMm, dpi)
        val h = PageSize.mmToPx(heightMm, dpi)
        require(w.isFinite() && h.isFinite() && w > 0 && h > 0)
        val doc = Document.blankPixels(width = w, height = h, dpi = dpi)
        doc.created = Clock.System.now().toEpochMilliseconds()
        doc.style = style
        embed(doc, style.template)
        cover?.let {
            doc.pages.add(0, Page(w, h, style = it, margins = PageMargins(0.0, 0.0, 0.0, 0.0)).apply { this.cover = true })
            embed(doc, it.template)
        }
        doc.enableReferenceAnchors()
        return doc
    }
}
