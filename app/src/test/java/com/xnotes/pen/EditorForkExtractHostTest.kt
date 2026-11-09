package com.xnotes.pen

import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import com.xnotes.core.model.Document
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.RecoveryCopyResult
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class EditorForkExtractHostTest {
    @Before fun noEglHandles() {
        fun <T> empty(type:Class<T>):T=ReflectionHelpers.callConstructor(type,ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE,0L))
        if(EGL14.EGL_NO_DISPLAY==null) EGL14.EGL_NO_DISPLAY=empty(EGLDisplay::class.java)
        if(EGL14.EGL_NO_CONTEXT==null) EGL14.EGL_NO_CONTEXT=empty(EGLContext::class.java)
        if(EGL14.EGL_NO_SURFACE==null) EGL14.EGL_NO_SURFACE=empty(EGLSurface::class.java)
    }
    private fun scenario(block:suspend (EditorPageCopyFixture)->Unit) {
        val base=RuntimeEnvironment.getApplication()
        val root=File(base.cacheDir,"editor-fork-extract-${UUID.randomUUID()}").apply {mkdirs()}
        val f=EditorPageCopyFixture(base,root,namespaced=true)
        val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true};val previous=singleton.get(null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var failure:Throwable?=null;var initialized=false
        val job=scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    singleton.set(null,f.storage)
                    com.xnotes.platform.FontCatalog.init(f.context);com.xnotes.platform.TemplateLibrary.init(f.context)
                    f.editor=Editor(f.context);initialized=true
                }
                withTimeout(15000L) {block(f)}
            } catch(error:Throwable) {failure=error}
            finally {withContext(NonCancellable) {
                f.release.countDown()
                if(initialized) {
                    withContext(Dispatchers.Main) {
                        val auto=Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(f.editor) as CoroutineScope
                        auto.cancel();auto.coroutineContext[Job]?.join()
                        suspendCancellableCoroutine<Unit> {waiter->Handler(Looper.getMainLooper()).post {waiter.resumeWith(Result.success(Unit))}}
                    }
                    (Editor::class.java.getDeclaredField("sessionOwner").apply {isAccessible=true}.get(f.editor) as LegacySessionQueue.Owner).awaitAccepted()
                }
                singleton.set(null,previous);withContext(Dispatchers.IO) {f.storage.registry.close()}
            }}
        }
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25L)
            // Pump input/capture posts without fast-forwarding the 1.2 s autosave debounce while IO runs.
            while(!job.isCompleted && System.nanoTime()<deadline) {shadowOf(Looper.getMainLooper()).idle();Thread.yield()}
            assertTrue("Real fork/extract operation did not settle",job.isCompleted);failure?.let {throw it}
        } finally {f.release.countDown();scope.cancel();root.deleteRecursively()}
    }
    private fun tree(f:EditorPageCopyFixture)=DocumentsContract.buildTreeDocumentUri(f.authority,"root").toString()
    private suspend fun openFolderSource(f:EditorPageCopyFixture):Document {
        withContext(Dispatchers.IO) {f.seed()}
        return withContext(Dispatchers.Main) {
            f.editor.updateBrowseRoot(tree(f))
            f.editor.openAsync(f.sourceUri,"source.xnote");assertTrue(f.editor.noteOpen)
            f.stopDebounce();f.editor.state.document
        }
    }
    private suspend fun awaitMain(condition:()->Boolean)=withTimeout(10000L) {
        while(!withContext(Dispatchers.Main) {condition()}) delay(1)
    }
    @Test fun aRealStampForkAdoptsItsScopeKeepsLateInkDirtyAndUndoRestoresRetiredPagesOnlyWithNewIds()=scenario {f->coroutineScope {
        val live=openFolderSource(f);val source=f.source.readBytes();val a=BundleReferenceInfo.inspectStrict(f.source)
        val originalPages=live.pages.toList();val history=withContext(Dispatchers.Main) {f.editor.history}
        withContext(Dispatchers.Main) {
            f.editor.deletePages(listOf(1));f.stopDebounce();f.addStroke(110.0,recordHistory=true)
        }
        assertTrue(f.source.setLastModified(f.source.lastModified()+10000L))
        val entered=CompletableDeferred<Unit>()
        f.afterTargetWrite={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        val flush=async(Dispatchers.Main) {f.editor.flushSplitDocument()}
        entered.await()
        // The publication has already copied the fixed C0 body to B; C1 belongs only to the owner.
        val b=BundleReferenceInfo.inspectStrict(f.target)
        withContext(Dispatchers.Main) {f.addStroke(990.0,recordHistory=true);f.stopDebounce()}
        f.release.countDown();assertFalse("C1 is still dirty after the completed fixed fork",flush.await())
        val bUri=DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree(f)),f.targetId).toString()
        withContext(Dispatchers.Main) {
            assertSame(live,f.editor.state.document);assertSame(history,f.editor.history)
            assertEquals(bUri,live.path);assertEquals(b.header.identity,live.identity)
            assertTrue("Late ink must stay dirty before any later debounce: writes=${f.writes.get()}, revision=${live.bodyRevision}",live.dirty)
            assertTrue(live.pages.mapNotNull {it.referenceIdentity.id}.toSet().intersect(a.pageIds).isEmpty())
            assertEquals(listOf(110.0,990.0),live.pages.single().items.filterIsInstance<com.xnotes.core.model.Stroke>().map {it.samples.first().x})
            f.editor.undo();f.editor.undo();f.editor.undo();f.stopDebounce()
            assertEquals(2,live.pages.size);assertSame(originalPages[1],live.pages[1])
        }
        f.afterTargetWrite=null
        assertTrue(withContext(Dispatchers.Main) {f.editor.flushSplitDocument()})
        val complete=BundleReferenceInfo.inspectStrict(f.target)
        assertEquals(b.header.identity,complete.header.identity);assertEquals(2,complete.pageIds.size)
        assertTrue(complete.pageIds.intersect(a.pageIds).isEmpty());assertArrayEquals(source,f.source.readBytes())
        withContext(Dispatchers.Main) {
            assertEquals(complete.orderedPageIds,live.pages.map {it.referenceIdentity.id});assertFalse(live.dirty)
            f.editor.redo();f.editor.redo();f.editor.redo();f.stopDebounce()
            assertEquals(1,live.pages.size);assertEquals(b.orderedPageIds,live.pages.map {it.referenceIdentity.id})
        }
    }}
    @Test fun rejectedForkAdoptionDoesNotForwardTheNextPublicFlushIntoThatUnadoptedFile()=scenario {f->coroutineScope {
        val live=openFolderSource(f);val source=f.source.readBytes();val a=BundleReferenceInfo.inspectStrict(f.source)
        val oldPath=live.path;val oldScope=live.pageReferenceScope;val oldIdentity=live.identity
        withContext(Dispatchers.Main) {f.addStroke(110.0,recordHistory=true);f.stopDebounce()}
        assertTrue(f.source.setLastModified(f.source.lastModified()+10000L))
        val entered=CompletableDeferred<Unit>()
        f.afterTargetWrite={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        val flush=async(Dispatchers.Main) {f.editor.flushSplitDocument()}
        entered.await()
        val b=f.target.readBytes();val bInfo=BundleReferenceInfo.inspectStrict(f.target)
        // Public model path changes model an external owner URI action while B is being published.
        val alias=DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree(f)),f.sourceId).toString()
        withContext(Dispatchers.Main) {live.path=alias}
        f.release.countDown();assertFalse(flush.await())
        withContext(Dispatchers.Main) {
            assertSame(live,f.editor.state.document);assertEquals(oldIdentity,live.identity);assertSame(oldScope,live.pageReferenceScope)
            assertEquals(alias,live.path);assertTrue(live.dirty);live.path=oldPath
        }
        f.afterTargetWrite=null;f.createdDocumentId="root/retry"
        assertTrue("A new flush must fork afresh instead of following an unadopted B",withContext(Dispatchers.Main) {f.editor.flushSplitDocument()})
        assertEquals(2,f.creates.get());assertEquals(2,f.writes.get());assertArrayEquals(b,f.target.readBytes())
        val c=BundleReferenceInfo.inspectStrict(f.retry)
        assertNotEquals(bInfo.header.identity,c.header.identity);assertTrue(c.pageIds.intersect(a.pageIds).isEmpty())
        assertTrue(c.pageIds.intersect(bInfo.pageIds).isEmpty());assertArrayEquals(source,f.source.readBytes())
        withContext(Dispatchers.Main) {assertEquals(c.header.identity,live.identity);assertEquals(c.orderedPageIds,live.pages.map {it.referenceIdentity.id})}
    }}
    @Test fun extractingWithMoveKeepsAllSourcePagesWhenRealLateInkChangesTheCapturedOwner()=scenario {f->
        val live=openFolderSource(f);val pages=live.pages.toList();val source=f.source.readBytes()
        val ids=pages.map {it.referenceIdentity.id};val entered=CompletableDeferred<Unit>()
        f.afterTargetWrite={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        withContext(Dispatchers.Main) {f.editor.extractPages(listOf(0),"picked page",move=true)}
        entered.await()
        withContext(Dispatchers.Main) {f.addStroke(990.0,recordHistory=true);f.stopDebounce()}
        f.release.countDown();awaitMain {f.editor.message==f.context.getString(com.xnotes.R.string.extract_copy_kept_source_changed)}
        val copied=BundleReferenceInfo.inspectStrict(f.target)
        assertEquals(1,copied.pageIds.size);assertTrue(copied.pageIds.intersect(ids.filterNotNull().toSet()).isEmpty())
        val disk=f.target.inputStream().use {f.codec.read(it)}
        assertTrue(disk.pages.single().items.isEmpty());assertArrayEquals(source,f.source.readBytes());assertEquals(0,f.deletes.get())
        withContext(Dispatchers.Main) {
            assertSame(live,f.editor.state.document);pages.zip(live.pages).forEach {(old,now)->assertSame(old,now)}
            assertEquals(ids,live.pages.map {it.referenceIdentity.id});assertTrue(live.dirty)
            assertEquals(listOf(990.0),live.pages.first().items.filterIsInstance<com.xnotes.core.model.Stroke>().map {it.samples.first().x})
        }
    }
    @Test fun cancellingAnExtractAfterTheProviderHasWrittenItsTargetRetainsBothCopiesAndCanCompleteTheSameStage()=scenario {f->
        val live=openFolderSource(f);val pages=live.pages.toList();val source=f.source.readBytes();val ids=pages.map {it.referenceIdentity.id}
        val entered=CompletableDeferred<Unit>()
        f.afterTargetWrite={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        withContext(Dispatchers.Main) {f.editor.extractPages(listOf(0),"cancelled page",move=true)}
        entered.await();val written=f.target.readBytes();val actual=BundleReferenceInfo.inspectStrict(f.target)
        withContext(Dispatchers.Main) {f.editor.cancelImportInProgress()}
        f.release.countDown();awaitMain {!f.editor.importing && f.editor.busyWith==null}
        assertTrue(f.target.isFile);assertArrayEquals(written,f.target.readBytes());assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
        assertEquals(1,actual.pageIds.size);assertTrue(actual.pageIds.intersect(ids.filterNotNull().toSet()).isEmpty())
        val uri=DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree(f)),f.targetId).toString()
        if(f.storage.hasRecovery(uri)) assertTrue(withContext(Dispatchers.Main) {f.editor.completeRecoveryCopy(uri,"cancelled page.xnote")} is RecoveryCopyResult.Saved)
        assertEquals(1,f.writes.get());assertArrayEquals(written,f.target.readBytes());assertArrayEquals(source,f.source.readBytes())
        withContext(Dispatchers.Main) {
            assertSame(live,f.editor.state.document);assertEquals(pages.size,live.pages.size)
            pages.zip(live.pages).forEach {(old,now)->assertSame(old,now)};assertEquals(ids,live.pages.map {it.referenceIdentity.id})
        }
    }
    @Test fun anAlreadyCancelledPublicMarkdownCreateReturnsNullWithoutOpeningOrDeletingAnyTarget()=scenario {f->
        val live=openFolderSource(f);val source=f.source.readBytes();val identity=live.identity
        withContext(Dispatchers.Main) {f.editor.cancelImportInProgress()}
        val made=withContext(Dispatchers.IO) {f.editor.createMarkdownNoteFile(tree(f),"root","cancelled markdown","# Cancelled\nDo not publish")}
        assertNull(made);assertEquals(0,f.creates.get());assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
        assertTrue(f.target.readBytes().isEmpty());assertArrayEquals(source,f.source.readBytes())
        withContext(Dispatchers.Main) {assertSame(live,f.editor.state.document);assertEquals(identity,live.identity);assertEquals(f.sourceUri,live.path)}
    }

    @Test fun aDocumentAtTheCopyLineageLimitStillSavesThroughThePublicOrdinaryFlush()=scenario {f->
        withContext(Dispatchers.Main) {f.editor.newNote()}
        awaitMain {f.editor.noteOpen}
        val fixed=withContext(Dispatchers.Main) {
            val live=f.editor.state.document
            live.pages.subList(1,live.pages.size).clear()
            live.enableReferenceAnchors()
            repeat(1024) {com.xnotes.core.model.PageReferenceCopy.prepare(live).adopt(live)}
            live.path=f.sourceUri;live.displayName="source.xnote"
            live.dirty=true;f.stopDebounce()
            live.fixedWriteSnapshot()
        }
        withContext(Dispatchers.IO) {f.source.outputStream().use {f.codec.write(fixed,it)}}
        val before=BundleReferenceInfo.inspectStrict(f.source)
        withContext(Dispatchers.Main) {f.addStroke(100.0,recordHistory=true);f.stopDebounce()}
        assertTrue(withContext(Dispatchers.Main) {f.editor.flushSplitDocument()})
        val saved=BundleReferenceInfo.inspectStrict(f.source)
        assertEquals(before.header.identity,saved.header.identity);assertEquals(before.orderedPageIds,saved.orderedPageIds)
        val actual=f.source.inputStream().use {f.codec.read(it)}
        assertEquals(listOf(100.0),actual.pages.single().items.filterIsInstance<com.xnotes.core.model.Stroke>().map {it.samples.first().x})
        assertEquals(1,f.writes.get());assertEquals(0,f.creates.get());assertEquals(0,f.deletes.get())
        withContext(Dispatchers.Main) {assertFalse(f.editor.state.document.dirty);assertEquals(f.sourceUri,f.editor.currentUri)}
    }

    @Test fun mergingSavedAnchoredNotesPublishesIndependentPageIdsWithoutChangingEitherInputOrTheOpenOwner()=scenario {f->
        val live=openFolderSource(f)
        withContext(Dispatchers.IO) {f.seed(f.retry)}
        val other=DocumentsContract.buildDocumentUri(f.authority,"root/retry").toString()
        val source=f.source.readBytes();val second=f.retry.readBytes()
        val a=BundleReferenceInfo.inspectStrict(f.source);val b=BundleReferenceInfo.inspectStrict(f.retry)
        val identity=live.identity;val pages=live.pages.toList();val history=withContext(Dispatchers.Main) {f.editor.history}
        val done=CompletableDeferred<Editor.MadeFile?>()
        withContext(Dispatchers.Main) {f.editor.mergeNotes(listOf(f.sourceUri,other),"combined") {done.complete(it)}}
        assertNotNull("A merge of two valid anchored inputs must publish successfully",done.await())
        val merged=BundleReferenceInfo.inspectStrict(f.target)
        assertNotEquals(a.header.identity!!.documentId,merged.header.identity!!.documentId)
        assertNotEquals(b.header.identity!!.documentId,merged.header.identity!!.documentId)
        assertEquals(4,merged.orderedPageIds.size);assertEquals(4,merged.pageIds.size)
        assertTrue(merged.pageIds.intersect(a.pageIds+b.pageIds).isEmpty())
        assertArrayEquals(source,f.source.readBytes());assertArrayEquals(second,f.retry.readBytes())
        assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
        withContext(Dispatchers.Main) {
            assertSame(live,f.editor.state.document);assertEquals(identity,live.identity);assertSame(history,f.editor.history)
            assertEquals(pages.size,live.pages.size);pages.zip(live.pages).forEach {(before,after)->assertSame(before,after)}
            assertEquals(f.sourceUri,live.path)
        }
    }

    @Test fun cancellingAMergeAfterTheTargetWriteKeepsItsExactCandidateAndBothInputs()=scenario {f->
        val live=openFolderSource(f)
        withContext(Dispatchers.IO) {f.seed(f.retry)}
        val source=f.source.readBytes();val second=f.retry.readBytes()
        val other=DocumentsContract.buildDocumentUri(f.authority,"root/retry").toString()
        val entered=CompletableDeferred<Unit>();val done=CompletableDeferred<Editor.MadeFile?>()
        f.afterTargetWrite={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        withContext(Dispatchers.Main) {f.editor.mergeNotes(listOf(f.sourceUri,other),"cancelled merge") {done.complete(it)}}
        entered.await();val written=f.target.readBytes();val actual=BundleReferenceInfo.inspectStrict(f.target)
        withContext(Dispatchers.Main) {f.editor.cancelImportInProgress()}
        f.release.countDown();assertNull(done.await())
        assertArrayEquals(written,f.target.readBytes());assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
        val uri=DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree(f)),f.targetId).toString()
        if(f.storage.hasRecovery(uri)) assertTrue(withContext(Dispatchers.Main) {f.editor.completeRecoveryCopy(uri,"cancelled merge.xnote")} is RecoveryCopyResult.Saved)
        assertEquals(actual.header.identity,BundleReferenceInfo.inspectStrict(f.target).header.identity)
        assertArrayEquals(written,f.target.readBytes());assertEquals(1,f.writes.get())
        assertArrayEquals(source,f.source.readBytes());assertArrayEquals(second,f.retry.readBytes())
        withContext(Dispatchers.Main) {assertSame(live,f.editor.state.document);assertEquals(f.sourceUri,live.path)}
    }

}
