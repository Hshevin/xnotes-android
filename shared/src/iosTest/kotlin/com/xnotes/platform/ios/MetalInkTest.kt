package com.xnotes.platform.ios

import com.xnotes.core.infinite.MeshData
import com.xnotes.core.model.Rgba
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W2-4 contract (docs/movinkpad/process/ipados-wave2-contracts-2026-11-24.md, "W2-4"): the Metal pipeline draws
 * core/infinite meshes. Rendered off screen so it runs on the simulator; the on-screen layer, wet ink and
 * latency are verified on the iPad.
 */
class MetalInkTest {

    private fun quad(l: Double, t: Double, r: Double, b: Double) = MeshData(
        doubleArrayOf(l, t, r, t, r, b, l, b),
        DoubleArray(8),
        intArrayOf(0, 1, 2, 0, 2, 3),
    )

    @Test
    fun aDeviceIsAvailable() {
        assertNotNull(MetalInk.createOrNull(), "Metal device missing on this simulator")
    }

    @Test
    fun meshesLandWhereTheViewportPutsThem() {
        val ink = assertNotNull(MetalInk.createOrNull())
        // Content 0..200 × 0..100 shown in a 100 × 50 target: scale 0.5, no offset.
        val px = ink.renderOffscreen(
            width = 100, height = 50, background = Rgba(255, 255, 255),
            viewport = MetalViewport(originX = 0.0, originY = 0.0, scale = 0.5),
            meshes = listOf(MetalMesh(quad(100.0, 0.0, 200.0, 100.0), Rgba(0, 0, 0))),
        )
        assertEquals(100 * 50, px.size)
        assertEquals(0xFF000000.toInt(), px[25 * 100 + 75], "right half is ink")
        assertEquals(0xFFFFFFFF.toInt(), px[25 * 100 + 25], "left half is paper")
    }

    @Test
    fun laterMeshesDrawOnTopAndAlphaBlends() {
        val ink = assertNotNull(MetalInk.createOrNull())
        val px = ink.renderOffscreen(
            width = 10, height = 10, background = Rgba(255, 255, 255),
            viewport = MetalViewport(0.0, 0.0, 1.0),
            meshes = listOf(
                MetalMesh(quad(0.0, 0.0, 10.0, 10.0), Rgba(0, 0, 0)),
                MetalMesh(quad(0.0, 0.0, 10.0, 10.0), Rgba(255, 255, 255, 128)),
            ),
        )
        val c = px[55]
        val g = (c shr 8) and 0xFF
        assertTrue(g in 124..132, "half-white over black should be mid grey, was ${g}")
    }

    @Test
    fun panningMovesContent() {
        val ink = assertNotNull(MetalInk.createOrNull())
        val px = ink.renderOffscreen(
            width = 20, height = 20, background = Rgba(255, 255, 255),
            viewport = MetalViewport(originX = 100.0, originY = 100.0, scale = 1.0),
            meshes = listOf(MetalMesh(quad(100.0, 100.0, 110.0, 110.0), Rgba(0, 0, 0))),
        )
        assertEquals(0xFF000000.toInt(), px[5 * 20 + 5])
        assertEquals(0xFFFFFFFF.toInt(), px[15 * 20 + 15])
    }
}
