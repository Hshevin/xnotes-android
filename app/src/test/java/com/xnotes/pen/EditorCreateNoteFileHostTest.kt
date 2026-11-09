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
import com.xnotes.core.model.PageStyle
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.CanvasCodec
import com.xnotes.mindmap.MapCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.RecoveryCopyResult
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class EditorCreateNoteFileHostTest {
    @Before fun noEglHandles() {
        fun <T> empty(type:Class<T>):T=ReflectionHelpers.callConstructor(type,ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE,0L))
        if(EGL14.EGL_NO_DISPLAY==null) EGL14.EGL_NO_DISPLAY=empty(EGLDisplay::class.java)
        if(EGL14.EGL_NO_CONTEXT==null) EGL14.EGL_NO_CONTEXT=empty(EGLContext::class.java)
        if(EGL14.EGL_NO_SURFACE==null) EGL14.EGL_NO_SURFACE=empty(EGLSurface::class.java)
    }
    private fun scenario(block:suspend (EditorPageCopyFixture)->Unit) {
        val base=RuntimeEnvironment.getApplication()
        val root=File(base.cacheDir,"editor-create-note-${UUID.randomUUID()}").apply {mkdirs()}
        val f=EditorPageCopyFixture(base,root)
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
            while(!job.isCompleted && System.nanoTime()<deadline) {shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));Thread.yield()}
            assertTrue("Real createNoteFile did not settle",job.isCompleted);failure?.let {throw it}
        } finally {f.release.countDown();scope.cancel();root.deleteRecursively()}
    }
    private fun setup(cover:Boolean=false)=Editor.NewNoteSetup(148.0,210.0,PageStyle(template="none"),PageStyle(template="none").takeIf {cover})
    private fun tree(f:EditorPageCopyFixture)=DocumentsContract.buildTreeDocumentUri(f.authority,"root").toString()
    private suspend fun openDirtySource(f:EditorPageCopyFixture):Document {
        withContext(Dispatchers.IO) {f.seed()}
        return withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");assertTrue(f.editor.noteOpen)
            f.stopDebounce();f.addStroke(77.0,recordHistory=true);f.editor.state.document
        }
    }
    private enum class Entry { NOTE,CANVAS,MAP,MARKDOWN }
    private suspend fun createEntry(f:EditorPageCopyFixture,entry:Entry)=withContext(Dispatchers.IO) {
        when(entry) {
            Entry.NOTE->f.editor.createBlankNoteFile(tree(f),"root","blank note")
            Entry.CANVAS->f.editor.createBlankCanvasFile(tree(f),"root","blank canvas")
            Entry.MAP->f.editor.createBlankMindMapFile(tree(f),"root","blank map")
            Entry.MARKDOWN->f.editor.createMarkdownNoteFile(tree(f),"root","markdown note","# New entry\ncreated markdown")
        }
    }

    @Test fun aProviderReturningAnOccupiedTargetOriginalSourceOrUnreadableTargetCannotOpenWriteOrDeleteAnything() {
        for(case in listOf("target","source","unreadable")) scenario {f->
            val live=openDirtySource(f);withContext(Dispatchers.IO) {f.seed(f.target)}
            val source=f.source.readBytes();val target=f.target.readBytes();val identity=live.identity
            val command=withContext(Dispatchers.Main) {f.editor.history.nextUndo}
            f.createdDocumentId=if(case=="source") "source" else "target";f.denyTargetRead=case=="unreadable"
            val result=withContext(Dispatchers.IO) {f.editor.createNoteFile(tree(f),"root","new note",setup())}
            assertEquals("Must execute the real provider.createDocument for $case",1,f.creates.get())
            assertNull("Unsafe provider result must not become a successful new note: $case",result)
            assertEquals("No output stream may be opened: $case",0,f.writeOpens.get());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
            assertArrayEquals(source,f.source.readBytes());assertArrayEquals(target,f.target.readBytes())
            withContext(Dispatchers.Main) {
                assertSame(live,f.editor.state.document);assertEquals(identity,live.identity);assertEquals(f.sourceUri,live.path)
                assertTrue(live.dirty);assertSame(command,f.editor.history.nextUndo)
            }
        }
    }
    @Test fun anEmptyProviderTargetPublishesANewNativeDocumentWithoutAdoptingTheOpenDirtySource()=scenario {f->
        val live=openDirtySource(f);val source=f.source.readBytes();val identity=live.identity
        val history=withContext(Dispatchers.Main) {f.editor.history};val command=history.nextUndo
        val made=withContext(Dispatchers.IO) {f.editor.createNoteFile(tree(f),"root","new cover note",setup(cover=true))}
        assertEquals(1,f.creates.get());assertNotNull("Actual empty-target publication must succeed",made)
        assertEquals("target",DocumentsContract.getDocumentId(Uri.parse(made!!)))
        val actual=withContext(Dispatchers.IO) {BundleReferenceInfo.inspectStrict(f.target)}
        val disk=withContext(Dispatchers.IO) {f.target.inputStream().use {f.codec.read(it)}}
        assertNotEquals(identity.documentId,actual.header.identity!!.documentId)
        assertEquals(actual.header.identity,disk.identity);assertEquals(Document.DEFAULT_NEW_PAGES+1,disk.pages.size)
        assertTrue(disk.pages.first().cover)
        if(actual.header.anchors==null) assertTrue(actual.orderedPageIds.isEmpty()) else {
            assertEquals(disk.pages.size,actual.orderedPageIds.size);assertEquals(disk.pages.size,actual.pageIds.size)
        }
        assertEquals(actual.header.identity!!.documentId,f.storage.registry.idAt(made))
        assertFalse(f.storage.hasRecovery(made));assertEquals(0,f.deletes.get());assertArrayEquals(source,f.source.readBytes())
        withContext(Dispatchers.Main) {
            assertSame(live,f.editor.state.document);assertEquals(identity,live.identity);assertEquals(f.sourceUri,live.path)
            assertTrue(live.dirty);assertSame(history,f.editor.history);assertSame(command,history.nextUndo)
        }
    }
    @Test fun aWrittenNewNoteSurvivesRegistrationFailureAndExplicitCompletionUsesTheSameCandidate()=scenario {f->
        val live=openDirtySource(f);val source=f.source.readBytes();val identity=live.identity
        withContext(Dispatchers.IO) {f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_new_note BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT,'new-note registration failed'); END")}
        val made=withContext(Dispatchers.IO) {f.editor.createNoteFile(tree(f),"root","new durable note",setup())}
        assertEquals(1,f.creates.get());assertNull("Registration failure is not success",made)
        assertTrue("A written candidate must not be deleted",f.target.isFile)
        assertEquals(0,f.deletes.get())
        val uri=DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree(f)),"target").toString()
        assertTrue("Write-after-failure must retain the exact publication stage",f.storage.hasRecovery(uri))
        val written=f.target.readBytes();val actual=BundleReferenceInfo.inspectStrict(f.target)
        assertNotEquals(identity.documentId,actual.header.identity!!.documentId);assertEquals(1,f.writes.get())
        assertArrayEquals(source,f.source.readBytes())
        withContext(Dispatchers.IO) {f.storage.registry.writableDatabase.execSQL("DROP TRIGGER fail_new_note")}
        val completed=withContext(Dispatchers.Main) {f.editor.completeRecoveryCopy(uri,"new durable note.xnote")}
        assertTrue(completed is RecoveryCopyResult.Saved);assertFalse(f.storage.hasRecovery(uri))
        assertEquals(1,f.writes.get());assertArrayEquals(written,f.target.readBytes());assertEquals(0,f.deletes.get())
        withContext(Dispatchers.Main) {
            assertSame(live,f.editor.state.document);assertEquals(identity,live.identity);assertEquals(f.sourceUri,live.path);assertTrue(live.dirty)
        }
    }
    @Test fun everyPublicBlankAndMarkdownEntryRejectsNonemptyOriginalAndUnreadableProviderTargetsBeforeAnyWrite() {
        for(entry in Entry.entries) for(case in listOf("target","source","unreadable")) scenario {f->
            val live=openDirtySource(f);withContext(Dispatchers.IO) {f.seed(f.target)}
            val source=f.source.readBytes();val target=f.target.readBytes();val identity=live.identity
            val history=withContext(Dispatchers.Main) {f.editor.history};val command=history.nextUndo
            f.createdDocumentId=if(case=="source") "source" else "target";f.denyTargetRead=case=="unreadable"
            val result=createEntry(f,entry)
            assertEquals("$entry/$case must execute the real createDocument",1,f.creates.get())
            assertNull("$entry/$case is not a new empty target",result)
            assertEquals("$entry/$case opened a provider write stream",0,f.writeOpens.get())
            assertEquals("$entry/$case published native bytes",0,f.writes.get());assertEquals(0,f.deletes.get())
            assertArrayEquals(source,f.source.readBytes());assertArrayEquals(target,f.target.readBytes())
            withContext(Dispatchers.Main) {
                assertSame(live,f.editor.state.document);assertEquals(identity,live.identity);assertEquals(f.sourceUri,live.path)
                assertTrue(live.dirty);assertSame(history,f.editor.history);assertSame(command,history.nextUndo)
            }
        }
    }
    @Test fun everyPublicBlankAndMarkdownEntryPublishesItsOwnFormatAndIdentityWithoutAdoptingTheLiveNote() {
        for(entry in Entry.entries) scenario {f->
            val live=openDirtySource(f);val source=f.source.readBytes();val identity=live.identity
            val history=withContext(Dispatchers.Main) {f.editor.history};val command=history.nextUndo
            val view=withContext(Dispatchers.Main) {Triple(f.editor.state.zoom,f.editor.state.scrollX,f.editor.state.scrollY)}
            val made=createEntry(f,entry)
            assertEquals("$entry must execute the real createDocument",1,f.creates.get())
            assertNotNull("$entry could not publish to a genuinely empty target",made)
            assertEquals("target",DocumentsContract.getDocumentId(Uri.parse(made!!)))
            if(entry==Entry.MAP) {
                val raw=f.target.readBytes()
                assertTrue("Maps must use their independent JSON format",String(raw,Charsets.UTF_8).trimStart().startsWith("{"))
                val map=MapCodec.decode(raw)
                assertNotEquals(identity.documentId,map.id);assertTrue(map.root.text.isNotBlank())
                assertEquals("The map creator must use its JSON output adapter",1,f.writeOpens.get())
                assertEquals("A map must not enter the native ZIP backend",0,f.writes.get())
            } else {
                val actual=withContext(Dispatchers.IO) {BundleReferenceInfo.inspectStrict(f.target)}
                assertNotEquals(identity.documentId,actual.header.identity!!.documentId)
                assertEquals(actual.header.identity!!.documentId,f.storage.registry.idAt(made))
                assertFalse(f.storage.hasRecovery(made));assertEquals(1,f.writes.get());assertEquals(0,f.writeOpens.get())
                if(entry==Entry.CANVAS) {
                    assertEquals("xcanvas",actual.format);assertNull(actual.header.anchors);assertTrue(actual.orderedPageIds.isEmpty())
                    val canvas=withContext(Dispatchers.IO) {f.target.inputStream().use {CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()).read(it)}}
                    assertEquals(actual.header.identity,canvas.identity);assertTrue(canvas.items.isEmpty())
                } else {
                    assertEquals("xnote",actual.format)
                    val note=withContext(Dispatchers.IO) {f.target.inputStream().use {f.codec.read(it)}}
                    assertEquals(actual.header.identity,note.identity);assertTrue(note.pages.isNotEmpty())
                    if(entry==Entry.NOTE) assertEquals(Document.DEFAULT_NEW_PAGES,note.pages.size)
                    if(entry==Entry.MARKDOWN) assertTrue(note.flow.plainText().contains("created markdown"))
                    if(actual.header.anchors==null) assertTrue(actual.orderedPageIds.isEmpty()) else {
                        assertEquals(note.pages.size,actual.orderedPageIds.size);assertEquals(note.pages.size,actual.pageIds.size)
                    }
                }
            }
            assertEquals(0,f.deletes.get());assertArrayEquals(source,f.source.readBytes())
            withContext(Dispatchers.Main) {
                assertSame(live,f.editor.state.document);assertEquals(identity,live.identity);assertEquals(f.sourceUri,live.path)
                assertTrue(live.dirty);assertSame(history,f.editor.history);assertSame(command,history.nextUndo)
                assertEquals(view,Triple(f.editor.state.zoom,f.editor.state.scrollX,f.editor.state.scrollY))
                assertEquals(identity.documentId,f.editor.lastReadSourceVersion()?.documentId)
            }
        }
    }
}
