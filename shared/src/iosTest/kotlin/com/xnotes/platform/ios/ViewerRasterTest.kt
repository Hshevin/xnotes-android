@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.platform.ios

import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.TextFlags
import kotlin.math.PI
import kotlin.test.*
import platform.QuartzCore.CAMetalLayer

class ViewerRasterTest {
    @Test fun metalPicturesKeepColorAndStackBetweenNightInkMeshes() {
        val ink = assertNotNull(MetalInk.createOrNull())
        val layer = CAMetalLayer()
        ink.attach(layer)
        assertNotNull(layer.device)
        val black = Rgba(0, 0, 0)
        fun mesh(from: Double, to: Double) = MetalMesh(MeshData(
            doubleArrayOf(from, 0.0, to, 0.0, to, 8.0, from, 8.0), DoubleArray(8),
            intArrayOf(0, 1, 2, 0, 2, 3), IntArray(4) { black.toArgb() }), black, nightInk = true)
        val surface = CgSurfaceFactory.create(8, 8)
        val picture = try {
            surface.fill(Rgba(255, 0, 0, 128))
            surface.renderer().fillRect(Rect(0.0, 4.0, 8.0, 4.0), black)
            ink.picture(surface, Rect(0.0, 0.0, 8.0, 8.0))
        } finally { surface.recycle() }
        val pixels = ink.renderOffscreen(8, 8, black, MetalViewport(0.0, 0.0, 1.0), listOf(mesh(0.0, 8.0), picture, mesh(4.0, 8.0)))
        assertEquals(Rgba(255, 127, 127), Rgba.fromArgb(pixels[2 * 8 + 1]))
        assertEquals(black, Rgba.fromArgb(pixels[6 * 8 + 1]))
        assertEquals(Rgba(255, 255, 255), Rgba.fromArgb(pixels[6 * 8 + 6]))
    }

    @Test fun pdfPixelsRotateClockwiseWithoutSwappingRedAndBlue() {
        val pixels = intArrayOf(0xffdd1100.toInt(), 0xff1122cc.toInt())
        for (turn in 0..3) {
            val image = CgSurfaceFactory.opaqueArgb(pixels, 2, 1, turn)
            try {
                assertEquals(if (turn % 2 == 0) 2 else 1, image.width)
                assertEquals(Rgba.fromArgb(pixels[if (turn < 2) 0 else 1]), image.pixel(0, 0))
            } finally { image.recycle() }
        }
    }

    @Test fun rotatedLabelTurnsAroundItsOwnCenter() {
        val surface = CgSurfaceFactory.create(100, 100)
        try {
            surface.fill(Rgba(255, 255, 255))
            surface.renderer().drawTextTurned("MMMM", Rect(8.0, 40.0, 84.0, 22.0), FontSpec(8.0),
                Rgba(0, 0, 0), TextFlags(), PI / 2)
            assertTrue((5..38).any { row -> (39..62).any { surface.pixel(it, row).r < 128 } })
            assertTrue((5..38).all { column -> (0..99).all { surface.pixel(column, it).r == 255 } })
        } finally { surface.recycle() }
    }

    @Test fun mapOutlinesIncludeWrappedFallbackGlyphs() {
        val font = FontSpec(12.0)
        val shapes = CgTextMeasurer.outline("A\n枝", font, 100.0)
        val below = shapes.filter { it.start.y > CgTextMeasurer.lineHeight(font) }
        assertTrue(below.any { it.closed && it.segments.size > 4 })
    }
}
