package com.xnotes.map.ui

import com.xnotes.core.FakeTextMeasurer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FakeTextMeasurer: 0.6 pt advance and 1.3 pt line height per point; titles are sized in canvas px. */
class MapTitlesTest {
    private val titles = MapTitles(FakeTextMeasurer())
    private val pt = 27.0 / MapTitles.PX_PER_POINT

    @Test fun aTitleIsAsWideAsItsTextPlusAPixel() {
        val s = titles.measure("abcd", 27.0, false, 320.0)
        assertEquals(kotlin.math.ceil(4 * 0.6 * pt) + 1.0, s.w, 1e-9)
        assertEquals(1.3 * pt, s.h, 1e-9)
    }

    @Test fun aLongTitleWrapsAtTheMaximumWidth() {
        assertEquals(320.0, titles.measure("x".repeat(200), 27.0, false, 320.0).w, 1e-9)
    }

    @Test fun anEmptyTitleIsOneEmptyLine() {
        val s = titles.measure("", 27.0, false, 320.0)
        assertEquals(0.0, s.w, 1e-9)
        assertEquals(1.3 * pt, s.h, 1e-9)
    }

    @Test fun theLongestLineSetsTheWidth() {
        val s = titles.measure("ab\nabcdef", 27.0, false, 320.0)
        assertEquals(kotlin.math.ceil(6 * 0.6 * pt) + 1.0, s.w, 1e-9)
        assertEquals(2 * 1.3 * pt, s.h, 1e-9)
    }

    @Test fun outlinesAreCachedAndBlankTitlesHaveNone() {
        val a = titles.outline("abc", 27.0, false, 320.0)
        assertTrue(a === titles.outline("abc", 27.0, false, 320.0))
        assertEquals(3, a.size)
        assertTrue(titles.outline("  ", 27.0, false, 320.0).isEmpty())
    }

    @Test fun chipTextIsCutShortWithAnEllipsis() {
        val adv = 15.0 / MapTitles.PX_PER_POINT * 0.6
        assertEquals("abc", titles.fit("abc", 15.0, 100.0))
        val cut = titles.fit("abcdefghij", 15.0, adv * 5.5)
        assertEquals("abcd…", cut)
    }
}
