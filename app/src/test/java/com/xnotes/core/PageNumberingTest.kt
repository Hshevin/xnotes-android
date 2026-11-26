package com.xnotes.core
import com.xnotes.core.model.PageNumbering
import org.junit.Assert.*
import org.junit.Test
class PageNumberingTest {
    @Test fun frontMatterAndBody() {
        val n = PageNumbering(20, 4)
        assertEquals("i", n.label(0))
        assertEquals("iv", n.label(3))
        assertEquals("1", n.label(4))
        assertEquals(3, n.resolve("IV"))
        assertEquals(19, n.resolve("16"))
        assertNull(n.resolve("17"))
        assertNull(n.resolve("iiii"))
        assertNull(n.resolve("0"))
    }
    @Test fun pdfStartingNumberAndOverflow() {
        val n = PageNumbering(20, 4, 5)
        assertEquals("5", n.label(4))
        assertEquals(4, n.resolve("5"))
        assertNull(n.resolve("4"))
        assertNull(n.resolve("2147483647"))
    }
    @Test fun defaultsAndInvalidStart() {
        assertEquals("1", PageNumbering(10, 0).label(0))
        assertEquals("1", PageNumbering(10, 100).label(0))
        assertNull(PageNumbering(0, 0).resolve("1"))
    }
}
