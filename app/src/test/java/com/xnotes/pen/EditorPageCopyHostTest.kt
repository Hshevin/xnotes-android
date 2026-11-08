package com.xnotes.pen

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.view.ContextThemeWrapper
import com.xnotes.core.history.AddItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.storage.MigrationPublication
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.RecoveryCopyResult
import com.xnotes.ui.Editor
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

/** A provider can deliberately return target again from createDocument for later fork/new-note tests. */
internal class EditorPageCopyFixture(base:Context,val root:File) {
    val authority="editor-page-copy"
    val sourceUri="content://$authority/document/source"
    val targetUri="content://$authority/document/target"
    val source=File(root,"source.xnote")
    val target=File(root,"target.xnote").apply {writeBytes(byteArrayOf())}
    val writes=AtomicInteger();val deletes=AtomicInteger();val writeOpens=AtomicInteger();val creates=AtomicInteger()
    val release=CountDownLatch(1)
    @Volatile var beforeTargetRead:(()->Unit)?=null
    private fun file(id:String)=when(id) {"source"->source;"target"->target;else->error("Unknown document: $id")}
    val provider=object:DocumentsProvider() {
        override fun onCreate()=true
        override fun queryRoots(projection:Array<out String>?)=MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
        override fun queryChildDocuments(parentDocumentId:String,projection:Array<out String>?,sortOrder:String?)=
            MatrixCursor(projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
        override fun queryDocument(documentId:String,projection:Array<out String>?):Cursor {
            val columns=projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val document=file(documentId)
            return MatrixCursor(columns).apply {addRow(columns.map {column->when(column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID->documentId
                DocumentsContract.Document.COLUMN_DISPLAY_NAME->document.name
                DocumentsContract.Document.COLUMN_MIME_TYPE->"application/octet-stream"
                DocumentsContract.Document.COLUMN_FLAGS->DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                DocumentsContract.Document.COLUMN_SIZE->document.length()
                DocumentsContract.Document.COLUMN_LAST_MODIFIED->document.lastModified()
                else->null
            }})}
        }
        override fun openDocument(documentId:String,mode:String,signal:CancellationSignal?):ParcelFileDescriptor {
            if('w' in mode) writeOpens.incrementAndGet()
            return ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.parseMode(mode))
        }
        override fun createDocument(parentDocumentId:String,mimeType:String,displayName:String):String {creates.incrementAndGet();return "target"}
        override fun deleteDocument(documentId:String) {deletes.incrementAndGet();file(documentId).delete()}
    }
    val context=object:ContextThemeWrapper(base,android.R.style.Theme_Material_Light_NoActionBar) {
        override fun getApplicationContext():Context=this
        override fun getFilesDir()=File(root,"files").apply {mkdirs()}
        override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        override fun getContentResolver():ContentResolver=ContentResolver.wrap(provider)
    }
    val codec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
    val storage=NotebookStorage(context,targetOverride={uri->
        val document=if(uri==sourceUri) source else if(uri==targetUri) target else error("Unexpected target $uri")
        object:MigrationPublication.Target {
            override fun read():java.io.InputStream {if(uri==targetUri) beforeTargetRead?.invoke();return document.inputStream()}
            override fun write(source:File) {writes.incrementAndGet();source.copyTo(document,overwrite=true)}
        }
    })
    lateinit var editor:Editor
    init {provider.attachInfo(context,ProviderInfo().apply {
        authority=this@EditorPageCopyFixture.authority;exported=true;grantUriPermissions=true
        readPermission="android.permission.MANAGE_DOCUMENTS";writePermission=readPermission
    })}
    fun seed(file:File=source) {
        val document=Document(mutableListOf(Page(500.0,500.0),Page(500.0,500.0))).apply {enableReferenceAnchors()}
        file.outputStream().use {codec.write(document.fixedWriteSnapshot(),it)}
    }
    fun addStroke(x:Double,recordHistory:Boolean=false):Stroke {
        val document=editor.state.document
        val page=document.pages.first()
        val stroke=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(x,130.0,.5),Sample(x+20,160.0,.75)))
        page.items+=stroke
        if(recordHistory) editor.history.push(AddItem(page,stroke))
        document.dirty=true
        return stroke
    }
    /** The fixture has no storage root; also stop a refreshed copy's debounce inside its Main callback. */
    fun stopDebounce() {
        (Editor::class.java.getDeclaredField("noteDebounceJob").apply {isAccessible=true}.get(editor) as? Job)?.cancel()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class EditorPageCopyHostTest {
    @Before fun noEglHandles() {
        fun <T> empty(type:Class<T>):T=ReflectionHelpers.callConstructor(type,ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE,0L))
        if(EGL14.EGL_NO_DISPLAY==null) EGL14.EGL_NO_DISPLAY=empty(EGLDisplay::class.java)
        if(EGL14.EGL_NO_CONTEXT==null) EGL14.EGL_NO_CONTEXT=empty(EGLContext::class.java)
        if(EGL14.EGL_NO_SURFACE==null) EGL14.EGL_NO_SURFACE=empty(EGLSurface::class.java)
    }
    private fun scenario(block:suspend (EditorPageCopyFixture)->Unit) {
        val base=RuntimeEnvironment.getApplication()
        val root=File(base.cacheDir,"editor-page-copy-${UUID.randomUUID()}").apply {mkdirs()}
        val f=EditorPageCopyFixture(base,root)
        val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true};val previous=singleton.get(null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var failure:Throwable?=null;var initialized=false
        val job=scope.launch {
            try {
                withContext(Dispatchers.Main) {singleton.set(null,f.storage);f.editor=Editor(f.context);initialized=true}
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
            assertTrue("Real Editor copy did not settle",job.isCompleted);failure?.let {throw it}
        } finally {f.release.countDown();scope.cancel();root.deleteRecursively()}
    }

    @Test fun anAnchoredNoteCanSaveAsANewEmptyTargetWithIndependentDocumentAndPageIds()=scenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        val sourceBytes=f.source.readBytes();val sourceInfo=BundleReferenceInfo.inspectStrict(f.source)
        val done=CompletableDeferred<Boolean>()
        val model=withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");assertTrue(f.editor.noteOpen)
            f.addStroke(110.0);f.editor.state.document.also {f.editor.saveToThen(f.targetUri) {done.complete(it)}}
        }
        val completed=done.await()
        val diagnostic=withContext(Dispatchers.Main) {"message=${f.editor.message}, writes=${f.writes.get()}, logs=${org.robolectric.shadows.ShadowLog.getLogsForTag("xnotes.save").joinToString { it.throwable?.stackTraceToString().orEmpty() }}"}
        assertTrue("Public Save-As must create a verified new native target, not publishNote over an empty file; $diagnostic, hasRecovery=${f.storage.hasRecovery(f.targetUri)}",completed)
        val saved=withContext(Dispatchers.IO) {BundleReferenceInfo.inspectStrict(f.target)}
        assertNotEquals(sourceInfo.header.identity!!.documentId,saved.header.identity!!.documentId)
        assertEquals(2,saved.orderedPageIds.toSet().size);assertTrue(saved.pageIds.intersect(sourceInfo.pageIds).isEmpty())
        assertArrayEquals(sourceBytes,f.source.readBytes());assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
        withContext(Dispatchers.Main) {
            assertSame(model,f.editor.state.document);assertEquals(f.targetUri,model.path)
            assertEquals(saved.header.identity,model.identity);assertEquals(saved.orderedPageIds,model.pages.map {it.referenceIdentity.id})
            assertFalse(model.dirty);assertEquals(1,model.pages.first().items.filterIsInstance<Stroke>().size)
            assertNull("The former source context must retire after adopting another document",f.editor.lastReadSourceVersion())
        }
        assertFalse(f.storage.hasRecovery(f.targetUri))
    }

    @Test fun aNonemptyProviderTargetIsNeverWrittenOrDeletedAndLateLiveEditsKeepTheirOriginalScope()=scenario {f->
        withContext(Dispatchers.IO) {f.seed();f.seed(f.target)}
        val sourceBytes=f.source.readBytes();val targetBytes=f.target.readBytes()
        val done=CompletableDeferred<Boolean>();val entered=CompletableDeferred<Unit>()
        val model=withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");assertTrue(f.editor.noteOpen);f.addStroke(110.0)
            f.editor.state.document
        }
        val identity=model.identity;val ids=model.pages.map {it.referenceIdentity.id}
        f.beforeTargetRead={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS)) {"Target read gate was not released"}}
        try {
            withContext(Dispatchers.Main) {f.editor.saveToThen(f.targetUri) {done.complete(it)}}
            kotlinx.coroutines.selects.select<Unit> {
                entered.onAwait {}
                done.onAwait {fail("Must actually inspect the provider's nonempty target before refusing it")}
            }
            withContext(Dispatchers.Main) {f.addStroke(990.0)}
        } finally {f.release.countDown()}
        assertFalse(done.await());assertArrayEquals(sourceBytes,f.source.readBytes());assertArrayEquals(targetBytes,f.target.readBytes())
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
        withContext(Dispatchers.Main) {
            assertSame(model,f.editor.state.document);assertEquals(identity,model.identity);assertEquals(ids,model.pages.map {it.referenceIdentity.id})
            assertEquals(f.sourceUri,model.path);assertTrue(model.dirty)
            assertEquals(identity.documentId,f.editor.lastReadSourceVersion()?.documentId)
            assertEquals(listOf(110.0,990.0),model.pages.first().items.filterIsInstance<Stroke>().map {it.samples.first().x})
        }
    }

    @Test fun anExplicitSaveAsReturningTheOriginalUriCannotFallBackToAnOrdinarySave()=scenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        val sourceBytes=f.source.readBytes();val done=CompletableDeferred<Boolean>()
        val model=withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");assertTrue(f.editor.noteOpen)
            f.stopDebounce();f.addStroke(110.0);f.editor.state.document
        }
        val identity=model.identity;val ids=model.pages.map {it.referenceIdentity.id}
        withContext(Dispatchers.Main) {
            f.editor.saveAsToThen(f.sourceUri,"source.xnote") {ok->f.stopDebounce();done.complete(ok)}
        }
        assertFalse("An explicit new-copy action must reject the original file, even when the provider returns its URI",done.await())
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
        assertArrayEquals(sourceBytes,f.source.readBytes())
        withContext(Dispatchers.Main) {
            assertSame(model,f.editor.state.document);assertEquals(identity,model.identity)
            assertEquals(ids,model.pages.map {it.referenceIdentity.id});assertEquals(f.sourceUri,model.path)
            assertTrue(model.dirty);assertEquals(identity.documentId,f.editor.lastReadSourceVersion()?.documentId)
            assertEquals(listOf(110.0),model.pages.first().items.filterIsInstance<Stroke>().map {it.samples.first().x})
        }
        assertFalse(f.storage.hasRecovery(f.sourceUri))
    }

    @Test fun saveAsKeepsItsFixedBodyWhileLateInkAndHistoryStayOnTheSameLivePagesAndViewport()=scenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        val sourceBytes=f.source.readBytes();val sourceInfo=BundleReferenceInfo.inspectStrict(f.source)
        val entered=CompletableDeferred<Unit>();val done=CompletableDeferred<Boolean>()
        val model=withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");assertTrue(f.editor.noteOpen)
            f.stopDebounce();f.addStroke(110.0,recordHistory=true)
            f.editor.state.zoom=1.25;f.editor.state.scrollX=17.0;f.editor.state.scrollY=43.0
            f.editor.state.document
        }
        val pages=model.pages.toList();val pageList=model.pages;val history=f.editor.history
        val viewport=withContext(Dispatchers.Main) {Triple(f.editor.state.zoom,f.editor.state.scrollX,f.editor.state.scrollY)}
        f.beforeTargetRead={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS)) {"Empty-target gate was not released"}}
        var lateCommand:com.xnotes.core.history.Command?=null
        try {
            withContext(Dispatchers.Main) {f.editor.saveToThen(f.targetUri) {ok->f.stopDebounce();done.complete(ok)}}
            kotlinx.coroutines.selects.select<Unit> {
                entered.onAwait {}
                done.onAwait {fail("Save-As must inspect the new target before completing")}
            }
            withContext(Dispatchers.Main) {f.addStroke(990.0,recordHistory=true);lateCommand=history.nextUndo}
        } finally {f.release.countDown()}
        assertTrue("A verified independent copy must succeed even when later ink remains dirty",done.await())
        val disk=withContext(Dispatchers.IO) {f.target.inputStream().use {f.codec.read(it)}}
        val saved=BundleReferenceInfo.inspectStrict(f.target)
        assertEquals(listOf(110.0),disk.pages.first().items.filterIsInstance<Stroke>().map {it.samples.first().x})
        assertNotEquals(sourceInfo.header.identity!!.documentId,saved.header.identity!!.documentId)
        assertTrue(saved.pageIds.intersect(sourceInfo.pageIds).isEmpty());assertArrayEquals(sourceBytes,f.source.readBytes())
        assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
        withContext(Dispatchers.Main) {
            assertSame(model,f.editor.state.document);assertSame(pageList,model.pages)
            pages.zip(model.pages).forEach {(before,after)->assertSame(before,after)}
            assertEquals(f.targetUri,model.path);assertEquals(saved.header.identity,model.identity)
            assertEquals(saved.orderedPageIds,model.pages.map {it.referenceIdentity.id});assertTrue(model.dirty)
            assertNull(f.editor.lastReadSourceVersion());assertSame(history,f.editor.history);assertSame(lateCommand,history.nextUndo)
            assertEquals(viewport,Triple(f.editor.state.zoom,f.editor.state.scrollX,f.editor.state.scrollY))
            assertEquals(listOf(110.0,990.0),model.pages.first().items.filterIsInstance<Stroke>().map {it.samples.first().x})
            history.undo();assertEquals(listOf(110.0),model.pages.first().items.filterIsInstance<Stroke>().map {it.samples.first().x})
            history.undo();assertTrue(model.pages.first().items.isEmpty())
            history.redo();history.redo()
            assertEquals(listOf(110.0,990.0),model.pages.first().items.filterIsInstance<Stroke>().map {it.samples.first().x})
            assertEquals(saved.orderedPageIds,model.pages.map {it.referenceIdentity.id})
        }
    }

    @Test fun writtenCopyRegistrationFailureKeepsTheOriginalLiveScopeAndExplicitCompletionDoesNotAdoptIt()=scenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        val sourceBytes=f.source.readBytes();val entered=CompletableDeferred<Unit>();val done=CompletableDeferred<Boolean>()
        val model=withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");assertTrue(f.editor.noteOpen)
            f.stopDebounce();f.addStroke(110.0,recordHistory=true);f.editor.state.document
        }
        val identity=model.identity;val ids=model.pages.map {it.referenceIdentity.id};val pages=model.pages.toList()
        withContext(Dispatchers.IO) {
            f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_after_write BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT,'copy registration failed'); END")
        }
        f.beforeTargetRead={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS)) {"Failed-copy target gate was not released"}}
        try {
            withContext(Dispatchers.Main) {f.editor.saveToThen(f.targetUri) {ok->f.stopDebounce();done.complete(ok)}}
            kotlinx.coroutines.selects.select<Unit> {
                entered.onAwait {}
                done.onAwait {fail("Copy must inspect the new target before reporting failure")}
            }
            withContext(Dispatchers.Main) {f.addStroke(990.0,recordHistory=true)}
        } finally {f.release.countDown()}
        assertFalse(done.await());assertEquals(1,f.writes.get());assertTrue(f.storage.hasRecovery(f.targetUri))
        val written=f.target.readBytes();val targetInfo=BundleReferenceInfo.inspectStrict(f.target)
        val disk=f.target.inputStream().use {f.codec.read(it)}
        assertEquals(listOf(110.0),disk.pages.first().items.filterIsInstance<Stroke>().map {it.samples.first().x})
        assertNotEquals(identity.documentId,targetInfo.header.identity!!.documentId);assertTrue(targetInfo.pageIds.intersect(ids.filterNotNull().toSet()).isEmpty())
        suspend fun assertOriginalLive()=withContext(Dispatchers.Main) {
            assertSame(model,f.editor.state.document);assertEquals(identity,model.identity);assertEquals(ids,model.pages.map {it.referenceIdentity.id})
            pages.zip(model.pages).forEach {(before,after)->assertSame(before,after)}
            assertEquals(f.sourceUri,model.path);assertTrue(model.dirty);assertEquals(identity.documentId,f.editor.lastReadSourceVersion()?.documentId)
            assertEquals(listOf(110.0,990.0),model.pages.first().items.filterIsInstance<Stroke>().map {it.samples.first().x})
        }
        assertOriginalLive();assertArrayEquals(sourceBytes,f.source.readBytes());assertEquals(0,f.deletes.get())
        withContext(Dispatchers.IO) {f.storage.registry.writableDatabase.execSQL("DROP TRIGGER fail_after_write")}
        val completed=withContext(Dispatchers.Main) {f.editor.completeRecoveryCopy(f.targetUri,"Completed copy")}
        assertTrue(completed is RecoveryCopyResult.Saved)
        assertFalse(f.storage.hasRecovery(f.targetUri));assertEquals(1,f.writes.get());assertArrayEquals(written,f.target.readBytes())
        assertOriginalLive();assertArrayEquals(sourceBytes,f.source.readBytes())
    }

    @Test fun saveAsAdmitsAnAttachedPdfAndMergesItsVerifiedReferenceIdentityIntoTheLiveCopy()=scenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        val sourceBytes=f.source.readBytes()
        val sourceInfo=com.xnotes.format.BundleReferenceInfo.inspectStrict(f.source)

        // A structurally valid one-page PDF with a real xref table; the note keeps its blank pages.
        val out=java.io.ByteArrayOutputStream()
        fun ascii(value:String) {out.write(value.toByteArray(Charsets.US_ASCII))}
        ascii("%PDF-1.4\n")
        val objects=listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << >> /Contents 4 0 R >>",
            "<< /Length 4 >>\nstream\nq\nQ\nendstream")
        val offsets=IntArray(objects.size+1)
        objects.forEachIndexed {index,body->offsets[index+1]=out.size();ascii("${index+1} 0 obj\n$body\nendobj\n")}
        val xref=out.size()
        ascii("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        for(index in 1 until offsets.size) ascii(java.lang.String.format(java.util.Locale.ROOT,"%010d 00000 n \n",offsets[index]))
        ascii("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        val original=out.toByteArray()
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(original).joinToString("") {"%02x".format(it)}
        // The enclosing scenario drains native/cache jobs before removing its root, so this input
        // remains present for the caller-owned live document and accepted session encoders.
        val input=java.io.File.createTempFile("save-as-original-",".pdf",f.root).apply {writeBytes(original)}
        val done=CompletableDeferred<Boolean>()
        val model=withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");assertTrue(f.editor.noteOpen)
            f.stopDebounce()
            f.editor.state.document.also {doc->
                assertEquals(sourceInfo.header.identity!!.documentId,doc.identity.documentId)
                assertTrue(doc.pages.all {it.pdfPage==null})
                doc.pdfFile=input
                doc.identity=doc.identity.copy(pdfResource=hash)
                doc.dirty=true
                f.editor.saveAsToThen(f.targetUri,"with-original.xnote") {ok->f.stopDebounce();done.complete(ok)}
            }
        }
        val saved=done.await()
        val diagnostic=withContext(Dispatchers.Main) {"message=${f.editor.message}, writes=${f.writes.get()}"}
        assertTrue("Attached-PDF Save-As must publish the admitted original; $diagnostic",saved)
        val actual=withContext(Dispatchers.IO) {com.xnotes.format.BundleReferenceInfo.inspectStrict(f.target)}
        val identity=requireNotNull(actual.header.identity)
        assertNotEquals(sourceInfo.header.identity!!.documentId,identity.documentId)
        assertTrue(identity.externalPdf);assertEquals(hash,identity.pdfResource);assertEquals(hash,actual.originalPdfHash)
        assertNull(actual.embeddedPdfHash)
        java.util.zip.ZipFile(f.target).use {zip->
            assertNull("Reference identity v2 must not embed a second original",zip.getEntry(com.xnotes.format.BundlePdf.ENTRY))
            val metadata=zip.getInputStream(zip.getEntry(com.xnotes.format.BundleIdentity.ENTRY)).use {org.json.JSONObject(String(it.readBytes(),Charsets.UTF_8))}
            assertEquals(2,metadata.getInt("version"));assertEquals(hash,metadata.getString("pdfResource"))
        }
        val resources=f.storage.pdfResources
        val stored=withContext(Dispatchers.IO) {resources.resolve(hash)}
        assertTrue(resources.isOwned(stored));assertArrayEquals(original,stored.readBytes())
        val leases=f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {it.moveToFirst();it.getInt(0)}
        assertEquals("The create/admission leases must be released before completion",0,leases)
        assertEquals(setOf(hash),resources.references("note:${identity.documentId}"))
        withContext(Dispatchers.IO) {f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0");resources.collect()}
        assertTrue("The permanent note reference protects the original without a lease",stored.isFile)
        assertArrayEquals(original,stored.readBytes());assertArrayEquals(original,input.readBytes());assertArrayEquals(sourceBytes,f.source.readBytes())
        assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get());assertFalse(f.storage.hasRecovery(f.targetUri))
        withContext(Dispatchers.Main) {
            assertSame(model,f.editor.state.document);assertEquals(f.targetUri,model.path);assertEquals(identity,model.identity)
            assertEquals(actual.orderedPageIds,model.pages.map {it.referenceIdentity.id})
            assertTrue(model.pages.all {it.pdfPage==null});assertFalse(model.dirty)
            assertEquals("Adoption must merge the actual admitted PDF file, not only its v2 identity",stored.canonicalFile,requireNotNull(model.pdfFile).canonicalFile)
            assertArrayEquals(original,requireNotNull(model.pdfFile).readBytes())
            assertNull(f.editor.lastReadSourceVersion())
        }
    }

}
