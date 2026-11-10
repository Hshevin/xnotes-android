package com.xnotes.pen

import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.provider.DocumentsContract
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.mindmap.*
import com.xnotes.persistNativeForMapPresentation
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.storage.*
import com.xnotes.ui.Editor
import com.xnotes.ui.theme.Palette
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual file actors, navigation, native owners and original reader/resource leases. Only the
 * original PDF engine's parsing/pixels are controlled; no locator pixels or device GPU are claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapGraphSourceLeafNavigationHostTest {
    private enum class Leaf {NATIVE,ORIGINAL}
    private class Fixture(val native:EditorPageCopyFixture,val leafKind:Leaf) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val maps=MapWorkspace(native.context,scope);val networks=FileNetworkWorkspace(native.context,scope)
        val binding=Any();val catalog=OriginalPdfCatalog(native.storage.registry)
        val renders=AtomicInteger();val closes=AtomicInteger()
        val access=OriginalPdfAccess(native.context,engineFactory={object:OriginalPdfEngine {
            override val pageCount=2
            override fun pageInfo(pdfKey:Int)=OriginalPdfPageInfo(Pt(600.0,800.0),PdfPageGeometry.flipped(800.0))
            override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):AndroidRasterSurface {
                renders.incrementAndGet();return AndroidRasterSurface.create(width,height).apply {bitmap.eraseColor(Color.WHITE)}
            }
            override fun closeAndAwait() {closes.incrementAndGet()}
        }},storageOverride=native.storage)
        val originals=OriginalPdfWorkspace(native.context,scope,access)
        val provider=AppStorageDocumentsProvider();val notes=AppStorageDocumentsProvider.rootDir(native.context)
        val folder=File(notes,"map-leaf-${UUID.randomUUID()}").apply {mkdirs()}
        val tree=AppStorageDocumentsProvider.treeUri(native.context)
        val a=File(folder,"A.xmap");val b=File(folder,"B.xmap")
        val pdf=File(native.root,"immutable-leaf-original.pdf").apply {writeText("immutable original bytes behind the controlled PDF engine")}
        val idA=UUID.randomUUID().toString();val idB=UUID.randomUUID().toString()
        lateinit var leaf:MapSource
        val source=MapSource(documentId=idB,fileName="B.xmap")
        lateinit var aEntry:MapWorkspace.Entry
        lateinit var bEntry:MapWorkspace.Entry
        lateinit var sources:MapSourceNavigator
        lateinit var originalNavigator:OriginalPdfNavigator
        lateinit var record:OriginalPdfCatalog.Record
        val readers=mutableListOf<OriginalPdfReader>();val extra=mutableListOf<MapDocumentLease>()
        val panes=mutableListOf<Editor>();val nativeModels=mutableListOf<com.xnotes.core.model.Document>()
        val nativeHistories=mutableListOf<com.xnotes.core.history.History>();val nativeUndos=mutableListOf<Any?>()
        val nativeRevisions=mutableListOf<Long>();val nativeBytes=mutableListOf<ByteArray>()
        var nativeOpened=0
        var closeJob:Job?=null
        fun uri(file:File)=DocumentsContract.buildDocumentUriUsingTree(tree,"root/"+file.relativeTo(notes).invariantSeparatorsPath)
        private fun stopDebounce(pane:Editor) {
            for(name in listOf("noteDebounceJob","canvasDebounceJob"))
                (Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(pane) as? Job)?.cancel()
        }
        suspend fun start() {
            provider.attachInfo(native.context,ProviderInfo().apply {
                authority=tree.authority;exported=true;grantUriPermissions=true
                readPermission="android.permission.MANAGE_DOCUMENTS";writePermission=readPermission
            })
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(requireNotNull(tree.authority),provider)
            if(leafKind==Leaf.ORIGINAL) openParkingPanes(native)
            else {
                withContext(Dispatchers.IO) {native.seed()}
                withContext(Dispatchers.Main) {native.editor.openAsync(native.sourceUri,"source.xnote");native.editor.view.layout(0,0,1000,1000)}
            }
            withContext(Dispatchers.Main) {
                panes+=native.editor.openPanes
                for(pane in panes) {
                    pane.selectTool(com.xnotes.core.tools.Tool.PEN)
                    parkingPen(pane,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));parkingPen(pane,MotionEvent.ACTION_MOVE,Pt(140.0,120.0))
                    parkingPen(pane,MotionEvent.ACTION_UP,Pt(180.0,160.0));stopDebounce(pane)
                    nativeModels+=pane.state.document;nativeHistories+=pane.history;nativeUndos+=pane.history.nextUndo
                    nativeRevisions+=pane.state.document.bodyRevision
                    assertTrue(pane.state.document.dirty);assertTrue(pane.history.canUndo)
                }
                native.editor.originalPdfAccess=access
            }
            nativeBytes+=native.source.readBytes()
            if(leafKind==Leaf.ORIGINAL) nativeBytes+=native.target.readBytes()
            leaf=if(leafKind==Leaf.ORIGINAL) {
                record=withContext(Dispatchers.IO) {
                    val candidate=access.candidate(pdf,"Original leaf")
                    try {(catalog.retain(candidate) as OriginalPdfCatalog.Retention.Retained).observation.record}
                    finally {candidate.closeAndAwait()}
                }
                MapSource(documentId=record.id,fileName="Original leaf",scope=SourceScope.PDF_ORIGINAL,pageId=record.pages[1],
                    originalPdf=PdfOriginalProjection(record.sha256,1,userRegion=Rect(20.0,30.0,80.0,90.0)))
            } else withContext(Dispatchers.Main) {
                val doc=native.editor.state.document
                MapSource(documentId=doc.identity.documentId,fileName="source.xnote",scope=SourceScope.PAGE,
                    pageId=doc.pages.last().referenceIdentity.id,region=Rect(20.0,30.0,80.0,90.0))
            }
            withContext(Dispatchers.IO) {
                a.writeBytes(MapCodec.encode(MindMapDocument(id=idA,nodes=listOf(MapNode(id="ar",parent=null),
                    MapNode(id="an",parent="ar",text="A saved",sources=listOf(source),primarySource=source.id)))))
                b.writeBytes(MapCodec.encode(MindMapDocument(id=idB,nodes=listOf(MapNode(id="br",parent=null),
                    MapNode(id="bn",parent="br",text="B saved",sources=listOf(leaf),primarySource=leaf.id)))))
                native.storage.registry.registerMapObserved(idA,a.name,uri(a).toString())
                native.storage.registry.registerMapObserved(idB,b.name,uri(b).toString())
            }
            withContext(Dispatchers.Main) {
                maps.bindHost(binding);networks.bindHost(binding);originals.bindHost(binding)
                originalNavigator=OriginalPdfNavigator(native.editor,maps,networks,originals,binding,scope)
                sources=MapSourceNavigator(native.editor,maps,binding,scope,
                    guard={_,_->if(leafKind==Leaf.ORIGINAL) error("Originals must not use Native replacement") else MapSourceGuardResult.Ready(emptySet())},
                    originalNavigator=originalNavigator,onOpened={_,_->nativeOpened++})
                assertTrue(maps.open(uri(a),"A",preparePresentation={native.editor.awaitMapSourceParking()?.let {proof->({proof.current()})}},flushNativeOnPresent=false))
            }
            await {!maps.opening && maps.visible}
            withContext(Dispatchers.Main) {
                aEntry=maps.current!!
                assertTrue(aEntry.session.operate {it.update("an") {n->n.copy(text="A dirty before departure")}})
                assertTrue(aEntry.session.operate {it.update("an") {n->n.copy(text="A redo remains")}})
                aEntry.session.undo();aEntry.session.cancelPendingAutosave()
            }
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        fun camera(entry:MapWorkspace.Entry,node:String,dx:Float,dy:Float):MindMapView.Viewport {
            entry.presentation.synchronize(entry.session);entry.session.selected=node
            val view=MindMapView(native.context).apply {
                document=entry.session.document!!;documentEpoch=entry.session.interactionEpoch;layout(0,0,1000,700)
                fit();zoomBy(1.3f);panText(dx,dy)
            }
            assertTrue(entry.presentation.showSources(entry.session,view,node));return view.viewport()
        }
        fun open(entry:MapWorkspace.Entry,node:String,source:MapSource):Boolean {
            val ticket=entry.session.captureSourceNavigation(node,source.id,source.target)!!
            return sources.open(entry,ticket,entry.session.captureSourceReturn(ticket)!!)
        }
        suspend fun toB() {
            withContext(Dispatchers.Main) {assertTrue(open(aEntry,"an",source))}
            await {!sources.busy}
            withContext(Dispatchers.Main) {bEntry=maps.current!!;assertEquals("FILE B failed: ${sources.failure}",idB,bEntry.session.document!!.id);assertTrue(maps.visible)}
        }
        fun dirtyB() {
            assertTrue(bEntry.session.operate {it.update("bn") {n->n.copy(text="B dirty before leaf")}})
            assertTrue(bEntry.session.operate {it.update("bn") {n->n.copy(text="B redo remains")}})
            bEntry.session.undo();bEntry.session.cancelPendingAutosave()
        }
        fun assertCamera(entry:MapWorkspace.Entry,node:String,source:MapSource,camera:MindMapView.Viewport) {
            assertSame(entry,maps.current);assertTrue(maps.visible);assertEquals(node,entry.session.selected)
            assertEquals(node,entry.presentation.sourcesFor);assertEquals(source.id,entry.presentation.sourceFocus?.sourceId)
            val view=MindMapView(native.context).apply {document=entry.session.document!!;documentEpoch=entry.session.interactionEpoch;layout(0,0,1000,700);fit()}
            assertTrue(entry.presentation.restore(entry.session,view))
            assertEquals(camera.zoom,view.viewport().zoom,1e-5f);assertEquals(camera.panX,view.viewport().panX,1e-4f);assertEquals(camera.panY,view.viewport().panY,1e-4f)
        }
        fun assertNative() {
            for(i in panes.indices) {
                assertSame(nativeModels[i],panes[i].state.document);assertSame(nativeHistories[i],panes[i].history)
                assertSame(nativeUndos[i],panes[i].history.nextUndo);assertEquals(nativeRevisions[i],panes[i].state.document.bodyRevision)
                assertTrue(panes[i].state.document.dirty);assertTrue(panes[i].history.canUndo)
            }
            assertArrayEquals(nativeBytes[0],native.source.readBytes())
            if(nativeBytes.size>1) assertArrayEquals(nativeBytes[1],native.target.readBytes())
            assertEquals(0,native.writes.get());assertEquals(0,native.writeOpens.get())
        }
        fun leases()=native.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases WHERE resource_id=?",arrayOf(record.sha256)).use {
            assertTrue(it.moveToFirst());it.getInt(0)
        }
        suspend fun finish()=withContext(NonCancellable) {
            withContext(Dispatchers.Main) {readers.forEach {it.retire()};if(this@Fixture::sources.isInitialized) sources.dispose();if(this@Fixture::originalNavigator.isInitialized) originalNavigator.dispose()}
            withContext(Dispatchers.IO) {readers.forEach {it.closeAndAwait()}}
            withContext(Dispatchers.Main) {originals.dispose()}.join()
            withContext(Dispatchers.Main) {networks.dispose();maps.dispose()}.join();closeJob?.join()
            val released=withContext(Dispatchers.Main) {extra.map {it.close()}};released.forEach {it.join()}
            scope.cancel();withContext(Dispatchers.IO) {folder.deleteRecursively()}
        }
    }
    private fun scenario(leaf:Leaf,test:suspend(Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native,leaf);try {f.start();test(f)} finally {f.finish()}
    }
    @Test fun aSavedBReallyColdOpensWithItsPermanentSourceAfterTheEntireVisitedPoolAndActorsRetire()=scenario(Leaf.NATIVE) {f->
        withContext(Dispatchers.Main) {f.camera(f.aEntry,"an",61f,-37f)};f.toB()
        val old=f.bEntry.session
        withContext(Dispatchers.Main) {
            assertTrue(old.operate {it.update("bn") {n->n.copy(text="B saved for true cold open")}});old.cancelPendingAutosave()
        }
        val written=withContext(Dispatchers.Main) {old.saveAttempt()}.await();assertTrue(written is MapSaveOutcome.Written)
        val target=withContext(Dispatchers.Main) {old.document!!.node("bn").sources.single().target}
        val sourceId=withContext(Dispatchers.Main) {old.document!!.node("bn").sources.single().id}
        withContext(Dispatchers.Main) {assertTrue(f.sources.returnToSourceMap())};f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {
            assertSame(f.aEntry,f.maps.current)
            assertTrue(f.sources.closeEntry(f.aEntry) {
                f.sources.retire();f.maps.close(f.aEntry)?.also {f.closeJob=it}!=null
            })
        }
        f.await {f.maps.current==null && !f.sources.busy};f.closeJob!!.join()
        withContext(Dispatchers.Main) {
            assertFalse(f.aEntry.lease.alive);assertFalse(f.bEntry.lease.alive)
            assertTrue(MapDocuments.observations(f.native.context).none {it.key in setOf(MapSaves.key(f.uri(f.a)),MapSaves.key(f.uri(f.b)))})
        }
        val fresh=withContext(Dispatchers.Main) {MapDocuments.acquire(f.native.context,f.uri(f.b)).also(f.extra::add)}
        f.await {!fresh.session.loading}
        withContext(Dispatchers.Main) {
            assertNotSame(old,fresh.session);assertFalse(fresh.session.loadFailed)
            assertEquals("B saved for true cold open",fresh.session.document!!.node("bn").text)
            val source=fresh.session.document!!.node("bn").sources.single()
            assertEquals(sourceId,source.id);assertEquals(target,source.target);assertEquals(f.leaf.pageId,source.pageId)
            assertFalse(fresh.session.canUndo);assertFalse(fresh.session.canRedo);assertFalse(fresh.session.dirty)
            f.assertNative()
        }
    }
    @Test fun aBToNativePageRoundTripPreservesBAndTheMapParentRouteThenRestoresA()=scenario(Leaf.NATIVE) {f->
        val aCamera=withContext(Dispatchers.Main) {f.camera(f.aEntry,"an",83f,-29f)};val aModel=f.aEntry.session.document
        f.toB()
        val bCamera=withContext(Dispatchers.Main) {f.dirtyB();f.camera(f.bEntry,"bn",-53f,41f)}
        val bModel=f.bEntry.session.document;val bRevision=f.bEntry.session.contentRevision
        val route=withContext(Dispatchers.Main) {f.maps.sourceReturnToken!!}
        withContext(Dispatchers.Main) {assertTrue(f.open(f.bEntry,"bn",f.leaf))};f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {
            assertEquals("Native source failed: ${f.sources.failure}",1,f.nativeOpened)
            assertFalse(f.maps.visible);assertSame(f.bEntry,f.maps.current);assertSame(route,f.maps.sourceReturnToken)
            assertSame(f.nativeModels.single(),f.native.editor.state.document)
            assertTrue(f.sources.returnToMap())
        }
        f.await {!f.sources.busy && f.maps.visible}
        withContext(Dispatchers.Main) {
            persistNativeForMapPresentation(f.native.editor,f.bEntry)
            assertSame(route,f.maps.sourceReturnToken);assertTrue(f.maps.canReturnSource)
            f.assertCamera(f.bEntry,"bn",f.leaf,bCamera);assertSame(bModel,f.bEntry.session.document)
            assertEquals(bRevision,f.bEntry.session.contentRevision);assertTrue(f.bEntry.session.canUndo);assertTrue(f.bEntry.session.canRedo)
            assertTrue(f.sources.returnToSourceMap())
        }
        f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {
            f.assertCamera(f.aEntry,"an",f.source,aCamera);assertSame(aModel,f.aEntry.session.document)
            assertTrue(f.aEntry.session.canUndo);assertTrue(f.aEntry.session.canRedo);assertTrue(f.bEntry.lease.alive)
            f.assertNative()
        }
    }
    @Test fun aBToActualOriginalReaderRoundTripKeepsTheParentRouteAndBothDirtyNativeOwnersUnwritten()=scenario(Leaf.ORIGINAL) {f->
        val pdf=f.pdf.readBytes();val aCamera=withContext(Dispatchers.Main) {f.camera(f.aEntry,"an",67f,-43f)}
        val aModel=f.aEntry.session.document;f.toB()
        val bCamera=withContext(Dispatchers.Main) {f.dirtyB();f.camera(f.bEntry,"bn",-79f,31f)}
        val bModel=f.bEntry.session.document;val bRevision=f.bEntry.session.contentRevision
        val route=withContext(Dispatchers.Main) {f.maps.sourceReturnToken!!}
        withContext(Dispatchers.Main) {assertTrue(f.open(f.bEntry,"bn",f.leaf))}
        f.await {!f.sources.busy && f.originals.visible}
        val shown=withContext(Dispatchers.Main) {f.originals.current!!}
        val reader=withContext(Dispatchers.Main) {
            val retained=shown.retainReader()!!
            try {OriginalPdfReader(f.native.context,shown,shown.pageIndex,shown.read.engine.pageInfo(1),Palette.DEFAULT,retained).also {r->
                f.readers+=r;r.view.layout(0,0,600,700)
                val bitmap=Bitmap.createBitmap(600,700,Bitmap.Config.ARGB_8888)
                try {r.view.draw(Canvas(bitmap))} finally {bitmap.recycle()}
            }} catch(error:Throwable) {retained.close();throw error}
        }
        f.await {f.renders.get()>0}
        withContext(Dispatchers.Main) {
            assertEquals(f.leaf.target,shown.target);assertEquals(1,shown.pageIndex)
            assertNull(reader.document.path);assertNull(reader.document.pdfFile);assertFalse(reader.document.dirty)
            assertTrue(reader.page.items.isEmpty());assertTrue(reader.page.markups.isEmpty());assertTrue(reader.page.rooms.isEmpty())
            assertSame(f.bEntry,f.maps.current);assertFalse(f.maps.visible);assertSame(route,f.maps.sourceReturnToken)
            assertEquals(1,f.leases());assertTrue(f.sources.returnToMap())
        }
        f.await {!f.sources.busy && f.maps.visible && f.originals.current==null}
        withContext(Dispatchers.Main) {
            persistNativeForMapPresentation(f.native.editor,f.bEntry)
            assertSame(route,f.maps.sourceReturnToken);assertTrue(f.maps.canReturnSource)
            f.assertCamera(f.bEntry,"bn",f.leaf,bCamera);assertSame(bModel,f.bEntry.session.document)
            assertEquals(bRevision,f.bEntry.session.contentRevision);assertTrue(f.bEntry.session.canUndo);assertTrue(f.bEntry.session.canRedo)
            assertEquals(1,f.leases());reader.retire()
        }
        withContext(Dispatchers.IO) {reader.closeAndAwait()};f.await {f.leases()==0}
        withContext(Dispatchers.Main) {assertTrue(f.sources.returnToSourceMap())};f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {
            persistNativeForMapPresentation(f.native.editor,f.aEntry)
            f.assertCamera(f.aEntry,"an",f.source,aCamera);assertSame(aModel,f.aEntry.session.document)
            assertTrue(f.aEntry.session.canUndo);assertTrue(f.aEntry.session.canRedo);assertTrue(f.bEntry.lease.alive)
            f.assertNative()
        }
        assertArrayEquals(pdf,f.pdf.readBytes())
    }
}
