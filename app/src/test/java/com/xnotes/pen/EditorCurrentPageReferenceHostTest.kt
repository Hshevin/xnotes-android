package com.xnotes.pen

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.Looper
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.mindmap.SourceScope
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.Editor
import com.xnotes.ui.PageReferenceEnableResult
import com.xnotes.ui.PageReferencePreparation
import java.io.File
import java.time.Duration
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

/** Real Editor/native publication and immutable actual-source reads, without a rendered GPU surface. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class EditorCurrentPageReferenceHostTest {
    @Before fun noEglHandles() {
        fun <T> empty(type:Class<T>):T=ReflectionHelpers.callConstructor(type,ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE,0L))
        if(EGL14.EGL_NO_DISPLAY==null) EGL14.EGL_NO_DISPLAY=empty(EGLDisplay::class.java)
        if(EGL14.EGL_NO_CONTEXT==null) EGL14.EGL_NO_CONTEXT=empty(EGLContext::class.java)
        if(EGL14.EGL_NO_SURFACE==null) EGL14.EGL_NO_SURFACE=empty(EGLSurface::class.java)
    }
    private fun scenario(block:suspend (EditorPageCopyFixture)->Unit) {
        val base=RuntimeEnvironment.getApplication()
        val root=File(base.cacheDir,"editor-page-reference-${UUID.randomUUID()}").apply {mkdirs()}
        val f=EditorPageCopyFixture(base,root)
        val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        val previous=singleton.get(null);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        var failure:Throwable?=null;var initialized=false
        val job=scope.launch {
            try {
                withContext(Dispatchers.Main) {singleton.set(null,f.storage);f.editor=Editor(f.context);initialized=true}
                withTimeout(15000L) {block(f)}
            } catch(error:Throwable) {failure=error}
            finally {withContext(NonCancellable) {
                f.release.countDown()
                if(initialized) {
                    val panes=withContext(Dispatchers.Main) {listOfNotNull(f.editor,f.editor.secondary)}
                    withContext(Dispatchers.Main) {
                        for(pane in panes) {
                            val auto=Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(pane) as CoroutineScope
                            auto.cancel();auto.coroutineContext[Job]?.join()
                        }
                        suspendCancellableCoroutine<Unit> {waiter->Handler(Looper.getMainLooper()).post {waiter.resumeWith(Result.success(Unit))}}
                    }
                    for(pane in panes) (Editor::class.java.getDeclaredField("sessionOwner").apply {isAccessible=true}.get(pane) as LegacySessionQueue.Owner).awaitAccepted()
                }
                singleton.set(null,previous);withContext(Dispatchers.IO) {f.storage.registry.close()}
            }}
        }
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25L)
            while(!job.isCompleted && System.nanoTime()<deadline) {shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));Thread.yield()}
            assertTrue("Real Editor page reference did not settle",job.isCompleted);failure?.let {throw it}
        } finally {f.release.countDown();scope.cancel();root.deleteRecursively()}
    }
    private suspend fun open(f:EditorPageCopyFixture,legacy:Boolean=false) {
        withContext(Dispatchers.IO) {
            if(legacy) f.target.outputStream().use {f.codec.write(Document(mutableListOf(Page(500.0,500.0),Page(500.0,500.0))),it)}
            else f.seed(f.target)
        }
        withContext(Dispatchers.Main) {f.editor.openAsync(f.targetUri,"target.xnote");assertTrue(f.editor.noteOpen);f.stopDebounce()}
    }
    private fun ready(value:PageReferencePreparation):com.xnotes.mindmap.MapSource {
        assertTrue("Expected a verified Ready source, got $value",value is PageReferencePreparation.Ready)
        return (value as PageReferencePreparation.Ready).source
    }
    /** Exercise the real close/pause producers after the reference dialog itself has gone away. */
    private suspend fun flushAfterReferenceDismissal(f:EditorPageCopyFixture) {
        val auto=withContext(Dispatchers.Main) {
            f.editor.pageReferenceRequest?.let {f.editor.clearPageReference(it)}
            // Bind the fixture like a granted folder note so flushAutosave/persist really enqueue
            // native writes; the provider has deliberately supplied no reliable known stamp.
            Editor::class.java.getDeclaredField("ownAutosaveUri").apply {isAccessible=true}.set(f.editor,f.targetUri)
            f.editor.flushAutosave();f.editor.persist()
            Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(f.editor) as CoroutineScope
        }
        repeat(3) {
            withContext(Dispatchers.Main) {suspendCancellableCoroutine<Unit> {waiter->
                Handler(Looper.getMainLooper()).post {waiter.resumeWith(Result.success(Unit))}
            }}
            auto.coroutineContext[Job]!!.children.toList().forEach {it.join()}
        }
        withContext(Dispatchers.Main) {
            assertFalse("The later ordinary close flush must preserve the source conflict",f.editor.flushSplitDocument())
            assertTrue(f.editor.state.document.dirty)
        }
    }

    @Test fun capturedActualPageRetainsItsSourceIdAndDoesNotUseTheOwnersViewportOrdinal()=scenario {f->
        open(f)
        withContext(Dispatchers.Main) {
            val owner=f.editor;val borrower=owner.secondaryPane()
            assertTrue(borrower.openSharedView(owner))
            val page=borrower.state.document.pages[1]
            assertTrue(borrower.requestPageReference(page))
            val request=requireNotNull(borrower.pageReferenceRequest)
            val first=ready(borrower.preparePageReference(request))
            val again=ready(borrower.preparePageReference(request))
            assertEquals(SourceScope.PAGE,first.scope);assertEquals(page.referenceIdentity.id,first.pageId)
            assertEquals(2,first.lastKnownPage);assertEquals(first.id,again.id)
            assertEquals(BundleMigration.hash(f.target),first.sourceVersion)
            assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
            borrower.clearPageReference(request);assertNull(borrower.pageReferenceRequest)
        }
    }

    @Test fun ordinarySavePublishesTheActualBVersionWithoutRebindingTheOldEditSource()=scenario {f->
        open(f)
        val hashA=BundleMigration.hash(f.target)
        withContext(Dispatchers.Main) {
            val model=f.editor.state.document;val page=model.pages.first();val pageId=page.referenceIdentity.id
            f.addStroke(110.0,recordHistory=true);f.stopDebounce()
            assertTrue(f.editor.requestPageReference(page));val request=requireNotNull(f.editor.pageReferenceRequest)
            val source=ready(f.editor.preparePageReference(request))
            assertSame(model,f.editor.state.document);assertSame(page,model.pages.first())
            assertEquals(pageId,source.pageId);assertFalse(model.dirty)
            assertEquals(hashA,f.editor.lastReadSourceVersion()?.sha256)
            assertNotEquals(hashA,source.sourceVersion);assertEquals(BundleMigration.hash(f.target),source.sourceVersion)
            assertEquals(1,f.writes.get());assertTrue(f.editor.history.canUndo)
        }
    }

    @Test fun aCleanExternallyChangedSameUuidAndPageCannotBecomeTheDisplayedBaseline()=scenario {f->
        open(f)
        val request=withContext(Dispatchers.Main) {f.editor.requestPageReference(f.editor.state.document.pages.first());requireNotNull(f.editor.pageReferenceRequest)}
        withContext(Dispatchers.IO) {
            val external=f.target.inputStream().use {f.codec.read(it)}
            external.pages.first().items+=com.xnotes.core.model.Stroke(com.xnotes.core.tools.Tool.PEN,
                com.xnotes.core.tools.ToolDefaults.configFor(com.xnotes.core.tools.Tool.PEN),
                listOf(com.xnotes.core.stroke.Sample(12.0,13.0,.5),com.xnotes.core.stroke.Sample(20.0,30.0,.75)))
            f.target.outputStream().use {f.codec.write(external.fixedWriteSnapshot(),it)}
        }
        val changed=f.target.readBytes()
        withContext(Dispatchers.Main) {
            assertSame(PageReferencePreparation.ReopenRequired,f.editor.preparePageReference(request))
            assertTrue(f.editor.state.document.pages.first().items.isEmpty());assertFalse(f.editor.dirty)
        }
        assertArrayEquals(changed,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
    }

    @Test fun legacyActivationNeverAssignsCatalogOrdinalsToTheOldModelAndRequiresActualReopen()=scenario {f->
        open(f,legacy=true)
        val old=withContext(Dispatchers.Main) {f.editor.state.document}
        val original=f.target.readBytes()
        withContext(Dispatchers.Main) {
            f.editor.requestPageReference(old.pages[1]);val request=requireNotNull(f.editor.pageReferenceRequest)
            val offer=(f.editor.preparePageReference(request) as PageReferencePreparation.EnableRequired).offer
            assertSame(PageReferenceEnableResult.Enabled,f.editor.enablePageReference(request,offer))
            assertTrue(old.pages.all {it.referenceIdentity.id==null});assertFalse(old.hasReferenceAnchors)
            assertSame(PageReferencePreparation.ReopenRequired,f.editor.preparePageReference(request))
            assertTrue(f.editor.reopenPageReference(request));assertFalse(f.editor.pageReferenceCurrent(request))
            val actual=f.editor.state.document;assertNotSame(old,actual)
            f.editor.requestPageReference(actual.pages[1]);val current=requireNotNull(f.editor.pageReferenceRequest)
            val source=ready(f.editor.preparePageReference(current))
            assertEquals(actual.pages[1].referenceIdentity.id,source.pageId);assertEquals(2,source.lastKnownPage)
        }
        assertFalse(original.contentEquals(f.target.readBytes()));assertEquals(1,f.writes.get());assertFalse(f.storage.hasRecovery(f.targetUri))
    }

    @Test fun retainedActivationContinuesTheExactCandidateWithoutMintingOrWritingAgain()=scenario {f->
        open(f,legacy=true)
        val request=withContext(Dispatchers.Main) {f.editor.requestPageReference(f.editor.state.document.pages.first());requireNotNull(f.editor.pageReferenceRequest)}
        val offer=withContext(Dispatchers.Main) {(f.editor.preparePageReference(request) as PageReferencePreparation.EnableRequired).offer}
        // enable first re-observes A and registers that observation. Inject only at the actual
        // target write, after A validation/staging, so this is a genuine post-write failure.
        f.beforeTargetWrite={
            f.beforeTargetWrite=null
            f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_after_write BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT,'activation registration failed'); END")
        }
        withContext(Dispatchers.Main) {
            val result=f.editor.enablePageReference(request,offer)
            assertSame("Expected retained activation after actual write; writes=${f.writes.get()}, pending=${f.storage.hasRecovery(f.targetUri)}",
                PageReferenceEnableResult.Retained,result)
        }
        assertTrue(f.storage.hasRecovery(f.targetUri));val fixed=f.target.readBytes()
        val ids=BundleReferenceInfo.inspectStrict(f.target).orderedPageIds
        withContext(Dispatchers.IO) {f.storage.registry.writableDatabase.execSQL("DROP TRIGGER fail_after_write")}
        withContext(Dispatchers.Main) {
            assertSame(PageReferenceEnableResult.Enabled,f.editor.continuePageReferenceEnable(request))
            assertTrue(f.editor.state.document.pages.all {it.referenceIdentity.id==null})
            assertTrue(f.editor.reopenPageReference(request))
        }
        assertArrayEquals(fixed,f.target.readBytes());assertEquals(ids,BundleReferenceInfo.inspectStrict(f.target).orderedPageIds)
        assertEquals(1,f.writes.get());assertFalse(f.storage.hasRecovery(f.targetUri))
    }

    @Test fun aNewEditDuringActualReadInvalidatesTheResultAndAReplacementRetiresTheRequest()=scenario {f->
        open(f)
        val request=withContext(Dispatchers.Main) {f.editor.requestPageReference(f.editor.state.document.pages.first());requireNotNull(f.editor.pageReferenceRequest)}
        val entered=CompletableDeferred<Unit>()
        f.beforeTargetRead={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        coroutineScope {
            val preparing=async(Dispatchers.Main) {f.editor.preparePageReference(request)}
            entered.await()
            withContext(Dispatchers.Main) {f.addStroke(990.0);f.stopDebounce()}
            f.release.countDown();assertSame(PageReferencePreparation.Changed,preparing.await())
        }
        f.beforeTargetRead=null
        withContext(Dispatchers.IO) {f.source.writeBytes(byteArrayOf())}
        withContext(Dispatchers.Main) {
            val done=CompletableDeferred<Boolean>();f.editor.saveAsToThen(f.sourceUri,"copy.xnote") {done.complete(it)}
            assertTrue(done.await());f.stopDebounce()
            assertFalse(f.editor.pageReferenceCurrent(request));assertSame(PageReferencePreparation.Changed,f.editor.preparePageReference(request))
            f.editor.requestPageReference(f.editor.state.document.pages.first());val newer=requireNotNull(f.editor.pageReferenceRequest)
            f.editor.clearPageReference(request);assertSame(newer,f.editor.pageReferenceRequest)
            val copied=ready(f.editor.preparePageReference(newer));assertEquals(BundleMigration.hash(f.source),copied.sourceVersion)
        }
    }

    @Test fun aDirtyDisplayedModelCannotOverwriteAnExternalSameIdentityVersionWhenStampsAreUnknown()=scenario {f->
        open(f)
        val request=withContext(Dispatchers.Main) {
            f.addStroke(110.0,recordHistory=true);f.stopDebounce()
            @Suppress("UNCHECKED_CAST")
            val stamps=Editor::class.java.getDeclaredField("knownStamps").apply {isAccessible=true}.get(f.editor) as MutableMap<String,Any>
            stamps.clear() // SAF may supply neither useful mtime nor size; no weak stamp can protect C.
            f.editor.requestPageReference(f.editor.state.document.pages.first());requireNotNull(f.editor.pageReferenceRequest)
        }
        withContext(Dispatchers.IO) {
            val external=f.target.inputStream().use {f.codec.read(it)}
            external.pages[1].items+=Stroke(com.xnotes.core.tools.Tool.PEN,
                com.xnotes.core.tools.ToolDefaults.configFor(com.xnotes.core.tools.Tool.PEN),
                listOf(com.xnotes.core.stroke.Sample(700.0,13.0,.5),com.xnotes.core.stroke.Sample(720.0,30.0,.75)))
            f.target.outputStream().use {f.codec.write(external.fixedWriteSnapshot(),it)}
        }
        val external=f.target.readBytes()
        val result=withContext(Dispatchers.Main) {f.editor.preparePageReference(request)}
        assertArrayEquals("Reference preparation must not overwrite external C before checking its known base",external,f.target.readBytes())
        assertEquals("The expected-version check must run before any target write",0,f.writes.get())
        assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
        assertSame(PageReferencePreparation.ReopenRequired,result)
        withContext(Dispatchers.Main) {
            assertTrue(f.editor.state.document.dirty)
            assertEquals(110.0,f.editor.state.document.pages.first().items.filterIsInstance<Stroke>().single().samples.first().x,.001)
            assertFalse("Reference reopen must not generic-save the rejected dirty model",f.editor.reopenPageReference(request))
        }
        flushAfterReferenceDismissal(f)
        assertArrayEquals(external,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
    }

    @Test fun aDirtyLegacySessionWithAPathHasNoAuthorityToForceSaveForAReference()=scenario {f->
        withContext(Dispatchers.IO) {
            f.seed(f.target)
            val recovered=f.target.inputStream().use {f.codec.read(it)}
            recovered.path=f.targetUri;recovered.displayName="target.xnote"
            recovered.pages.first().items+=Stroke(com.xnotes.core.tools.Tool.PEN,
                com.xnotes.core.tools.ToolDefaults.configFor(com.xnotes.core.tools.Tool.PEN),
                listOf(com.xnotes.core.stroke.Sample(110.0,13.0,.5),com.xnotes.core.stroke.Sample(130.0,30.0,.75)))
            recovered.dirty=true
            val files=f.context.filesDir
            val session=com.xnotes.platform.SessionStore(File(files,"session"),f.codec,File(files,"pdfsrc"),File(files,"noteimg"))
            assertEquals(com.xnotes.platform.LegacySessionWrite.SAVED,session.saveChecked(recovered.fixedWriteSnapshot(),
                com.xnotes.platform.SessionStore.Metadata(f.targetUri,"target.xnote",true,1.0,0.0,0.0,false,com.xnotes.canvas.ViewOverrides())))
        }
        val bytes=f.target.readBytes()
        withContext(Dispatchers.Main) {
            f.editor.restoreSession();f.stopDebounce()
            assertTrue(f.editor.noteOpen);assertEquals(f.targetUri,f.editor.currentUri);assertTrue(f.editor.state.document.dirty)
            assertNull(f.editor.lastReadSourceVersion())
            assertTrue(f.editor.requestPageReference(f.editor.state.document.pages.first()))
            val request=requireNotNull(f.editor.pageReferenceRequest)
            assertSame(PageReferencePreparation.ReopenRequired,f.editor.preparePageReference(request))
            assertFalse("A cached path must not become save authority through reference reopen",f.editor.reopenPageReference(request))
            assertTrue(f.editor.state.document.dirty)
        }
        flushAfterReferenceDismissal(f)
        assertArrayEquals(bytes,f.target.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
    }

    @Test fun anOrdinaryPublishedBWithConcurrentC1CanSafelyFlushC1AgainstBForTheReference()=scenario {f->
        open(f)
        val hashA=BundleMigration.hash(f.target);val entered=CompletableDeferred<Unit>();val done=CompletableDeferred<Boolean>()
        val page=withContext(Dispatchers.Main) {f.addStroke(110.0,recordHistory=true);f.stopDebounce();f.editor.state.document.pages.first()}
        f.beforeTargetWrite={f.beforeTargetWrite=null;entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        try {
            withContext(Dispatchers.Main) {f.editor.saveToThen(f.targetUri) {ok->f.stopDebounce();done.complete(ok)}}
            entered.await()
            withContext(Dispatchers.Main) {f.addStroke(990.0,recordHistory=true);f.stopDebounce()}
        } finally {f.release.countDown()}
        assertTrue(done.await());val hashB=BundleMigration.hash(f.target)
        assertNotEquals(hashA,hashB)
        val savedB=f.target.inputStream().use {f.codec.read(it)}
        assertEquals(listOf(110.0),savedB.pages.first().items.filterIsInstance<Stroke>().map {it.samples.first().x})
        withContext(Dispatchers.Main) {
            assertSame(page,f.editor.state.document.pages.first());assertTrue(f.editor.state.document.dirty)
            assertTrue(f.editor.requestPageReference(page));val request=requireNotNull(f.editor.pageReferenceRequest)
            val source=ready(f.editor.preparePageReference(request))
            assertSame(page,f.editor.state.document.pages.first());assertFalse(f.editor.state.document.dirty)
            assertEquals(BundleMigration.hash(f.target),source.sourceVersion);assertNotEquals(hashB,source.sourceVersion)
            assertEquals(page.referenceIdentity.id,source.pageId);assertTrue(f.editor.history.canUndo)
        }
        val savedC1=f.target.inputStream().use {f.codec.read(it)}
        assertEquals(listOf(110.0,990.0),savedC1.pages.first().items.filterIsInstance<Stroke>().map {it.samples.first().x})
        assertEquals(2,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun cancellingTheSourceDialogDuringBStillAutosavesLaterPenC1WithoutAnotherAction()=scenario {f->
        open(f)
        val entered=CompletableDeferred<Unit>();val c1Written=CompletableDeferred<Unit>()
        val request=withContext(Dispatchers.Main) {
            Editor::class.java.getDeclaredField("ownAutosaveUri").apply {isAccessible=true}.set(f.editor,f.targetUri)
            f.editor.view.layout(0,0,1000,1000)
            f.addStroke(110.0,recordHistory=true);f.stopDebounce()
            assertTrue(f.editor.requestPageReference(f.editor.state.document.pages.first()))
            requireNotNull(f.editor.pageReferenceRequest)
        }
        f.beforeTargetWrite={f.beforeTargetWrite=null;entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        f.afterTargetWrite={if(f.writes.get()==2) c1Written.complete(Unit)}
        coroutineScope {
            val preparing=async(Dispatchers.Main) {f.editor.preparePageReference(request)}
            entered.await()
            val native=withContext(Dispatchers.Main) {
                f.editor.clearPageReference(request);preparing.cancel()
                f.editor.selectTool(com.xnotes.core.tools.Tool.PEN)
                val from=f.editor.state.contentToViewport(f.editor.state.fromPageSpace(0,com.xnotes.core.geometry.Pt(240.0,240.0)))
                fun send(action:Int,dx:Float,dy:Float,time:Long) {
                    val pointer=android.view.MotionEvent.PointerProperties().apply {id=0;toolType=android.view.MotionEvent.TOOL_TYPE_STYLUS}
                    val coordinates=android.view.MotionEvent.PointerCoords().apply {x=from.x.toFloat()+dx;y=from.y.toFloat()+dy;pressure=.6f}
                    val event=android.view.MotionEvent.obtain(1L,time,action,1,arrayOf(pointer),arrayOf(coordinates),0,0,
                        1f,1f,0,0,android.view.InputDevice.SOURCE_STYLUS,0)
                    try {assertTrue(f.editor.view.onTouchEvent(event))} finally {event.recycle()}
                }
                send(android.view.MotionEvent.ACTION_DOWN,0f,0f,1L)
                send(android.view.MotionEvent.ACTION_MOVE,40f,20f,2L)
                send(android.view.MotionEvent.ACTION_UP,70f,30f,3L)
                assertEquals("The real pen UP must commit C1",2,f.editor.state.document.pages.first().items.filterIsInstance<Stroke>().size)
                assertTrue(f.editor.state.document.dirty)
                Editor::class.java.getDeclaredField("noteWriteJob").apply {isAccessible=true}.get(f.editor) as Job
            }
            preparing.cancelAndJoin()
            f.release.countDown();native.join()
        }
        val saved=withTimeoutOrNull(4000L) {c1Written.await();true} ?: false
        assertTrue("C1 must resume the existing debounce after B ends, without flush/new edit; writes=${f.writes.get()}",saved)
        withContext(Dispatchers.Main) {
            (Editor::class.java.getDeclaredField("noteWriteJob").apply {isAccessible=true}.get(f.editor) as? Job)?.join()
            assertFalse(f.editor.state.document.dirty);assertNull(f.editor.pageReferenceRequest)
        }
        val actual=f.target.inputStream().use {f.codec.read(it)}
        assertEquals(2,actual.pages.first().items.filterIsInstance<Stroke>().size)
        assertEquals(2,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun replacingTheReferencedDocumentReleasesItsRuntimeProtectionAndOriginalTicket()=scenario {f->
        open(f)
        val old=withContext(Dispatchers.Main) {
            val model=f.editor.state.document
            f.editor.requestPageReference(model.pages.first())
            ready(f.editor.preparePageReference(requireNotNull(f.editor.pageReferenceRequest)))
            assertNotNull(Editor::class.java.getDeclaredField("pageReferenceProtection").apply {isAccessible=true}.get(f.editor))
            f.editor.newNote();model
        }
        withTimeout(5000L) {
            while(withContext(Dispatchers.Main) {f.editor.state.document===old}) delay(1L)
        }
        withContext(Dispatchers.Main) {
            assertNull(Editor::class.java.getDeclaredField("pageReferenceProtection").apply {isAccessible=true}.get(f.editor))
            assertNull(f.editor.pageReferenceRequest);assertNull(f.editor.currentUri)
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }

    @Test fun anUnsavedPageRequiresTheExistingSaveUiAndCannotCreateAFalseSource()=scenario {f->
        withContext(Dispatchers.Main) {
            f.editor.newNote();val page=f.editor.state.document.pages.first()
            assertTrue(f.editor.requestPageReference(page));val request=requireNotNull(f.editor.pageReferenceRequest)
            assertSame(PageReferencePreparation.SaveRequired,f.editor.preparePageReference(request))
            assertEquals(0,f.writes.get());assertNull(f.editor.currentUri)
        }
    }
}
