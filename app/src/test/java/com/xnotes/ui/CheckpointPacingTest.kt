package com.xnotes.ui

import org.junit.Assert.*
import org.junit.Test

/**
 * A layout or view change still checkpoints quickly, but a content change waits for the user to
 * pause so a dense document is not re-encoded after every stroke.
 */
class CheckpointPacingTest {
    @Test fun viewOnlyChangesKeepTheShortSettle() {
        assertEquals(180L,CheckpointPacing.VIEW_SETTLE_MS)
        assertEquals(CheckpointPacing.VIEW_SETTLE_MS,CheckpointPacing.settleMs(contentChanged=false))
    }
    @Test fun contentChangesWaitForAPauseLongerThanTheFileAutosaveDebounce() {
        assertEquals(1500L,CheckpointPacing.CONTENT_SETTLE_MS)
        assertEquals(CheckpointPacing.CONTENT_SETTLE_MS,CheckpointPacing.settleMs(contentChanged=true))
        // A bound document's file save (1200 ms debounce) normally starts first, so the checkpoint
        // then defers to it instead of encoding a second copy.
        assertTrue(CheckpointPacing.CONTENT_SETTLE_MS>1200L)
    }
}
