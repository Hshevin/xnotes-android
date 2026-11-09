package com.xnotes.pen

import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.mindmap.OriginalPdfLibraryState
import com.xnotes.presentOriginalPdfLibrary
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses the same Main handoff and two actual native input surfaces; never creates PDF pixels. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class OriginalPdfLibraryHostTest {
    @Test fun theActualLibraryHandoffWaitsForBothPensAndParksDirtyModelsWithoutSaving()=runParkingScenario {f->
        val other=openParkingPanes(f)
        val original=f.source.readBytes();val second=f.target.readBytes()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val library=withContext(Dispatchers.Main) {OriginalPdfLibraryState(f.context,scope)}
        try {
            coroutineScope {
                withContext(Dispatchers.Main) {
                    parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
                    parkingPen(f.editor,MotionEvent.ACTION_MOVE,Pt(160.0,120.0))
                }
                val showing=async(Dispatchers.Main) {presentOriginalPdfLibrary(f.editor,library) {true}}
                parkingDispatch();assertFalse(showing.isCompleted)
                withContext(Dispatchers.Main) {
                    assertFalse(library.visible)
                    parkingPen(f.editor,MotionEvent.ACTION_UP,Pt(180.0,140.0))
                    parkingPen(other,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
                    parkingPen(other,MotionEvent.ACTION_MOVE,Pt(140.0,120.0))
                }
                parkingDispatch();assertFalse(showing.isCompleted)
                withContext(Dispatchers.Main) {parkingPen(other,MotionEvent.ACTION_UP,Pt(180.0,140.0))}
                assertTrue(withTimeout(5000) {showing.await()})
                withContext(Dispatchers.Main) {
                    assertTrue(library.visible)
                    assertTrue(f.editor.dirty);assertTrue(other.dirty)
                    assertTrue(f.editor.history.canUndo);assertTrue(other.history.canUndo)
                    library.close()
                }
            }
            assertArrayEquals(original,f.source.readBytes());assertArrayEquals(second,f.target.readBytes())
            assertEquals(0,f.writes.get())
        } finally {
            withContext(Dispatchers.Main) {library.dispose()};scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
    @Test fun aRetiredLibraryLaunchDoesNotCancelOrPublishTheActualPenTransaction()=runParkingScenario {f->
        openParkingPanes(f)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val library=withContext(Dispatchers.Main) {OriginalPdfLibraryState(f.context,scope)}
        try {
            coroutineScope {
                var current=true
                withContext(Dispatchers.Main) {
                    parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
                    parkingPen(f.editor,MotionEvent.ACTION_MOVE,Pt(160.0,120.0))
                }
                val showing=async(Dispatchers.Main) {presentOriginalPdfLibrary(f.editor,library) {current}}
                parkingDispatch()
                withContext(Dispatchers.Main) {
                    current=false
                    assertTrue(f.editor.stylusInContact)
                    parkingPen(f.editor,MotionEvent.ACTION_UP,Pt(180.0,140.0))
                }
                assertFalse(withTimeout(5000) {showing.await()})
                withContext(Dispatchers.Main) {assertFalse(library.visible);assertTrue(f.editor.history.canUndo)}
                assertEquals(0,f.writes.get())
            }
        } finally {
            withContext(Dispatchers.Main) {library.dispose()};scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
