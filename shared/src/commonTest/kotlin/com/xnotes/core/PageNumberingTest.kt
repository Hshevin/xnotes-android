package com.xnotes.core
import com.xnotes.core.model.PageNumbering
import com.xnotes.core.testkit.*
import kotlin.test.Test
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
    @Test fun rangesOffsetAndAbsoluteResolution() {
        val plain = PageNumbering(46, 0)
        assertFalse(plain.hasBodyOffset)
        assertTrue(plain.isBodyFirst(0))
        assertFalse(plain.isBodyFirst(2))
        assertNull(plain.frontRange())
        assertEquals("1–46", plain.bodyRange())
        assertEquals("1–46", plain.absoluteRange())
        assertEquals("1–46", plain.validRangeLabel(byBody = false))
        assertEquals("1–46", plain.validRangeLabel(byBody = true))
        assertEquals(2, plain.resolveAbsolute(" 3 "))
        assertNull(plain.resolveAbsolute("0"))
        assertNull(plain.resolveAbsolute("47"))

        val withFront = PageNumbering(46, 4)
        assertTrue(withFront.hasBodyOffset)
        assertTrue(withFront.isBodyFirst(4))
        assertFalse(withFront.isBodyFirst(0))
        assertEquals("i–iv", withFront.frontRange())
        assertEquals("1–42", withFront.bodyRange())
        assertEquals("i–iv · 1–42", withFront.validRangeLabel(byBody = true))
        assertEquals("1–46", withFront.validRangeLabel(byBody = false))

        val singleFront = PageNumbering(2, 1)
        assertEquals("i", singleFront.frontRange())
        assertEquals("1", singleFront.bodyRange())
        assertEquals("i · 1", singleFront.validRangeLabel(byBody = true))

        val pdfOffset = PageNumbering(10, 0, firstNumber = 51)
        assertTrue(pdfOffset.hasBodyOffset)
        assertFalse(pdfOffset.isBodyFirst(0))
        assertNull(pdfOffset.frontRange())
        assertEquals("51–60", pdfOffset.bodyRange())
        assertEquals("51–60", pdfOffset.validRangeLabel(byBody = true))

        val singlePage = PageNumbering(1, 0)
        assertEquals("1", singlePage.absoluteRange())
        assertEquals("1", singlePage.bodyRange())

        assertEquals("15", withFront.displayLabel(14, byBody = false))
        assertEquals("46", withFront.displayTotal(14, byBody = false))
        assertEquals("11", withFront.displayLabel(14, byBody = true))
        assertEquals("42", withFront.displayTotal(14, byBody = true))
        assertEquals("iii", withFront.displayLabel(2, byBody = true))
        assertEquals("iv", withFront.displayTotal(2, byBody = true))
        assertEquals("55", pdfOffset.displayLabel(4, byBody = true))
        assertEquals("60", pdfOffset.displayTotal(4, byBody = true))
        assertEquals(
            listOf(com.xnotes.core.model.PageNumberMode.ABSOLUTE, com.xnotes.core.model.PageNumberMode.BODY),
            com.xnotes.core.model.PageNumberMode.entries,
        )
    }
}
