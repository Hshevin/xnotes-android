package com.xnotes.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The sources panel sits beside its node, never over the node or the node's action bar (device test2). */
class MapSourcesPanelPlacementTest {
    private val window = IntSize(2880, 1800)
    private val panel = IntSize(600, 260)
    private val gap = 18

    private fun box(at: IntOffset) = IntRect(at.x, at.y, at.x + panel.width, at.y + panel.height)

    private fun assertClear(at: IntOffset, node: IntRect, bar: IntRect) {
        val r = box(at)
        assertFalse("covers the node", r.overlaps(node))
        assertFalse("covers the bar", r.overlaps(bar))
        assert(r.left >= 0 && r.top >= 0 && r.right <= window.width && r.bottom <= window.height) { "outside the window: $r" }
    }

    @Test fun aNodeWithRoomOnItsRightGetsThePanelThere() {
        // Device test2: node "Src" at about (705, 890)-(790, 955), bar above it.
        val node = IntRect(705, 890, 790, 955)
        val bar = IntRect(455, 790, 1040, 860)
        val at = sourcesPanelPlacement(node, bar, panel, window, gap)
        assertEquals(node.right + gap, at.x)
        assertEquals(node.top, at.y)
        assertClear(at, node, bar)
    }

    @Test fun aNodeAtTheRightEdgeGetsThePanelOnItsLeft() {
        val node = IntRect(2600, 900, 2800, 960)
        val bar = IntRect(2300, 820, 2870, 880)
        val at = sourcesPanelPlacement(node, bar, panel, window, gap)
        assertEquals(node.left - gap - panel.width, at.x)
        assertClear(at, node, bar)
    }

    @Test fun aWideNodeFallsBackToBelowItAndItsBar() {
        val node = IntRect(100, 400, 2780, 460)
        val bar = IntRect(1100, 320, 1700, 380)
        val at = sourcesPanelPlacement(node, bar, panel, window, gap)
        assertEquals(node.bottom + gap, at.y)
        assertClear(at, node, bar)
    }

    @Test fun aWideNodeNearTheBottomGetsThePanelAboveItsBar() {
        val node = IntRect(100, 1600, 2780, 1660)
        val bar = IntRect(1100, 1520, 1700, 1580)
        val at = sourcesPanelPlacement(node, bar, panel, window, gap)
        assertEquals(bar.top - gap - panel.height, at.y)
        assertClear(at, node, bar)
    }

    @Test fun aNodeNearTheTopKeepsThePanelInsideTheWindow() {
        val node = IntRect(400, 10, 480, 60)
        val bar = IntRect(200, 80, 780, 140) // bar below: no room above the node
        val at = sourcesPanelPlacement(node, bar, panel, window, gap)
        assertClear(at, node, bar)
    }
}
