package com.xnotes.pen

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Looper
import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.format.CanvasCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.platform.NativeWorkingSession
import com.xnotes.platform.SessionStore
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.Editor
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

/** Actual startup cache adoption and original-file writes, without a rendered GPU surface. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class EditorProtectedSessionHostTest {
    @Before fun noEglHandles() {
        fun <T> empty(type:Class<T>):T=ReflectionHelpers.callConstructor(type,
            ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE,0L))
        if(EGL14.EGL_NO_DISPLAY==null) EGL14.EGL_NO_DISPLAY=empty(EGLDisplay::class.java)
        if(EGL14.EGL_NO_CONTEXT==null) EGL14.EGL_NO_CONTEXT=empty(EGLContext::class.java)
        if(EGL14.EGL_NO_SURFACE==null) EGL14.EGL_NO_SURFACE=empty(EGLSurface::class.java)
    }
    private fun field(editor:Editor,name:String)=Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(editor)
    private fun slot(f:EditorPageCopyFixture)=field(f.editor,"sessionDir") as File
    private fun owner(f:EditorPageCopyFixture)=field(f.editor,"sessionOwner") as LegacySessionQueue.Owner
    private fun native(f:EditorPageCopyFixture)=NativeWorkingSession(slot(f),f.codec,
        CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()),f.storage.pdfResources)
    private fun seed(f:EditorPageCopyFixture,path:String?=null):Document {
        val document=Document(mutableListOf(Page(500.0,500.0))).apply {this.path=path;dirty=false}
        if(path!=null) f.source.outputStream().use {f.codec.write(document,it)}
        val directory=slot(f)
        val store=SessionStore(directory,f.codec,File(f.root,"pdf").apply {mkdirs()},File(f.root,"assets").apply {mkdirs()})
        assertEquals(com.xnotes.platform.LegacySessionWrite.SAVED,store.saveChecked(document,
            SessionStore.Metadata(path,null,false,1.0,0.0,0.0,false,ViewOverrides())))
        return document
    }
    private fun scenario(block:suspend (EditorPageCopyFixture)->Unit) {
        val base=RuntimeEnvironment.getApplication()
        val root=File(base.cacheDir,"protected-session-${UUID.randomUUID()}").apply {mkdirs()}
        val f=EditorPageCopyFixture(base,root)
        val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        val previous=singleton.get(null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        var failure:Throwable?=null
        var initialized=false
        val task=scope.launch {
            try {
                withContext(Dispatchers.Main) {singleton.set(null,f.storage);f.editor=Editor(f.context);initialized=true}
                withTimeout(15000L) {block(f)}
            } catch(error:Throwable) {failure=error}
            finally {withContext(NonCancellable) {
                if(initialized) {
                    val editors=withContext(Dispatchers.Main) {listOfNotNull(f.editor,f.editor.secondary)}
                    withContext(Dispatchers.Main) {
                        for(editor in editors) {
                            val autosave=field(editor,"autosaveScope") as CoroutineScope
                            autosave.cancel();autosave.coroutineContext[Job]?.join()
                        }
                    }
                    for(editor in editors) (field(editor,"sessionOwner") as LegacySessionQueue.Owner).awaitAccepted()
                }
                singleton.set(null,previous)
                withContext(Dispatchers.IO) {f.storage.registry.close()}
            }}
        }
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25)
            while(!task.isCompleted && System.nanoTime()<deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));Thread.yield()
            }
            assertTrue("Protected session scenario did not settle",task.isCompleted)
            failure?.let {throw it}
        } finally {f.release.countDown();scope.cancel();root.deleteRecursively()}
    }

    @Test fun nativeClearedNeverRevivesAnOlderLegacyNote()=scenario {f->
        withContext(Dispatchers.IO) {
            seed(f)
            val session=native(f)
            assertTrue(session.publishClear(session.reserveClear()).published)
        }
        val body=File(slot(f),"document.xnote").readBytes()
        withContext(Dispatchers.Main) {f.editor.restoreSession();assertFalse(f.editor.noteOpen)}
        assertArrayEquals(body,File(slot(f),"document.xnote").readBytes())
    }

    @Test fun unknownNativeHeadKeepsBothItAndLegacyRecoveryUnadopted()=scenario {f->
        withContext(Dispatchers.IO) {seed(f)}
        val head=File(slot(f),"head.json").apply {writeText("{\"version\":999,\"future\":true}")}
        val headBytes=head.readBytes()
        val body=File(slot(f),"document.xnote").readBytes()
        withContext(Dispatchers.Main) {f.editor.restoreSession();assertFalse(f.editor.noteOpen)}
        assertArrayEquals(headBytes,head.readBytes())
        assertArrayEquals(body,File(slot(f),"document.xnote").readBytes())
    }

    @Test fun aStringDirtyFlagCannotTurnMalformedMetadataIntoAnAdoptedNote()=scenario {f->
        withContext(Dispatchers.IO) {seed(f)}
        val metadata=File(slot(f),"session.json")
        metadata.writeText(metadata.readText().replace("\"dirty\":false","\"dirty\":\"false\""))
        val original=metadata.readBytes()
        withContext(Dispatchers.Main) {f.editor.restoreSession();assertFalse(f.editor.noteOpen)}
        assertArrayEquals(original,metadata.readBytes())
    }

    @Test fun anOldCleanCacheDoesNotGrantSameTargetSaveAuthority()=scenario {f->
        withContext(Dispatchers.IO) {seed(f,f.sourceUri)}
        val original=f.source.readBytes()
        val done=CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            f.editor.restoreSession();assertTrue(f.editor.noteOpen)
            assertFalse(f.editor.state.document.dirty)
            f.editor.saveToThen(f.sourceUri) {done.complete(it)}
        }
        assertFalse(done.await())
        assertEquals(0,f.writes.get())
        assertArrayEquals(original,f.source.readBytes())
    }

    @Test fun defaultUnsavedCacheRestoresWithStickyProtectionAndNoAutosaveBinding()=scenario {f->
        val old=withContext(Dispatchers.IO) {seed(f)}
        withContext(Dispatchers.Main) {
            f.editor.restoreSession()
            assertTrue(f.editor.noteOpen);assertTrue(f.editor.requiresIndependentSave)
            assertFalse(f.editor.state.document.dirty);assertNull(f.editor.autosaveUri)
            assertEquals(old.identity,f.editor.state.document.identity)
            assertFalse(f.editor.discardForOpen().afterSaveApplies())
            f.editor.goHome()
            assertTrue("Closing needs SaveAs or an actual Discard choice",f.editor.noteOpen)
            f.editor.authorizeRecoveredDiscard(f.editor.discardForOpen())
            f.editor.goHome();assertFalse(f.editor.noteOpen)
        }
    }

    @Test fun aLaterSameOwnerCommandRejectsAnOtherwiseVerifiedPreparedRestore()=scenario {f->
        withContext(Dispatchers.IO) {seed(f)}
        val prepared=withContext(Dispatchers.Main) {requireNotNull(f.editor.prepareNoteSessionRestore())}
        try {
            assertTrue(withContext(Dispatchers.Main) {prepared.verify()})
            requireNotNull(owner(f).submit {Unit}).await()
            withContext(Dispatchers.Main) {assertFalse(prepared.commit());assertFalse(f.editor.noteOpen)}
        } finally {prepared.close()}
        assertTrue(File(slot(f),"document.xnote").isFile)
    }

    @Test fun aNewUnknownHeadAfterVerificationCannotBeHiddenByThePreparedBody()=scenario {f->
        withContext(Dispatchers.IO) {seed(f)}
        val prepared=withContext(Dispatchers.Main) {requireNotNull(f.editor.prepareNoteSessionRestore())}
        try {
            assertTrue(withContext(Dispatchers.Main) {prepared.verify()})
            val head=File(slot(f),"head.json").apply {writeText("{\"version\":999}")}
            val bytes=head.readBytes()
            withContext(Dispatchers.Main) {assertFalse(prepared.commit());assertFalse(f.editor.noteOpen)}
            assertArrayEquals(bytes,head.readBytes())
        } finally {prepared.close()}
    }

    @Test fun protectedEditsPersistInTheWorkingSlotWithoutTouchingTheOriginal()=scenario {f->
        withContext(Dispatchers.IO) {seed(f,f.sourceUri)}
        val original=f.source.readBytes()
        withContext(Dispatchers.Main) {
            f.editor.restoreSession();f.addStroke(70.0,recordHistory=true);f.editor.persist()
        }
        withTimeout(5000) {
            while(true) {
                delay(10)
                owner(f).awaitAccepted()
                val settled=withContext(Dispatchers.Main) {
                    (field(f.editor,"sessionWriteJob") as? Job)?.isActive!=true && field(f.editor,"sessionPending")==false
                }
                if(settled) break
            }
        }
        withContext(Dispatchers.IO) {
            val doc=File(slot(f),"document.xnote").inputStream().use {f.codec.read(it)}
            assertEquals(1,doc.pages.single().items.size)
        }
        assertEquals(0,f.writes.get());assertArrayEquals(original,f.source.readBytes())
        withContext(Dispatchers.Main) {assertTrue(f.editor.requiresIndependentSave)}
    }

    @Test fun successfulIndependentSaveUsesNewIdentityAndRetainsLateEdits()=scenario {f->
        withContext(Dispatchers.IO) {seed(f,f.sourceUri)}
        val original=f.source.readBytes()
        val oldId=withContext(Dispatchers.Main) {f.editor.restoreSession();f.editor.state.document.identity.documentId}
        f.beforeTargetWrite={runBlocking {withContext(Dispatchers.Main) {f.addStroke(90.0,recordHistory=true)}}}
        val done=CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {f.editor.saveAsToThen(f.targetUri,"Recovered.xnote") {done.complete(it)}}
        assertTrue(done.await())
        withContext(Dispatchers.Main) {
            assertFalse(f.editor.requiresIndependentSave)
            assertNotEquals(oldId,f.editor.state.document.identity.documentId)
            assertEquals(f.targetUri,f.editor.currentUri)
            assertTrue("C1 accepted after the saved B must remain dirty",f.editor.state.document.dirty)
        }
        assertArrayEquals(original,f.source.readBytes())
        withContext(Dispatchers.IO) {
            val saved=f.target.inputStream().use {f.codec.read(it)}
            assertTrue(saved.pages.single().items.isEmpty())
        }
    }

    @Test fun failedIndependentSaveKeepsActualModelProtectionAndOriginalBytes()=scenario {f->
        withContext(Dispatchers.IO) {seed(f,f.sourceUri)}
        val original=f.source.readBytes()
        val model=withContext(Dispatchers.Main) {f.editor.restoreSession();f.editor.state.document}
        f.denyTargetRead=true
        val done=CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {f.editor.saveAsToThen(f.targetUri,"Recovered.xnote") {done.complete(it)}}
        assertFalse(done.await())
        withContext(Dispatchers.Main) {assertSame(model,f.editor.state.document);assertTrue(f.editor.requiresIndependentSave)}
        assertArrayEquals(original,f.source.readBytes());assertEquals(0,f.writes.get())
    }

    @Test fun protectedPhysicalSlotRejectsUntitledNoteAndCanvasBeforeReplacingTheBuffer()=scenario {f->
        withContext(Dispatchers.IO) {seed(f)}
        val original=File(slot(f),"document.xnote").readBytes()
        File(slot(f),"head.json").writeText("{\"version\":999}")
        val buffer=withContext(Dispatchers.Main) {f.editor.state.document}
        withContext(Dispatchers.Main) {f.editor.newNote();f.editor.newCanvas()}
        delay(25)
        withContext(Dispatchers.Main) {assertSame(buffer,f.editor.state.document);assertFalse(f.editor.noteOpen);assertFalse(f.editor.canvasOpen)}
        assertArrayEquals(original,File(slot(f),"document.xnote").readBytes())
    }

    @Test fun fixingInvalidMetadataAllowsTheSameEditorToRetryRecovery()=scenario {f->
        withContext(Dispatchers.IO) {seed(f)}
        val metadata=File(slot(f),"session.json");val valid=metadata.readBytes()
        metadata.writeText("{\"dirty\":\"false\"}")
        withContext(Dispatchers.Main) {f.editor.restoreSession();assertFalse(f.editor.noteOpen)}
        metadata.writeBytes(valid)
        withContext(Dispatchers.Main) {f.editor.restoreSession();assertTrue(f.editor.noteOpen);assertTrue(f.editor.requiresIndependentSave)}
    }

    @Test fun aMismatchedHintDoesNotRetireTheSameEditorsLaterCorrectRestore()=scenario {f->
        val model=withContext(Dispatchers.IO) {seed(f)}
        withContext(Dispatchers.Main) {
            f.editor.restoreSession(com.xnotes.mindmap.MapNativeRestoreHint(UUID.randomUUID().toString(),null,com.xnotes.core.util.DocumentKind.NOTE))
            assertFalse(f.editor.noteOpen)
            f.editor.restoreSession(com.xnotes.mindmap.MapNativeRestoreHint(model.identity.documentId,null,com.xnotes.core.util.DocumentKind.NOTE))
            assertTrue(f.editor.noteOpen);assertTrue(f.editor.requiresIndependentSave)
        }
    }

    @Test fun anOldSplitTupleCannotTurnTheProtectedActualModelIntoAMissingFile()=scenario {f->
        val model=withContext(Dispatchers.IO) {seed(f)}
        File(f.context.filesDir,"split-workspace.json").writeText(org.json.JSONObject().put("secondOpen",true)
            .put("primaryOpen",true).put("primaryId",UUID.randomUUID().toString()).put("primaryPath",f.targetUri).toString())
        withContext(Dispatchers.Main) {
            f.editor.restoreSession();f.editor.restoreSplitWorkspace()
            assertEquals(model.identity.documentId,f.editor.visibleDocumentId)
            assertTrue(f.editor.requiresIndependentSave);assertNull(f.editor.secondary)
        }
    }

    private suspend fun closeOneProtectedSharedView(f:EditorPageCopyFixture,closeOwner:Boolean) {
        withContext(Dispatchers.IO) {seed(f,f.sourceUri)}
        val original=f.source.readBytes()
        val model=withContext(Dispatchers.Main) {
            f.editor.restoreSession()
            val second=f.editor.secondaryPane()
            assertTrue(second.openSharedView(f.editor))
            f.editor.closePane(if(closeOwner) f.editor else second)
            f.editor.state.document
        }
        withTimeout(5000) {while(withContext(Dispatchers.Main) {f.editor.openPanes.size>1}) delay(10)}
        withContext(Dispatchers.Main) {
            assertEquals(1,f.editor.openPanes.size)
            val remaining=f.editor.openPanes.single()
            assertSame(model,remaining.state.document)
            assertTrue(remaining.requiresIndependentSave)
            remaining.goHome()
            assertTrue("The last protected view still needs an explicit choice",remaining.noteOpen)
        }
        assertEquals(0,f.writes.get());assertArrayEquals(original,f.source.readBytes())
    }
    @Test fun closingABorrowedProtectedViewRetainsItsActualOwner()=scenario {closeOneProtectedSharedView(it,false)}
    @Test fun closingTheProtectedOwnerRetainsOneViewAndItsStickyProtection()=scenario {closeOneProtectedSharedView(it,true)}
}
