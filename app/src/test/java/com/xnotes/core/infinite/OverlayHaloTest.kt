package com.xnotes.core.infinite

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The infinite canvas mixes black night paper with imported white PDFs, so every overlay mark is
 * drawn over a wider copy of itself in the opposite tone. These check the edge comes first, is the
 * opposite tone, and reaches further than the mark it backs.
 */
class OverlayHaloTest {

    private val white = Rgba(255, 255, 255)
    /** Night mode's ink shader repaints exactly (0, 0, 0) white, so the dark edge is one step off it. */
    private val black = Rgba(1, 1, 1)
    private val tolerance = StrokeTessellator.DEFAULT_TOLERANCE

    private fun extentX(part: MeshPart): Double {
        val p = part.mesh.positions
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in p.indices step 2) { lo = minOf(lo, p[i]); hi = maxOf(hi, p[i]) }
        return hi - lo
    }

    @Test fun haloIsTheOppositeTone() {
        assertEquals(black, OverlayTessellator.haloFor(white))
        assertEquals(white, OverlayTessellator.haloFor(black))
        assertEquals(black, OverlayTessellator.coreFor(white))
        assertEquals(white, OverlayTessellator.coreFor(black))
    }

    @Test fun bandDrawsItsHaloFirstAndWider() {
        val parts = OverlayTessellator.band(Rect(0.0, 0.0, 100.0, 60.0), 1.0, white, tolerance)
        assertEquals(2, parts.size)
        assertEquals(black, parts[0].color)
        assertEquals(white, parts[1].color)
        assertTrue(extentX(parts[0]) > extentX(parts[1]))
    }

    @Test fun lassoRunsCarryTheirHalo() {
        val points = (0..40).map { Pt(it * 5.0, (it % 7) * 3.0) }
        val parts = OverlayTessellator.lasso(points, 2.0, white, tolerance)
        assertEquals(listOf(black, white), parts.map { it.color })
    }

    @Test fun selectionPairsEveryMarkWithAHalo() {
        val box = com.xnotes.core.geometry.Obb.fromAabb(Rect(0.0, 0.0, 80.0, 50.0))
        val parts = OverlayTessellator.selection(box, 1.0, white, tolerance, vertexDots = listOf(Pt(0.0, 0.0)))
        // Outline, handles and vertex dots each come as halo then mark; the dot core comes last.
        assertEquals(listOf(black, white, black, white, black, white, black), parts.map { it.color })
    }
}
