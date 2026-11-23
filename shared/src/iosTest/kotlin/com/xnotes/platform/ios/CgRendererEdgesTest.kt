package com.xnotes.platform.ios

import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.Pen
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import okio.ByteString.Companion.decodeBase64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CgRendererEdgesTest {
    private val red = Rgba(255, 0, 0)
    private val blue = Rgba(0, 0, 255)
    private val clear = Rgba(0, 0, 0, 0)
    private fun surface() = CgSurfaceFactory.create(32, 32).also { it.fill(clear) }

    @Test
    fun separateRenderersDoNotInheritAClipOrTransform() {
        val target = surface()
        val first = target.renderer()
        first.translate(16.0, 0.0)
        first.rotate(90.0)
        first.clipPolygon(listOf(Pt(0.0, 0.0), Pt(16.0, 0.0), Pt(0.0, 16.0)))
        target.renderer().fillRect(Rect(0.0, 0.0, 32.0, 32.0), red)
        first.clear()
        assertEquals(clear, target.pixel(12, 2))
        assertEquals(red, target.pixel(3, 13))
        assertEquals(red, target.pixel(31, 31))
    }

    @Test
    fun croppedBlitAndOverlappingSelfBlitUseTopLeftSourcePixels() {
        val src = surface()
        val dest = surface()
        src.renderer().fillRect(Rect(0.0, 0.0, 32.0, 16.0), red)
        src.renderer().fillRect(Rect(0.0, 16.0, 32.0, 16.0), blue)
        dest.renderer().drawRaster(src, Rect(0.0, 0.0, 32.0, 32.0), Rect(0.0, 0.0, 32.0, 16.0))
        assertEquals(red, dest.pixel(16, 25))
        src.renderer().drawRaster(src, Rect(0.0, 12.0, 32.0, 20.0), Rect(0.0, 0.0, 32.0, 20.0))
        assertEquals(red, src.pixel(16, 26))
        assertEquals(blue, src.pixel(16, 30))
    }

    @Test
    fun translucentPixelsAreStraightAndScreenCompositesOnlyOnRestore() {
        val target = surface()
        val paint = target.renderer()
        val color = Rgba(255, 0, 0, 100)
        target.fill(color)
        assertEquals(color, target.pixel(3, 2))
        val bounds = Rect(0.0, 0.0, 32.0, 32.0)
        target.fill(red)
        paint.saveLayerBlended(bounds, 1.0, BlendMode.SCREEN)
        paint.save()
        paint.fillRect(bounds, blue)
        paint.restore()
        paint.restore()
        assertEquals(Rgba(255, 0, 255), target.pixel(3, 2))
    }

    @Test
    fun dashedCosmeticPenKeepsRunLengthsAfterScale() {
        val target = surface()
        val paint = target.renderer()
        paint.scale(2.0, 2.0)
        paint.strokePolyline(listOf(Pt(0.0, 4.0), Pt(16.0, 4.0)),
            Pen(red, width = 2.0, dashed = true, dashOn = 8.0, dashGap = 8.0, dashPhase = 4.0))
        assertEquals(red, target.pixel(1, 8))
        assertEquals(clear, target.pixel(8, 8))
        assertEquals(red, target.pixel(16, 8))
    }

    @Test
    fun innerGlowIsClippedAndOuterGlowSpills() {
        val target = surface()
        val paint = target.renderer()
        paint.fillCircleGlow(Pt(16.0, 16.0), 8.0, red, 4.0, true)
        assertEquals(clear, target.pixel(25, 16))
        assertTrue(target.pixel(22, 16).a in 1..254)
        target.fill(clear)
        paint.fillCircleGlow(Pt(16.0, 16.0), 8.0, red, 4.0, false)
        assertTrue(target.pixel(25, 16).a in 1..254)
    }

    @Test
    fun encodedImageCanTurnAndMirrorWithoutInvertingItsRows() {
        val source = KFiles.tempDirectory() / "${Uuids.random()}.png"
        val bytes = "iVBORw0KGgoAAAANSUhEUgAAAAQAAAACCAIAAADwyuo0AAAAEUlEQVR4nGP4z8AARwxI7P8AZ7IH+SY4448AAAAASUVORK5CYII=".decodeBase64()!!
        KFiles.system.write(source) { write(bytes) }
        try {
            val target = surface()
            val image = ImageData(source, 4, 2)
            target.renderer().drawImage(image, Rect(0.0, 0.0, 16.0, 32.0), orientation = 90, flip = Flip.X)
            assertEquals(red, target.pixel(1, 16))
            assertEquals(blue, target.pixel(14, 16))
        } finally { KFiles.system.delete(source) }
    }
}
