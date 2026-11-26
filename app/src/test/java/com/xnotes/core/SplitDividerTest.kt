package com.xnotes.core

import com.xnotes.core.util.SplitDividerDrag
import org.junit.Assert.*
import org.junit.Test

class SplitDividerTest {
    @Test fun snapsNearCenterAndRequiresMovingFurtherToLeave() {
        val drag = SplitDividerDrag(0.3f)
        assertEquals(0.5f, drag.move(0.18f), 0.0001f)
        assertEquals(0.5f, drag.move(0.06f), 0.0001f)
        assertEquals(0.58f, drag.move(0.04f), 0.0001f)
    }
    @Test fun snapsFromEitherSideAndClampsBothEdges() {
        val drag = SplitDividerDrag(0.7f)
        assertEquals(0.5f, drag.move(-0.18f), 0.0001f)
        assertEquals(0.18f, drag.move(-1f), 0.0001f)
        assertEquals(0.82f, drag.move(2f), 0.0001f)
    }
}
