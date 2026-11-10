package com.xnotes.pen

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.provider.DocumentsContract
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.AddItem
import com.xnotes.core.infinite.AddCanvasItem
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.*
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.mindmap.*
import com.xnotes.persistNativeForMapPresentation
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.requestPagedRegionReference
import com.xnotes.storage.*
import com.xnotes.ui.Editor
import com.xnotes.ui.NativeReferenceResult
import com.xnotes.ui.Pane
import com.xnotes.ui.theme.Palette
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual map actors, native note owners and source/flow controllers. Host rebinding is runtime
 * configuration coverage, not Activity/Surface/GPU acceptance. Only original PDF pixels are fake. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapMixedSourceNavigationHostTest {
    private class Fixture(val native:EditorPageCopyFixture,val dirtyNote:Boolean=true,val mapPane:Pane=Pane.SECONDARY,val canvasAtTarget:Boolean=false) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val maps=MapWorkspace(native.context,scope);val networks=FileNetworkWorkspace(native.context,scope)
        var binding:Any=Any()
        val layout=MapSplitLayout(mapPane,mapPane,.37f,true,false)
        val originalProvider=AppStorageDocumentsProvider();val notes=AppStorageDocumentsProvider.rootDir(native.context)
        val folder=File(notes,"mixed-map-${UUID.randomUUID()}").apply {mkdirs()}
        val tree=AppStorageDocumentsProvider.treeUri(native.context)
        val a=File(folder,"A.xmap");val b=File(folder,"B.xmap")
        val aId=UUID.randomUUID().toString();val bId=UUID.randomUUID().toString()
        val aSource=MapSource(documentId=bId,fileName="B.xmap")
        val mapWrites=AtomicInteger();var rejectMapWrites=false
        lateinit var note:Editor
        lateinit var noteModel:Document
        lateinit var noteHistory:com.xnotes.core.history.History
        var noteUndo:Any?=null;var noteRevision=0L
        lateinit var noteBytes:ByteArray
        lateinit var targetModel:Document
        lateinit var targetSource:MapSource
        lateinit var noteSource:MapSource
        lateinit var canvasSource:MapSource
        lateinit var originalSource:MapSource
        val canvas=InfiniteDocument()
        lateinit var canvasBytes:ByteArray
        lateinit var aEntry:MapWorkspace.Entry
        lateinit var bEntry:MapWorkspace.Entry
        lateinit var sources:MapSourceNavigator
        lateinit var originalNavigator:OriginalPdfNavigator
        var opened:NativeReferenceResult.Opened?=null
        var guard:suspend ()->MapSourceGuardResult={MapSourceGuardResult.Ready(emptySet())}
        val catalog=OriginalPdfCatalog(native.storage.registry)
        val pdf=File(native.root,"mixed-original.pdf").apply {writeText("controlled immutable original PDF")}
        lateinit var record:OriginalPdfCatalog.Record
        val renders=AtomicInteger()
        val access=OriginalPdfAccess(native.context,engineFactory={object:OriginalPdfEngine {
            override val pageCount=2
            override fun pageInfo(pdfKey:Int)=OriginalPdfPageInfo(Pt(600.0,800.0),PdfPageGeometry.flipped(800.0))
            override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):AndroidRasterSurface {
                renders.incrementAndGet();return AndroidRasterSurface.create(width,height).apply {bitmap.eraseColor(Color.WHITE)}
            }
            override fun closeAndAwait() {}
        }},storageOverride=native.storage)
        val originals=OriginalPdfWorkspace(native.context,scope,access)
        val readers=mutableListOf<OriginalPdfReader>();val flows=mutableListOf<PageToMapController>();val extra=mutableListOf<MapDocumentLease>()
        var release:CountDownLatch?=null
        var closed:Job?=null
        fun uri(file:File)=DocumentsContract.buildDocumentUriUsingTree(tree,"root/"+file.relativeTo(notes).invariantSeparatorsPath)
        val retryUri get()=DocumentsContract.buildDocumentUri(native.authority,"retry").toString()
        val canvasFile get()=if(canvasAtTarget) native.target else native.retry
        val canvasUri get()=if(canvasAtTarget) native.targetUri else retryUri
        val newNoteFile get()=if(canvasAtTarget) native.retry else native.target
        val newNoteUri get()=if(canvasAtTarget) retryUri else native.targetUri
        private val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri:Uri)="application/octet-stream"
            override fun query(uri:Uri,p:Array<out String>?,s:String?,args:Array<out String>?,sort:String?):Cursor {
                @Suppress("UNCHECKED_CAST") val columns=p as? Array<String>
                return originalProvider.queryDocument(DocumentsContract.getDocumentId(uri),columns)
            }
            override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
                val file=File(notes,DocumentsContract.getDocumentId(uri).removePrefix("root/"))
                if('w' in mode) {mapWrites.incrementAndGet();if(rejectMapWrites) throw IOException("Controlled map publication failure")}
                return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode))
            }
            override fun insert(uri:Uri,v:ContentValues?):Uri?=null
            override fun update(uri:Uri,v:ContentValues?,s:String?,args:Array<out String>?)=0
            override fun delete(uri:Uri,s:String?,args:Array<out String>?):Int=error("Mixed navigation cannot delete sources")
        }
        fun stroke(x:Double)=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(x,100.0,.5),Sample(x+60.0,150.0,.7)))
        fun stopDebounce(editor:Editor=note) {
            for(name in listOf("noteDebounceJob","canvasDebounceJob"))
                (Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(editor) as? Job)?.cancel()
        }
        suspend fun start() {
            originalProvider.attachInfo(native.context,ProviderInfo().apply {
                authority=tree.authority;exported=true;grantUriPermissions=true
                readPermission="android.permission.MANAGE_DOCUMENTS";writePermission=readPermission
            })
            provider.attachInfo(native.context,ProviderInfo().apply {authority=tree.authority})
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(requireNotNull(tree.authority),provider)
            withContext(Dispatchers.IO) {
                native.seed();native.seed(newNoteFile)
                val saved=native.source.inputStream().use {native.codec.read(it)}
                saved.pages[1].items+=stroke(50.0)
                native.source.outputStream().use {native.codec.write(saved.fixedWriteSnapshot(),it)}
                targetModel=newNoteFile.inputStream().use {native.codec.read(it)}
                canvasFile.outputStream().use {CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(canvas,it)}
                canvasBytes=canvasFile.readBytes()
                native.storage.registry.register(targetModel.identity.documentId,"xnote","New target",newNoteUri,pageReferences=true)
                native.storage.registry.register(canvas.identity.documentId,"xcanvas","Sketch",canvasUri,pageReferences=false)
                val candidate=access.candidate(pdf,"Original")
                try {record=(catalog.retain(candidate) as OriginalPdfCatalog.Retention.Retained).observation.record}
                finally {candidate.closeAndAwait()}
            }
            withContext(Dispatchers.Main) {
                note=if(layout.nativePane==Pane.PRIMARY) native.editor else native.editor.secondaryPane()
                note.openAsync(native.sourceUri,"source.xnote");note.view.layout(0,0,1000,800)
                noteModel=note.state.document;noteHistory=note.history
                if(dirtyNote) {val s=stroke(230.0);noteModel.pages[0].items+=s;noteHistory.push(AddItem(noteModel.pages[0],s));noteModel.dirty=true}
                stopDebounce();noteUndo=noteHistory.nextUndo;noteRevision=noteModel.bodyRevision
                native.editor.originalPdfAccess=access
                noteSource=MapSource(documentId=noteModel.identity.documentId,fileName="Current note",scope=SourceScope.PAGE,
                    pageId=noteModel.pages[1].referenceIdentity.id,region=Rect(30.0,40.0,100.0,90.0))
                targetSource=MapSource(documentId=targetModel.identity.documentId,fileName="New note",scope=SourceScope.PAGE,
                    pageId=targetModel.pages[1].referenceIdentity.id)
                canvasSource=MapSource(documentId=canvas.identity.documentId,fileName="Canvas")
                originalSource=MapSource(documentId=record.id,fileName="Original",scope=SourceScope.PDF_ORIGINAL,pageId=record.pages[1],
                    originalPdf=PdfOriginalProjection(record.sha256,1,userRegion=Rect(20.0,30.0,80.0,90.0)))
            }
            noteBytes=native.source.readBytes()
            withContext(Dispatchers.IO) {
                a.writeBytes(MapCodec.encode(MindMapDocument(id=aId,nodes=listOf(MapNode(id="ar",parent=null),
                    MapNode(id="an",parent="ar",text="A body",sources=listOf(aSource),primarySource=aSource.id)))))
                b.writeBytes(MapCodec.encode(MindMapDocument(id=bId,nodes=listOf(MapNode(id="br",parent=null),MapNode(id="bn",parent="br",text="B body",
                    sources=listOf(noteSource,targetSource,canvasSource,originalSource),primarySource=noteSource.id)))))
                native.storage.registry.registerMapObserved(aId,a.name,uri(a).toString());native.storage.registry.registerMapObserved(bId,b.name,uri(b).toString())
            }
            withContext(Dispatchers.Main) {
                bind();createNavigators()
                assertTrue(maps.open(uri(a),"A",presentation=MapOpenPresentation.Split(layout),flushNativeOnPresent=false,
                    preparePresentation={native.editor.awaitMapSourceParking()?.let {proof->({proof.current()})}}))
            }
            await {!maps.opening && maps.visible}
            withContext(Dispatchers.Main) {aEntry=maps.current!!;aEntry.presentation.synchronize(aEntry.session)}
        }
        fun bind() {maps.bindHost(binding);networks.bindHost(binding);originals.bindHost(binding)}
        fun createNavigators() {
            originalNavigator=OriginalPdfNavigator(native.editor,maps,networks,originals,binding,scope)
            sources=MapSourceNavigator(native.editor,maps,binding,scope,guard={_,_->guard()},originalNavigator=originalNavigator,onOpened={result,_->opened=result})
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        fun frame(entry:MapWorkspace.Entry,node:String,dx:Float=51f,dy:Float=-29f):MindMapView.Viewport {
            entry.presentation.synchronize(entry.session);entry.session.selected=node
            val view=MindMapView(native.context).apply {document=entry.session.document!!;documentEpoch=entry.session.interactionEpoch
                layout(0,0,700,800);fit();zoomBy(1.2f);panText(dx,dy)}
            assertTrue(entry.presentation.showSources(entry.session,view,node));return view.viewport()
        }
        fun open(entry:MapWorkspace.Entry,node:String,source:MapSource):Boolean {
            val ticket=entry.session.captureSourceNavigation(node,source.id,source.target)!!
            return sources.open(entry,ticket,entry.session.captureSourceReturn(ticket)!!)
        }
        suspend fun toB() {
            withContext(Dispatchers.Main) {frame(aEntry,"an");assertTrue(open(aEntry,"an",aSource))};await {!sources.busy}
            withContext(Dispatchers.Main) {bEntry=maps.current!!;assertEquals("B open: ${sources.failure}",bId,bEntry.session.document!!.id);assertTrue(maps.visible)}
        }
        fun noteUnchanged() {
            assertTrue(note.noteOpen);assertFalse(note.canvasOpen);assertSame(noteModel,note.state.document)
            assertSame(noteHistory,note.history);assertSame(noteUndo,note.history.nextUndo);assertEquals(noteRevision,noteModel.bodyRevision)
            assertEquals(native.sourceUri,noteModel.path);assertEquals(dirtyNote,noteModel.dirty)
            assertArrayEquals(noteBytes,native.source.readBytes());assertEquals(0,native.writes.get());assertEquals(0,native.writeOpens.get())
        }
        fun assertReturned(entry:MapWorkspace.Entry,node:String,source:MapSource,camera:MindMapView.Viewport) {
            assertSame(entry,maps.current);assertTrue(maps.visible);assertEquals(node,entry.session.selected)
            assertEquals(source.id,entry.presentation.sourceFocus?.sourceId)
            val view=MindMapView(native.context).apply {document=entry.session.document!!;documentEpoch=entry.session.interactionEpoch;layout(0,0,700,800);fit()}
            assertTrue(entry.presentation.restore(entry.session,view))
            assertEquals(camera.zoom,view.viewport().zoom,1e-5f);assertEquals(camera.panX,view.viewport().panX,1e-4f);assertEquals(camera.panY,view.viewport().panY,1e-4f)
        }
        fun close(entry:MapWorkspace.Entry=maps.current!!)=sources.closeEntry(entry) {
            sources.retire();maps.closeToHome(entry)?.also {closed=it}!=null
        }
        fun leases()=native.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases WHERE resource_id=?",arrayOf(record.sha256)).use {assertTrue(it.moveToFirst());it.getInt(0)}
        suspend fun finish()=withContext(NonCancellable) {
            release?.countDown();native.beforeTargetRead=null;native.denyTargetRead=false;rejectMapWrites=false
            withContext(Dispatchers.Main) {readers.forEach {it.retire()};if(::sources.isInitialized) sources.dispose();if(::originalNavigator.isInitialized) originalNavigator.dispose()}
            withContext(Dispatchers.IO) {readers.forEach {it.closeAndAwait()}}
            withContext(Dispatchers.Main) {flows.forEach {it.close()?.join()};originals.dispose()}.join()
            withContext(Dispatchers.Main) {networks.dispose();maps.dispose()}.join();closed?.join()
            val jobs=withContext(Dispatchers.Main) {extra.map {it.close()}};jobs.forEach {it.join()}
            scope.cancel();scope.coroutineContext[Job]?.join();withContext(Dispatchers.IO) {folder.deleteRecursively()}
        }
    }
    private fun scenario(dirtyNote:Boolean=true,mapPane:Pane=Pane.SECONDARY,canvasAtTarget:Boolean=false,test:suspend(Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native,dirtyNote,mapPane,canvasAtTarget);try {f.start();test(f)} finally {f.finish()}
    }
    @Test fun aMapChildAndItsCloseKeepTheSameActualNoteInItsPhysicalSlot()=scenario {f->
        val originalLayout=withContext(Dispatchers.Main) {f.maps.splitPresentation.layout!!}
        f.toB()
        withContext(Dispatchers.Main) {
            assertEquals(originalLayout,f.maps.splitPresentation.layout);f.noteUnchanged()
            assertTrue(f.bEntry.session.operate {it.update("bn") {n->n.copy(text="B independently edited")}})
            f.bEntry.session.cancelPendingAutosave();assertTrue(f.bEntry.session.canUndo);assertTrue(f.note.history.canUndo)
            assertTrue(f.close(f.bEntry))
        }
        f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {
            assertSame(f.aEntry,f.maps.current);assertTrue(f.maps.visible);assertTrue(f.bEntry.lease.alive)
            assertEquals(originalLayout,f.maps.splitPresentation.layout);f.noteUnchanged()
            assertEquals("B independently edited",f.bEntry.session.document!!.node("bn").text)
        }
    }
    @Test fun anAlreadyOpenNotePageIsLocatedInPlaceWhileTheMapStaysVisible()=scenario {f->
        f.toB();val before=f.maps.splitPresentation.layout!!
        val camera=withContext(Dispatchers.Main) {f.frame(f.bEntry,"bn",73f,-41f)}
        withContext(Dispatchers.Main) {assertTrue(f.open(f.bEntry,"bn",f.noteSource))};f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {
            assertNotNull("PAGE open: ${f.sources.failure}",f.opened);assertSame(f.note,f.opened!!.pane)
            assertTrue(f.maps.visible);assertSame(f.bEntry,f.maps.current);assertNotNull(f.bEntry.sourceReturn)
            assertEquals(before.copy(focusedPane=before.nativePane),f.maps.splitPresentation.layout)
            f.noteUnchanged();assertTrue(f.sources.returnToMap())
        }
        f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {
            f.assertReturned(f.bEntry,"bn",f.noteSource,camera)
            assertEquals(before.copy(focusedPane=before.mapPane),f.maps.splitPresentation.layout);f.noteUnchanged()
        }
    }
    @Test fun aNewNoteReadFailureGuardCancellationAndLateIoCancellationKeepBothMixedSlots()=scenario {f->
        f.toB();withContext(Dispatchers.Main) {f.frame(f.bEntry,"bn")}
        val before=f.maps.splitPresentation.layout;val model=f.bEntry.session.document
        f.native.denyTargetRead=true;f.guard={MapSourceGuardResult.Ready(setOf(f.note.discardForOpen()))}
        withContext(Dispatchers.Main) {assertTrue(f.open(f.bEntry,"bn",f.targetSource))};f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {assertNull(f.opened);assertEquals(before,f.maps.splitPresentation.layout);assertSame(model,f.bEntry.session.document);f.noteUnchanged()}
        f.native.denyTargetRead=false;f.guard={MapSourceGuardResult.Cancelled}
        withContext(Dispatchers.Main) {assertTrue(f.open(f.bEntry,"bn",f.targetSource))};f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {assertNull(f.opened);assertEquals(before,f.maps.splitPresentation.layout);f.noteUnchanged()}
        val entered=CountDownLatch(1);val release=CountDownLatch(1);f.release=release
        f.guard={MapSourceGuardResult.Ready(setOf(f.note.discardForOpen()))}
        f.native.beforeTargetRead={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        withContext(Dispatchers.Main) {assertTrue(f.open(f.bEntry,"bn",f.targetSource))}
        assertTrue(withContext(Dispatchers.IO) {entered.await(5,TimeUnit.SECONDS)})
        withContext(Dispatchers.Main) {f.sources.cancel()};f.native.beforeTargetRead=null;release.countDown()
        f.await {f.scope.coroutineContext[Job]!!.children.none()}
        withContext(Dispatchers.Main) {assertNull(f.opened);assertSame(f.bEntry,f.maps.current);assertTrue(f.maps.visible);assertEquals(before,f.maps.splitPresentation.layout);f.noteUnchanged()}
    }
    @Test fun aPageRegionImportsIntoTheAlreadyOpenActorAndFailedSaveRetriesWithoutDuplicatingTheSourceThenColdOpens()=scenario(dirtyNote=false) {f->
        val flow=withContext(Dispatchers.Main) {
            f.note.controller.selectAll();val capture=f.note.sourceSelectionRegion()!!
            assertSame(f.noteModel.pages[1],capture.page);assertTrue(requestPagedRegionReference(f.note,capture.document,capture))
            PageToMapController(f.native.context,f.note.pageReferenceRequest!!,f.scope).also {f.flows+=it;assertTrue(it.start())}
        }
        f.await {flow.step==PageToMapStep.DESTINATION}
        withContext(Dispatchers.Main) {assertTrue(flow.selectOpenMap(f.aEntry) {f.maps.current===f.aEntry && f.maps.splitPresentation.layout!=null})}
        f.await {flow.step==PageToMapStep.TARGET}
        val concurrent=MapSource(documentId=UUID.randomUUID().toString(),fileName="Concurrent other source")
        withContext(Dispatchers.Main) {
            assertSame(f.aEntry.session,flow.targetSession);assertTrue(flow.chooseExistingNode("an"))
            assertTrue(f.aEntry.session.operate {it.update("an") {n->n.copy(text="Latest concurrent body",sources=n.sources+concurrent)}})
            f.aEntry.session.cancelPendingAutosave();f.rejectMapWrites=true;assertTrue(flow.confirm())
        }
        f.await {flow.step==PageToMapStep.SAVE_FAILED}
        val imported=withContext(Dispatchers.Main) {
            val n=f.aEntry.session.document!!.node("an");assertEquals("Latest concurrent body",n.text)
            assertEquals(listOf(f.aSource,concurrent),n.sources.take(2));assertEquals(f.aSource.id,n.primarySource)
            assertEquals(3,n.sources.size);assertEquals(SourceScope.PAGE,n.sources.last().scope)
            assertEquals(f.noteModel.pages[1].referenceIdentity.id,n.sources.last().pageId);assertNotNull(n.sources.last().region)
            n.sources.last().also {f.rejectMapWrites=false;assertTrue(flow.retrySave())}
        }
        f.await {flow.step==PageToMapStep.ADDED}
        withContext(Dispatchers.Main) {
            assertSame(f.aEntry.session,flow.targetSession);assertEquals(1,f.aEntry.session.document!!.node("an").sources.count {it.id==imported.id})
            f.aEntry.session.undo();assertEquals(listOf(f.aSource,concurrent),f.aEntry.session.document!!.node("an").sources)
            f.aEntry.session.redo();f.aEntry.session.cancelPendingAutosave();assertTrue(f.aEntry.session.saveAttempt().await() is MapSaveOutcome.Written)
            flow.close()?.join()
        }
        val old=f.aEntry.session
        withContext(Dispatchers.Main) {f.maps.close(f.aEntry)}!!.join()
        f.await {MapDocuments.observations(f.native.context).none {it.key==MapSaves.key(f.uri(f.a))}}
        val cold=withContext(Dispatchers.Main) {MapDocuments.acquire(f.native.context,f.uri(f.a)).also(f.extra::add)}
        f.await {!cold.session.loading}
        withContext(Dispatchers.Main) {
            assertNotSame(old,cold.session);assertEquals(imported,cold.session.document!!.node("an").sources.last())
            assertEquals("Latest concurrent body",cold.session.document!!.node("an").text);assertFalse(cold.session.canUndo)
            f.noteUnchanged()
        }
    }
    @Test fun aFailedRootCloseLeavesTheMixedSlotEditableThenSuccessShowsHomeAndClosingHomeRemovesOnlyThatSlot()=scenario {f->
        val before=f.maps.splitPresentation.layout!!;f.rejectMapWrites=true
        withContext(Dispatchers.Main) {
            assertTrue(f.aEntry.session.operate {it.update("an") {n->n.copy(text="Root must remain")}});f.aEntry.session.cancelPendingAutosave()
            assertFalse(f.maps.removeSplitHome(before.mapPane));assertTrue(f.close())
        }
        f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {
            assertSame(f.aEntry,f.maps.current);assertTrue(f.maps.visible);assertEquals(before,f.maps.splitPresentation.layout)
            assertTrue(f.aEntry.session.dirty);assertFalse(f.aEntry.session.closing);f.noteUnchanged()
            assertTrue(f.aEntry.session.operate {it.update("an") {n->n.copy(text="Editable after failure")}})
            f.aEntry.session.cancelPendingAutosave();f.rejectMapWrites=false;assertTrue(f.close())
        }
        f.await {f.maps.current==null && !f.sources.busy};f.closed!!.join()
        withContext(Dispatchers.Main) {
            assertEquals(before,f.maps.splitPresentation.layout);assertFalse(f.maps.visible);f.noteUnchanged()
            assertTrue(f.maps.removeSplitHome(before.mapPane));assertNull(f.maps.splitPresentation.layout);f.noteUnchanged()
        }
        assertEquals("Editable after failure",MapCodec.decode(f.a.readBytes()).node("an").text)
    }
    @Test fun originalReaderParkingRestoresTheSameActualNoteOwnerAndItsMixedLayout()=scenario {f->
        f.toB();val before=f.maps.splitPresentation.layout!!
        val camera=withContext(Dispatchers.Main) {f.frame(f.bEntry,"bn")}
        withContext(Dispatchers.Main) {assertTrue(f.open(f.bEntry,"bn",f.originalSource))};f.await {!f.sources.busy && f.originals.visible}
        val shown=withContext(Dispatchers.Main) {f.originals.current!!}
        val reader=withContext(Dispatchers.Main) {
            val retained=shown.retainReader()!!
            OriginalPdfReader(f.native.context,shown,shown.pageIndex,shown.read.engine.pageInfo(1),Palette.DEFAULT,retained).also {r->
                f.readers+=r;r.view.layout(0,0,600,700)
                val bitmap=Bitmap.createBitmap(600,700,Bitmap.Config.ARGB_8888);try {r.view.draw(Canvas(bitmap))} finally {bitmap.recycle()}
            }
        }
        f.await {f.renders.get()>0}
        withContext(Dispatchers.Main) {
            assertFalse(f.maps.visible);assertNull(f.maps.splitPresentation.layout);f.noteUnchanged()
            assertEquals(f.originalSource.target,shown.target);assertEquals(1,f.leases());assertTrue(f.sources.returnToMap())
        }
        f.await {!f.sources.busy && f.maps.visible}
        withContext(Dispatchers.Main) {
            assertEquals(before,f.maps.splitPresentation.layout);f.noteUnchanged();f.assertReturned(f.bEntry,"bn",f.originalSource,camera)
            persistNativeForMapPresentation(f.native.editor,f.bEntry);f.noteUnchanged();reader.retire()
        }
        withContext(Dispatchers.IO) {reader.closeAndAwait()};f.await {f.leases()==0}
        withContext(Dispatchers.Main) {assertTrue(f.close(f.bEntry))};f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {assertSame(f.aEntry,f.maps.current);assertEquals(before,f.maps.splitPresentation.layout);f.noteUnchanged()}
    }
    @Test fun canvasUsesFullWindowProtectionAndReturnsToMapPlusActualCanvasHomeWithoutRevivingTheClosedNote()=scenario(mapPane=Pane.PRIMARY) {f->
        f.toB();val before=f.maps.splitPresentation.layout!!;val oldNote=f.note;val originalBytes=f.noteBytes
        fun range(layout:MapSplitLayout):Pair<Float,Float> {
            val first=if(layout.reversed) Pane.SECONDARY else Pane.PRIMARY
            return if(layout.mapPane==first) 0f to layout.ratio else layout.ratio to 1f
        }
        f.guard={MapSourceGuardResult.Ready(setOf(oldNote.discardForOpen()))}
        withContext(Dispatchers.Main) {f.frame(f.bEntry,"bn");assertTrue(f.open(f.bEntry,"bn",f.canvasSource))};f.await {!f.sources.busy}
        val actualCanvas=withContext(Dispatchers.Main) {
            assertNotNull("Canvas open: ${f.sources.failure}",f.opened);assertTrue(f.native.editor.canvasOpen)
            assertFalse(oldNote.noteOpen);assertFalse(f.maps.visible);assertNull(f.maps.splitPresentation.layout)
            assertEquals(f.canvas.identity.documentId,f.native.editor.infinite.document.identity.documentId)
            f.native.editor.infinite.also {ed->val s=f.stroke(300.0);ed.document.add(s);ed.history.push(AddCanvasItem(ed.document,s));ed.document.dirty=true;f.stopDebounce(f.native.editor)}
        }
        withContext(Dispatchers.Main) {assertTrue(f.sources.returnToMap())};f.await {!f.sources.busy && f.maps.visible}
        withContext(Dispatchers.Main) {
            val returned=f.maps.splitPresentation.layout!!
            assertTrue(returned.nativeHome);assertEquals(Pane.PRIMARY,returned.nativePane);assertEquals(Pane.SECONDARY,returned.mapPane)
            assertEquals(range(before),range(returned));assertFalse(oldNote.noteOpen)
            assertSame(actualCanvas,f.native.editor.infinite);assertTrue(actualCanvas.document.dirty);assertTrue(actualCanvas.history.canUndo)
            persistNativeForMapPresentation(f.native.editor,f.bEntry);assertTrue(actualCanvas.document.dirty)
            assertTrue(f.close(f.bEntry))
        }
        f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {assertSame(f.aEntry,f.maps.current);assertTrue(f.maps.splitPresentation.layout!!.nativeHome);assertFalse(oldNote.noteOpen)}
        assertArrayEquals(originalBytes,f.native.source.readBytes());assertArrayEquals(f.canvasBytes,f.native.retry.readBytes());assertEquals(0,f.native.writes.get())
    }
    @Test fun rebindingAHostKeepsTheActualMapPoolLayoutHistoryAndOriginalPickerSlot()=scenario {f->
        f.toB()
        val picker=withContext(Dispatchers.Main) {
            assertTrue(f.maps.updateSplitLayout {it.copy(ratio=.62f,sideBySide=false,reversed=true)})
            assertTrue(f.bEntry.session.operate {it.update("bn") {n->n.copy(text="Before host recreation")}});f.bEntry.session.cancelPendingAutosave()
            f.bEntry.session.beginExistingSource("bn")!!
        }
        val layout=f.maps.splitPresentation.layout;val model=f.bEntry.session.document;val revision=f.bEntry.session.contentRevision
        val epoch=f.bEntry.session.interactionEpoch;val route=f.maps.sourceReturnToken
        withContext(Dispatchers.Main) {
            f.sources.dispose();f.originalNavigator.dispose();f.binding=Any();f.bind();f.createNavigators()
            assertSame(f.bEntry,f.maps.current);assertSame(model,f.bEntry.session.document);assertEquals(layout,f.maps.splitPresentation.layout)
            assertEquals(revision,f.bEntry.session.contentRevision);assertEquals(epoch,f.bEntry.session.interactionEpoch)
            assertSame(route,f.maps.sourceReturnToken);assertTrue(f.bEntry.session.canUndo);assertTrue(f.bEntry.session.receiveExistingSource(picker,null))
            f.noteUnchanged();assertTrue(f.close(f.bEntry))
        }
        f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {assertSame(f.aEntry,f.maps.current);assertEquals(layout,f.maps.splitPresentation.layout);f.noteUnchanged()}
    }
    @Test fun aPreparedSharedEntryLeaveProofExpiresOnActualPenDownWithoutCancellingTheStroke()=scenario {f->
        val session=f.aEntry.session
        val held=withContext(Dispatchers.Main) {MapDocuments.acquire(f.native.context,f.uri(f.a)).also(f.extra::add)}
        f.await {!held.session.loading}
        val view=withContext(Dispatchers.Main) {
            assertSame(session,held.session);assertTrue(f.aEntry.lease.hasOtherOwners)
            assertTrue(session.operate {it.copy(nodes=it.nodes+MapNode(id="ink",parent="ar",kind=NodeKind.INK,x=400.0,y=120.0,width=320.0,height=220.0))})
            session.cancelPendingAutosave()
            MindMapView(f.native.context).apply {
                document=session.document!!;documentEpoch=session.interactionEpoch;documentRevision=session.contentRevision
                onSelect={session.selected=it};onInkContact={session.inkContact=it}
                onInk={id,old,next->session.commitInk(id,old,next).also {accepted->if(accepted) document=session.document!!}}
                layout(0,0,1000,800);fit()
            }
        }
        val ready=CompletableDeferred<()->Boolean>()
        withContext(Dispatchers.Main) {assertTrue(f.sources.prepareEntryLeave(f.aEntry,onReady={ready.complete(it)}))}
        val proof=ready.await();f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {
            assertTrue(proof());val revision=session.contentRevision;val original=session.document
            val down=SystemClock.uptimeMillis();var tick=0L
            fun event(action:Int,point:Pt) {
                val p=view.screenPoint(point)
                val prop=MotionEvent.PointerProperties().apply {id=9;toolType=MotionEvent.TOOL_TYPE_STYLUS}
                val at=MotionEvent.PointerCoords().apply {x=p.x.toFloat();y=p.y.toFloat();pressure=.6f}
                val e=MotionEvent.obtain(down,down+tick++,action,1,arrayOf(prop),arrayOf(at),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
                try {assertTrue(view.onTouchEvent(e))} finally {e.recycle()}
            }
            event(MotionEvent.ACTION_DOWN,Pt(480.0,200.0));event(MotionEvent.ACTION_MOVE,Pt(515.0,225.0))
            assertTrue("The actual View owns a pending stroke",session.inkContact);assertTrue(view.inkInput.active)
            assertSame(original,session.document);assertEquals(revision,session.contentRevision)
            assertFalse("Sharing waives saving, never the fresh input guard",proof())
            f.sources.cancel();assertTrue(session.inkContact);assertTrue(view.inkInput.active)
            event(MotionEvent.ACTION_UP,Pt(550.0,245.0))
            assertFalse(session.inkContact);assertFalse(view.inkInput.active)
            assertEquals(revision+1,session.contentRevision);assertEquals(1,session.document!!.node("ink").ink.size)
            assertTrue(session.document!!.node("ink").ink.single().points.size>=3);assertTrue(session.canUndo)
            session.cancelPendingAutosave();assertFalse(proof());f.noteUnchanged()
        }
    }
    private fun originalReturnAfterReplacement(replaceDuringReturn:Boolean)=scenario {f->
        f.toB();val camera=withContext(Dispatchers.Main) {f.frame(f.bEntry,"bn")}
        withContext(Dispatchers.Main) {assertTrue(f.open(f.bEntry,"bn",f.originalSource))}
        f.await {!f.sources.busy && f.originals.visible}
        val shown=f.originals.current!!;val oldModel=f.noteModel
        if(replaceDuringReturn) {
            val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1);f.release=release
            // A real accepted per-slot IO queue prefix holds pure parking after its initial
            // identity check. No timing guess and no fake navigation/currentness proof.
            val accepted=withContext(Dispatchers.Main) {
                val owner=Editor::class.java.getDeclaredField("sessionOwner").apply {isAccessible=true}.get(f.note) as com.xnotes.platform.LegacySessionQueue.Owner
                owner.submit {entered.complete(Unit);check(release.await(10,TimeUnit.SECONDS))}!!
            }
            entered.await()
            withContext(Dispatchers.Main) {
                val departed=(shown.returnTo as OriginalPdfWorkspace.Return.Map).nativeIdentity!!
                assertTrue(f.native.editor.matchesNativePresentationIdentity(departed))
                assertTrue(f.sources.returnToMap());assertTrue(f.sources.returning)
            }
            f.await {f.maps.opening && f.originalNavigator.busy}
            withContext(Dispatchers.Main) {
                val departed=(shown.returnTo as OriginalPdfWorkspace.Return.Map).nativeIdentity!!
                assertTrue(f.native.editor.matchesNativePresentationIdentity(departed))
                val next=Document(mutableListOf(Page(500.0,500.0))).apply {identity=oldModel.identity}
                // Real owner adoption while the earlier accepted IO is still pending.
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(f.note,"replaceDocument",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(Document::class.java,next))
                assertSame(next,f.note.state.document);assertFalse(f.native.editor.matchesNativePresentationIdentity(departed))
                assertSame(shown,f.originals.current);assertFalse(f.maps.visible)
            }
            release.countDown();accepted.await();f.await {!f.sources.busy}
        } else {
            withContext(Dispatchers.Main) {f.note.newNote(setOf(f.note.discardForOpen()))}
            f.await {f.note.state.document!==oldModel}
        }
        val replacement=withContext(Dispatchers.Main) {
            assertSame(shown,f.originals.current);assertTrue(f.originals.visible);assertFalse(f.maps.visible)
            // The stable UUID alone cannot prove this is the parked runtime model.
            f.note.state.document.also {doc->
                doc.identity=oldModel.identity
                val stroke=f.stroke(420.0);doc.pages.first().items+=stroke;f.note.history.push(AddItem(doc.pages.first(),stroke));doc.dirty=true
                f.stopDebounce()
            }
        }
        val history=f.note.history;val undo=history.nextUndo;val revision=replacement.bodyRevision
        withContext(Dispatchers.Main) {assertTrue(f.sources.returnToMap())}
        f.await {!f.sources.busy && f.maps.visible}
        withContext(Dispatchers.Main) {
            assertNull("A changed native owner cannot restore the parked mixed layout",f.maps.splitPresentation.layout)
            assertNull(f.maps.splitPresentation.returnLayout);assertNull(f.originals.current);assertFalse(f.originals.visible)
            assertSame(replacement,f.note.state.document);assertSame(history,f.note.history);assertSame(undo,history.nextUndo)
            assertEquals(revision,replacement.bodyRevision);assertTrue(replacement.dirty);assertNull(replacement.path)
            f.assertReturned(f.bEntry,"bn",f.originalSource,camera)
            persistNativeForMapPresentation(f.native.editor,f.bEntry)
            assertSame(replacement,f.note.state.document);assertTrue(replacement.dirty);assertSame(undo,f.note.history.nextUndo)
            assertEquals(NativeReferenceIssue.CHANGED,f.originalNavigator.failure)
            assertTrue(f.sources.returnToSourceMap())
        }
        f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {assertSame(f.aEntry,f.maps.current);assertNull(f.maps.splitPresentation.layout);assertSame(replacement,f.note.state.document)}
        f.await {f.leases()==0}
        assertArrayEquals(f.noteBytes,f.native.source.readBytes());assertEquals(0,f.native.writes.get())
    }
    @Test fun replacingTheParkedNativeModelEvenWithTheSameUuidReturnsFromOriginalToASafeSingleMap()=originalReturnAfterReplacement(false)
    @Test fun nativeReplacementDuringOriginalReturnRejectsThatAttemptThenExplicitReturnSafelyLeavesTheReader()=originalReturnAfterReplacement(true)
    private fun canvasHomeNoteSource(mode:Int)=scenario(mapPane=Pane.PRIMARY,canvasAtTarget=true) {f->
        f.toB();val a=f.aEntry;val b=f.bEntry;val mapModel=b.session.document
        f.guard={MapSourceGuardResult.Ready(setOf(f.note.discardForOpen()))}
        withContext(Dispatchers.Main) {f.frame(b,"bn");assertTrue(f.open(b,"bn",f.canvasSource))};f.await {!f.sources.busy}
        val actual=withContext(Dispatchers.Main) {
            assertNotNull("Canvas open: ${f.sources.failure}",f.opened)
            f.native.editor.infinite.also {ed->val stroke=f.stroke(330.0);ed.document.add(stroke);ed.history.push(AddCanvasItem(ed.document,stroke));ed.document.dirty=true;f.stopDebounce(f.native.editor)}
        }
        withContext(Dispatchers.Main) {assertTrue(f.sources.returnToMap())};f.await {!f.sources.busy && f.maps.visible}
        val layout=f.maps.splitPresentation.layout!!;val model=actual.document;val history=actual.history;val undo=history.nextUndo
        fun unchanged() {
            assertSame(b,f.maps.current);assertSame(mapModel,b.session.document);assertTrue(f.maps.visible)
            assertEquals(layout,f.maps.splitPresentation.layout);assertTrue(layout.nativeHome);assertTrue(f.native.editor.canvasOpen)
            assertSame(actual,f.native.editor.infinite);assertSame(model,actual.document);assertSame(history,actual.history);assertSame(undo,actual.history.nextUndo)
            assertTrue(model.dirty);assertTrue(a.lease.alive);assertTrue(b.lease.alive);assertFalse(f.note.noteOpen)
            assertArrayEquals(f.canvasBytes,f.canvasFile.readBytes());assertEquals(0,f.native.writes.get())
        }
        f.guard={if(mode==1) MapSourceGuardResult.Cancelled else MapSourceGuardResult.Ready(emptySet())};f.opened=null
        if(mode==0) f.native.beforeTargetWrite={throw IOException("Controlled actual Canvas source publication failure")}
        withContext(Dispatchers.Main) {assertTrue(f.open(b,"bn",f.targetSource))};f.await {!f.sources.busy}
        if(mode!=2) {
            withContext(Dispatchers.Main) {assertNull(f.opened);unchanged()}
            assertEquals(mode==0,withContext(Dispatchers.IO) {f.native.storage.hasRecovery(f.canvasUri)})
            return@scenario
        }
        withContext(Dispatchers.Main) {
            assertNotNull("NOTE from Canvas Home: ${f.sources.failure}",f.opened);assertSame(f.native.editor,f.opened!!.pane)
            assertFalse(f.native.editor.canvasOpen);assertEquals(f.targetModel.identity.documentId,f.native.editor.state.document.identity.documentId)
            assertSame(b,f.maps.current);assertSame(mapModel,b.session.document);assertTrue(f.maps.visible);assertTrue(a.lease.alive)
            assertEquals(layout.copy(nativeHome=false,focusedPane=layout.nativePane),f.maps.splitPresentation.layout)
            assertFalse(f.note.noteOpen);assertSame(history,actual.history);assertSame(undo,actual.history.nextUndo);assertFalse(model.dirty)
            assertTrue(f.sources.returnToMap())
        }
        f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {assertTrue(f.close(b))};f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {assertSame(a,f.maps.current);assertTrue(f.native.editor.noteOpen);assertFalse(f.native.editor.canvasOpen)}
        assertEquals(1,f.canvasFile.inputStream().use {CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()).read(it)}.items.size)
        assertArrayEquals(f.noteBytes,f.native.source.readBytes());assertEquals(1,f.native.writes.get())
    }

    @Test fun aCanvasHomeNoteSourceSaveFailureKeepsBothSlotsAndItsPendingRecovery()=canvasHomeNoteSource(0)
    @Test fun cancellingACanvasHomeNoteSourceKeepsBothActualOwnersWithoutWriting()=canvasHomeNoteSource(1)
    @Test fun aCanvasHomeNoteSourceSavesTheCanvasAndReplacesOnlyItsActualNativeSlot()=canvasHomeNoteSource(2)

    private fun closeActualCanvasHome(failWrite:Boolean)=scenario(mapPane=Pane.PRIMARY,canvasAtTarget=true) {f->
        f.toB();f.guard={MapSourceGuardResult.Ready(setOf(f.note.discardForOpen()))}
        withContext(Dispatchers.Main) {f.frame(f.bEntry,"bn");assertTrue(f.open(f.bEntry,"bn",f.canvasSource))};f.await {!f.sources.busy}
        val actual=withContext(Dispatchers.Main) {
            assertNotNull("Canvas open: ${f.sources.failure}",f.opened)
            f.native.editor.infinite.also {ed->val stroke=f.stroke(370.0);ed.document.add(stroke);ed.history.push(AddCanvasItem(ed.document,stroke));ed.document.dirty=true;f.stopDebounce(f.native.editor)}
        }
        withContext(Dispatchers.Main) {assertTrue(f.sources.returnToMap())};f.await {!f.sources.busy && f.maps.visible}
        val layout=f.maps.splitPresentation.layout!!;val entry=f.bEntry;val mapModel=entry.session.document
        val model=actual.document;val history=actual.history;val undo=history.nextUndo
        if(failWrite) {
            f.native.beforeTargetWrite={throw IOException("Controlled Canvas Home close write failure")}
            withContext(Dispatchers.Main) {
                assertNull(f.native.editor.detachMixedPane(f.native.editor,allowCanvasHome=true))
                assertSame(entry,f.maps.current);assertSame(mapModel,entry.session.document);assertTrue(f.maps.visible)
                assertEquals(layout,f.maps.splitPresentation.layout);assertSame(model,actual.document);assertTrue(model.dirty)
                assertTrue(f.native.editor.canvasOpen);assertSame(history,actual.history);assertSame(undo,history.nextUndo)
            }
            assertArrayEquals(f.canvasBytes,f.canvasFile.readBytes());assertEquals(0,f.native.writes.get())
            assertTrue(withContext(Dispatchers.IO) {f.native.storage.hasRecovery(f.canvasUri)})
            return@scenario
        }
        val entered=CountDownLatch(1);val release=CountDownLatch(1);f.release=release
        f.native.beforeTargetWrite={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        val closing=f.scope.async {f.native.editor.detachMixedPane(f.native.editor,allowCanvasHome=true)}
        assertTrue(withContext(Dispatchers.IO) {entered.await(5,TimeUnit.SECONDS)})
        withContext(Dispatchers.Main) {closing.cancel()};release.countDown();closing.join();f.native.beforeTargetWrite=null
        withContext(Dispatchers.Main) {
            assertNotNull(f.native.editor.awaitMapSourceParking())
            assertTrue(f.native.editor.canvasOpen);assertSame(model,actual.document);assertSame(history,actual.history);assertSame(undo,history.nextUndo)
            assertFalse("An accepted write finishes even though this close was cancelled",model.dirty)
            assertSame(entry,f.maps.current);assertSame(mapModel,entry.session.document);assertEquals(layout,f.maps.splitPresentation.layout)
            assertTrue(f.maps.visible);assertTrue(f.aEntry.lease.alive);assertTrue(entry.lease.alive)
            // This is the same typed detach + Single transition used by the real Home-close hook.
            assertNotNull(f.native.editor.detachMixedPane(f.native.editor,allowCanvasHome=true))
            f.maps.splitPresentation.present(MapOpenPresentation.Single)
            assertFalse(f.native.editor.noteOpen);assertFalse(f.native.editor.canvasOpen);assertNull(f.maps.splitPresentation.layout)
            assertSame(entry,f.maps.current);assertSame(mapModel,entry.session.document);assertTrue(f.maps.visible)
        }
        assertEquals(1,f.native.writes.get());assertEquals(1,f.canvasFile.inputStream().use {CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()).read(it)}.items.size)
        assertArrayEquals(f.noteBytes,f.native.source.readBytes())
    }
    @Test fun closingTheActualCanvasHomeWithAFailedWritePreservesBothSlotsAndPendingRecovery()=closeActualCanvasHome(true)
    @Test fun cancellingTheActualCanvasHomeCloseKeepsTheAcceptedWriteThenRetryClosesOnlyThatSlot()=closeActualCanvasHome(false)

    private fun restoreActualMixedNativeSession(wrongHint:Boolean)=scenario(mapPane=Pane.PRIMARY) {f->
        f.toB();val oldRoot=f.native.editor;val oldNote=f.note;val previous=oldNote.state.document
        withContext(Dispatchers.Main) {oldNote.newNote(setOf(oldNote.discardForOpen()))}
        f.await {oldNote.state.document!==previous}
        val expected=withContext(Dispatchers.Main) {
            val doc=oldNote.state.document;val stroke=f.stroke(680.0)
            doc.pages.first().items+=stroke;oldNote.history.push(AddItem(doc.pages.first(),stroke));doc.dirty=true
            oldNote.state.pendingInitialView=null;oldNote.state.didInitialFit=true
            oldNote.state.setView(1.7,95.0,175.0)
            f.stopDebounce();oldRoot.mixedPresentationActive=true
            val hint=oldNote.nativeRestoreHint()!!;assertNull(hint.path)
            assertTrue(f.maps.splitPresentation.rememberNativeHint(if(wrongHint) hint.copy(documentId=UUID.randomUUID().toString()) else hint))
            oldRoot.persist()
            Triple(doc.identity.documentId,doc.pages.first().items.filterIsInstance<Stroke>().single(),oldNote.readingPosition())
        }
        val sessionFile=File(f.native.context.filesDir,"session-b/document.xnote")
        f.await {sessionFile.isFile && runCatching {sessionFile.inputStream().use {f.native.codec.read(it)}.identity.documentId==expected.first}.getOrDefault(false)}
        val layout=f.maps.splitPresentation.layout;val entry=f.bEntry;val model=entry.session.document
        val epoch=entry.session.interactionEpoch;val route=f.maps.sourceReturnToken
        val picker=withContext(Dispatchers.Main) {entry.session.beginExistingSource("bn")!!}
        val fresh=withContext(Dispatchers.Main) {
            assertNotNull(oldRoot.awaitMapSourceParking())
            for(pane in listOfNotNull(oldRoot,oldRoot.secondary))
                (Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(pane) as CoroutineScope).cancel()
            Editor(f.native.context).also {f.native.editor=it}
        }
        withContext(Dispatchers.Main) {
            val restored=com.xnotes.restoreMixedNativeSession(fresh,f.maps)
            assertEquals(!wrongHint,restored);assertFalse("Never read a stale session from the Map slot",fresh.noteOpen)
            val nativeSlot=fresh.secondary!!
            if(wrongHint) {
                assertFalse("The actual stored identity must match before model adoption",nativeSlot.noteOpen)
                assertEquals(layout!!.copy(nativeHome=true),f.maps.splitPresentation.layout)
            } else {
                assertTrue(nativeSlot.noteOpen);assertFalse(nativeSlot.canvasOpen);assertNull(nativeSlot.state.document.path)
                assertEquals(expected.first,nativeSlot.state.document.identity.documentId);assertTrue(nativeSlot.state.document.dirty)
                assertEquals(expected.second.samples,nativeSlot.state.document.pages.first().items.filterIsInstance<Stroke>().single().samples)
                nativeSlot.view.layout(0,0,1000,800);if(!nativeSlot.state.didInitialFit) nativeSlot.state.establishInitialView()
                assertEquals(expected.third.zoom,nativeSlot.state.zoom,1e-6)
                assertEquals(expected.third.x,nativeSlot.state.scrollX,1e-6);assertEquals(expected.third.y,nativeSlot.state.scrollY,1e-6)
                assertEquals(layout,f.maps.splitPresentation.layout)
            }
            assertSame(entry,f.maps.current);assertSame(model,entry.session.document);assertEquals(epoch,entry.session.interactionEpoch)
            assertSame(route,f.maps.sourceReturnToken);assertTrue(f.maps.visible);assertTrue(f.aEntry.lease.alive)
            assertTrue(entry.session.receiveExistingSource(picker,null))
        }
        assertArrayEquals(f.noteBytes,f.native.source.readBytes());assertEquals(0,f.native.writes.get());assertEquals(0,f.native.writeOpens.get())
    }
    @Test fun actualMainMixedRestoreRecoversTheUnsavedNoteAndReadingPositionFromOnlyItsSecondarySlot()=restoreActualMixedNativeSession(false)
    @Test fun actualMainMixedRestoreRejectsAWrongStoredIdentityBeforeAdoptionAndLeavesThatSlotHome()=restoreActualMixedNativeSession(true)

}
