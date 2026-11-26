package com.xnotes.core

import com.xnotes.core.util.isOppositeSplitPane
import org.junit.Assert.*
import org.junit.Test

class SplitDropTargetTest {
    @Test fun onlyOtherPaneAcceptsDropAtUnequalRatio() {
        assertFalse(isOppositeSplitPane(true, 200f, 40f, 1000f, 600f, .3f))
        assertTrue(isOppositeSplitPane(true, 300f, 40f, 1000f, 600f, .3f))
        assertTrue(isOppositeSplitPane(false, 299f, 40f, 1000f, 600f, .3f))
        assertFalse(isOppositeSplitPane(false, 300f, 40f, 1000f, 600f, .3f))
    }
    @Test fun outsideWindowAndInvalidCoordinatesCancel() {
        for ((axis, cross) in listOf(1000f to 10f, -1f to 10f, 500f to -1f,
            500f to 600f, Float.NaN to 10f, 500f to Float.NaN)) {
            assertFalse(isOppositeSplitPane(true, axis, cross, 1000f, 600f, .3f))
        }
    }
}
