package com.xnotes.platform.ios

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W3-A contract (docs/movinkpad/process/ipados-wave3-contracts-2026-11-24.md, "W3-A"): CoreText measuring and
 * drawing behind core/pal, so flow text, labels and Chinese notes show on the iPad.
 */
class CgTextTest {

    /** 12 pt, which Android sets at 12 × 150 / 72 = 25 px (AndroidText.POINTS_TO_PX); the iPad must match. */
    private val font = FontSpec(pointSize = 12.0)

    private fun darkPixels(s: CgSurface, area: Rect): Int {
        var n = 0
        for (y in area.y.toInt() until (area.y + area.h).toInt()) for (x in area.x.toInt() until (area.x + area.w).toInt()) {
            val p = s.pixel(x, y)
            if (p.r < 128 && p.g < 128 && p.b < 128) n++
        }
        return n
    }

    @Test
    fun measuringGrowsWithTextAndWrapsAtTheWidth() {
        val m = CgTextMeasurer
        val one = m.advances("a", font).sum()
        val four = m.advances("aaaa", font).sum()
        assertTrue(one > 0.0)
        assertEquals(4 * one, four, one * 0.05)
        val lh = m.lineHeight(font)
        assertTrue(lh in 25.0..40.0, "line height $lh for a 25 px font")
        val wrapped = m.measure("word ".repeat(40), font, wrapWidth = 200.0)
        assertTrue(wrapped.w <= 200.0 + 0.5)
        assertTrue(wrapped.h >= 3 * lh - 0.5, "long text wraps onto several lines")
        val metrics = m.metrics(font)
        assertTrue(metrics.ascent > 0.0 && metrics.descent > 0.0)
    }

    @Test
    fun chineseIsMeasuredAndDrawn() {
        assertTrue(CgTextMeasurer.advances("课堂笔记", font).all { it > 10.0 })
        val s = CgSurfaceFactory.create(200, 60).also { it.fill(Rgba(255, 255, 255)) }
        s.renderer().drawText("课堂笔记", Rect(0.0, 0.0, 200.0, 60.0), font, Rgba(0, 0, 0))
        assertTrue(darkPixels(s, Rect(0.0, 0.0, 200.0, 60.0)) > 100)
    }

    @Test
    fun textIsDrawnInsideItsRectAndUprightInTopDownSpace() {
        val s = CgSurfaceFactory.create(200, 100).also { it.fill(Rgba(255, 255, 255)) }
        s.renderer().drawText("Hello", Rect(10.0, 10.0, 180.0, 40.0), font, Rgba(0, 0, 0))
        assertTrue(darkPixels(s, Rect(10.0, 10.0, 180.0, 40.0)) > 50)
        assertEquals(0, darkPixels(s, Rect(0.0, 60.0, 200.0, 40.0)), "nothing below the rect (would mean a flipped y axis)")
    }

    @Test
    fun aBaselineRunSitsOnItsBaseline() {
        val s = CgSurfaceFactory.create(200, 100).also { it.fill(Rgba(255, 255, 255)) }
        s.renderer().drawTextRun("Hxx", 10.0, 60.0, font, Rgba(0, 0, 0))
        assertTrue(darkPixels(s, Rect(0.0, 35.0, 200.0, 25.0)) > 30, "glyphs above the baseline")
        assertEquals(0, darkPixels(s, Rect(0.0, 0.0, 200.0, 20.0)))
    }
}
