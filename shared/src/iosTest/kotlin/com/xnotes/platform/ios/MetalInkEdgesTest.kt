package com.xnotes.platform.ios

import com.xnotes.core.infinite.MeshData
import com.xnotes.core.model.Rgba
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MetalInkEdgesTest {
    @Test
    fun narrowRibbonWidensAndFadesEvenFarFromWorldOrigin() {
        val origin = 100_000_000.0
        val mesh = MeshData(
            doubleArrayOf(origin, 5.4, origin + 16, 5.4, origin + 16, 5.6, origin, 5.6),
            doubleArrayOf(0.0, -0.1, 0.0, -0.1, 0.0, 0.1, 0.0, 0.1),
            intArrayOf(0, 1, 2, 0, 2, 3),
        )
        val pixels = assertNotNull(MetalInk.createOrNull()).renderOffscreen(16, 12, Rgba(0, 0, 0, 0),
            MetalViewport(origin, 0.0, 1.0), listOf(MetalMesh(mesh, Rgba(255, 0, 0))))
        for (column in 1..14) {
            val color = Rgba.fromArgb(pixels[5 * 16 + column])
            assertEquals(255, color.r)
            assertTrue(color.a in 49..53, "column $column: $color")
            assertEquals(0, pixels[4 * 16 + column])
        }
    }

    @Test
    fun vertexColorsOverrideTheMeshColorAndReadbackIsStraight() {
        val tint = Rgba(255, 0, 0, 128)
        val mesh = MeshData(doubleArrayOf(0.0, 0.0, 16.0, 0.0, 0.0, 16.0), DoubleArray(6), intArrayOf(0, 1, 2),
            IntArray(3) { tint.toArgb() })
        val pixels = assertNotNull(MetalInk.createOrNull()).renderOffscreen(16, 16, Rgba(0, 0, 0, 0),
            MetalViewport(0.0, 0.0, 1.0), listOf(MetalMesh(mesh, Rgba(0, 0, 255))))
        assertEquals(tint.toArgb(), pixels[2 * 16 + 2])
        assertEquals(0, pixels[14 * 16 + 14])
    }
}
