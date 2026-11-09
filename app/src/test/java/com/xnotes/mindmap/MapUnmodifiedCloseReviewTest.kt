package com.xnotes.mindmap

import com.xnotes.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class MapUnmodifiedCloseReviewTest {
    @Test fun closingAnUntouchedGraphAfterExternalChangeDoesNotWriteOrReportConflict() {
        MapComposeReviewFixture(MindMapDocument()).use {f->
            var closed=false;f.screen {closed=true};assertFalse(f.session.dirty)
            val external=f.session.document!!.update(f.session.document!!.root.id) {it.copy(text="External change")}
            val bytes=MapCodec.encode(external);f.file.writeBytes(bytes)
            f.click(R.string.map_close);f.await {!f.session.saving}
            assertTrue("Untouched graph must close without trying to save its obsolete snapshot",closed)
            assertFalse(f.session.conflict);assertFalse(f.session.saveFailed);assertFalse(f.session.dirty)
            assertArrayEquals(bytes,f.file.readBytes())
        }
    }
}
