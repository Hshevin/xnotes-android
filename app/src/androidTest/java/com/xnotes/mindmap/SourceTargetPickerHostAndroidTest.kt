package com.xnotes.mindmap

import android.net.Uri
import com.xnotes.R
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual workspace's registered launcher, including replacement of its keyed body. */
class SourceTargetPickerHostAndroidTest {
    @Test fun anOutstandingPickerOccupiesTheStableHostAcrossMapEntriesAndDeliversOnlyToItsOriginalController()=SourceTargetAndroidFixture.run {f->
        f.openSources();f.ui.click(f.label(R.string.source_target_replace))
        f.host.await("first replace draft") {f.controller.state?.step==SourceTargetEditStep.CHOOSE_SOURCE}
        f.ui.click(f.label(R.string.source_target_choose_file))
        f.host.await("first actual OpenDocument launch") {f.registry.launches.size==1 && f.controller.pendingPicker!=null}
        val old=f.host.onMain {f.controller}
        val oldSlot=f.host.onMain {old.pendingPicker!!}
        val code=f.host.onMain {f.registry.launches.single()}
        f.open(1);f.openSources()
        val incoming=f.host.onMain {f.controller}
        assertNotSame(old,incoming)
        f.ui.awaitEnabled(f.label(R.string.source_target_replace),false)
        assertEquals(1,f.host.onMain {f.registry.launches.size})
        f.host.onMain {assertNull(incoming.state);assertSame(oldSlot,old.pendingPicker)
            f.registry.deliver(code,Uri.fromFile(f.file))}
        f.host.await("original slot consumed through its original registration") {old.pendingPicker==null && old.state==null}
        f.ui.awaitEnabled(f.label(R.string.source_target_replace),true)
        f.host.onMain {assertNull(old.state);assertNull(incoming.state)
            assertEquals(f.source.target,f.currentSource().target);assertFalse(f.session.canUndo)}
        f.ui.click(f.label(R.string.source_target_replace))
        f.ui.click(f.label(R.string.source_target_choose_file))
        f.host.await("new entry can launch only after the old result is consumed") {f.registry.launches.size==2 && incoming.pendingPicker!=null}
        val newRequest=f.host.onMain {incoming.state!!.id}
        f.host.onMain {f.registry.deliver(f.registry.launches.last(),Uri.fromFile(f.file))}
        f.awaitVisual()
        f.host.onMain {assertEquals(newRequest,incoming.state!!.id);assertEquals(SourceRangeScope.WHOLE_FILE,incoming.state!!.selectionScope)
            assertNull(incoming.state!!.region);assertNull(incoming.pendingPicker);assertEquals(f.source.target,f.currentSource().target)}
        f.ui.click(f.label(R.string.cancel));f.host.await("new target draft canceled") {incoming.state==null}
        f.assertSourceUnchanged()
    }
}
