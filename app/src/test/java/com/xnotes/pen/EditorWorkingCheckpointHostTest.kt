package com.xnotes.pen

import android.view.MotionEvent
import com.xnotes.canvas.InitialView
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.Tool
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.ui.Editor
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual private cache receipts; no original-file save, rendered GPU or Activity recreation. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class EditorWorkingCheckpointHostTest {
    private fun field(editor:Editor,name:String)=Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(editor)
    private fun slot(editor:Editor)=field(editor,"sessionDir") as File
    private fun owner(editor:Editor)=field(editor,"sessionOwner") as LegacySessionQueue.Owner
    private suspend fun open(f:EditorPageCopyFixture) {
        withContext(Dispatchers.IO) {f.seed()}
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote")
            assertTrue(f.editor.noteOpen);f.editor.view.layout(0,0,1000,800)
            f.editor.selectTool(Tool.PEN);f.stopDebounce()
        }
    }

    @Test fun anExplicitOverrideEqualToTheDefaultStillChangesThePersistedView()=runParkingScenario {f->
        open(f)
        withContext(Dispatchers.Main) {
            val old=requireNotNull(f.editor.checkpointNoteWorkingSession {true})
            val resolved=f.editor.viewSettings
            val version=f.editor.presentationVersion
            val explicit=f.editor.viewOverrides.copy(contrast=resolved.contrast)
            assertNotEquals(f.editor.viewOverrides,explicit)
            f.editor.updateViewOverrides(explicit)
            assertEquals("Rendering can remain identical while the persistence contract changes",resolved,f.editor.viewSettings)
            assertTrue(f.editor.presentationVersion>version)
            assertFalse(old.current())
            val next=requireNotNull(f.editor.checkpointNoteWorkingSession {true})
            assertEquals(explicit,next.noteView!!.position.overrides)
        }
        assertEquals(0,f.writes.get())
    }

    @Test fun togglingZoomLockInvalidatesTheCheckpointThatCapturedThePreviousLock()=runParkingScenario {f->
        open(f)
        withContext(Dispatchers.Main) {
            val checkpoint=requireNotNull(f.editor.checkpointNoteWorkingSession {true})
            assertTrue(checkpoint.current())
            f.editor.toggleZoomLock()
            assertFalse("A receipt must not certify metadata from before the zoom-lock change",checkpoint.current())
        }
        assertEquals(0,f.writes.get())
    }

    @Test fun actualEditedBodyIsDurableAndViewOnlyCheckpointReusesItsBody()=runParkingScenario {f->
        open(f)
        val original=f.source.readBytes()
        val first=withContext(Dispatchers.Main) {
            f.addStroke(120.0,recordHistory=true);f.stopDebounce()
            requireNotNull(f.editor.checkpointNoteWorkingSession {true})
        }
        val body=File(slot(f.editor),"document.xnote")
        val bytes=body.readBytes()
        val inode=Files.readAttributes(body.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS).fileKey()
        val loaded=body.inputStream().use {f.codec.read(it)}
        assertEquals(1,loaded.pages.first().items.filterIsInstance<Stroke>().size)
        assertEquals(first.hint.documentId,loaded.identity.documentId)
        withContext(Dispatchers.Main) {
            assertTrue(first.current())
            f.editor.state.pendingInitialView=InitialView.Restore(1.25,17.0,27.0,true)
            val second=requireNotNull(f.editor.checkpointNoteWorkingSession {true})
            assertTrue(second.current());assertTrue(second.noteView!!.fitWidth)
            assertEquals(17.0,second.noteView!!.position.x,0.0)
            assertEquals(first.hint,second.hint)
        }
        assertArrayEquals(bytes,body.readBytes())
        assertEquals("A view-only checkpoint must reuse the actual body",inode,
            Files.readAttributes(body.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS).fileKey())
        assertEquals(0,f.writes.get());assertArrayEquals(original,f.source.readBytes())
    }

    @Test fun laterQueueCommandAndAnUnknownNativeHeadCannotReuseAnOldSuccess()=runParkingScenario {f->
        open(f)
        val first=withContext(Dispatchers.Main) {requireNotNull(f.editor.checkpointNoteWorkingSession {true})}
        requireNotNull(owner(f.editor).submit {Unit}).await()
        withContext(Dispatchers.Main) {assertFalse(first.current())}
        val second=withContext(Dispatchers.Main) {requireNotNull(f.editor.checkpointNoteWorkingSession {true})}
        val head=File(slot(f.editor),"head.json").apply {writeText("{\"future\":true}")}
        val bytes=head.readBytes()
        withContext(Dispatchers.Main) {
            assertFalse(second.current())
            assertNull(f.editor.checkpointNoteWorkingSession {true})
        }
        assertArrayEquals(bytes,head.readBytes());assertEquals(0,f.writes.get())
    }

    @Test fun aFailedPrivateMetadataPublicationDoesNotBecomeSuccessWhenTheJobSettles()=runParkingScenario {f->
        open(f)
        val metadata=File(slot(f.editor),"session.json")
        metadata.delete();assertTrue(metadata.mkdirs())
        try {
            withContext(Dispatchers.Main) {
                f.addStroke(160.0,recordHistory=true);f.stopDebounce()
                assertNull(f.editor.checkpointNoteWorkingSession {true})
            }
            owner(f.editor).awaitAccepted()
            assertTrue(metadata.isDirectory);assertEquals(0,f.writes.get())
        } finally {metadata.delete()}
    }

    @Test fun cancellingAWaitingCheckpointDoesNotCommitOrCancelTheLiveStroke()=runParkingScenario {f->
        open(f)
        withContext(Dispatchers.Main) {parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))}
        coroutineScope {
            val waiting=async(Dispatchers.Main) {f.editor.checkpointNoteWorkingSession {true}}
            parkingDispatch()
            assertFalse(waiting.isCompleted)
            waiting.cancelAndJoin()
            withContext(Dispatchers.Main) {
                assertFalse(f.editor.history.canUndo)
                assertTrue(f.editor.state.document.pages.first().items.none {it is Stroke})
                parkingPen(f.editor,MotionEvent.ACTION_MOVE,Pt(120.0,130.0))
                parkingPen(f.editor,MotionEvent.ACTION_UP,Pt(160.0,150.0))
                assertTrue(f.editor.history.canUndo)
                assertEquals(1,f.editor.state.document.pages.first().items.filterIsInstance<Stroke>().size)
            }
        }
        assertEquals(0,f.writes.get())
    }
}
