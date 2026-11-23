package com.xnotes.platform.ios

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.Pen
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W2-4 contract (docs/movinkpad/process/ipados-wave2-contracts-2026-11-24.md, "W2-4"): the CoreGraphics
 * implementation of core/pal draws what the Android renderer draws. Pixel checks sample the middle of shapes
 * and well outside them, never anti-aliased edges.
 */
class CgRendererTest {

    private val black = Rgba(0, 0, 0)
    private val white = Rgba(255, 255, 255)
    private val clear = Rgba(0, 0, 0, 0)

    private fun surface(w: Int = 100, h: Int = 100, fill: Rgba = white) =
        CgSurfaceFactory.create(w, h).also { it.fill(fill) }

    private fun near(expected: Rgba, actual: Rgba, tolerance: Int = 3) =
        abs(expected.r - actual.r) <= tolerance && abs(expected.g - actual.g) <= tolerance &&
            abs(expected.b - actual.b) <= tolerance && abs(expected.a - actual.a) <= tolerance

    private fun assertPixel(expected: Rgba, s: CgSurface, x: Int, y: Int, tolerance: Int = 3) {
        val actual = s.pixel(x, y)
        assertTrue(near(expected, actual, tolerance), "pixel ($x,$y): expected $expected, was $actual")
    }

    @Test
    fun surfaceHasTheRequestedSizeAndFillsIncludingTransparent() {
        val s = CgSurfaceFactory.create(30, 20, devicePixelRatio = 2.0)
        assertEquals(30, s.width)
        assertEquals(20, s.height)
        assertEquals(2.0, s.devicePixelRatio)
        s.fill(clear)
        assertPixel(clear, s, 5, 5, 0)
        s.fill(Rgba(10, 20, 30))
        assertPixel(Rgba(10, 20, 30), s, 29, 19, 0)
    }

    @Test
    fun originIsTopLeftWithYDown() {
        val s = surface()
        s.renderer().fillRect(Rect(0.0, 0.0, 100.0, 10.0), black)
        assertPixel(black, s, 50, 2)
        assertPixel(white, s, 50, 95)
    }

    @Test
    fun transformsAndSaveRestoreCompose() {
        val s = surface()
        val r = s.renderer()
        r.save()
        r.translate(50.0, 50.0)
        r.scale(2.0, 2.0)
        r.fillRect(Rect(0.0, 0.0, 10.0, 10.0), black) // device 50..70
        r.restore()
        r.fillRect(Rect(0.0, 0.0, 5.0, 5.0), Rgba(255, 0, 0))
        assertPixel(black, s, 60, 60)
        assertPixel(white, s, 75, 75)
        assertPixel(Rgba(255, 0, 0), s, 2, 2)
    }

    @Test
    fun clipRectLimitsDrawing() {
        val s = surface()
        val r = s.renderer()
        r.save()
        r.clipRect(Rect(0.0, 0.0, 50.0, 100.0))
        r.fillRect(Rect(0.0, 0.0, 100.0, 100.0), black)
        r.restore()
        assertPixel(black, s, 25, 50)
        assertPixel(white, s, 75, 50)
    }

    @Test
    fun polygonFillRulesDiffer() {
        // Two nested squares wound the same way: non-zero fills the hole, even-odd leaves it.
        val outer = listOf(Pt(10.0, 10.0), Pt(90.0, 10.0), Pt(90.0, 90.0), Pt(10.0, 90.0))
        val inner = listOf(Pt(30.0, 30.0), Pt(70.0, 30.0), Pt(70.0, 70.0), Pt(30.0, 70.0))
        val path = outer + outer.first() + inner + inner.first()

        val nz = surface()
        nz.renderer().fillPolygon(path, black, FillRule.NONZERO)
        assertPixel(black, nz, 50, 50)
        assertPixel(black, nz, 20, 50)

        val eo = surface()
        eo.renderer().fillPolygon(path, black, FillRule.EVEN_ODD)
        assertPixel(white, eo, 50, 50)
        assertPixel(black, eo, 20, 50)
    }

    @Test
    fun diskRibbonPaintsRoundCappedInk() {
        val s = surface()
        val centers = floatArrayOf(20f, 50f, 80f, 50f)
        val radii = floatArrayOf(6f, 6f)
        s.renderer().fillDiskRibbon(centers, radii, black)
        assertPixel(black, s, 50, 50)
        assertPixel(black, s, 15, 50) // round cap reaches past the first centre
        assertPixel(white, s, 50, 60)
        assertPixel(white, s, 5, 50)
    }

    @Test
    fun cosmeticPenKeepsItsDeviceWidthUnderScale() {
        val s = surface()
        val r = s.renderer()
        r.scale(10.0, 10.0)
        r.strokePolyline(listOf(Pt(1.0, 5.0), Pt(9.0, 5.0)), Pen(black, width = 2.0, cosmetic = true))
        assertPixel(black, s, 50, 50)
        assertPixel(white, s, 50, 56) // a content-width pen of 2 would be 20 px tall
    }

    @Test
    fun multiplyDarkensAndLeavesWhiteToTheInk() {
        val s = surface()
        val r = s.renderer()
        r.fillRect(Rect(0.0, 0.0, 50.0, 100.0), Rgba(128, 128, 128))
        r.saveLayerBlended(Rect(0.0, 0.0, 100.0, 100.0), 1.0, BlendMode.MULTIPLY)
        r.fillRect(Rect(0.0, 40.0, 100.0, 20.0), Rgba(255, 255, 0))
        r.restore()
        assertPixel(Rgba(128, 128, 0), s, 25, 50, 4) // grey × yellow
        assertPixel(Rgba(255, 255, 0), s, 75, 50, 4) // white × yellow
        assertPixel(Rgba(128, 128, 128), s, 25, 10, 4)
    }

    @Test
    fun layerAlphaFadesWholeGroupsOnce() {
        val s = surface()
        val r = s.renderer()
        r.saveLayerAlpha(Rect(0.0, 0.0, 100.0, 100.0), 0.5)
        r.fillRect(Rect(0.0, 0.0, 60.0, 100.0), black)
        r.fillRect(Rect(40.0, 0.0, 60.0, 100.0), black) // overlap must not get darker
        r.restore()
        assertPixel(Rgba(128, 128, 128), s, 20, 50, 4)
        assertPixel(Rgba(128, 128, 128), s, 50, 50, 4)
    }

    @Test
    fun rastersBlitIntoOtherSurfaces() {
        val src = surface(10, 10, black)
        val dst = surface()
        dst.renderer().drawRaster(src, Rect(20.0, 20.0, 40.0, 40.0))
        assertPixel(black, dst, 40, 40)
        assertPixel(white, dst, 10, 10)
        assertPixel(white, dst, 70, 70)
    }

    @Test
    fun drawingAppendsToExistingContent() {
        val s = surface()
        s.renderer().fillRect(Rect(0.0, 0.0, 10.0, 10.0), black)
        s.renderer().fillRect(Rect(90.0, 90.0, 10.0, 10.0), black)
        assertPixel(black, s, 5, 5)
        assertPixel(black, s, 95, 95)
    }

    @Test
    fun recycleIsIdempotent() {
        val s = surface()
        s.recycle()
        s.recycle()
    }
}
