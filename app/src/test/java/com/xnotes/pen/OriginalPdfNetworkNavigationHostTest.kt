package com.xnotes.pen

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.AddCanvasItem
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Stroke
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.CanvasCodec
import com.xnotes.mindmap.*
import com.xnotes.persistNativeForMapPresentation
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.platform.AndroidTextMeasurer
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real network metadata, actors, native parking, PNG, actual View cameras and reader handoffs.
 * Only PDF parsing/pixels use an injected engine; these are not device/PDFium acceptance results. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OriginalPdfNetworkNavigationHostTest {
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val maps=MapWorkspace(native.context,scope);val networks=FileNetworkWorkspace(native.context,scope)
        val binding=Any();val catalog=OriginalPdfCatalog(native.storage.registry)
        val engineOpens=AtomicInteger();val rasterReads=AtomicInteger()
        val access=OriginalPdfAccess(native.context,engineFactory={
            engineOpens.incrementAndGet()
            object:OriginalPdfEngine {
                override val pageCount=2
                override fun pageInfo(pdfKey:Int)=OriginalPdfPageInfo(Pt(600.0,800.0),PdfPageGeometry.flipped(800.0))
                override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):AndroidRasterSurface {
                    rasterReads.incrementAndGet()
                    return AndroidRasterSurface.create(width,height).apply {bitmap.eraseColor(Color.WHITE)}
                }
                override fun closeAndAwait() {}
            }
        },storageOverride=native.storage)
        val originals=OriginalPdfWorkspace(native.context,scope,access)
        lateinit var network:FileNetworkWorkspace.Entry
        lateinit var coordinator:LocalNetworkCoordinator
        lateinit var backlinks:MapBacklinkNavigator
        lateinit var sourceNavigator:MapSourceNavigator
        lateinit var originalNavigator:OriginalPdfNavigator
        lateinit var original:OriginalPdfCatalog.Record
        lateinit var orphan:OriginalPdfCatalog.Record
        lateinit var originalSource:MapSource
        lateinit var graph:MindMapDocument
        lateinit var graphFile:File
        var liveLease:MapDocumentLease?=null
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        val frame=FrameLayout(activity.get()).also {activity.get().setContentView(it)}
        val nativeModels=mutableListOf<Any>();val nativeHistories=mutableListOf<Any>()
        lateinit var nativeBytes:ByteArray;lateinit var otherBytes:ByteArray
        var canvas=false
        suspend fun await(check:()->Boolean)=withTimeout(15000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        private fun stopNativeDebounce(editor:Editor) {
            for(name in listOf("noteDebounceJob","canvasDebounceJob"))
                (Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(editor) as? Job)?.cancel()
        }
        suspend fun start(live:Boolean,canvas:Boolean,collection:Boolean) {
            this.canvas=canvas
            if(canvas) {
                withContext(Dispatchers.IO) {native.target.outputStream().use {CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(InfiniteDocument(),it)}}
                withContext(Dispatchers.Main) {
                    assertTrue(native.editor.openCanvasAsync(native.targetUri,"Drawing.xcanvas"))
                    val editor=native.editor.infinite;val model=editor.document
                    val stroke=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(120.0,140.0,.5),Sample(170.0,190.0,.8)))
                    model.add(stroke);editor.history.push(AddCanvasItem(model,stroke));model.dirty=true
                    editor.viewport.scrollX=-345.0;editor.viewport.scrollY=678.0;editor.viewport.zoom=1.25
                    stopNativeDebounce(native.editor);nativeModels+=model;nativeHistories+=editor.history
                }
            } else {
                openParkingPanes(native)
                withContext(Dispatchers.Main) {
                    for(pane in native.editor.openPanes) {
                        parkingPen(pane,MotionEvent.ACTION_DOWN,Pt(100.0,100.0));parkingPen(pane,MotionEvent.ACTION_UP,Pt(170.0,140.0))
                        stopNativeDebounce(pane);nativeModels+=pane.state.document;nativeHistories+=pane.history
                    }
                }
            }
            nativeBytes=native.source.takeIf {it.exists()}?.readBytes() ?: byteArrayOf();otherBytes=native.target.readBytes()
            val nativeId=withContext(Dispatchers.Main) {if(canvas) native.editor.infinite.document.identity.documentId else native.editor.state.document.identity.documentId}
            withContext(Dispatchers.IO) {
                fun retain(label:String):OriginalPdfCatalog.Record {
                    val input=File(native.root,"$label.pdf").apply {writeText("immutable original $label")}
                    val candidate=access.candidate(input,label)
                    return try {(catalog.retain(candidate) as OriginalPdfCatalog.Retention.Retained).observation.record} finally {candidate.closeAndAwait()}
                }
                original=retain("Referenced original");orphan=retain("Orphan original")
                val target=MapSourceTarget(original.id,SourceScope.PDF_ORIGINAL,original.pages[1],
                    originalPdf=PdfOriginalProjection(original.sha256,1,userRegion=Rect(40.0,80.0,100.0,90.0)))
                val preview=OriginalPdfSourcePreviewBackend.load(native.context,listOf(target),setOf(target),access=access,catalog=catalog).single()
                val asset=requireNotNull(preview.asset);val rendered=requireNotNull(preview.renderedVersion)
                originalSource=MapSource(documentId=original.id,fileName=original.title,scope=target.scope,pageId=target.pageId,
                    originalPdf=target.originalPdf,note="Specific original region",status=SourceStatus.CURRENT,lastKnownPage=2,
                    previewKey=asset.key,sourceVersion=preview.rawVersion,observedVersion=rendered,acceptedPreviewVersion=rendered,
                    lastPreview=MapLastPreview(asset.key,target,rendered,1))
                graph=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,collapsed=true),
                    MapNode(id="concept",parent="root",text="Native and original",sources=listOf(
                        MapSource(documentId=nativeId,fileName=if(canvas) "Drawing.xcanvas" else "Note.xnote"),originalSource))),
                    previewAssets=mapOf(asset.key to asset))
                graphFile=File(AppStorageDocumentsProvider.rootDir(native.context),"original-network-${UUID.randomUUID()}.xmap").apply {writeBytes(MapCodec.encode(graph))}
                native.storage.registry.registerMapObserved(graph.id,graphFile.name,Uri.fromFile(graphFile).toString())
            }
            if(live) {
                liveLease=withContext(Dispatchers.Main) {MapDocuments.acquire(native.context,Uri.fromFile(graphFile))}
                await {liveLease?.session?.loading==false}
            }
            withContext(Dispatchers.Main) {
                native.editor.originalPdfAccess=access
                maps.bindHost(binding);networks.bindHost(binding);originals.bindHost(binding)
                originalNavigator=OriginalPdfNavigator(native.editor,maps,networks,originals,binding,scope)
                sourceNavigator=MapSourceNavigator(native.editor,maps,binding,scope,
                    guard={_,_->error("An original may never use native replacement")},originalNavigator=originalNavigator)
                backlinks=MapBacklinkNavigator(native.editor,maps,binding,scope,onPresented={persistNativeForMapPresentation(native.editor,it)})
                coordinator=LocalNetworkCoordinator(native.editor,maps,networks,binding,scope)
                if(canvas) {
                    assertTrue(coordinator.openLibrary(requireNotNull(LibraryNetworkLaunchRequest.capture("Library",
                        listOf(AppStorageDocumentsProvider.treeUri(native.context).toString())) {true})))
                } else assertTrue(coordinator.open(requireNotNull(LocalNetworkLaunchRequest.capture(
                    requireNotNull(native.editor.captureBacklinkContext()),"Current note") {true})))
            }
            await {networks.visible && !coordinator.busy && networks.current?.controller?.report!=null &&
                networks.current?.controller?.loading==false && networks.current?.controller?.filtering==false}
            withContext(Dispatchers.Main) {network=networks.current!!}
            val opens=engineOpens.get()
            if(collection) {
                withContext(Dispatchers.Main) {
                    assertTrue(network.controller.fileChoices.any {it.documentId==orphan.id})
                    assertTrue(network.controller.showCollection(setOf(original.id,orphan.id,nativeId)))
                }
                await {!network.controller.loading && !network.controller.filtering && network.controller.browseMode==NetworkBrowseMode.FILES}
            }
            withContext(Dispatchers.Main) {assertTrue(network.controller.applyFilter(NetworkFilter(kinds=setOf(NetworkFileKind.PDF_ORIGINAL))))}
            await {!network.controller.loading && !network.controller.filtering && network.controller.report?.matchedFiles?.contains(original.id)==true}
            withContext(Dispatchers.Main) {assertTrue(network.controller.setExpanded(graph.id,true))}
            await {!network.controller.loading && !network.controller.filtering && graph.id in network.controller.expandedFiles}
            assertEquals("Network metadata must not open or render a PDF",opens,engineOpens.get())
        }
        fun install(view:View) {
            frame.removeAllViews();frame.addView(view,FrameLayout.LayoutParams(1000,700));frame.layout(0,0,1000,700);view.layout(0,0,1000,700)
        }
        fun networkView():Pair<MindMapView,LocalNetworkAttachment> {
            val scene=network.builder.build(network.controller.report!!,LocalNetworkLabels("File","Concept","Ink","Reference","References %d","Belongs"))
            val p=network.presentation;p.reconcile(scene)
            val attach=p.attach()
            val view=MindMapView(activity.get()).apply {readOnlyNavigation=true;document=scene.document;documentEpoch=attach.epoch}
            install(view)
            val plan=p.prepareCamera(attach,LocalNetworkGeometry(1000,700,view.resources.displayMetrics.density))!!
            assertTrue(applyLocalNetworkCamera(p,view,plan))
            return view to attach
        }
        suspend fun readFromMap(entry:MapWorkspace.Entry,nodeId:String,source:MapSource):MindMapView.Viewport {
            lateinit var expected:MindMapView.Viewport
            withContext(Dispatchers.Main) {
                entry.presentation.synchronize(entry.session)
                val view=MindMapView(activity.get()).apply {document=entry.session.document!!;documentEpoch=entry.session.interactionEpoch
                    expandedAncestors=entry.session.navigationExpandedAncestors}
                install(view)
                val hadFocus=entry.presentation.pendingBacklinkFocus!=null
                if(hadFocus) assertTrue(entry.presentation.applyPendingBacklinkFocus(entry.session,view))
                entry.presentation.closeSources()
                // Closing a source panel first restores its protected normal camera. Only after
                // that real consumer completes may the user's next camera replace the bookmark.
                val restoredNormal=entry.presentation.restore(entry.session,view)
                if(hadFocus) assertTrue(restoredNormal)
                view.onViewportChanged={entry.presentation.capture(entry.session,view)}
                view.restoreViewport(MindMapView.Viewport(1.13f,67f,-29f),1000,700,view.resources.displayMetrics.density)
                assertTrue(entry.presentation.capture(entry.session,view))
                assertTrue(entry.presentation.showSources(entry.session,view,nodeId));expected=view.viewport()
                val probe=MindMapView(activity.get()).apply {document=entry.session.document!!;documentEpoch=entry.session.interactionEpoch
                    expandedAncestors=entry.session.navigationExpandedAncestors;layout(0,0,1000,700)}
                assertTrue(entry.presentation.restore(entry.session,probe))
                assertCamera(expected,probe.viewport()) // Verify the captured value even before leaving the map.
                val ticket=entry.session.captureSourceNavigation(nodeId,source.id)!!
                assertTrue(sourceNavigator.open(entry,ticket,entry.session.captureSourceReturn(ticket)!!))
            }
            await {originals.visible && !sourceNavigator.busy}
            val shown=withContext(Dispatchers.Main) {originals.current!!}
            assertEquals(source.target,shown.target)
            val retained=withContext(Dispatchers.Main) {shown.retainReader()!!}
            val info=withContext(Dispatchers.IO) {shown.read.pageInfo(com.xnotes.core.model.PdfKey.of(shown.pageIndex,source.originalPdf!!.viewTurns))}
            val reader=withContext(Dispatchers.Main) {OriginalPdfReader(activity.get(),shown,shown.pageIndex,info,Palette.DEFAULT,retained)}
            try {
                val before=rasterReads.get()
                withContext(Dispatchers.Main) {
                    assertFalse(maps.visible);assertFalse(networks.visible);assertSame(entry,network.graphReturn)
                    install(reader.view);reader.view.viewTreeObserver.dispatchOnPreDraw();reader.view.viewTreeObserver.dispatchOnPreDraw()
                    val bitmap=Bitmap.createBitmap(1000,700,Bitmap.Config.ARGB_8888)
                    try {reader.view.draw(Canvas(bitmap))} finally {bitmap.recycle()}
                    assertNull(reader.document.path);assertTrue(reader.page.items.isEmpty());assertTrue(reader.page.markups.isEmpty())
                }
                await {rasterReads.get()>before}
                withContext(Dispatchers.Main) {assertTrue(originalNavigator.close())}
                await {maps.visible && !originalNavigator.busy && originals.current==null}
                withContext(Dispatchers.Main) {
                    persistNativeForMapPresentation(native.editor,entry)
                    assertSame(entry,maps.current);assertEquals(nodeId,entry.presentation.sourcesFor)
                    assertEquals(source.id,entry.presentation.sourceFocus!!.sourceId)
                    val restored=MindMapView(activity.get()).apply {document=entry.session.document!!;documentEpoch=entry.session.interactionEpoch
                        expandedAncestors=entry.session.navigationExpandedAncestors}
                    install(restored);assertTrue(entry.presentation.restore(entry.session,restored));assertCamera(expected,restored.viewport())
                }
            } finally {withContext(Dispatchers.Main) {reader.retire()};withContext(Dispatchers.IO) {reader.closeAndAwait()}}
            return expected
        }
        suspend fun roundTripEvidence() {
            lateinit var camera:LocalNetworkCamera
            lateinit var filter:NetworkFilter
            lateinit var expanded:Set<String>
            withContext(Dispatchers.Main) {
                val (view,_)=networkView()
                view.restoreViewport(MindMapView.Viewport(.8f,93f,-41f),1000,700,view.resources.displayMetrics.density)
                camera=LocalNetworkCamera(view.viewport(),1000,700,view.resources.displayMetrics.density)
                // A real user camera observation, made after the restored network's normal frame.
                val p=network.presentation;assertTrue(p.captureCamera(p.captureInput()!!.attachment,camera))
                assertTrue(p.selectNode(NetworkSceneNodeKey.File(original.id)))
                filter=network.controller.filter;expanded=network.controller.expandedFiles.toSet()
                val concept=network.controller.report!!.concepts.single {it.key.nodeId=="concept"}
                val evidence=concept.members.single {it.row.sourceId==originalSource.id}
                assertEquals(if(liveLease==null) BacklinkOrigin.STORED else BacklinkOrigin.LIVE,evidence.row.origin)
                assertEquals(SourceStatus.CURRENT,evidence.row.status);assertNotNull(evidence.row.preview)
                val anchor=if(concept.origin?.kind==NetworkOriginKind.OWNER) evidence else
                    concept.members.first {it.fileId==(concept.origin?.fileId ?: network.query.focusId)}
                val input=p.captureInput()!!
                val request=network.controller.captureFocus(anchor,evidence)!!.withPresentationGuard {input.current() && networks.current(binding,network) && networks.visible}!!
                assertTrue(backlinks.openNetwork(request) {mapsEntry->assertTrue(networks.parkForMap(binding,network,mapsEntry))})
            }
            await {maps.visible && !backlinks.busy}
            val entry=withContext(Dispatchers.Main) {maps.current!!}
            withContext(Dispatchers.Main) {assertEquals("concept",entry.session.selected);assertEquals(originalSource.id,entry.presentation.pendingBacklinkFocus!!.sourceId)}
            readFromMap(entry,"concept",entry.session.document!!.node("concept").sources.single {it.id==originalSource.id})
            withContext(Dispatchers.Main) {assertTrue(coordinator.resume(network,entry))}
            await {networks.visible && !coordinator.busy && !network.controller.loading && !network.controller.filtering}
            withContext(Dispatchers.Main) {
                assertSame(network,networks.current);assertEquals(filter,network.controller.filter);assertEquals(expanded,network.controller.expandedFiles)
                assertEquals(NetworkSceneNodeKey.File(original.id),network.presentation.selectedNode)
                val (view,_)=networkView();assertEquals(camera.viewport,view.viewport())
            }
        }
        private fun assertCamera(expected:MindMapView.Viewport,actual:MindMapView.Viewport) {
            assertEquals("zoom",expected.zoom,actual.zoom,1e-6f)
            assertEquals("horizontal camera",expected.panX,actual.panX,.001f)
            assertEquals("vertical camera",expected.panY,actual.panY,.001f)
        }
        fun assertNativeUnchanged() {
            if(canvas) {
                val editor=native.editor.infinite
                assertSame(nativeModels.single(),editor.document);assertSame(nativeHistories.single(),editor.history)
                assertTrue(editor.document.dirty);assertTrue(editor.history.canUndo)
                assertEquals(Triple(-345.0,678.0,1.25),Triple(editor.viewport.scrollX,editor.viewport.scrollY,editor.viewport.zoom))
            } else native.editor.openPanes.forEachIndexed {i,pane->
                assertSame(nativeModels[i],pane.state.document);assertSame(nativeHistories[i],pane.history);assertTrue(pane.state.document.dirty);assertTrue(pane.history.canUndo)
            }
            if(native.source.exists()) assertArrayEquals(nativeBytes,native.source.readBytes())
            assertArrayEquals(otherBytes,native.target.readBytes());assertEquals(0,native.writes.get())
        }
        suspend fun finish()=withContext(NonCancellable) {
            withContext(Dispatchers.Main) {
                if(this@Fixture::sourceNavigator.isInitialized) sourceNavigator.dispose()
                if(this@Fixture::originalNavigator.isInitialized) originalNavigator.dispose()
                if(this@Fixture::backlinks.isInitialized) backlinks.dispose()
                if(this@Fixture::coordinator.isInitialized) coordinator.dispose()
                frame.removeAllViews();networks.dispose();maps.dispose()
            }.join()
            withContext(Dispatchers.Main) {originals.dispose()}.join()
            withContext(Dispatchers.Main) {liveLease?.close(preserveDirty=true)}?.join()
            scope.cancel();withContext(Dispatchers.Main) {activity.pause().stop().destroy()}
            if(this@Fixture::graphFile.isInitialized) graphFile.delete()
        }
    }
    private fun scenario(live:Boolean,canvas:Boolean,collection:Boolean,block:suspend(Fixture)->Unit)=runParkingScenario {native->
        val fixture=withContext(Dispatchers.Main) {Fixture(native)}
        try {fixture.start(live,canvas,collection);block(fixture)} finally {fixture.finish()}
    }
    @Test fun storedLocalOriginalEvidenceReturnsThroughTheExactMapAndNetworkWhileBothDirtyNotesRemainUntouched()=
        scenario(live=false,canvas=false,collection=false) {f->f.roundTripEvidence();withContext(Dispatchers.Main) {f.assertNativeUnchanged()}}
    @Test fun liveCollectionAndOrphanCreateCanonicalMixedCardsThenReadAndReturnWithoutSavingTheParkedCanvas()=
        scenario(live=true,canvas=true,collection=true) {f->
            f.roundTripEvidence()
            val destination=File(AppStorageDocumentsProvider.rootDir(f.native.context),"mixed-original-cards-${UUID.randomUUID()}.xmap").apply {writeBytes(byteArrayOf())}
            var flow:NetworkToCanvasController?=null
            try {
                withContext(Dispatchers.Main) {
                    val scene=f.network.builder.build(f.network.controller.report!!,LocalNetworkLabels("File","Concept","Ink","Reference","References %d","Belongs"))
                    val selection=NetworkCanvasSelection.capture(f.network,scene)!!
                    assertEquals(3,selection.seeds.size)
                    for(seed in selection.seeds.filter {it.target.scope==SourceScope.PDF_ORIGINAL}) {
                        assertEquals(0,seed.target.originalPdf!!.pageIndex);assertNull(seed.target.originalPdf!!.userRegion)
                    }
                    flow=NetworkToCanvasController(f.native.context,selection,f.scope)
                    assertTrue(flow!!.chooseNew(Uri.fromFile(destination)));assertTrue(flow!!.confirm())
                }
                f.await {flow!!.step==NetworkCanvasStep.ADDED && !flow!!.busy}
                withContext(Dispatchers.Main) {assertTrue(f.coordinator.openCanvas(f.network,flow!!.captureOpen()!!))}
                f.await {f.maps.visible && !f.coordinator.busy}
                val entry=withContext(Dispatchers.Main) {f.maps.current!!}
                lateinit var card:MapNode
                withContext(Dispatchers.Main) {
                    persistNativeForMapPresentation(f.native.editor,entry)
                    val doc=entry.session.document!!;card=doc.nodes.single {it.fileCardId==f.original.id}
                    val other=doc.nodes.first {it.fileCardId!=f.original.id}
                    assertTrue(entry.session.changeRelation(null,MapRelation(from=card.id,to=other.id,kind=RelationKind.DEPENDS_ON,label="Read next")) is RelationEditResult.Applied)
                    entry.session.cancelPendingAutosave();assertTrue(entry.session.saveAttempt().await() is MapSaveOutcome.Written)
                }
                val reopened=MapCodec.decode(destination.readBytes());assertEquals(6,MapCodec.toJson(reopened).getInt("version"))
                assertEquals(3,reopened.nodes.size);assertEquals(1,reopened.relations.size)
                f.readFromMap(entry,card.id,card.sources.single())
                withContext(Dispatchers.Main) {assertTrue(f.coordinator.resume(f.network,entry,closeMap=true))}
                f.await {f.networks.visible && !f.coordinator.busy}
                withContext(Dispatchers.Main) {f.assertNativeUnchanged()}
            } finally {withContext(Dispatchers.Main) {flow?.close()}?.join();destination.delete()}
        }
}
