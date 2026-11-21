package com.xnotes.map.source

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.map.SourceKind
import com.xnotes.map.SourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SourceLinksTest {

    private var ids = 0
    private fun newId() = "s${++ids}"

    private fun note(pages: Int = 2, anchored: Boolean = true): Document {
        val doc = Document.blank()
        while (doc.pages.size < pages) doc.pages.add(Page(100.0, 100.0))
        doc.path = "content://docs/Math.xnote"
        doc.displayName = "Math.xnote"
        if (anchored) doc.enableReferenceAnchors()
        return doc
    }

    @Test fun aSavedPageWithAnIdIsReady() {
        val doc = note()
        val page = doc.pages[1]
        val offer = SourceLinks.forPage(doc, page, ::newId)
        assertEquals(
            PageLinkOffer.Ready(SourceRef("s1", SourceKind.NOTE_PAGE, doc.identity.documentId, page.referenceIdentity.id, "Math · 2")),
            offer,
        )
    }

    @Test fun aPdfPageIsAPdfLink() {
        val doc = note()
        doc.pages[0].pdfPage = 0
        assertEquals(SourceKind.PDF_PAGE, (SourceLinks.forPage(doc, doc.pages[0], ::newId) as PageLinkOffer.Ready).ref.kind)
    }

    @Test fun eachMissingConditionHasItsOwnAnswerInOrder() {
        val unsaved = note().also { it.path = null }
        assertEquals(PageLinkOffer.Unsaved, SourceLinks.forPage(unsaved, Page(1.0, 1.0), ::newId))

        val doc = note()
        assertEquals(PageLinkOffer.NotInDocument, SourceLinks.forPage(doc, Page(1.0, 1.0), ::newId))

        val plain = note(anchored = false)
        assertEquals(PageLinkOffer.NeedsPageIds, SourceLinks.forPage(plain, plain.pages[0], ::newId))

        val later = Page(100.0, 100.0).also { doc.pages.add(it) }
        assertEquals(PageLinkOffer.PageNotSaved, SourceLinks.forPage(doc, later, ::newId))
        assertEquals(0, ids)
    }

    @Test fun offeringChangesNothingInTheModel() {
        val doc = note()
        val later = Page(100.0, 100.0).also { doc.pages.add(it) }
        val revision = doc.bodyRevision
        SourceLinks.forPage(doc, later, ::newId)
        SourceLinks.forPage(doc, doc.pages[0], ::newId)
        assertNull("no id is handed out on the way", later.referenceIdentity.id)
        assertEquals(revision, doc.bodyRevision)

        val plain = note(anchored = false)
        SourceLinks.forPage(plain, plain.pages[0], ::newId)
        assertEquals(false, plain.hasReferenceAnchors)
    }

    @Test fun aCanvasLinksAsAWhole() {
        val canvas = InfiniteDocument(path = "content://docs/Board.xcanvas", displayName = "Board.xcanvas")
        assertEquals(
            PageLinkOffer.Ready(SourceRef("s1", SourceKind.CANVAS, canvas.identity.documentId, null, "Board")),
            SourceLinks.forCanvas(canvas, ::newId),
        )
        assertSame(PageLinkOffer.Unsaved, SourceLinks.forCanvas(InfiniteDocument(), ::newId))
    }
}
