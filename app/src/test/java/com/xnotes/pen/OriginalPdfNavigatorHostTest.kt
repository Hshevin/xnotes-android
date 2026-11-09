package com.xnotes.pen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.mindmap.*
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.storage.*
import com.xnotes.ui.theme.Palette
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual native owners, SQLite, map actor and managed resource leases. The engine is injected,
 * so these host contracts do not assert Android PDFium rendering or physical Surface behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class OriginalPdfNavigatorHostTest {
    private class Engine:OriginalPdfEngine {
        var openEntered:CountDownLatch?=null;var openRelease:CountDownLatch?=null
        var renderEntered:CountDownLatch?=null;var renderRelease:CountDownLatch?=null
        val closes=AtomicInteger();val renders=AtomicInteger()
        override val pageCount:Int get() {openEntered?.countDown();openRelease?.await();return 2}
        override fun pageInfo(pdfKey:Int)=OriginalPdfPageInfo(Pt(600.0,800.0),PdfPageGeometry.flipped(800.0))
        override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):AndroidRasterSurface {
            renders.incrementAndGet();renderEntered?.countDown();renderRelease?.await()
            return AndroidRasterSurface.create(width,height).apply {bitmap.eraseColor(android.graphics.Color.WHITE)}
        }
        override fun closeAndAwait() {closes.incrementAndGet()}
    }
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val maps=MapWorkspace(native.context,scope)
        val networks=FileNetworkWorkspace(native.context,scope)
        val catalog=OriginalPdfCatalog(native.storage.registry)
        val engines=java.util.concurrent.CopyOnWriteArrayList<Engine>()
        var configure:(Engine)->Unit={}
        val access=OriginalPdfAccess(native.context,engineFactory={Engine().also {configure(it);engines+=it}},storageOverride=native.storage)
        val originals=OriginalPdfWorkspace(native.context,scope,access)
        var binding:Any=Any()
        lateinit var navigator:OriginalPdfNavigator
        lateinit var sources:MapSourceNavigator
        lateinit var observation:OriginalPdfCatalog.Observation
        lateinit var entry:MapWorkspace.Entry
        val file=File(native.root,"original-navigation.xmap")
        val pdf=File(native.root,"immutable-original.pdf").apply {writeText("held original bytes, never a native note")}
        suspend fun start() {
            openParkingPanes(native)
            withContext(Dispatchers.Main) {
                for(pane in native.editor.openPanes) {
                    parkingPen(pane,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));parkingPen(pane,MotionEvent.ACTION_UP,Pt(160.0,140.0))
                }
            }
            observation=withContext(Dispatchers.IO) {
                val candidate=access.candidate(pdf,"Original")
                try {(catalog.retain(candidate) as OriginalPdfCatalog.Retention.Retained).observation} finally {candidate.closeAndAwait()}
            }
            val source=MapSource(documentId=observation.record.id,scope=SourceScope.PDF_ORIGINAL,pageId=observation.record.pages[1],
                fileName="Original",originalPdf=PdfOriginalProjection(observation.record.sha256,1,userRegion=com.xnotes.core.geometry.Rect(20.0,30.0,80.0,90.0)))
            file.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),MapNode(id="n",parent="r",text="unchanged",sources=listOf(source))))))
            withContext(Dispatchers.Main) {
                bind();maps.open(Uri.fromFile(file),"Original map");createNavigators()
            }
            await {maps.visible && !maps.opening}
            withContext(Dispatchers.Main) {entry=maps.current!!;entry.presentation.synchronize(entry.session);entry.session.selected="n"}
        }
        fun bind() {maps.bindHost(binding);networks.bindHost(binding);originals.bindHost(binding)}
        fun createNavigators() {
            navigator=OriginalPdfNavigator(native.editor,maps,networks,originals,binding,scope,captureHost={
                val panes=native.editor.openPanes;val models=panes.map {it.state.document};val owners=panes.map {it.documentOwner}
                val check:()->Boolean={panes.zip(models).all {(pane,model)->pane.state.document===model} && panes.zip(owners).all {(pane,owner)->pane.documentOwner===owner}}
                check
            })
            sources=MapSourceNavigator(native.editor,maps,binding,scope,guard={_,_->error("Originals must not enter native replacement guards")},originalNavigator=navigator)
        }
        fun bookmark():Pair<MapSourceNavigationTicket,MapSourceReturnBookmark> {
            val source=entry.session.document!!.node("n").sources.single()
            val ticket=requireNotNull(entry.session.captureSourceNavigation("n",source.id))
            return ticket to requireNotNull(entry.session.captureSourceReturn(ticket))
        }
        fun open():Boolean {val (ticket,back)=bookmark();return sources.open(entry,ticket,back)}
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        suspend fun finish()=withContext(NonCancellable) {
            withContext(Dispatchers.Main) {if(this@Fixture::sources.isInitialized) sources.dispose();if(this@Fixture::navigator.isInitialized) navigator.dispose();networks.dispose();maps.dispose()}.join()
            withContext(Dispatchers.Main) {originals.dispose()}.join();scope.cancel()
        }
    }
    private fun scenario(block:suspend(Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native);try {f.start();block(f)} finally {f.finish()}
    }
    @Test fun mapOriginalUsesAnIndependentReadOnlyModelAndReturnsWithoutSavingEitherDirtyNativePane()=scenario {f->
        val panes=f.native.editor.openPanes;val models=panes.map {it.state.document};val histories=panes.map {it.history}
        val bytes=f.native.source.readBytes();val second=f.native.target.readBytes();val body=f.entry.session.document
        withContext(Dispatchers.Main) {assertTrue(f.open())};f.await {f.originals.visible && !f.navigator.busy}
        val shown=withContext(Dispatchers.Main) {f.originals.current!!}
        val retained=withContext(Dispatchers.Main) {shown.retainReader()!!}
        val reader=withContext(Dispatchers.Main) {OriginalPdfReader(f.native.context,shown,shown.pageIndex,
            shown.read.engine.pageInfo(1),Palette.DEFAULT,retained)}
        try {
            withContext(Dispatchers.Main) {
                assertFalse(f.maps.visible);assertSame(body,f.entry.session.document);assertEquals(1,shown.pageIndex)
                assertNull(reader.document.path);assertNull(reader.document.pdfFile);assertTrue(reader.page.items.isEmpty())
                assertTrue(reader.page.markups.isEmpty());assertTrue(reader.page.rooms.isEmpty());assertFalse(reader.document.dirty)
                assertTrue(shown.movePage(-1));assertEquals(0,shown.pageIndex)
                assertEquals(1,f.entry.session.document!!.node("n").sources.single().originalPdf!!.pageIndex)
                assertTrue(f.sources.returnToMap())
            }
            f.await {f.maps.visible && !f.navigator.busy}
            withContext(Dispatchers.Main) {
                assertNull(f.originals.current);assertSame(f.entry,f.maps.current);assertEquals("n",f.entry.session.selected)
                assertEquals("n",f.entry.presentation.sourcesFor);assertNull(f.entry.sourceReturn)
                assertFalse(f.entry.flushNativeOnPresent);assertTrue(f.entry.skipNativePersistOnPresent)
                panes.forEachIndexed {i,pane->assertSame(models[i],pane.state.document);assertSame(histories[i],pane.history);assertTrue(models[i].dirty);assertTrue(pane.history.canUndo)}
                reader.retire()
            }
        } finally {withContext(Dispatchers.Main) {reader.retire()};withContext(Dispatchers.IO) {reader.closeAndAwait()}}
        assertArrayEquals(bytes,f.native.source.readBytes());assertArrayEquals(second,f.native.target.readBytes());assertEquals(0,f.native.writes.get())
    }
    @Test fun sourceDeleteAndUndoDuringSlowOpenCannotReviveTheOldTicketOrAdoptAReader()=scenario {f->
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        f.configure={it.openEntered=entered;it.openRelease=release}
        val original=withContext(Dispatchers.Main) {f.bookmark().also {(ticket,back)->assertTrue(f.sources.open(f.entry,ticket,back))}}
        assertTrue(withContext(Dispatchers.IO) {entered.await(5,TimeUnit.SECONDS)})
        lateinit var fresh:MapSourceNavigationTicket
        lateinit var afterUndoDocument:MindMapDocument
        var afterUndoSelection:Set<String> = emptySet()
        var afterUndoSelected:String?=null
        var afterUndoPanel:String?=null
        try {withContext(Dispatchers.Main) {
            assertTrue(f.entry.session.operate {doc->doc.update("n") {it.copy(sources=emptyList())}});f.entry.session.undo()
            // Undo restores the source data, but its retired navigation lifetime stays retired.
            assertNull(f.entry.session.currentSourceNavigation(original.first))
            fresh=f.bookmark().first
            assertNotNull(f.entry.session.currentSourceNavigation(fresh))
            afterUndoDocument=f.entry.session.document!!
            afterUndoSelection=f.entry.session.selectedNodes.toSet();afterUndoSelected=f.entry.session.selected
            afterUndoPanel=f.entry.presentation.sourcesFor
        }} finally {release.countDown()}
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertTrue(f.maps.visible);assertNull(f.originals.current);assertNull(f.entry.sourceReturn)
            assertNull(f.entry.session.currentSourceNavigation(original.first))
            assertNotNull(f.entry.session.currentSourceNavigation(fresh))
            assertSame(afterUndoDocument,f.entry.session.document)
            assertEquals(afterUndoSelection,f.entry.session.selectedNodes);assertEquals(afterUndoSelected,f.entry.session.selected)
            // The failed old request cannot reopen a panel for the newly restored membership.
            assertEquals(afterUndoPanel,f.entry.presentation.sourcesFor)
        }
        assertEquals(1,f.engines.last().closes.get());assertEquals(0,f.native.writes.get())
    }
    @Test fun anOriginalRemovedDuringReadingStillReturnsSafelyWithoutRestoringAStaleSource()=scenario {f->
        withContext(Dispatchers.Main) {assertTrue(f.open())};f.await {f.originals.visible && !f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertTrue(f.entry.session.operate {doc->doc.update("n") {it.copy(sources=emptyList())}})
            f.catalog.delete(f.observation)
            assertFalse(f.originals.current!!.current());assertTrue(f.navigator.close())
        }
        f.await {f.maps.visible && !f.navigator.busy}
        withContext(Dispatchers.Main) {assertNull(f.originals.current);assertNull(f.entry.sourceReturn);assertTrue(f.entry.session.document!!.node("n").sources.isEmpty())}
        assertEquals(0,f.native.writes.get())
    }
    @Test fun libraryOpenAndClosePreserveAnExistingNativeSourceReturnAndWaitForTheRealPen()=scenario {f->
        val target=f.entry.session.document!!.node("n").sources.single().target
        lateinit var bookmark:MapSourceReturnBookmark
        withContext(Dispatchers.Main) {
            bookmark=f.bookmark().second;f.entry.sourceReturn=bookmark;assertTrue(f.maps.hide(f.entry))
            parkingPen(f.native.editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
            assertTrue(f.navigator.openLibrary(target) {true})
        }
        parkingDispatch();assertTrue(withContext(Dispatchers.Main) {f.navigator.busy});assertNull(withContext(Dispatchers.Main) {f.originals.current})
        withContext(Dispatchers.Main) {f.navigator.cancel();assertTrue(f.native.editor.stylusInContact);parkingPen(f.native.editor,MotionEvent.ACTION_UP,Pt(150.0,130.0))}
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {assertTrue(f.navigator.openLibrary(target) {true})};f.await {f.originals.visible && !f.navigator.busy}
        withContext(Dispatchers.Main) {assertTrue(f.navigator.close())};f.await {f.originals.current==null && !f.navigator.busy}
        withContext(Dispatchers.Main) {assertFalse(f.maps.visible);assertSame(bookmark,f.entry.sourceReturn);assertTrue(f.native.editor.state.document.dirty)}
        assertEquals(0,f.native.writes.get())
    }
    @Test fun closingTheEntryKeepsItsResourceLeaseUntilAnAcceptedRasterAndOldHostHaveDrained()=scenario {f->
        withContext(Dispatchers.Main) {assertTrue(f.open())};f.await {f.originals.visible && !f.navigator.busy}
        val shown=withContext(Dispatchers.Main) {f.originals.current!!}
        val engine=f.engines.last();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        engine.renderEntered=entered;engine.renderRelease=release
        val reader=withContext(Dispatchers.Main) {OriginalPdfReader(f.native.context,shown,shown.pageIndex,
            engine.pageInfo(1),Palette.DEFAULT,shown.retainReader()!!).also {it.view.layout(0,0,600,600)
                val bitmap=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888);it.view.draw(Canvas(bitmap));bitmap.recycle()}}
        try {
            assertTrue(withContext(Dispatchers.IO) {entered.await(5,TimeUnit.SECONDS)})
            withContext(Dispatchers.Main) {assertTrue(f.navigator.close())};f.await {f.maps.visible && !f.navigator.busy}
            assertEquals(0,engine.closes.get())
            val managed=File(f.native.storage.pdfResources.directory,"${f.observation.record.sha256}.pdf")
            withContext(Dispatchers.IO) {
                f.catalog.delete(f.observation)
                f.native.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0 WHERE id=?",arrayOf(f.observation.record.sha256))
                assertEquals(0,f.native.storage.pdfResources.collect());assertTrue(managed.isFile)
            }
            withContext(Dispatchers.Main) {reader.retire()}
            val draining=CoroutineScope(Dispatchers.IO).async {reader.closeAndAwait()}
            delay(20);assertFalse(draining.isCompleted);assertEquals(0,engine.closes.get())
            release.countDown();draining.await()
            withTimeout(5000) {while(managed.exists()) {withContext(Dispatchers.IO) {f.native.storage.pdfResources.collect()};delay(1)}}
        } finally {release.countDown();withContext(Dispatchers.Main) {reader.retire()};withContext(Dispatchers.IO) {reader.closeAndAwait()}}
        assertEquals(1,engine.closes.get());assertEquals(0,f.native.writes.get())
    }
    @Test fun hostRecreationResumesTheSameReaderDataAndAnOldReaderCannotControlOrCloseItsReplacement()=scenario {f->
        withContext(Dispatchers.Main) {assertTrue(f.open())};f.await {f.originals.visible && !f.navigator.busy}
        val shown=withContext(Dispatchers.Main) {f.originals.current!!};val engine=f.engines.last()
        val oldRetained=withContext(Dispatchers.Main) {shown.retainReader()!!}
        val old=withContext(Dispatchers.Main) {OriginalPdfReader(f.native.context,shown,shown.pageIndex,
            engine.pageInfo(1),Palette.DEFAULT,oldRetained)}
        var replacement:OriginalPdfReader?=null
        try {
            withContext(Dispatchers.Main) {
                val saved=com.xnotes.canvas.CanvasState.ViewSnapshot(1.5,20.0,30.0,false,false)
                shown.saveCamera(oldRetained,shown.pageIndex,saved)
                assertTrue(oldRetained.current());assertSame(saved,shown.camera)
                f.sources.dispose();f.navigator.dispose()
                assertTrue(f.originals.unbindHost(f.binding))
                // There is no replacement retainReader yet: old callbacks must already be inert.
                assertFalse(oldRetained.current())
                shown.saveCamera(oldRetained,shown.pageIndex,com.xnotes.canvas.CanvasState.ViewSnapshot(4.0,90.0,120.0,false,false))
                assertSame(saved,shown.camera);assertEquals(0,engine.closes.get())
                f.binding=Any();f.bind();f.createNavigators()
                assertFalse(oldRetained.current());assertSame(saved,shown.camera)
                assertFalse(f.originals.visible);assertSame(shown,f.originals.current);assertTrue(f.originals.resumeRequested)
                assertTrue(f.navigator.resume())
            }
            f.await {f.originals.visible && !f.navigator.busy}
            withContext(Dispatchers.Main) {
                replacement=OriginalPdfReader(f.native.context,shown,shown.pageIndex,engine.pageInfo(1),Palette.DEFAULT,shown.retainReader()!!)
                old.retire();assertSame(shown,f.originals.current);assertEquals(0,engine.closes.get())
            }
            withContext(Dispatchers.IO) {old.closeAndAwait()}
            assertEquals(0,engine.closes.get())
            withContext(Dispatchers.Main) {assertTrue(f.navigator.close())};f.await {f.maps.visible && !f.navigator.busy}
        } finally {
            withContext(Dispatchers.Main) {old.retire();replacement?.retire()}
            withContext(Dispatchers.IO) {old.closeAndAwait();replacement?.closeAndAwait()}
        }
        withTimeout(5000) {while(engine.closes.get()==0) delay(1)};assertEquals(1,engine.closes.get());assertEquals(0,f.native.writes.get())
    }
    @Test fun aLegacyFileCardUsingAnOriginalUuidCannotAdoptARealNativeNoteWithThatSameUuid()=scenario {f->
        val id=f.observation.record.id
        val retryUri=android.provider.DocumentsContract.buildDocumentUri(f.native.authority,"retry").toString()
        withContext(Dispatchers.IO) {
            val note=com.xnotes.core.model.Document(mutableListOf(com.xnotes.core.model.Page(500.0,500.0))).apply {
                identity=identity.copy(documentId=id);enableReferenceAnchors()
            }
            f.native.retry.outputStream().use {f.native.codec.write(note.fixedWriteSnapshot(),it)}
            f.native.storage.registry.registerObserved(com.xnotes.format.BundleHeader.read(f.native.retry),"xnote","retry.xnote",retryUri)
            f.native.storage.openSource(retryUri,com.xnotes.core.util.DocumentKind.NOTE).use {assertEquals(id,it.version.documentId)}
        }
        val models=f.native.editor.openPanes.map {it.state.document};val histories=f.native.editor.openPanes.map {it.history}
        val before=f.native.retry.readBytes();var guarded=0;var adopted=0
        withContext(Dispatchers.Main) {
            f.sources.dispose()
            f.sources=MapSourceNavigator(f.native.editor,f.maps,f.binding,f.scope,
                guard={_,_->guarded++;MapSourceGuardResult.Ready(emptySet())},originalNavigator=f.navigator,onOpened={_,_->adopted++})
            assertTrue(f.entry.session.operate {doc->doc.update("n") {node->node.copy(kind=NodeKind.REFERENCE,fileCardId=id,
                sources=node.sources.map {it.copy(scope=SourceScope.FILE,pageId=null,originalPdf=null)})}})
            f.entry.session.cancelPendingAutosave();assertTrue(f.open())
        }
        f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {
            assertTrue(f.maps.visible);assertNull(f.originals.current);assertNull(f.entry.sourceReturn)
            assertEquals(com.xnotes.storage.NativeReferenceIssue.UNSUPPORTED,f.sources.failure)
            assertEquals(0,guarded);assertEquals(0,adopted)
            f.native.editor.openPanes.forEachIndexed {i,pane->assertSame(models[i],pane.state.document);assertSame(histories[i],pane.history)}
        }
        assertArrayEquals(before,f.native.retry.readBytes());assertEquals(0,f.native.writes.get())
    }
    @Test fun missingOrUnsupportedOriginalCatalogNeverFallsBackToTheNativeReferenceBranch()=scenario {f->
        var guarded=0;var adopted=0
        withContext(Dispatchers.Main) {
            f.sources.dispose();f.sources=MapSourceNavigator(f.native.editor,f.maps,f.binding,f.scope,
                guard={_,_->guarded++;MapSourceGuardResult.Ready(emptySet())},originalNavigator=f.navigator,onOpened={_,_->adopted++})
        }
        val saved=f.entry.session.document!!.node("n").sources.single()
        for(unsupported in listOf(false,true)) {
            if(unsupported) withContext(Dispatchers.IO) {
                f.native.storage.registry.writableDatabase.execSQL("UPDATE original_pdf_documents SET row_schema=99 WHERE id=?",arrayOf(f.observation.record.id))
            }
            withContext(Dispatchers.Main) {
                assertTrue(f.entry.session.operate {doc->doc.update("n") {it.copy(sources=listOf(if(unsupported) saved else saved.copy(documentId=java.util.UUID.randomUUID().toString())))}})
                f.entry.session.cancelPendingAutosave();assertTrue(f.open())
            }
            f.await {!f.sources.busy}
            withContext(Dispatchers.Main) {assertTrue(f.maps.visible);assertNull(f.originals.current);assertNotNull(f.sources.failure);assertEquals(0,guarded);assertEquals(0,adopted)}
        }
        assertEquals(0,f.native.writes.get())
    }
    @Test fun aNativeSourceStillOpensThroughTheActualNavigatorWhenItsIdentityDomainIsUnambiguous()=scenario {f->
        val model=f.native.editor.state.document;val history=f.native.editor.history
        val keptBytes=f.native.source.readBytes();val outgoingBefore=f.native.target.readBytes()
        // Ordinary native navigation keeps its existing save-before-detach policy for the other
        // pane. Establish that actual saved outgoing baseline; originals have separate zero-write tests.
        withContext(Dispatchers.Main) {assertTrue(f.native.editor.secondary!!.flushSplitDocument())}
        val savedOutgoing=f.native.target.readBytes();val acceptedWrites=f.native.writes.get()
        assertEquals(1,acceptedWrites);assertFalse(outgoingBefore.contentEquals(savedOutgoing))
        var guarded=0;var adopted=0
        withContext(Dispatchers.Main) {
            f.sources.dispose();f.sources=MapSourceNavigator(f.native.editor,f.maps,f.binding,f.scope,
                guard={_,_->guarded++;MapSourceGuardResult.Ready(emptySet())},originalNavigator=f.navigator,onOpened={_,_->adopted++})
            assertTrue(f.entry.session.operate {doc->doc.update("n") {node->node.copy(sources=node.sources.map {
                it.copy(documentId=model.identity.documentId,scope=SourceScope.FILE,pageId=null,originalPdf=null)
            })}})
            f.entry.session.cancelPendingAutosave();assertTrue(f.open())
        }
        f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {
            assertFalse(f.maps.visible);assertNull(f.originals.current);assertEquals(1,guarded);assertEquals(1,adopted)
            assertSame(model,f.native.editor.state.document);assertSame(history,f.native.editor.history)
            assertTrue(model.dirty);assertTrue(history.canUndo);assertTrue(f.sources.returnToMap())
        }
        f.await {f.maps.visible && !f.sources.busy}
        assertEquals(acceptedWrites,f.native.writes.get())
        assertArrayEquals(keptBytes,f.native.source.readBytes());assertArrayEquals(savedOutgoing,f.native.target.readBytes())
    }
    @Test fun aDomainEpochChangeWhileTheNativeGuardWaitsRejectsItsFinalAdoption()=scenario {f->
        val model=f.native.editor.state.document;val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var adopted=0
        withContext(Dispatchers.Main) {
            f.sources.dispose();f.sources=MapSourceNavigator(f.native.editor,f.maps,f.binding,f.scope,
                guard={_,_->entered.complete(Unit);release.await();MapSourceGuardResult.Ready(emptySet())},
                originalNavigator=f.navigator,onOpened={_,_->adopted++})
            assertTrue(f.entry.session.operate {doc->doc.update("n") {node->node.copy(sources=node.sources.map {
                it.copy(documentId=model.identity.documentId,scope=SourceScope.FILE,pageId=null,originalPdf=null)
            })}})
            f.entry.session.cancelPendingAutosave();assertTrue(f.open())
        }
        entered.await();withContext(Dispatchers.IO) {f.catalog.delete(f.observation)};release.complete(Unit)
        f.await {!f.sources.busy}
        withContext(Dispatchers.Main) {
            assertEquals(0,adopted);assertTrue(f.maps.visible);assertNull(f.originals.current)
            assertSame(model,f.native.editor.state.document);assertEquals(com.xnotes.storage.NativeReferenceIssue.CHANGED,f.sources.failure)
        }
        assertEquals(0,f.native.writes.get())
    }

}
