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
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.storage.NativeSourceSnapshot
import com.xnotes.storage.NotebookStorage
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

/** Real provider, permanent page catalogue, preview renderer, source-list controller and saved history. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapSourcePreviewConsumerTest {
    private class Provider(val root:File):ContentProvider() {
        val writes=mutableMapOf<String,Int>()
        val sourceReadOpens=java.util.concurrent.atomic.AtomicInteger()
        var denySource=false;var denyGraphRead=false;var failGraphWrite=false
        var beforeSourceRead:(()->Unit)?=null
        fun file(uri:Uri)=File(root,requireNotNull(uri.lastPathSegment))
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/octet-stream"
        override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?):Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {addRow(arrayOf(file(uri).name,file(uri).length()))}
        override fun insert(uri:Uri,values:ContentValues?):Uri?=null
        override fun update(uri:Uri,values:ContentValues?,s:String?,a:Array<out String>?)=0
        override fun delete(uri:Uri,s:String?,a:Array<out String>?)=error("Preview never deletes provider files")
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            val writing='w' in mode
            if(uri.lastPathSegment=="graph.xmap") {
                if(writing && failGraphWrite) throw java.io.IOException("Graph provider write failed")
                if(!writing && denyGraphRead) throw SecurityException("Graph read permission lost")
            }
            if(writing) writes[uri.toString()]=(writes[uri.toString()] ?: 0)+1
            else if(uri.lastPathSegment=="source.xnote") {
                sourceReadOpens.incrementAndGet()
                if(denySource) throw SecurityException("Permission lost")
                beforeSourceRead?.invoke()
            }
            return ParcelFileDescriptor.open(file(uri),if(writing) ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_CREATE else ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
    private class Fixture:AutoCloseable {
        val base=RuntimeEnvironment.getApplication()
        val root=File(base.cacheDir,"source-preview-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
        }
        val authority="source-preview-${UUID.randomUUID()}";val provider=Provider(root)
        val native=Uri.parse("content://$authority/document/source.xnote")
        val map=Uri.parse("content://$authority/document/graph.xmap")
        val copy=Uri.parse("content://$authority/document/copy.xmap")
        val storage=NotebookStorage(context)
        val field=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        val previous=field.get(null)
        val codec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
        val sourceModel=Document(mutableListOf(Page(240.0,240.0),Page(240.0,240.0))).apply {enableReferenceAnchors()}
        val sessions=mutableListOf<MapSession>()
        lateinit var session:MapSession;lateinit var panel:SourceListEditor
        lateinit var one:MapSource;lateinit var two:MapSource
        init {
            field.set(null,storage)
            provider.attachInfo(context,ProviderInfo().apply {this.authority=this@Fixture.authority})
            ShadowContentResolver.registerProviderInternal(authority,provider)
            sourceModel.pages[1].items+=stroke(20.0)
            writeNative()
            val offer=MapSourceAccess.offer(context,native)
            val pages=(offer.catalog!!.pages as NativeSourceSnapshot.ReferenceCatalog.Pages.Available).orderedIds
            one=MapSourceAccess.select(context,offer,pages[1]).copy(note="row one")
            two=MapSourceAccess.select(context,MapSourceAccess.offer(context,native),pages[0]).copy(note="row two")
            val concept=MapNode(id="n",parent="r",kind=NodeKind.INK,text="independent body",
                ink=listOf(MapInk(listOf(InkPoint(4.0,5.0,.75,100.0)))),sources=listOf(one,two),primarySource=one.id)
            val graph=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),concept))
            provider.file(map).writeBytes(MapCodec.encode(graph));provider.file(copy).writeBytes(byteArrayOf())
            session=MapSession().also {sessions+=it;it.open(context,map)};await {!session.loading};assertFalse(session.loadFailed)
            panel=SourceListEditor(session,session.sourceEditSnapshot("n")!!)
        }
        fun stroke(x:Double)=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(x,30.0,.6),Sample(x+20,50.0,.8)))
        fun writeNative() {provider.file(native).outputStream().use {codec.write(sourceModel.fixedWriteSnapshot(),it)}}
        fun source(id:String)=session.document!!.node("n").sources.single {it.id==id}
        fun png(doc:MindMapDocument=session.document!!,id:String=one.id):ByteArray {
            val key=requireNotNull(doc.node("n").sources.single {it.id==id}.previewKey)
            return doc.previewAssets.getValue(key).pngBytes()
        }
        fun firstPreview() {
            assertTrue(panel.showPreview(one.id))
            await {source(one.id).previewKey!=null && one.id !in panel.busySourceIds}
            assertEquals(SourceStatus.CURRENT,source(one.id).status)
            assertArrayEquals(byteArrayOf(-119,80,78,71,13,10,26,10),png().copyOfRange(0,8))
        }
        override fun close() {
            provider.beforeSourceRead=null
            if(::panel.isInitialized) panel.close()
            sessions.forEach {s->ViewModelStore().apply {put("map",s);clear()}}
            await {sessions.all {it.viewModelScope.coroutineContext[Job]!!.isCompleted}}
            storage.registry.close();field.set(null,previous);root.deleteRecursively()
        }
    }
    companion object {
        private fun await(condition:()->Boolean) {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12)
            while(System.nanoTime()<deadline) {shadowOf(Looper.getMainLooper()).idle();if(condition()) return;Thread.sleep(1)}
            fail("Real source preview consumer did not settle")
        }
    }
    private fun scenario(block:(Fixture)->Unit) {val f=Fixture();try {block(f)} finally {f.close()}}
    @Test fun visibleSourceGetsAPersistedPreviewCheckKeepsItStaleAndExplicitRefreshSurvivesReopenAndCopy()=scenario {f->
        val body=f.session.document!!.node("n");f.firstPreview();assertFalse(f.session.canUndo)
        val old=f.png();val key=f.source(f.one.id).previewKey
        f.sourceModel.pages[1].items+=f.stroke(170.0);f.writeNative()
        assertTrue(f.panel.check(f.one.id));await {f.source(f.one.id).status==SourceStatus.STALE && f.one.id !in f.panel.busySourceIds}
        assertEquals(key,f.source(f.one.id).previewKey);assertArrayEquals(old,f.png());assertFalse(f.session.canUndo)
        assertTrue(f.panel.refresh(f.one.id));await {f.source(f.one.id).status==SourceStatus.CURRENT && f.one.id !in f.panel.busySourceIds}
        assertFalse(old.contentEquals(f.png()));assertEquals(body.text,f.session.document!!.node("n").text)
        assertEquals(body.ink,f.session.document!!.node("n").ink);assertEquals("row one",f.source(f.one.id).note)
        val current=f.png();f.session.save();await {!f.session.saving};assertFalse(f.session.saveFailed)
        assertEquals(3,org.json.JSONObject(String(f.provider.file(f.map).readBytes())).getInt("version"))
        f.provider.denySource=true
        val reopened=MapSession().also {f.sessions+=it;it.open(f.context,f.map)};await {!reopened.loading}
        assertArrayEquals(current,f.png(reopened.document!!))
        var copied=false;f.session.saveCopy(f.copy) {copied=true};await {!f.session.saving};assertTrue(copied)
        val independent=MapCodec.decode(f.provider.file(f.copy).readBytes())
        assertNotEquals(f.session.document!!.id,independent.id);assertArrayEquals(current,f.png(independent))
        assertEquals(0,f.provider.writes[f.native.toString()] ?: 0)
    }
    @Test fun deletedPermanentPageAndUnavailableSourceKeepTheLastPngAndNeverRetargetAnotherOrdinal()=scenario {f->
        f.firstPreview();val old=f.png();val oldKey=f.source(f.one.id).previewKey
        f.sourceModel.pages.removeAt(1);f.writeNative()
        assertTrue(f.panel.check(f.one.id));await {f.source(f.one.id).status==SourceStatus.DELETED && f.one.id !in f.panel.busySourceIds}
        assertEquals(f.one.pageId,f.source(f.one.id).pageId);assertEquals(oldKey,f.source(f.one.id).previewKey);assertArrayEquals(old,f.png())
        f.provider.denySource=true
        assertTrue(f.panel.refresh(f.one.id));await {f.source(f.one.id).status==SourceStatus.UNAVAILABLE && f.one.id !in f.panel.busySourceIds}
        assertArrayEquals(old,f.png());assertEquals(f.one.pageId,f.source(f.one.id).pageId);assertFalse(f.session.canUndo)
        assertEquals(0,f.provider.writes[f.native.toString()] ?: 0)
    }
    @Test fun aLatePanelNoteOrderingAndPrimaryCallbackPreserveTheActuallyRecheckedPageHint()=scenario {f->
        f.firstPreview();assertEquals(2,f.source(f.one.id).lastKnownPage)
        java.util.Collections.swap(f.sourceModel.pages,0,1);f.writeNative()
        assertTrue(f.panel.check(f.one.id))
        await {f.source(f.one.id).lastKnownPage==1 && f.one.id !in f.panel.busySourceIds}
        assertEquals(f.one.pageId,f.source(f.one.id).pageId)
        // No composition sync occurs: these are callbacks from the row displaying the former hint.
        assertTrue(f.panel.note(f.one.id,"note after reorder"))
        assertEquals(1,f.source(f.one.id).lastKnownPage)
        assertTrue(f.panel.move(f.one.id,1))
        assertEquals(1,f.source(f.one.id).lastKnownPage)
        assertTrue(f.panel.primary(f.two.id))
        assertEquals(1,f.source(f.one.id).lastKnownPage)
        assertEquals(f.one.pageId,f.source(f.one.id).pageId)
        assertEquals("note after reorder",f.source(f.one.id).note)
        assertEquals(listOf(f.two.id,f.one.id),f.session.document!!.node("n").sources.map {it.id})
        assertEquals(f.two.id,f.session.document!!.node("n").primarySource)
    }
    @Test fun aLateDerivedResultPreservesRealBodyNoteOrderingAndPrimaryChangesWithoutConsumingOrClearingHistory()=scenario {f->
        f.firstPreview();f.sourceModel.pages[1].items+=f.stroke(170.0);f.writeNative()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        f.provider.beforeSourceRead={entered.countDown();check(release.await(8,TimeUnit.SECONDS))}
        try {
            assertTrue(f.panel.refresh(f.one.id));assertTrue(entered.await(8,TimeUnit.SECONDS))
            assertTrue(f.session.operate {it.update("n") {n->n.copy(text="late body")}})
            assertTrue(f.panel.note(f.one.id,"late note"));assertTrue(f.panel.move(f.one.id,1));assertTrue(f.panel.primary(f.two.id))
            f.provider.beforeSourceRead=null;release.countDown()
            await {f.one.id !in f.panel.busySourceIds && f.source(f.one.id).status==SourceStatus.CURRENT}
            val node=f.session.document!!.node("n");val png=f.png()
            assertEquals("late body",node.text);assertEquals("late note",f.source(f.one.id).note)
            assertEquals(listOf(f.two.id,f.one.id),node.sources.map {it.id});assertEquals(f.two.id,node.primarySource)
            f.session.undo();assertTrue(f.session.canRedo);assertArrayEquals(png,f.png())
            assertTrue(f.panel.check(f.one.id));await {f.one.id !in f.panel.busySourceIds}
            assertTrue("Derived check must preserve the real redo branch",f.session.canRedo)
            f.session.redo();assertEquals(f.two.id,f.session.document!!.node("n").primarySource);assertArrayEquals(png,f.png())
        } finally {f.provider.beforeSourceRead=null;release.countDown()}
    }
    @Test fun changedRangesAndClosedPanelsRetireTheOldJobWithoutAddingItsNewAssetOrOverwritingTheOldPreview()=scenario {f->
        f.firstPreview();val old=f.png();val oldKey=f.source(f.one.id).previewKey
        f.session.save();await {!f.session.saving}
        f.sourceModel.pages[1].items+=f.stroke(170.0);f.writeNative()
        val before=f.session.viewModelScope.coroutineContext[Job]!!.children.toSet()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        f.provider.beforeSourceRead={entered.countDown();check(release.await(8,TimeUnit.SECONDS))}
        try {
            assertTrue(f.panel.refresh(f.one.id));assertTrue(entered.await(8,TimeUnit.SECONDS))
            val jobs=f.session.viewModelScope.coroutineContext[Job]!!.children.filter {it !in before}.toList()
            val expected=f.session.sourceEditSnapshot("n")!!
            assertTrue(f.session.editSources(expected,expected.node.copy(sources=expected.node.sources.map {
                if(it.id==f.one.id) it.copy(region=Rect(50.0,50.0,80.0,80.0)) else it
            })))
            val keys=f.session.document!!.previewAssets.keys.toSet();f.panel.close()
            f.provider.beforeSourceRead=null;release.countDown();await {jobs.all {it.isCompleted}}
            assertEquals(oldKey,f.source(f.one.id).previewKey);assertArrayEquals(old,f.png())
            assertEquals(keys,f.session.document!!.previewAssets.keys);assertEquals(Rect(50.0,50.0,80.0,80.0),f.source(f.one.id).region)
            assertFalse(f.panel.note(f.one.id,"late disposed callback"))
        } finally {f.provider.beforeSourceRead=null;release.countDown()}
    }
    @Test fun visibleRowsForTheSameNativeFileShareOneActualSourceObservation()=scenario {f->
        f.provider.sourceReadOpens.set(0)
        val baseline=kotlinx.coroutines.runBlocking {MapSourcePreviewBackend.load(f.context,listOf(f.one.target),emptySet())}
        assertEquals(SourceStatus.CURRENT,baseline.single().status)
        val once=f.provider.sourceReadOpens.get();assertTrue(once>0)
        f.provider.sourceReadOpens.set(0)
        assertTrue(f.panel.showPreview(f.one.id));assertTrue(f.panel.showPreview(f.two.id))
        await {listOf(f.one.id,f.two.id).all {f.source(it).previewKey!=null && it !in f.panel.busySourceIds}}
        assertEquals("Both visible cards must use one source snapshot rather than one per row",once,f.provider.sourceReadOpens.get())
        assertTrue(f.png(id=f.one.id).isNotEmpty());assertTrue(f.png(id=f.two.id).isNotEmpty());assertFalse(f.session.canUndo)
    }
    @Test fun aSelfReferenceAndTwoMapsReferencingEachOtherKeepSemanticPreviewBaselinesStable()=semanticCycle(false)
    @Test fun versionFourCardsKeepSelfAndMutualPreviewBaselinesStable()=semanticCycle(true)
    private fun semanticCycle(extended:Boolean)=scenario {f->
        if(extended) {
            assertTrue(f.session.operate {it.addFileCards(listOf(MapFileCardSeed(
                java.util.UUID.randomUUID().toString(),"File card",600.0,300.0))).document})
            f.session.save();await {!f.session.saving}
            assertTrue(MapCodec.semanticPreviewVersion(f.session.document!!).startsWith("xmap-render-v2:"))
        }
        val aId=f.session.document!!.id
        val self=MapSourceAccess.offer(f.context,f.map).fileSource
        val back=MapSource(documentId=aId,fileName="graph.xmap",sourceVersion=self.sourceVersion)
        val other=MindMapDocument(nodes=listOf(MapNode(id="b",parent=null,text="Other map",sources=listOf(back))))
        f.provider.file(f.copy).writeBytes(MapCodec.encode(other))
        val ref=MapSourceAccess.offer(f.context,f.copy).fileSource
        assertTrue(f.session.operate {it.update("n") {n->n.copy(sources=n.sources+listOf(self,ref))}})
        f.panel.sync(f.session.sourceEditSnapshot("n")!!);f.session.save();await {!f.session.saving}
        assertTrue(f.panel.refresh(self.id));await {self.id !in f.panel.busySourceIds && f.source(self.id).lastPreview!=null}
        assertTrue(f.panel.refresh(ref.id));await {ref.id !in f.panel.busySourceIds && f.source(ref.id).lastPreview!=null}
        f.session.save();await {!f.session.saving}
        val b=MapSession().also {f.sessions+=it;it.open(f.context,f.copy)};await {!b.loading}
        val panel=SourceListEditor(b,b.sourceEditSnapshot("b")!!)
        try {
            assertTrue(panel.refresh(back.id));await {back.id !in panel.busySourceIds && b.document!!.root.sources.single().lastPreview!=null}
            b.save();await {!b.saving}
            val selfKey=f.source(self.id).previewKey;val otherKey=f.source(ref.id).previewKey
            repeat(3) {
                assertTrue(f.panel.check(self.id));await {self.id !in f.panel.busySourceIds}
                assertTrue(f.panel.check(ref.id));await {ref.id !in f.panel.busySourceIds}
                assertTrue(panel.check(back.id));await {back.id !in panel.busySourceIds}
                assertEquals(SourceStatus.CURRENT,f.source(self.id).status);assertEquals(SourceStatus.CURRENT,f.source(ref.id).status)
                assertEquals(SourceStatus.CURRENT,b.document!!.root.sources.single().status)
                assertEquals(selfKey,f.source(self.id).previewKey);assertEquals(otherKey,f.source(ref.id).previewKey)
            }
            val revision=f.session.contentRevision
            assertTrue(f.panel.check(self.id));await {self.id !in f.panel.busySourceIds}
            assertEquals("Self/cache metadata changes cannot create a preview refresh loop",revision,f.session.contentRevision)
            assertTrue(f.session.operate {it.update("n") {n->n.copy(text="real body change")}})
            f.session.save();await {!f.session.saving}
            assertTrue(f.panel.check(self.id));await {self.id !in f.panel.busySourceIds && f.source(self.id).status==SourceStatus.STALE}
            assertEquals(selfKey,f.source(self.id).previewKey)
            assertTrue(f.panel.refresh(self.id));await {self.id !in f.panel.busySourceIds && f.source(self.id).status==SourceStatus.CURRENT}
            f.session.save();await {!f.session.saving}
            assertTrue(f.panel.check(self.id));await {self.id !in f.panel.busySourceIds}
            assertEquals(SourceStatus.CURRENT,f.source(self.id).status)
        } finally {panel.close()}
    }

    @Test fun failedCloseOrFileVersionReadKeepsTheGraphAndLetsANewPanelRefreshWithoutAContentEdit() {
        for(failure in listOf("close write","use file read")) scenario {f->
            f.firstPreview()
            val before=f.session.document!!;val body=before.node("n");val png=f.png();var closed=false
            if(failure=="close write") {
                f.provider.failGraphWrite=true
                f.session.save(onClosed={closed=true})
                await {!f.session.saving && !f.session.closing}
                assertFalse(closed)
            } else {
                f.provider.denyGraphRead=true
                f.session.useFileVersion()
                await {!f.session.saving && !f.session.replacingDocument}
            }
            assertTrue("Must fail the real graph provider operation: $failure",f.session.saveFailed)
            assertEquals(before,f.session.document);assertArrayEquals(png,f.png())
            f.provider.failGraphWrite=false;f.provider.denyGraphRead=false
            f.panel.close();f.panel=SourceListEditor(f.session,f.session.sourceEditSnapshot("n")!!)
            assertTrue("A failed $failure must not permanently retire every source lifetime",f.panel.refresh(f.one.id))
            await {f.one.id !in f.panel.busySourceIds}
            assertEquals(SourceStatus.CURRENT,f.source(f.one.id).status)
            assertEquals(body.text,f.session.document!!.node("n").text);assertEquals(body.ink,f.session.document!!.node("n").ink)
            assertArrayEquals(png,f.png());assertFalse(f.session.canUndo)
        }
    }

    @Test fun droppedRawBodyItemsFailRefreshAndKeepThePreviousPngAndOriginalBytes()=scenario {f->
        val expected=f.session.sourceEditSnapshot("n")!!
        assertTrue(f.session.editSources(expected,expected.node.copy(sources=expected.node.sources.map {
            if(it.id==f.one.id) it.copy(region=Rect(0.0,0.0,120.0,120.0)) else it
        })))
        f.firstPreview();val descriptor=f.source(f.one.id).lastPreview;val png=f.png()
        for(item in listOf(org.json.JSONObject().put("kind","future-kind"),
            org.json.JSONObject().put("kind","image").put("asset","assets/missing.png"))) {
            f.writeNative();val file=f.provider.file(f.native)
            val entries=java.util.zip.ZipFile(file).use {zip->zip.entries().asSequence().filterNot {it.isDirectory}
                .associateTo(linkedMapOf()) {it.name to zip.getInputStream(it).use {input->input.readBytes()}}}
            val manifest=org.json.JSONObject(String(entries.getValue("manifest.json")))
            manifest.getJSONArray("pages").getJSONObject(1).getJSONArray("items").put(item)
            entries["manifest.json"]=manifest.toString().toByteArray()
            java.util.zip.ZipOutputStream(file.outputStream()).use {zip->entries.forEach {(name,bytes)->
                zip.putNextEntry(java.util.zip.ZipEntry(name));zip.write(bytes);zip.closeEntry()
            }}
            val original=file.readBytes();assertTrue(f.panel.refresh(f.one.id));await {f.one.id !in f.panel.busySourceIds}
            assertEquals(SourceStatus.UNAVAILABLE,f.source(f.one.id).status);assertTrue(f.source(f.one.id).hasHistoricalPreview)
            assertEquals(descriptor,f.source(f.one.id).lastPreview);assertArrayEquals(png,f.png())
            assertArrayEquals(original,file.readBytes());assertEquals(0,f.provider.writes[f.native.toString()] ?: 0)
            assertTrue(File(f.context.cacheDir,"source-preview-assets").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun sameInsidePageRangeBecomesEmptyAndKeepsAnExplicitHistoricalPngAcrossReopen()=scenario {f->
        val expected=f.session.sourceEditSnapshot("n")!!
        assertTrue(f.session.editSources(expected,expected.node.copy(sources=expected.node.sources.map {
            if(it.id==f.one.id) it.copy(region=Rect(0.0,0.0,120.0,120.0)) else it
        })))
        f.firstPreview();val target=f.source(f.one.id).target;val descriptor=f.source(f.one.id).lastPreview;val png=f.png()
        f.sourceModel.pages[1].items.clear();f.writeNative()
        assertTrue(f.panel.refresh(f.one.id));await {f.one.id !in f.panel.busySourceIds}
        assertEquals(SourceStatus.EMPTY,f.source(f.one.id).status);assertEquals(target,f.source(f.one.id).target)
        assertEquals(descriptor,f.source(f.one.id).lastPreview);assertArrayEquals(png,f.png());assertTrue(f.source(f.one.id).hasHistoricalPreview)
        f.session.save();await {!f.session.saving};assertFalse(f.session.saveFailed)
        val reopened=MapSession().also {f.sessions+=it;it.open(f.context,f.map)};await {!reopened.loading}
        f.panel.close();f.session=reopened;f.panel=SourceListEditor(reopened,reopened.sourceEditSnapshot("n")!!)
        assertEquals(SourceStatus.EMPTY,f.source(f.one.id).status);assertArrayEquals(png,f.png());assertTrue(f.source(f.one.id).hasHistoricalPreview)
        assertEquals(0,f.provider.writes[f.native.toString()] ?: 0)
    }

    @Test fun explicitEmptyRangeProofKeepsTheLastPngAcrossReopenAndExpiresOnSourceOrTargetChanges()=scenario {f->
        f.firstPreview();val oldPng=f.png();val oldDescriptor=f.source(f.one.id).lastPreview
        fun range(rect:Rect) {
            val expected=f.session.sourceEditSnapshot("n")!!
            assertTrue(f.session.editSources(expected,expected.node.copy(sources=expected.node.sources.map {
                if(it.id==f.one.id) it.copy(region=rect) else it
            })))
        }
        range(Rect(900.0,900.0,50.0,50.0))
        assertTrue(f.panel.refresh(f.one.id));await {f.one.id !in f.panel.busySourceIds}
        assertEquals("Explicit refresh can prove an empty region without replacing its previous PNG",SourceStatus.EMPTY,f.source(f.one.id).status)
        assertEquals(oldDescriptor,f.source(f.one.id).lastPreview);assertArrayEquals(oldPng,f.png())
        f.session.save();await {!f.session.saving};assertFalse(f.session.saveFailed)
        val reopened=MapSession().also {f.sessions+=it;it.open(f.context,f.map)};await {!reopened.loading}
        f.panel.close();f.session=reopened;f.panel=SourceListEditor(reopened,reopened.sourceEditSnapshot("n")!!)
        assertEquals(SourceStatus.EMPTY,f.source(f.one.id).status);assertArrayEquals(oldPng,f.png())
        assertTrue(f.panel.check(f.one.id));await {f.one.id !in f.panel.busySourceIds}
        assertEquals("An unchanged reopened proof stays EMPTY even though its last image depicts the old range",SourceStatus.EMPTY,f.source(f.one.id).status)

        f.sourceModel.pages[1].items+=f.stroke(170.0);f.writeNative()
        assertTrue(f.panel.check(f.one.id));await {f.one.id !in f.panel.busySourceIds}
        assertEquals("The old empty proof cannot authorize a changed file version",SourceStatus.STALE,f.source(f.one.id).status)
        assertArrayEquals(oldPng,f.png())
        assertTrue(f.panel.refresh(f.one.id));await {f.one.id !in f.panel.busySourceIds}
        assertEquals(SourceStatus.EMPTY,f.source(f.one.id).status)
        range(Rect(1100.0,1100.0,50.0,50.0))
        assertEquals("Another range does not inherit the old EMPTY proof",SourceStatus.STALE,f.source(f.one.id).status)
        assertTrue(f.panel.check(f.one.id));await {f.one.id !in f.panel.busySourceIds}
        assertEquals(SourceStatus.STALE,f.source(f.one.id).status)
        range(Rect(0.0,0.0,220.0,220.0))
        assertTrue(f.panel.refresh(f.one.id));await {f.one.id !in f.panel.busySourceIds}
        assertEquals(SourceStatus.CURRENT,f.source(f.one.id).status)
        assertEquals(f.source(f.one.id).target,f.source(f.one.id).lastPreview!!.target)
        assertTrue(f.png().isNotEmpty());assertEquals(0,f.provider.writes[f.native.toString()] ?: 0)
    }

}
