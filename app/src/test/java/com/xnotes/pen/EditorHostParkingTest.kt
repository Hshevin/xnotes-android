package com.xnotes.pen

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.tools.Tool
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.Editor
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

internal fun initializeParkingEglHandles() {
    fun <T> empty(type:Class<T>):T=ReflectionHelpers.callConstructor(type,ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE,0L))
    if(EGL14.EGL_NO_DISPLAY==null) EGL14.EGL_NO_DISPLAY=empty(EGLDisplay::class.java)
    if(EGL14.EGL_NO_CONTEXT==null) EGL14.EGL_NO_CONTEXT=empty(EGLContext::class.java)
    if(EGL14.EGL_NO_SURFACE==null) EGL14.EGL_NO_SURFACE=empty(EGLSurface::class.java)
}
internal fun runParkingScenario(block:suspend (EditorPageCopyFixture)->Unit) {
    initializeParkingEglHandles()
    val base=RuntimeEnvironment.getApplication();val root=File(base.cacheDir,"native-parking-${UUID.randomUUID()}").apply {mkdirs()}
    val f=EditorPageCopyFixture(base,root)
    val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true};val previous=singleton.get(null)
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var failure:Throwable?=null;var initialized=false
    val job=scope.launch {
        try {
            withContext(Dispatchers.Main) {singleton.set(null,f.storage);f.editor=Editor(f.context);initialized=true}
            withTimeout(20000L) {block(f)}
        } catch(error:Throwable) {failure=error}
        finally {withContext(NonCancellable) {
            f.release.countDown()
            if(initialized) {
                val panes=withContext(Dispatchers.Main) {listOfNotNull(f.editor,f.editor.secondary)}
                withContext(Dispatchers.Main) {
                    for(pane in panes) {
                        pane.controller.resetGestureState()
                        val auto=Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(pane) as CoroutineScope
                        auto.cancel();auto.coroutineContext[Job]?.join()
                    }
                    suspendCancellableCoroutine<Unit> {waiter->Handler(Looper.getMainLooper()).post {waiter.resumeWith(Result.success(Unit))}}
                }
                for(pane in panes) (Editor::class.java.getDeclaredField("sessionOwner").apply {isAccessible=true}.get(pane) as LegacySessionQueue.Owner).awaitAccepted()
                com.xnotes.ui.SplitRecentWriter.forFilesDir(f.context.filesDir).awaitAccepted()
            }
            singleton.set(null,previous);withContext(Dispatchers.IO) {f.storage.registry.close()}
        }}
    }
    try {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30L)
        while(!job.isCompleted && System.nanoTime()<deadline) {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle();Thread.yield()
        }
        assertTrue("Actual native host parking did not settle",job.isCompleted);failure?.let {throw it}
    } finally {f.release.countDown();scope.cancel();if(job.isCompleted) root.deleteRecursively()}
}
internal suspend fun openParkingPanes(f:EditorPageCopyFixture):Editor {
    withContext(Dispatchers.IO) {f.seed();f.seed(f.target)}
    return withContext(Dispatchers.Main) {
        f.editor.openAsync(f.sourceUri,"source.xnote")
        val other=f.editor.secondaryPane();other.openAsync(f.targetUri,"target.xnote")
        assertNotSame(f.editor.documentOwner,other.documentOwner)
        listOf(f.editor,other).forEach {it.view.layout(0,0,1000,1000);it.selectTool(Tool.PEN)}
        other
    }
}
private var parkingEventTime=1L
internal fun parkingPen(editor:Editor,action:Int,point:Pt) {
    val at=editor.state.contentToViewport(editor.state.fromPageSpace(0,point))
    val pointer=MotionEvent.PointerProperties().apply {id=0;toolType=MotionEvent.TOOL_TYPE_STYLUS}
    val coords=MotionEvent.PointerCoords().apply {x=at.x.toFloat();y=at.y.toFloat();pressure=.6f}
    val event=MotionEvent.obtain(1L,parkingEventTime++,action,1,arrayOf(pointer),arrayOf(coords),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
    try {assertTrue(editor.view.onTouchEvent(event))} finally {event.recycle()}
}
internal suspend fun parkingDispatch()=withContext(Dispatchers.Main) {
    suspendCancellableCoroutine<Unit> {waiter->Handler(Looper.getMainLooper()).post {waiter.resumeWith(Result.success(Unit))}}
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class EditorHostParkingTest {
    @Before fun noEgl() {initializeParkingEglHandles()}

    @Test fun bothIndependentInputPanesMustFinishBeforeDirtyModelsCanParkWithoutSaving()=runParkingScenario {f->
        val other=openParkingPanes(f);val original=f.source.readBytes();val second=f.target.readBytes()
        val models=withContext(Dispatchers.Main) {f.editor.state.document to other.state.document}
        coroutineScope {
            withContext(Dispatchers.Main) {
                parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));parkingPen(f.editor,MotionEvent.ACTION_MOVE,Pt(150.0,120.0))
            }
            val waiting=async(Dispatchers.Main) {f.editor.awaitHostParking()}
            parkingDispatch();assertFalse(waiting.isCompleted)
            withContext(Dispatchers.Main) {
                parkingPen(f.editor,MotionEvent.ACTION_UP,Pt(180.0,140.0))
                parkingPen(other,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));parkingPen(other,MotionEvent.ACTION_MOVE,Pt(140.0,130.0))
            }
            parkingDispatch();assertFalse("The independent second pane is still in contact",waiting.isCompleted)
            withContext(Dispatchers.Main) {parkingPen(other,MotionEvent.ACTION_UP,Pt(170.0,150.0))}
            val proof=withTimeout(5000L) {waiting.await()};assertNotNull(proof)
            withContext(Dispatchers.Main) {
                assertTrue(proof!!.current());assertSame(models.first,f.editor.state.document);assertSame(models.second,other.state.document)
                assertTrue(models.first.dirty);assertTrue(models.second.dirty)
                assertEquals(1,models.first.pages.first().items.filterIsInstance<Stroke>().size)
                assertEquals(1,models.second.pages.first().items.filterIsInstance<Stroke>().size)
                assertTrue(f.editor.history.canUndo);assertTrue(other.history.canUndo)
            }
        }
        assertArrayEquals(original,f.source.readBytes());assertArrayEquals(second,f.target.readBytes());assertEquals(0,f.writes.get())
    }

    @Test fun cancellingAParkingWaitDoesNotCancelTheActualPenTransaction()=runParkingScenario {f->
        openParkingPanes(f)
        coroutineScope {
            withContext(Dispatchers.Main) {parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));parkingPen(f.editor,MotionEvent.ACTION_MOVE,Pt(140.0,120.0))}
            val waiting=async(Dispatchers.Main) {f.editor.awaitHostParking()}
            parkingDispatch();assertFalse(waiting.isCompleted);waiting.cancelAndJoin()
            withContext(Dispatchers.Main) {
                assertTrue(f.editor.stylusInContact);assertTrue(f.editor.controller.hasPendingContentTransaction)
                parkingPen(f.editor,MotionEvent.ACTION_UP,Pt(180.0,140.0))
                assertEquals(1,f.editor.state.document.pages.first().items.filterIsInstance<Stroke>().size)
                assertTrue(f.editor.history.canUndo)
            }
        }
        assertEquals(0,f.writes.get())
    }

    @Test fun cancellingParkingDoesNotCancelAnAlreadyAcceptedNativeWrite()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed(f.target)}
        val entered=CompletableDeferred<Unit>();val done=CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {f.editor.openAsync(f.targetUri,"target.xnote");f.addStroke(110.0);f.stopDebounce()}
        f.beforeTargetWrite={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        coroutineScope {
            withContext(Dispatchers.Main) {f.editor.saveToThen(f.targetUri) {done.complete(it)}}
            entered.await();val waiting=async(Dispatchers.Main) {f.editor.awaitHostParking()}
            parkingDispatch();assertFalse(waiting.isCompleted);waiting.cancelAndJoin();f.release.countDown()
            assertTrue(done.await())
        }
        assertEquals(1,f.writes.get());assertEquals(1,f.target.inputStream().use {f.codec.read(it)}.pages.first().items.filterIsInstance<Stroke>().size)
    }

    @Test fun aParkingProofRejectsLateInputLayoutBodyModelAndAcceptedWriteChanges()=runParkingScenario {f->
        openParkingPanes(f)
        withContext(Dispatchers.Main) {
            val input=requireNotNull(f.editor.awaitHostParking());assertTrue(input.current())
            parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));assertFalse(input.current())
            parkingPen(f.editor,MotionEvent.ACTION_CANCEL,Pt(100.0,100.0))
            val layout=requireNotNull(f.editor.awaitHostParking())
            f.editor.view.layout(0,0,1200,1000);assertFalse(layout.current())
            val body=requireNotNull(f.editor.awaitHostParking());f.addStroke(220.0);assertFalse(body.current())
            val dirty=requireNotNull(f.editor.awaitHostParking());assertTrue(dirty.current())
            val done=CompletableDeferred<Boolean>();f.editor.saveToThen(f.sourceUri) {done.complete(it)}
            assertFalse("A newly accepted encoder invalidates the old proof",dirty.current());assertTrue(done.await());assertFalse(dirty.current())
            val model=requireNotNull(f.editor.awaitHostParking());val before=f.editor.state.document
            f.editor.newNote()
            withTimeout(5000L) {while(f.editor.state.document===before) {delay(1L)}}
            assertFalse(model.current())
        }
    }

    @Test fun parkingCommitsTheActualTextDraftWithoutForcingItsDirtySourceToSave()=runParkingScenario {f->
        openParkingPanes(f);val original=f.source.readBytes()
        withContext(Dispatchers.Main) {
            f.editor.selectTool(Tool.TEXT_BOX)
            parkingPen(f.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));parkingPen(f.editor,MotionEvent.ACTION_UP,Pt(100.0,100.0))
            f.editor.updateEditingText("unconfirmed native text")
            assertNotNull(f.editor.controller.activeTextItem())
            val proof=requireNotNull(f.editor.awaitHostParking());assertTrue(proof.current())
            assertEquals("unconfirmed native text",f.editor.state.document.pages.first().items.filterIsInstance<TextItem>().single().text)
            assertTrue(f.editor.state.document.dirty);assertTrue(f.editor.history.canUndo)
        }
        assertArrayEquals(original,f.source.readBytes());assertEquals(0,f.writes.get())
    }

    @Test fun returningToNativeUsesANewWetPadAndPreservesFrontBufferingAndDocumentHistory()=runParkingScenario {f->
        openParkingPanes(f)
        withContext(Dispatchers.Main) {
            f.addStroke(110.0,recordHistory=true);val model=f.editor.state.document;val history=f.editor.history
            f.editor.pad.frontBuffering=false
            assertNotNull(f.editor.awaitHostParking())
            val old=f.editor.pad;val oldFront=f.editor.controller.frontInk
            assertSame(f.editor.surfaces,f.editor.surfacesForAttach())
            assertNotSame(old,f.editor.pad);assertNotSame(oldFront,f.editor.controller.frontInk)
            assertFalse(f.editor.pad.frontBuffering);assertNull(old.onSurfaceLost)
            assertSame(model,f.editor.state.document);assertSame(history,f.editor.history);assertTrue(history.canUndo)
            assertEquals(1,model.pages.first().items.filterIsInstance<Stroke>().size)
        }
        assertEquals(0,f.writes.get()) // Source-level reattachment is not a GPU/device verification.
    }
}
