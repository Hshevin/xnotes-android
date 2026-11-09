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
internal class EditorPageCopyFixture(base:Context,val root:File,private val namespaced:Boolean=false) {
    val authority="editor-page-copy"
    val sourceId=if(namespaced) "root/source" else "source"
    val targetId=if(namespaced) "root/target" else "target"
    val sourceUri=DocumentsContract.buildDocumentUri(authority,sourceId).toString()
    val targetUri=DocumentsContract.buildDocumentUri(authority,targetId).toString()
    val source=File(root,"source.xnote")
    val target=File(root,"target.xnote").apply {writeBytes(byteArrayOf())}
    var sourceDisplayName="source.xnote"
    var targetDisplayName="target.xnote"
    var retryDisplayName="retry.xnote"
    val retry=File(root,"retry.xnote").apply {writeBytes(byteArrayOf())}
    private val madeIds=java.util.concurrent.CopyOnWriteArrayList<String>()
    val writes=AtomicInteger();val deletes=AtomicInteger();val writeOpens=AtomicInteger();val creates=AtomicInteger()
    val release=CountDownLatch(1)
    @Volatile var createdDocumentId=targetId
    @Volatile var denyTargetRead=false
    @Volatile var beforeTargetRead:(()->Unit)?=null
    @Volatile var beforeTargetWrite:(()->Unit)?=null
    @Volatile var afterTargetWrite:(()->Unit)?=null
    private fun file(id:String)=when(id.substringAfterLast('/')) {"source"->source;"target"->target;"retry"->retry;"root"->root;else->error("Unknown document: $id")}
    val provider=object:DocumentsProvider() {
        override fun onCreate()=true
        override fun queryRoots(projection:Array<out String>?)=MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
        override fun queryChildDocuments(parentDocumentId:String,projection:Array<out String>?,sortOrder:String?):Cursor {
            val columns=projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            return MatrixCursor(columns).apply {
                if(namespaced && parentDocumentId=="root") for(id in (listOf(sourceId)+madeIds).distinct()) {
                    queryDocument(id,columns).use {c->
                        while(c.moveToNext()) addRow(columns.indices.map {at->when(c.getType(at)) {
                            Cursor.FIELD_TYPE_INTEGER->c.getLong(at);Cursor.FIELD_TYPE_STRING->c.getString(at);else->null
                        }})
                    }
                }
            }
        }
        override fun isChildDocument(parentDocumentId:String,documentId:String)=documentId.startsWith("$parentDocumentId/")
        override fun queryDocument(documentId:String,projection:Array<out String>?):Cursor {
            val columns=projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val document=file(documentId)
            return MatrixCursor(columns).apply {addRow(columns.map {column->when(column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID->documentId
                DocumentsContract.Document.COLUMN_DISPLAY_NAME->when(documentId.substringAfterLast('/')) {"source"->sourceDisplayName;"target"->targetDisplayName;"retry"->retryDisplayName;else->document.name}
                DocumentsContract.Document.COLUMN_MIME_TYPE->if(documentId=="root") DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                DocumentsContract.Document.COLUMN_FLAGS->DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                DocumentsContract.Document.COLUMN_SIZE->document.length()
                DocumentsContract.Document.COLUMN_LAST_MODIFIED->document.lastModified()
                else->null
            }})}
        }
        override fun openDocument(documentId:String,mode:String,signal:CancellationSignal?):ParcelFileDescriptor {
            if(documentId.substringAfterLast('/')=="target" && 'w' !in mode && denyTargetRead) throw SecurityException("Target read permission lost")
            if('w' in mode) writeOpens.incrementAndGet()
            return ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.parseMode(mode))
        }
        override fun createDocument(parentDocumentId:String,mimeType:String,displayName:String):String {
            creates.incrementAndGet();madeIds.add(createdDocumentId)
            if(namespaced) when(createdDocumentId.substringAfterLast('/')) {"target"->targetDisplayName=displayName;"retry"->retryDisplayName=displayName}
            return createdDocumentId
        }
        override fun deleteDocument(documentId:String) {deletes.incrementAndGet();file(documentId).delete()}
    }
    // Robolectric's resolver calls the pre-O query overload, which DocumentsProvider deliberately
    // rejects. Delegate just that resolver boundary to the same document operations and real files.
    private val resolverProvider=object:android.content.ContentProvider() {
        override fun onCreate()=true
        override fun query(uri:android.net.Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?)=
            if(uri.pathSegments.lastOrNull()=="children") provider.queryChildDocuments(DocumentsContract.getDocumentId(uri),projection,sort)
            else provider.queryDocument(DocumentsContract.getDocumentId(uri),projection)
        override fun openFile(uri:android.net.Uri,mode:String)=provider.openDocument(DocumentsContract.getDocumentId(uri),mode,null)
        override fun getType(uri:android.net.Uri)="application/octet-stream"
        override fun insert(uri:android.net.Uri,values:android.content.ContentValues?):android.net.Uri?=null
        override fun update(uri:android.net.Uri,values:android.content.ContentValues?,selection:String?,args:Array<out String>?)=0
        override fun delete(uri:android.net.Uri,selection:String?,args:Array<out String>?):Int {
            provider.deleteDocument(DocumentsContract.getDocumentId(uri));return 1
        }
        override fun call(method:String,arg:String?,extras:android.os.Bundle?)=provider.call(method,arg,extras)
    }
    val context=object:ContextThemeWrapper(base,android.R.style.Theme_Material_Light_NoActionBar) {
        override fun getApplicationContext():Context=this
        override fun getFilesDir()=File(root,"files").apply {mkdirs()}
        override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        override fun getContentResolver():ContentResolver=base.contentResolver
    }
    val codec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
    val storage=NotebookStorage(context,targetOverride={uri->
        val documentId=DocumentsContract.getDocumentId(android.net.Uri.parse(uri))
        val document=file(documentId)
        object:MigrationPublication.Target {
            override fun read():java.io.InputStream {
                if(documentId.substringAfterLast('/')=="target") {if(denyTargetRead) throw SecurityException("Target read permission lost");beforeTargetRead?.invoke()}
                return document.inputStream()
            }
            override fun write(source:File) {if(documentId.substringAfterLast('/')=="target") beforeTargetWrite?.invoke();writes.incrementAndGet();source.copyTo(document,overwrite=true);if(documentId.substringAfterLast('/')=="target") afterTargetWrite?.invoke()}
        }
    })
    lateinit var editor:Editor
    init {provider.attachInfo(context,ProviderInfo().apply {
        authority=this@EditorPageCopyFixture.authority;exported=true;grantUriPermissions=true
        readPermission="android.permission.MANAGE_DOCUMENTS";writePermission=readPermission
    });resolverProvider.attachInfo(context,ProviderInfo().apply {authority=this@EditorPageCopyFixture.authority})
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority,resolverProvider)}
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


    @Test fun aStoredAnchoredFileCopiesItsRawPayloadAndPdfWithoutSavingOrAdoptingTheLiveDraft()=scenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        val model=withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");assertTrue(f.editor.noteOpen)
            f.stopDebounce();f.addStroke(110.0,recordHistory=true)
            f.editor.state.zoom=1.25;f.editor.state.scrollX=17.0;f.editor.state.scrollY=43.0
            f.editor.state.document
        }
        val liveIdentity=model.identity;val pages=model.pages.toList();val liveIds=pages.map {it.referenceIdentity.id}
        val history=f.editor.history;val undo=history.nextUndo
        val sourceBinding=withContext(Dispatchers.Main) {f.editor.lastReadSourceVersion()}
        val viewport=withContext(Dispatchers.Main) {Triple(f.editor.state.zoom,f.editor.state.scrollX,f.editor.state.scrollY)}

        // Build a valid PDF and an existing reference-style stored file after the no-PDF Editor opened.
        // This keeps PDF rendering outside the scenario and proves copying uses disk bytes, not that model.
        val pdfOut=java.io.ByteArrayOutputStream()
        fun ascii(value:String) {pdfOut.write(value.toByteArray(Charsets.US_ASCII))}
        ascii("%PDF-1.4\n")
        val objects=listOf("<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << >> /Contents 4 0 R >>",
            "<< /Length 4 >>\nstream\nq\nQ\nendstream")
        val offsets=IntArray(objects.size+1)
        objects.forEachIndexed {i,body->offsets[i+1]=pdfOut.size();ascii("${i+1} 0 obj\n$body\nendobj\n")}
        val xref=pdfOut.size();ascii("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        for(i in 1 until offsets.size) ascii(java.lang.String.format(java.util.Locale.ROOT,"%010d 00000 n \n",offsets[i]))
        ascii("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        val pdfBytes=pdfOut.toByteArray()
        val resources=f.storage.pdfResources
        val managed=withContext(Dispatchers.IO) {resources.admit(pdfBytes.inputStream(),"stored original")}
        val pdfHash=managed.nameWithoutExtension
        val rawEntries=java.util.zip.ZipFile(f.source).use {zip->zip.entries().asSequence().associate {entry->
            entry.name to zip.getInputStream(entry).use {it.readBytes()}
        }}
        val oldManifest=String(rawEntries.getValue("manifest.json"),Charsets.UTF_8).trimEnd()
        assertTrue(oldManifest.contains("\"has_pdf\":false"))
        val rawManifest=oldManifest.replaceFirst("\"has_pdf\":false","\"has_pdf\":true").dropLast(1)+
            ",\"future_stored_ink\":{\"samples\":[[-0,1.2500e+02,0.1234567890123456789]],\"page_hint\":\"${liveIds.first()}\"}}"
        withContext(Dispatchers.IO) {
            val identity=com.xnotes.format.BundleIdentity.read(f.source)!!.copy(pdfResource=pdfHash,externalPdf=true)
            java.util.zip.ZipOutputStream(f.source.outputStream()).use {zip->
                for((name,bytes) in rawEntries) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(when(name) {
                        com.xnotes.format.BundleIdentity.ENTRY->identity.bytes()
                        "manifest.json"->rawManifest.toByteArray(Charsets.UTF_8)
                        else->bytes
                    });zip.closeEntry()
                }
                zip.putNextEntry(java.util.zip.ZipEntry("future/unparsed-ink.bin"))
                zip.write(ByteArray(65539) {(it*31).toByte()});zip.closeEntry()
            }
            resources.pin("note:${identity.documentId}",setOf(pdfHash))
        }
        var protectedDuringPublication=false
        f.beforeTargetWrite={
            resources.pin("note:${liveIdentity.documentId}",emptySet())
            f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
            resources.collect()
            assertTrue("The source lease must survive portable encoding and destination publication",managed.isFile)
            val leases=f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases WHERE resource_id=?",arrayOf(pdfHash)).use {it.moveToFirst();it.getInt(0)}
            assertTrue("The copy must hold the actual original during publication",leases>0)
            protectedDuringPublication=true
        }
        val sourceBytes=f.source.readBytes();val before=com.xnotes.format.BundleReferenceInfo.inspectStrict(f.source)
        val copied=withContext(Dispatchers.Main) {f.editor.copyStoredFileTo(f.sourceUri,f.targetUri,"portable-copy.xnote").also {f.stopDebounce()}}
        assertTrue("A stored-file action must publish a verified portable copy",copied)
        assertTrue(protectedDuringPublication)
        val after=com.xnotes.format.BundleReferenceInfo.inspectStrict(f.target)
        assertNotEquals(before.header.identity!!.documentId,after.header.identity!!.documentId)
        assertEquals(before.orderedPageIds.size,after.orderedPageIds.size);assertTrue(before.pageIds.intersect(after.pageIds).isEmpty())
        assertFalse(after.header.identity.externalPdf);assertEquals(pdfHash,after.header.identity.pdfResource)
        assertEquals(pdfHash,after.embeddedPdfHash)
        val excluded=setOf(com.xnotes.format.BundleIdentity.ENTRY,com.xnotes.format.AnchorMetadata.ENTRY,"manifest.json",com.xnotes.format.BundlePdf.ENTRY)
        assertEquals(com.xnotes.format.BundleMigration.fingerprints(f.source).filterKeys {it !in excluded},
            com.xnotes.format.BundleMigration.fingerprints(f.target).filterKeys {it !in excluded})
        java.util.zip.ZipFile(f.target).use {zip->
            assertArrayEquals(pdfBytes,zip.getInputStream(zip.getEntry(com.xnotes.format.BundlePdf.ENTRY)).use {it.readBytes()})
            var expected=rawManifest
            before.orderedPageIds.zip(after.orderedPageIds).forEach {(old,new)->
                expected=expected.replace("\"reference_id\":\"$old\"","\"reference_id\":\"$new\"")
            }
            assertArrayEquals(expected.toByteArray(Charsets.UTF_8),zip.getInputStream(zip.getEntry("manifest.json")).use {it.readBytes()})
        }
        assertArrayEquals(sourceBytes,f.source.readBytes());assertArrayEquals(pdfBytes,managed.readBytes())
        assertEquals(1,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
        assertFalse(f.storage.hasRecovery(f.targetUri))
        withContext(Dispatchers.Main) {
            assertSame(model,f.editor.state.document);assertEquals(liveIdentity,model.identity);assertEquals(f.sourceUri,model.path);assertTrue(model.dirty)
            assertEquals(liveIds,model.pages.map {it.referenceIdentity.id});pages.zip(model.pages).forEach {(a,b)->assertSame(a,b)}
            assertSame(history,f.editor.history);assertSame(undo,history.nextUndo);assertFalse(history.canRedo)
            assertSame(sourceBinding,f.editor.lastReadSourceVersion())
            assertEquals(viewport,Triple(f.editor.state.zoom,f.editor.state.scrollX,f.editor.state.scrollY))
            assertEquals(listOf(110.0),model.pages.first().items.filterIsInstance<com.xnotes.core.model.Stroke>().map {it.samples.first().x})
        }
    }

    @Test fun aStoredFileCopyRejectsTheSourceItselfAndEveryNonemptyProviderTargetWithoutWritingOrDeleting() {
        for(sameSource in listOf(true,false)) scenario {f->
            withContext(Dispatchers.IO) {f.seed();if(!sameSource) f.seed(f.target)}
            val model=withContext(Dispatchers.Main) {
                f.editor.openAsync(f.sourceUri,"source.xnote");f.stopDebounce();f.addStroke(110.0,recordHistory=true)
                f.editor.state.zoom=1.25;f.editor.state.scrollX=17.0;f.editor.state.scrollY=43.0;f.editor.state.document
            }
            val identity=model.identity;val ids=model.pages.map {it.referenceIdentity.id};val undo=f.editor.history.nextUndo
            val viewport=withContext(Dispatchers.Main) {Triple(f.editor.state.zoom,f.editor.state.scrollX,f.editor.state.scrollY)}
            val sourceBytes=f.source.readBytes();val targetBytes=f.target.readBytes()
            val destination=if(sameSource) f.sourceUri else f.targetUri
            assertFalse(withContext(Dispatchers.Main) {f.editor.copyStoredFileTo(f.sourceUri,destination,"copy.xnote").also {f.stopDebounce()}})
            assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
            assertArrayEquals(sourceBytes,f.source.readBytes());assertArrayEquals(targetBytes,f.target.readBytes())
            assertFalse(f.storage.hasRecovery(f.sourceUri));assertFalse(f.storage.hasRecovery(f.targetUri))
            withContext(Dispatchers.Main) {
                assertSame(model,f.editor.state.document);assertEquals(identity,model.identity);assertEquals(f.sourceUri,model.path);assertTrue(model.dirty)
                assertEquals(ids,model.pages.map {it.referenceIdentity.id});assertSame(undo,f.editor.history.nextUndo)
                assertEquals(viewport,Triple(f.editor.state.zoom,f.editor.state.scrollX,f.editor.state.scrollY))
            }
        }
    }

    @Test fun aStoredCanvasCopyKeepsItsRawManifestAndAssetsAndDoesNotAdoptTheOpenEditor()=scenario {f->
        f.sourceDisplayName="saved.xcanvas"
        val original=com.xnotes.format.BundleIdentity()
        val manifest=""" {"format":"xcanvas","version":1,"items":[],"future":{"pressure":0.1234567890123456789,"sample":-0}} """.toByteArray()
        val asset=ByteArray(65539) {(it*17).toByte()}
        java.util.zip.ZipOutputStream(f.source.outputStream()).use {zip->
            for((name,bytes) in listOf("manifest.json" to manifest,"future/raw.bin" to asset,com.xnotes.format.BundleIdentity.ENTRY to original.bytes())) {
                zip.putNextEntry(java.util.zip.ZipEntry(name));zip.write(bytes);zip.closeEntry()
            }
        }
        val before=f.source.readBytes()
        val model=withContext(Dispatchers.Main) {f.editor.state.document}
        assertTrue(withContext(Dispatchers.Main) {f.editor.copyStoredFileTo(f.sourceUri,f.targetUri,"independent.xcanvas")})
        val actual=BundleReferenceInfo.inspectStrict(f.target)
        assertEquals("xcanvas",actual.format);assertNotEquals(original.documentId,actual.header.identity!!.documentId)
        assertTrue(actual.pageIds.isEmpty());assertNull(actual.header.anchors)
        java.util.zip.ZipFile(f.target).use {zip->
            assertArrayEquals(manifest,zip.getInputStream(zip.getEntry("manifest.json")).use {it.readBytes()})
            assertArrayEquals(asset,zip.getInputStream(zip.getEntry("future/raw.bin")).use {it.readBytes()})
        }
        assertArrayEquals(before,f.source.readBytes());assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get());assertEquals(0,f.writeOpens.get())
        withContext(Dispatchers.Main) {assertSame(model,f.editor.state.document);assertFalse(f.editor.canvasOpen)}
    }

    @Test fun sharingAStoredNativeBundlePreservesItsIdentityAndEveryRawEntry()=scenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        val name=withContext(Dispatchers.IO) {
            checkNotNull(f.context.contentResolver.query(android.net.Uri.parse(f.sourceUri),arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)).use {cursor->
                check(cursor.moveToFirst());cursor.getString(0)
            }
        }
        assertEquals("source.xnote",name)
        val before=f.source.readBytes();val original=BundleReferenceInfo.inspectStrict(f.source)
        val output=java.io.ByteArrayOutputStream()
        withContext(Dispatchers.IO) {f.editor.copyFileTo(f.sourceUri,output)}
        val shared=File(f.root,"shared.xnote").apply {writeBytes(output.toByteArray())}
        val actual=BundleReferenceInfo.inspectStrict(shared)
        assertEquals(original.header.identity,actual.header.identity);assertEquals(original.orderedPageIds,actual.orderedPageIds)
        assertEquals(com.xnotes.format.BundleMigration.fingerprints(f.source),com.xnotes.format.BundleMigration.fingerprints(shared))
        assertArrayEquals(before,f.source.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
    }

    @Test fun aStoredCopyRejectsLossOfAnAlreadyRegisteredPageContractBeforeOpeningTheDestination()=scenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        val model=withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");f.stopDebounce();f.addStroke(110.0,recordHistory=true);f.editor.state.document
        }
        withContext(Dispatchers.IO) {f.storage.registry.register(model.identity.documentId,"xnote","source.xnote",f.sourceUri,pageReferences=true)}
        assertTrue(f.storage.registry.expectationAt(f.sourceUri)!!.requiresPages)
        val identity=model.identity;val undo=f.editor.history.nextUndo
        withContext(Dispatchers.IO) {
            val stripped=Document(mutableListOf(Page(500.0,500.0))).apply {this.identity=identity}
            f.source.outputStream().use {f.codec.write(stripped.fixedWriteSnapshot(),it)}
        }
        val sourceBytes=f.source.readBytes()
        assertFalse(withContext(Dispatchers.Main) {f.editor.copyStoredFileTo(f.sourceUri,f.targetUri,"copy.xnote")})
        assertArrayEquals(sourceBytes,f.source.readBytes());assertEquals(0L,f.target.length())
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get());assertFalse(f.storage.hasRecovery(f.targetUri))
        withContext(Dispatchers.Main) {assertSame(model,f.editor.state.document);assertSame(undo,f.editor.history.nextUndo);assertTrue(model.dirty);assertEquals(identity,model.identity)}
    }

    @Test fun cancellingAStoredCopyDuringTargetInspectionDoesNotWriteOrReportSuccess()=scenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        val sourceBytes=f.source.readBytes();val entered=CompletableDeferred<Unit>()
        f.beforeTargetRead={entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}
        val copy=CoroutineScope(currentCoroutineContext()).async(Dispatchers.Main) {f.editor.copyStoredFileTo(f.sourceUri,f.targetUri,"copy.xnote")}
        try {entered.await();copy.cancel()} finally {f.release.countDown()}
        try {copy.await();fail("Cancellation must propagate rather than return ordinary copy success or failure")} catch(expected:CancellationException) {}
        assertArrayEquals(sourceBytes,f.source.readBytes());assertEquals(0L,f.target.length())
        assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get());assertFalse(f.storage.hasRecovery(f.targetUri))
    }

    @Test fun aStoredCopyWrittenBeforeRegistrationFailureCanOnlyContinueItsRetainedCandidate()=scenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        val sourceBytes=f.source.readBytes()
        val model=withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote");f.stopDebounce();f.addStroke(110.0,recordHistory=true);f.editor.state.document
        }
        val identity=model.identity;val undo=f.editor.history.nextUndo
        withContext(Dispatchers.IO) {f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_stored_copy BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT,'copy registration failed'); END")}
        assertFalse(withContext(Dispatchers.Main) {f.editor.copyStoredFileTo(f.sourceUri,f.targetUri,"copy.xnote")})
        assertTrue(f.storage.hasRecovery(f.targetUri));assertEquals(1,f.writes.get())
        val written=f.target.readBytes();val saved=BundleReferenceInfo.inspectStrict(f.target)
        assertNotEquals(identity.documentId,saved.header.identity!!.documentId)
        assertFalse(withContext(Dispatchers.Main) {f.editor.copyStoredFileTo(f.sourceUri,f.targetUri,"retry.xnote")})
        assertArrayEquals(written,f.target.readBytes());assertEquals(1,f.writes.get());assertTrue(f.storage.hasRecovery(f.targetUri))
        withContext(Dispatchers.IO) {f.storage.registry.writableDatabase.execSQL("DROP TRIGGER fail_stored_copy")}
        assertTrue(withContext(Dispatchers.Main) {f.editor.completeRecoveryCopy(f.targetUri,"Completed stored copy")} is RecoveryCopyResult.Saved)
        assertFalse(f.storage.hasRecovery(f.targetUri));assertArrayEquals(written,f.target.readBytes());assertEquals(1,f.writes.get())
        assertArrayEquals(sourceBytes,f.source.readBytes());assertEquals(0,f.deletes.get());assertEquals(0,f.writeOpens.get())
        withContext(Dispatchers.Main) {
            assertSame(model,f.editor.state.document);assertEquals(identity,model.identity);assertEquals(f.sourceUri,model.path)
            assertSame(undo,f.editor.history.nextUndo);assertTrue(model.dirty)
        }
    }

}
