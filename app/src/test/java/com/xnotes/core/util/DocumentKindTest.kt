package com.xnotes.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentKindTest {

    @Test fun aNameIsTheKindItsExtensionSays() {
        assertEquals(DocumentKind.NOTE, DocumentKind.ofName("Lecture 3.xnote"))
        assertEquals(DocumentKind.CANVAS, DocumentKind.ofName("Board.XCANVAS"))
        assertNull(DocumentKind.ofName("photo.png"))
        assertNull(DocumentKind.ofName("pdf"))
    }

    @Test fun aPdfIsNotADocumentWhilePdfDocumentsAreOff() {
        // The original PDF stays immutable: a .pdf is an import source, never a listed document.
        assertFalse(DocumentKind.PDF_DOCUMENTS_ENABLED)
        assertNull(DocumentKind.ofName("Lecture 3.pdf"))
        assertNull(DocumentKind.ofName("scan.PDF"))
        assertFalse(DocumentKind.isDocument("Lecture 3.pdf"))
        assertFalse(DocumentKind.isDocument("Lecture 3.pdf.bak"))
        assertTrue(DocumentKind.isDocument("Lecture 3.xnote"))
    }

    @Test fun theExtensionComesOffAndGoesOnOnce() {
        assertEquals("Lecture 3", DocumentKind.stripSuffix("Lecture 3.xnote"))
        assertEquals("Lecture 3.pdf", DocumentKind.stripSuffix("Lecture 3.pdf"))
        assertEquals("Lecture 3.pdf", DocumentKind.withSuffix("Lecture 3", DocumentKind.PDF))
        assertEquals("Lecture 3.pdf", DocumentKind.withSuffix("Lecture 3.pdf", DocumentKind.PDF))
        // A PDF renamed with a note's extension typed in still ends up a PDF.
        assertEquals("Lecture 3.pdf", DocumentKind.withSuffix(DocumentKind.stripSuffix("Lecture 3.xnote"), DocumentKind.PDF))
    }
}
