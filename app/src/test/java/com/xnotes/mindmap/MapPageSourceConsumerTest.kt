package com.xnotes.mindmap

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.xnotes.core.geometry.Pt
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleAnchors
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundlePdf
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.storage.NativeSourceSnapshot
import com.xnotes.storage.NotebookStorage
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Draft API consumer tests: real provider/ZIP/storage/session/history, no fake page catalogue. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapPageSourceConsumerTest {
    private val sourceId="ac73007e-c7d3-4075-8870-355f203e36f5"
    private val oldSource=MapSource(documentId="f655fb72-9cf4-4c59-a63b-7e1e6ba873a6",fileName="Earlier source",note="keep this note")
    private val pdf="%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n".toByteArray()
    private class Provider(val root:File):ContentProvider() {
        val writes=mutableMapOf<String,Int>()
        val reads=mutableMapOf<String,Int>()
        var beforeRead:((Uri)->Unit)?=null
        fun file(uri:Uri)=File(root,requireNotNull(uri.lastPathSegment))
        fun writes(uri:Uri)=writes[uri.toString()] ?: 0
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/octet-stream"
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {addRow(arrayOf(file(uri).name,file(uri).length()))}
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=0
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=error("A source choice cannot delete files")
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            val writing='w' in mode
            if(writing) writes[uri.toString()]=writes(uri)+1 else {
                reads[uri.toString()]=(reads[uri.toString()] ?: 0)+1;beforeRead?.invoke(uri)
            }
            return ParcelFileDescriptor.open(file(uri),if(writing) ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE else ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
    private inner class Fixture:AutoCloseable {
        val base=RuntimeEnvironment.getApplication()
        val root=File(base.cacheDir,"page-source-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
        }
        val authority="page-source-${UUID.randomUUID()}"
        val provider=Provider(root)
        val native=Uri.parse("content://$authority/document/source.xnote")
        val map=Uri.parse("content://$authority/document/graph.xmap")
        val canvas=Uri.parse("content://$authority/document/sketch.xcanvas")
        val otherMap=Uri.parse("content://$authority/document/other.xmap")
        val storage=NotebookStorage(context)
        private val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        private val previous=singleton.get(null)
        val session=MapSession()
        init {
            provider.attachInfo(context,ProviderInfo().apply {authority=this@Fixture.authority})
            ShadowContentResolver.registerProviderInternal(authority,provider)
            singleton.set(null,storage)
            nativeBundle(provider.file(native))
            nativeBundle(provider.file(canvas),kind=DocumentKind.CANVAS,id="caf43182-3dc3-4d26-aa0c-2d5bf31c1fb5")
            provider.file(otherMap).writeBytes(MapCodec.encode(MindMapDocument()))
            provider.file(map).writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(
                MapNode(id="r",parent=null,kind=NodeKind.INK,text="original title",sources=listOf(oldSource),primarySource=oldSource.id),
                MapNode(id="other",parent="r",text="Other")))))
            session.open(context,map);await {!session.loading};assertFalse(session.loadFailed)
        }
        fun choose():MapSourceChoice {
            val request=session.beginExistingSource("r")!!
            assertTrue(session.sourcePicker.receive(request,native))
            await {session.sourcePicker.state?.let {!it.busy && it.offer!=null}==true}
            return session.sourcePicker.state!!
        }
        fun pages(choice:MapSourceChoice)=
            (choice.offer!!.catalog!!.pages as NativeSourceSnapshot.ReferenceCatalog.Pages.Available).orderedIds
        override fun close() {
            provider.beforeRead=null
            session.save();await {!session.saving}
            val job=session.viewModelScope.coroutineContext[Job]!!
            ViewModelStore().apply {put("test",session);clear()}
            await {job.children.none()}
            storage.registry.close();singleton.set(null,previous);root.deleteRecursively()
        }
    }
    private fun scenario(block:(Fixture)->Unit) {Fixture().use(block)}
    private fun await(condition:()->Boolean) {
        repeat(500) {shadowOf(Looper.getMainLooper()).idle();if(condition()) return;Thread.sleep(5)}
        fail("Actual PAGE source consumer did not settle")
    }
    private fun nativeBundle(file:File,kind:DocumentKind=DocumentKind.NOTE,body:String="original payload",anchored:Boolean=false,id:String=sourceId) {
        ZipOutputStream(file.outputStream()).use {z->
            BundleIdentity(id).write(z)
            if(anchored) AnchorMetadata(id).write(z)
            if(kind==DocumentKind.NOTE) {z.putNextEntry(ZipEntry(BundlePdf.ENTRY));z.write(pdf);z.closeEntry()}
            z.putNextEntry(ZipEntry("manifest.json"))
            val pageA=if(anchored) "\"reference_id\":\"${UUID.randomUUID()}\"," else ""
            val pageB=if(anchored) "\"reference_id\":\"${UUID.randomUUID()}\"," else ""
            val content=if(kind==DocumentKind.NOTE) "\"pages\":[{$pageA\"items\":[{\"type\":\"future-ink\",\"number\":1e+03}]},{$pageB\"items\":[]}]" else "\"items\":[]"
            z.write("""{"format":"${kind.extension}","version":1,"has_pdf":${kind==DocumentKind.NOTE},$content,"body":"$body"}""".toByteArray());z.closeEntry()
            z.putNextEntry(ZipEntry("unknown/future.bin"));z.write(byteArrayOf(0,-1,3,-128,10));z.closeEntry()
        }
    }
    private fun entry(file:File,name:String)=ZipFile(file).use {zip->zip.getInputStream(zip.getEntry(name)).use {it.readBytes()}}

    @Test fun explicitActivationThenSecondPageAppendsToLatestBodyAsOneUndoAndSurvivesSavingAndReopening()=scenario {f->
        val a=File(f.root,"observed-a.zip").also {f.provider.file(f.native).copyTo(it)}
        val choice=f.choose();val original=f.session.document!!
        assertSame(NativeSourceSnapshot.ReferenceCatalog.Pages.NeedsActivation,choice.offer!!.catalog!!.pages)
        assertEquals(0,f.provider.writes(f.native));assertEquals(original,f.session.document)
        assertTrue(f.session.sourcePicker.activatePages(choice)) // Actual UI confirmation owns this command.
        await {f.session.sourcePicker.state?.let {!it.busy && it.offer?.catalog?.pages is NativeSourceSnapshot.ReferenceCatalog.Pages.Available}==true}
        val activated=f.session.sourcePicker.state!!;val page=f.pages(activated)[1]
        assertEquals(1,f.provider.writes(f.native));BundleAnchors.verifyActivation(a,f.provider.file(f.native))
        assertArrayEquals(pdf,entry(f.provider.file(f.native),BundlePdf.ENTRY))
        assertArrayEquals(entry(a,"unknown/future.bin"),entry(f.provider.file(f.native),"unknown/future.bin"))
        val list=SourceListEditor(f.session,f.session.sourceEditSnapshot("r")!!)
        val ink=MapInk(listOf(InkPoint(8.0,9.0,.8,123.0)))
        assertTrue(f.session.operate {it.update("r") {n->n.copy(text="latest independent title",ink=listOf(ink))}})
        assertTrue(list.note(oldSource.id,"latest note"));val beforeAppend=f.session.document
        assertTrue(f.session.sourcePicker.choosePage(activated,page));await {f.session.sourcePicker.state==null}
        val changed=f.session.document!!;val added=changed.root.sources.last()
        assertEquals("latest independent title",changed.root.text);assertEquals(listOf(ink),changed.root.ink)
        assertEquals(oldSource.copy(note="latest note"),changed.root.sources.first());assertEquals(oldSource.id,changed.root.primarySource)
        assertEquals(SourceScope.PAGE,added.scope);assertEquals(sourceId,added.documentId);assertEquals(page,added.pageId)
        assertEquals(activated.offer!!.catalog!!.sha256,added.sourceVersion);assertNull(added.region)
        f.session.undo();assertEquals(beforeAppend,f.session.document);f.session.redo();assertEquals(changed,f.session.document)
        f.session.save();await {!f.session.saving};assertEquals(changed,MapCodec.decode(f.provider.file(f.map).readBytes()))
        val reopened=MapSession();reopened.open(f.context,f.map);await {!reopened.loading}
        assertEquals(added,reopened.document!!.root.sources.last())
        ViewModelStore().apply {put("reopened",reopened);clear()}
        assertEquals(1,f.provider.writes(f.native))
    }

    @Test fun secondPageInANewReferenceDraftKeepsItsEditedTitleUntilOneNodeAndRelationConfirmation()=scenario {f->
        nativeBundle(f.provider.file(f.native),anchored=true);val original=f.session.document!!
        assertTrue(f.session.beginConnectedNode("r",Pt(600.0,400.0)));val token=f.session.connectedDraft!!.token
        assertTrue(f.session.chooseConnectedKind(token,NodeKind.REFERENCE));val draft=f.session.connectedDraft!!
        val request=f.session.beginConnectedSource(token)!!;assertTrue(f.session.sourcePicker.receive(request.id,f.native))
        await {f.session.sourcePicker.state?.let {!it.busy && it.offer!=null}==true}
        val choice=f.session.sourcePicker.state!!;val second=f.pages(choice)[1]
        assertTrue(f.session.editConnectedNode(token,draft.node!!.copy(text="Independent reference title")))
        assertTrue(f.session.sourcePicker.choosePage(choice,second));await {f.session.sourcePicker.state==null}
        assertEquals(original,f.session.document);assertFalse(f.session.canUndo)
        assertEquals("Independent reference title",f.session.connectedDraft!!.node!!.text)
        assertEquals(second,f.session.connectedDraft!!.node!!.sources.single().pageId)
        assertTrue(f.session.finishConnectedNode(token,draft.node.copy(text="Confirmed title")))
        val changed=f.session.document!!;val added=changed.node(draft.nodeId)
        assertEquals("Confirmed title",added.text);assertEquals(second,added.sources.single().pageId)
        assertEquals(SourceScope.PAGE,added.sources.single().scope);assertEquals(draft.center,added.bounds.center)
        assertTrue(changed.relations.any {it.from==draft.from && it.to==draft.nodeId})
        f.session.undo();assertEquals(original,f.session.document);f.session.redo();assertEquals(changed,f.session.document)
        assertEquals(0,f.provider.writes(f.native))
    }

    @Test fun cancelledOrOldChoicesAndAnActivationReleasedAfterGraphReplacementCannotAttachToNewTransactions()=scenario {f->
        val original=f.session.document!!;val a=f.provider.file(f.native).readBytes()
        val old=f.choose();assertTrue(f.session.sourcePicker.cancel(old));assertEquals(original,f.session.document)
        val request=f.session.beginExistingSource("other")!!
        assertFalse(f.session.sourcePicker.chooseFile(old));assertFalse(f.session.sourcePicker.receive(old.requestId,f.native))
        assertTrue(f.session.sourcePicker.receive(request,null));assertNull(f.session.sourcePicker.state)
        assertEquals(original,f.session.document);assertEquals(0,f.provider.writes(f.native));assertArrayEquals(a,f.provider.file(f.native).readBytes())
        val ready=f.choose();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        f.provider.beforeRead={uri->if(uri==f.native) {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}}
        try {
            assertTrue(f.session.sourcePicker.activatePages(ready));assertTrue(entered.await(5,TimeUnit.SECONDS))
            val jobs=f.session.viewModelScope.coroutineContext[Job]!!.children.toList()
            f.session.useFileVersion();await {!f.session.replacingDocument}
            val fresh=f.session.beginExistingSource("other")!!
            f.provider.beforeRead=null;release.countDown();await {jobs.all {it.isCompleted}}
            assertEquals(original,f.session.document);assertFalse(f.session.canUndo);assertFalse(f.session.sourceFailed)
            assertNull(f.session.sourcePicker.state);assertFalse(f.session.sourcePicker.chooseFile(ready))
            assertTrue(f.session.sourcePicker.receive(fresh,null));assertEquals(0,f.provider.writes(f.native))
            assertArrayEquals(a,f.provider.file(f.native).readBytes())
        } finally {f.provider.beforeRead=null;release.countDown()}
    }

    @Test fun staleSourceBeforeActivationOrFinalSelectionIsRejectedAndCanvasAndMapsOfferOnlyFiles()=scenario {f->
        val original=f.session.document!!;val choice=f.choose()
        nativeBundle(f.provider.file(f.native),body="same UUID external change")
        val external=f.provider.file(f.native).readBytes()
        assertTrue(f.session.sourcePicker.activatePages(choice))
        await {f.session.sourcePicker.state?.let {!it.busy && it.problem!=null}==true}
        assertEquals(0,f.provider.writes(f.native));assertArrayEquals(external,f.provider.file(f.native).readBytes());assertEquals(original,f.session.document)
        assertTrue(f.session.sourcePicker.cancel(f.session.sourcePicker.state!!))
        nativeBundle(f.provider.file(f.native),anchored=true)
        val fresh=f.choose();val second=f.pages(fresh)[1]
        assertFalse(f.session.sourcePicker.choosePage(fresh,UUID.randomUUID().toString()))
        nativeBundle(f.provider.file(f.native),anchored=true,body="external activated replacement")
        assertTrue(f.session.sourcePicker.choosePage(fresh,second))
        await {f.session.sourcePicker.state?.let {!it.busy && it.problem!=null}==true}
        assertEquals(original,f.session.document);assertEquals(0,f.provider.writes(f.native))
        f.session.sourcePicker.cancel(f.session.sourcePicker.state!!)
        for(uri in listOf(f.canvas,f.otherMap)) {
            val request=f.session.beginExistingSource("r")!!;assertTrue(f.session.sourcePicker.receive(request,uri))
            await {f.session.sourcePicker.state?.let {!it.busy && it.offer!=null}==true}
            val file=f.session.sourcePicker.state!!
            assertFalse(f.session.sourcePicker.activatePages(file));assertFalse(f.session.sourcePicker.choosePage(file,second))
            assertTrue(f.session.sourcePicker.chooseFile(file));await {f.session.sourcePicker.state==null}
            val source=f.session.document!!.root.sources.last();assertEquals(SourceScope.FILE,source.scope);assertNull(source.pageId)
            assertEquals(0,f.provider.writes(uri))
        }
    }

    @Test fun writtenButUnregisteredActivationOffersExplicitContinuationOfTheSamePageIdsBeforeAnyGraphCommit()=scenario {f->
        val choice=f.choose();val before=f.session.document!!
        f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_page_choice_index BEFORE UPDATE ON documents BEGIN SELECT RAISE(ABORT,'injected registration failure'); END")
        try {
            assertTrue(f.session.sourcePicker.activatePages(choice))
            await {f.session.sourcePicker.state?.let {!it.busy && it.problem!=null}==true}
            val failed=f.session.sourcePicker.state!!
            assertTrue(failed.canResume);assertEquals(1,f.provider.writes(f.native));assertTrue(f.storage.hasRecovery(f.native.toString()))
            val b=f.provider.file(f.native).readBytes();val ids=BundleReferenceInfo.inspectStrict(f.provider.file(f.native)).orderedPageIds
            assertEquals(before,f.session.document);assertFalse(f.session.canUndo)
            f.storage.registry.writableDatabase.execSQL("DROP TRIGGER fail_page_choice_index")
            assertTrue(f.session.sourcePicker.completeActivation(failed))
            await {f.session.sourcePicker.state?.let {!it.busy && it.offer?.catalog?.pages is NativeSourceSnapshot.ReferenceCatalog.Pages.Available}==true}
            val completed=f.session.sourcePicker.state!!;assertEquals(ids,f.pages(completed));assertEquals(1,f.provider.writes(f.native))
            assertArrayEquals(b,f.provider.file(f.native).readBytes());assertFalse(f.storage.hasRecovery(f.native.toString()))
            assertTrue(f.session.sourcePicker.choosePage(completed,ids[1]));await {f.session.sourcePicker.state==null}
            assertEquals(ids[1],f.session.document!!.root.sources.last().pageId);f.session.undo();assertEquals(before,f.session.document)
        } finally {f.storage.registry.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_page_choice_index")}
    }

    @Test fun existingPageWaitsForPenReleaseBeforeItsFinalReadAndRejectsSourceChangesDuringTheWait()=scenario {f->
        nativeBundle(f.provider.file(f.native),anchored=true)
        val choice=f.choose();val second=f.pages(choice)[1];val reads=f.provider.reads[f.native.toString()] ?: 0
        f.session.inkContact=true
        assertTrue(f.session.sourcePicker.choosePage(choice,second));shadowOf(Looper.getMainLooper()).idle()
        assertEquals(reads,f.provider.reads[f.native.toString()] ?: 0)
        val stroke=MapInk(listOf(InkPoint(3.0,4.0,.7,90.0)))
        assertTrue(f.session.commitInk("r",emptyList(),listOf(stroke)))
        val before=f.session.document
        nativeBundle(f.provider.file(f.native),anchored=true,body="changed while the pen was down")
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {f.session.inkContact=false}
        await {f.session.sourcePicker.state?.let {!it.busy && it.problem!=null}==true}
        assertEquals(before,f.session.document);assertEquals(listOf(stroke),f.session.document!!.root.ink)
        assertEquals(listOf(oldSource),f.session.document!!.root.sources);assertEquals(0,f.provider.writes(f.native))
        assertTrue((f.provider.reads[f.native.toString()] ?: 0)>reads)
    }

    @Test fun deletingATargetOrChangingTheReferenceDraftKindRetiresAnActivationBeforeItsReadLatchReleases() {
        for(connected in listOf(false,true)) scenario {f->
            val token=if(connected) {
                assertTrue(f.session.beginConnectedNode("r",Pt(600.0,400.0)))
                f.session.connectedDraft!!.token.also {assertTrue(f.session.chooseConnectedKind(it,NodeKind.REFERENCE))}
            } else null
            val request=if(token!=null) f.session.beginConnectedSource(token)!!.id else f.session.beginExistingSource("other")!!
            assertTrue(f.session.sourcePicker.receive(request,f.native))
            await {f.session.sourcePicker.state?.let {!it.busy && it.offer!=null}==true}
            val choice=f.session.sourcePicker.state!!;val before=f.provider.file(f.native).readBytes()
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            f.provider.beforeRead={uri->if(uri==f.native) {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}}
            try {
                assertTrue(f.session.sourcePicker.activatePages(choice));assertTrue(entered.await(5,TimeUnit.SECONDS))
                val workers=f.session.viewModelScope.coroutineContext[Job]!!.children.toList()
                if(token!=null) assertTrue(f.session.editConnectedNode(token,f.session.connectedDraft!!.node!!.copy(kind=NodeKind.TEXT,text="New kind")))
                else assertTrue(f.session.operate {it.delete("other")})
                assertNull(f.session.sourcePicker.state)
                f.provider.beforeRead=null;release.countDown();await {workers.all {it.isCompleted}}
                assertEquals(0,f.provider.writes(f.native));assertArrayEquals(before,f.provider.file(f.native).readBytes())
                assertFalse(f.session.sourcePicker.receive(request,f.native))
                if(token==null) {f.session.undo();assertNotNull(f.session.document!!.node("other"));assertFalse(f.session.sourcePicker.receive(request,f.native))}
                else assertEquals(NodeKind.TEXT,f.session.connectedDraft!!.node!!.kind)
            } finally {f.provider.beforeRead=null;release.countDown()}
        }
    }

    @Test fun aFullReferenceDraftRejectsTheNextSourceWithoutConsumingItsValidExistingSourcesOrTicket()=scenario {f->
        assertTrue(f.session.beginConnectedNode("r",Pt(600.0,400.0)));val token=f.session.connectedDraft!!.token
        assertTrue(f.session.chooseConnectedKind(token,NodeKind.REFERENCE))
        val draft=f.session.connectedDraft!!;val sources=(0 until 128).map {oldSource.copy(id="existing-$it")}
        val full=draft.copy(node=draft.node!!.copy(text="Full valid draft",sources=sources,primarySource=sources.first().id))
        MindMapDocument(nodes=listOf(full.node!!.copy(parent=null,free=false))).validate()
        // Seed the valid precondition only; all chooser/accept/confirm behavior below is production.
        val field=MapSession::class.java.declaredFields.single {it.name.startsWith("connectedDraft") && androidx.compose.runtime.MutableState::class.java.isAssignableFrom(it.type)}.apply {isAccessible=true}
        @Suppress("UNCHECKED_CAST") val state=field.get(f.session) as androidx.compose.runtime.MutableState<ConnectedNodeDraft?>
        state.value=full
        val request=f.session.beginConnectedSource(token)!!;assertTrue(f.session.sourcePicker.receive(request.id,f.native))
        await {f.session.sourcePicker.state?.let {!it.busy && it.offer!=null}==true}
        val choice=f.session.sourcePicker.state!!;assertTrue(f.session.sourcePicker.chooseFile(choice))
        await {f.session.sourcePicker.state?.let {!it.busy && it.problem!=null}==true}
        assertEquals(full,f.session.connectedDraft);assertEquals(request,f.session.connectedSourceRequest)
        assertTrue(f.session.sourcePicker.cancel(f.session.sourcePicker.state!!));assertTrue(f.session.finishConnectedNode(token))
        assertEquals(sources,f.session.document!!.node(draft.nodeId).sources)
    }

    @Test fun aJsonMapCannotReclassifyKnownNativeIdentityOrItsMonotonicPageContract() {
        for(pages in listOf(false,true)) scenario {f->
            val uri=f.otherMap;val document=MindMapDocument(id=sourceId)
            f.provider.file(uri).writeBytes(MapCodec.encode(document))
            f.storage.registry.register(sourceId,"xnote","Known note",uri.toString(),pageReferences=pages)
            val before=f.storage.registry.readableDatabase.rawQuery("SELECT kind,name FROM documents WHERE id=?",arrayOf(sourceId)).use {it.moveToFirst();it.getString(0) to it.getString(1)}
            assertThrows(com.xnotes.storage.ReferenceContractViolation::class.java) {MapSourceAccess.offer(f.context,uri)}
            val after=f.storage.registry.readableDatabase.rawQuery("SELECT kind,name FROM documents WHERE id=?",arrayOf(sourceId)).use {it.moveToFirst();it.getString(0) to it.getString(1)}
            assertEquals(before,after);assertEquals(sourceId,f.storage.registry.idAt(uri.toString()))
            assertEquals(pages,f.storage.registry.expectationForId(sourceId).requiresPages);assertEquals(0,f.provider.writes(uri))
        }
    }
}
