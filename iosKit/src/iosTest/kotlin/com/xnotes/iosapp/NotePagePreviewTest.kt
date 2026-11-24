package com.xnotes.iosapp

import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.Rgba
import com.xnotes.core.template.TemplateCatalog
import com.xnotes.storage.lite.LiteNoteSetup
import kotlin.test.*

class NotePagePreviewTest {
    @Test fun previewResolvesPaperTemplateLabelsAndCoverIndependently() {
        val json = """{"xtemplate":1,"name":"Heading","items":[
            {"type":"text","text":"Notes","x":"5mm","y":"18mm","size":"10mm","fill":"#000000"}]}"""
        val entry = TemplateCatalog.plain(json, TemplateCatalog.Source.IMPORTED)
        val catalog = TemplateCatalog(listOf(entry))
        val paper = Rgba(210, 220, 230)
        val setup = LiteNoteSetup(100.0, 50.0, PageStyle(pageColor = paper, template = entry.key),
            PageStyle(pageColor = Rgba(0, 0, 0), template = PageTemplates.NONE))
        fun pixels(cover: Boolean): IntArray {
            val bitmap = previewNotePage(setup, catalog, cover, edge = 200, dark = false).bitmap()
            assertEquals(200, bitmap.width)
            assertEquals(100, bitmap.height)
            return IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }
        }
        val body = pixels(false)
        assertEquals(paper.toArgb(), body.last())
        assertTrue(body.count { it == Rgba(0, 0, 0).toArgb() } > 10)
        assertTrue(pixels(true).all { it == Rgba(0, 0, 0).toArgb() })
    }
}
