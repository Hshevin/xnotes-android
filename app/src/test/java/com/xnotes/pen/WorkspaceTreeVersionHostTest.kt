package com.xnotes.pen

import com.xnotes.ui.Editor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T3-1: a file created, renamed or deleted from one pane's home must relist the other pane's
 * home too. Both explorers key their listings on their own editor's tree version, so the
 * notification has to reach every pane of the workspace, whichever pane raised it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class WorkspaceTreeVersionHostTest {
    @Before fun noEgl() {initializeParkingEglHandles()}

    @Test fun aTreeChangeRaisedInEitherPaneBumpsBothPanes()=runParkingScenario {f->
        withContext(Dispatchers.Main) {
            val primary=f.editor;val secondary=primary.secondaryPane()
            val before=primary.treeVersion to secondary.treeVersion

            secondary.notifyTreeChanged() // the explorer in the second pane made a file
            assertEquals(before.first+1,primary.treeVersion)
            assertEquals(before.second+1,secondary.treeVersion)

            primary.refreshStoredFile(null) // an independent editor handed a file back
            assertEquals(before.first+2,primary.treeVersion)
            assertEquals(before.second+2,secondary.treeVersion)
        }
    }

    @Test fun aSinglePaneWorkspaceStillBumpsItself()=runParkingScenario {f->
        withContext(Dispatchers.Main) {
            val primary=f.editor;val before=primary.treeVersion
            primary.notifyTreeChanged()
            assertEquals(before+1,primary.treeVersion)
        }
    }

    /** The recent-arrangement flag is a plain read now; no per-editor collector may outlive a released pane. */
    @Test fun recentArrangementFlagReadsTheSharedWriterWithoutACollector()=runParkingScenario {f->
        withContext(Dispatchers.Main) {
            val primary=f.editor
            val auto=Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(primary) as kotlinx.coroutines.CoroutineScope
            val active=auto.coroutineContext[kotlinx.coroutines.Job]!!.children.filter {it.isActive}.toList()
            assertFalse(primary.hasSavedSplit) // nothing written in this fresh files dir
            assertEquals("a fresh editor must not hold long-lived collectors on its autosave scope",emptyList<kotlinx.coroutines.Job>(),active)
        }
    }
}
