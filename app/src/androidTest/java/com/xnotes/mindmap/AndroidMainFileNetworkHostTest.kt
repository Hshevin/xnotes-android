package com.xnotes.mindmap

import android.content.Intent
import android.graphics.Rect as AndroidRect
import android.graphics.drawable.AnimationDrawable
import android.net.Uri
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import com.xnotes.MainActivity
import com.xnotes.R
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.DocumentCodec
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.pen.PenTestRunner
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.Editor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Main, sidebar, network Canvas, evidence actions and source return. Source compilation only. */
class AndroidMainFileNetworkHostTest {
    private val runner get()=PenTestRunner.instance
    private fun <T> main(action:()->T):T {
        var result:Result<T>?=null;runner.runOnMainSync {result=runCatching(action)}
        return requireNotNull(result).getOrThrow()
    }
    private fun waitUntil(label:String,check:()->Boolean) {
        repeat(1200) {runner.waitForIdleSync();if(check()) return;Thread.sleep(10)}
        fail("Actual Main file network did not settle: $label")
    }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun graph(activity:MainActivity)=views(activity.window.decorView).filterIsInstance<MindMapView>().singleOrNull()
    private fun tree(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null) emptyList() else
        listOf(node)+(0 until node.childCount).flatMap {tree(node.getChild(it))}
    private fun nodes()=tree(runner.uiAutomation.rootInActiveWindow)
    private fun matches(node:AccessibilityNodeInfo,label:String)=node.text?.toString()==label || node.contentDescription?.toString()==label
    private fun visible(label:String)=nodes().any {it.isVisibleToUser && matches(it,label)}
    private fun clickable(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
        var at:AccessibilityNodeInfo?=node
        repeat(6) {val value=at;if(value?.isClickable==true && value.isEnabled && value.isVisibleToUser) return value;at=value?.parent}
        return null
    }
    private fun scroll():Boolean=nodes().filter {it.isVisibleToUser && it.isScrollable}.maxByOrNull {
        AndroidRect().also(it::getBoundsInScreen).height()
    }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)==true
    private fun click(label:String) {
        var target:AccessibilityNodeInfo?=null
        waitUntil(label) {target=nodes().filter {it.isVisibleToUser && matches(it,label)}.firstNotNullOfOrNull(::clickable);target!=null}
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync()
    }
    private fun clickScrolled(label:String) {
        repeat(35) {
            runner.waitForIdleSync()
            val target=nodes().filter {it.isVisibleToUser && matches(it,label)}.firstNotNullOfOrNull(::clickable)
            if(target!=null) {assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync();return}
            assertTrue("Dialog action remains reachable: $label",scroll());Thread.sleep(30)
        }
        fail("No visible dialog action: $label")
    }
    private fun openSourceRow(note:String,action:String) {
        var target:AccessibilityNodeInfo?=null
        repeat(35) {
            runner.waitForIdleSync()
            val anchor=nodes().firstOrNull {it.isVisibleToUser && it.text?.toString()==note}
            if(anchor!=null) {
                val at=AndroidRect().also(anchor::getBoundsInScreen)
                target=nodes().filter {it.isVisibleToUser && matches(it,action)}.mapNotNull(::clickable).distinctBy {
                    val bounds=AndroidRect().also(it::getBoundsInScreen);listOf(bounds.left,bounds.top,bounds.right,bounds.bottom)
                }.filter {AndroidRect().also(it::getBoundsInScreen).centerY()>at.centerY()}.minByOrNull {
                    AndroidRect().also(it::getBoundsInScreen).centerY()-at.centerY()
                }
            }
            if(target!=null) {assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync();return}
            assertTrue("Specific source and its action must remain reachable: $note",scroll());Thread.sleep(30)
        }
        fail("No actual source-row action for $note")
    }
    private fun center(view:MindMapView):Pt {
        val camera=view.viewport();val scale=view.contentScale()
        return Pt((view.width/2.0-camera.panX)/scale,(view.height/2.0-camera.panY)/scale)
    }
    private fun positions(document:MindMapDocument)=document.nodes.associate {it.id to Pt(it.x,it.y)}
    private fun seed(file:File):Document {
        val document=Document(mutableListOf(Page(500.0,500.0),Page(500.0,500.0))).apply {enableReferenceAnchors()}
        file.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(document.fixedWriteSnapshot(),it)}
        return document
    }
    private fun join(job:Job?) {if(job!=null) runBlocking {withTimeout(15000) {job.join()}}}

    @Test fun theActualMainSidebarOpensCoSourceTAndReturnsToTheOriginalSNetworkWithItsCameraAndExplanation() {
        val base=runner.targetContext
        val existing=listOf("session","session-b").flatMap {slot->listOf("document.xnote","session.json","canvas.xcanvas","canvas-session.json").map {File(base.filesDir,"$slot/$it")}}
        val split=File(base.filesDir,"split-workspace.json")
        val splitOpen=split.exists() && runCatching {org.json.JSONObject(split.readText()).optBoolean("secondOpen",false)}.getOrDefault(true)
        assertTrue("Run on an empty instrumentation profile; existing recovery data is never replaced",existing.none {it.exists()} && !splitOpen)
        val source=File.createTempFile("network-source-s-",".xnote",base.cacheDir)
        val targetFolder=File(AppStorageDocumentsProvider.rootDir(base),"network-filter-${UUID.randomUUID()}").apply {mkdirs()}
        val target=File(targetFolder,"Network target T.xnote").apply {createNewFile()}
        val map=File(AppStorageDocumentsProvider.rootDir(base),"network-main-${UUID.randomUUID()}.xmap")
        val mapUri=DocumentsContract.buildDocumentUriUsingTree(AppStorageDocumentsProvider.treeUri(base),"root/${map.name}")
        val s=seed(source);val t=seed(target);assertNotEquals(s.identity.documentId,t.identity.documentId)
        val anchor=MapSource(documentId=s.identity.documentId,fileName=source.name,scope=SourceScope.PAGE,
            pageId=s.pages[0].referenceIdentity.id,lastKnownPage=1,note="The original S membership")
        val coSource=MapSource(documentId=t.identity.documentId,fileName=target.name,scope=SourceScope.PAGE,
            pageId=t.pages[1].referenceIdentity.id,lastKnownPage=2,note="Actual co-source T permanent second page")
        val laterSources=listOf(anchor.copy(id=UUID.randomUUID().toString(),note="Added concept S membership"),
            coSource.copy(id=UUID.randomUUID().toString(),note="Added concept T membership"))
        val labels=LocalNetworkLabels(base.getString(R.string.network_file),base.getString(R.string.network_concept),base.getString(R.string.map_ink_node),
            base.getString(R.string.graph_reference),base.getString(R.string.network_reference_count),base.getString(R.string.graph_belongs))
        val twoConcepts=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Network owner"),
            MapNode(id="layout-one",parent="root",sources=listOf(anchor,coSource)),MapNode(id="layout-two",parent="root",sources=laterSources)))
        val fixtureQuery=NetworkQuery(s.identity.documentId,source.name,emptyList())
        val location=BacklinkLocation(mapUri,setOf(mapUri.toString()),MapSaves.key(mapUri),twoConcepts.id,map.name)
        val projected=MapBacklinkLibrary(base,emptyList()).projectNetwork(twoConcepts,s.identity.documentId,location,BacklinkOrigin.STORED,false)
        val referenceScene=LocalNetworkSceneBuilder().build(LocalNetworkAssembler.assemble(fixtureQuery,projected.concepts,issuer=Any(),generation=0),labels)
        fun referencePoint(nodeId:String):Pt {
            val renderId=referenceScene.nodes.entries.single {(_,key)->key is NetworkSceneNodeKey.Concept && key.key.nodeId==nodeId}.key
            val node=referenceScene.document.node(renderId);return Pt(node.x,node.y)
        }
        // Choose the initial concept from the later fresh-layout slot deterministically, using the
        // real public adapter. Adding the other concept later must preserve the first one's old slot.
        val conceptId=listOf("layout-one","layout-two").maxBy {referencePoint(it).y}
        val addedConceptId=listOf("layout-one","layout-two").single {it!=conceptId}
        val conceptRenderId=referenceScene.nodes.entries.single {(_,key)->key is NetworkSceneNodeKey.Concept && key.key.nodeId==conceptId}.key
        val original=MindMapDocument(id=twoConcepts.id,nodes=listOf(MapNode(id="root",parent=null,text="Network owner"),
            MapNode(id=conceptId,parent="root",text="A concept connects its two independent files",sources=listOf(anchor,coSource),primarySource=anchor.id)))
        val addedConcept=MapNode(id=addedConceptId,parent="root",text="An added concept keeps the first one in place",sources=laterSources,primarySource=laterSources[0].id)
        map.writeBytes(MapCodec.encode(original))
        val sourceBytes=source.readBytes();val targetBytes=target.readBytes()
        var activity:MainActivity?=null;var native:Editor?=null;var maps:MapWorkspace?=null;var networks:FileNetworkWorkspace?=null
        val ownedIds=setOf(s.identity.documentId,t.identity.documentId)
        try {
            val host=runner.startActivitySync(Intent(base,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=host
            waitUntil("Main restored its real empty workspace") {main {
                host.documentEditor!=null && host.mapWorkspace!=null && host.networkWorkspace!=null && views(host.window.decorView).let {all->
                    all.any {it.javaClass.name=="androidx.compose.ui.platform.AndroidComposeView" && it.width>0 && it.height>0} &&
                        all.filterIsInstance<ImageView>().none {it.drawable is AnimationDrawable}
                }
            }}
            val editor=main {requireNotNull(host.documentEditor)};native=editor
            val graphWorkspace=main {requireNotNull(host.mapWorkspace)};maps=graphWorkspace
            val networkWorkspace=main {requireNotNull(host.networkWorkspace)};networks=networkWorkspace
            main {assertTrue("Existing native owners must never be discarded by this fixture",editor.openPanes.isEmpty())
                assertNull(graphWorkspace.current);assertNull(networkWorkspace.current)}
            runBlocking {withTimeout(15000) {
                withContext(Dispatchers.IO) {
                    val storage=NotebookStorage.get(host)
                    storage.registry.registerMapObserved(original.id,map.name,mapUri.toString())
                    val actualT=BundleReferenceInfo.inspectStrict(target)
                    assertEquals(t.identity.documentId,actualT.header.identity!!.documentId)
                    assertEquals(coSource.pageId,actualT.orderedPageIds[1])
                    storage.registry.registerObserved(actualT.header,"xnote",target.name,Uri.fromFile(target).toString())
                }
                withContext(Dispatchers.Main) {assertTrue(editor.openSingleFromHome(Uri.fromFile(source).toString(),source.name));editor.sidebarVisible=true}
            }}
            val originalNative=main {editor.state.document}
            click(host.getString(R.string.graph_backlinks_title))
            waitUntil("real sidebar current-page relation") {visible(anchor.note)}
            click(host.getString(R.string.network_title))
            waitUntil("owned network and actual readonly canvas") {main {
                networkWorkspace.visible && networkWorkspace.current?.let {entry->
                    !entry.controller.loading && entry.controller.report?.concepts?.any {it.location.graphId==original.id}==true &&
                        entry.presentation.cameraReady && graph(host)?.let {it.readOnlyNavigation && it.isAttachedToWindow}==true
                }==true
            }}
            val entry=main {networkWorkspace.current!!}
            assertTrue(visible(host.getString(R.string.network_explanation)))
            assertTrue(visible(host.getString(R.string.network_semantics)))
            click("A concept connects its two independent files")
            waitUntil("concept evidence contains the co-source rather than only center S") {visible(coSource.note)}
            // D2a: actual controls use the complete source catalog, never the 250-node scene.
            val filterView=main {graph(host)!!}
            val filterCamera=main {filterView.zoomBy(1.17f);filterView.panText(29f,-17f);filterView.viewport()}
            val filterSize=main {filterView.width to filterView.height}
            val filterPositions=main {positions(filterView.document)}
            val filterMapBytes=map.readBytes()
            click(host.getString(R.string.network_filter))
            clickScrolled(host.getString(R.string.network_filter_none)) // first section: source files
            clickScrolled(target.name)
            click(host.getString(R.string.cancel))
            main {assertNull(entry.controller.filter.fileIds);assertFalse(entry.controller.filter.active)}
            click(host.getString(R.string.network_filter))
            clickScrolled(host.getString(R.string.network_filter_none))
            clickScrolled(target.name)
            click(host.getString(R.string.apply))
            waitUntil("applied exact source T collection with full concept evidence") {main {
                !entry.controller.filtering && entry.controller.filter.fileIds==setOf(t.identity.documentId) &&
                    entry.controller.report?.concepts?.singleOrNull()?.members?.map {it.row.sourceId}?.toSet()==setOf(anchor.id,coSource.id)
            }}
            main {
                val report=entry.controller.report!!
                assertEquals(setOf(s.identity.documentId,t.identity.documentId,original.id),report.files.map {it.documentId}.toSet())
                assertEquals(setOf(s.identity.documentId,t.identity.documentId),report.concepts.single().members.map {it.fileId}.toSet())
                assertTrue(report.filterActive);assertTrue(t.identity.documentId in report.matchedFiles)
                assertSame(filterView,graph(host));assertEquals(filterSize,filterView.width to filterView.height)
                assertEquals(filterCamera,filterView.viewport());assertEquals(filterPositions,positions(filterView.document))
            }
            click(host.getString(R.string.network_filter))
            clickScrolled(host.getString(R.string.network_filter_folder_internal))
            clickScrolled(targetFolder.name)
            click(host.getString(R.string.network_filter_folder_use))
            click(host.getString(R.string.apply))
            waitUntil("application-owned child directory restricts sources without changing browseRoot") {main {
                !entry.controller.filtering && entry.controller.filter.folder?.let {
                    DocumentsContract.getDocumentId(Uri.parse(it.uri))=="root/${targetFolder.name}"
                }==true && entry.controller.report?.concepts?.singleOrNull()?.members?.size==2
            }}
            main {
                assertSame(originalNative,editor.state.document);assertFalse(originalNative.dirty)
                assertEquals(filterCamera,graph(host)!!.viewport());assertEquals(filterSize,graph(host)!!.let {it.width to it.height})
                assertEquals(filterPositions,positions(graph(host)!!.document));assertFalse(graphWorkspace.visible)
            }
            assertArrayEquals(sourceBytes,source.readBytes());assertArrayEquals(targetBytes,target.readBytes())
            assertArrayEquals(filterMapBytes,map.readBytes())
            // The original concrete T open/return assertions below also execute under both filters.
            // This is a real closed graph, not the easier already-live case. Its first own acquire
            // must expose LOADING to the controller without replacing the diagram being checked.
            val locationKey=MapSaves.key(mapUri)
            val oldView=main {graph(host)!!};val diagram=main {oldView.document}
            val oldSelection=main {entry.presentation.selectedNode}
            val oldGeometry=main {oldView.width to oldView.height}
            val oldViewport=main {oldView.viewport()}
            val oldPositions=main {positions(oldView.document)}
            val input=main {entry.presentation.captureInput()!!}
            main {
                assertTrue(MapDocuments.observations(host).none {it.key==locationKey})
                assertTrue(entry.controller.report!!.concepts.single().members.all {it.row.origin==BacklinkOrigin.STORED && it.row.membership==null})
                assertTrue(oldSelection is NetworkSceneNodeKey.Concept)
            }
            val publication=MapSaves.lockFor(mapUri)
            runBlocking {publication.lock()}
            try {
                openSourceRow(coSource.note,host.getString(R.string.network_open_reference))
                waitUntil("own actual actor is loading while the controller reports current facts") {main {
                    networkWorkspace.visible && MapDocuments.observations(host).any {it.key==locationKey && it.loading} &&
                        entry.controller.report?.let {it.concepts.isEmpty() && it.issues.any {issue->issue.problem==BacklinkProblem.LOADING}}==true
                } && visible(host.getString(R.string.network_opening_snapshot))}
                main {
                    assertSame(oldView,graph(host));assertSame(diagram,graph(host)!!.document)
                    assertEquals(oldGeometry,graph(host)!!.let {it.width to it.height})
                    assertEquals(oldViewport,graph(host)!!.viewport());assertEquals(oldSelection,entry.presentation.selectedNode)
                    assertEquals(oldPositions,positions(graph(host)!!.document));assertFalse(entry.controller.report!!.complete)
                    assertTrue("A self-created LOADING overlay must not retire this original input ticket",input.current())
                    assertTrue(entry.presentation.cameraReady);assertFalse(graphWorkspace.visible)
                }
                click(host.getString(R.string.cancel))
                waitUntil("cancel releases the held illustration and displays the current LOADING projection") {main {
                    networkWorkspace.visible && !graphWorkspace.visible && entry.controller.report?.concepts?.isEmpty()==true &&
                        entry.presentation.selectedNode==null && graph(host)?.document?.nodes?.size==1
                } && !visible(host.getString(R.string.network_opening_snapshot))}
                assertFalse(main {input.current()})
            } finally {publication.unlock()}
            waitUntil("cancelled own actor finishes without a hidden writer and the library rereads the actual graph") {main {
                networkWorkspace.visible && !entry.controller.loading && entry.presentation.cameraReady &&
                    MapDocuments.observations(host).none {it.key==locationKey} &&
                    entry.controller.report?.concepts?.singleOrNull()?.members?.all {it.row.origin==BacklinkOrigin.STORED}==true
            }}
            main {assertNull("Cancel reads current data; it does not silently restore the old selection",entry.presentation.selectedNode)}
            assertArrayEquals(sourceBytes,source.readBytes());assertArrayEquals(targetBytes,target.readBytes())
            click("A concept connects its two independent files")
            waitUntil("latest concept and T source can be selected again") {visible(coSource.note)}
            val initialPosition=main {positions(graph(host)!!.document).getValue(conceptRenderId)}
            assertNotEquals("Fixture must distinguish a fresh sorted layout from the retained slot",referencePoint(conceptId),initialPosition)
            map.writeBytes(MapCodec.encode(original.copy(nodes=original.nodes+addedConcept)))
            click(host.getString(R.string.network_refresh))
            waitUntil("the real refreshed file contains both concepts without resetting its stable positions") {main {
                !entry.controller.loading && entry.controller.report?.concepts?.size==2 && entry.presentation.cameraReady
            }}
            main {
                assertEquals(initialPosition,positions(graph(host)!!.document).getValue(conceptRenderId))
                assertEquals(NetworkSceneNodeKey.Concept(NetworkConceptKey(locationKey,original.id,conceptId)),entry.presentation.selectedNode)
            }
            val camera=main {
                assertSame(originalNative,editor.state.document);assertEquals(s.identity.documentId,entry.query.focusId)
                val view=graph(host)!!;view.zoomBy(1.2f);view.panText(53f,-31f)
                view.viewport() to center(view)
            }
            val selection=main {entry.presentation.selectedNode}
            val successView=main {graph(host)!!};val successDiagram=main {successView.document}
            val stablePositions=main {positions(successDiagram)};val successGeometry=main {successView.width to successView.height}
            val successViewport=main {successView.viewport()};val successInput=main {entry.presentation.captureInput()!!}
            runBlocking {publication.lock()}
            try {
                openSourceRow(coSource.note,host.getString(R.string.network_open_reference))
                waitUntil("the second own stored load keeps its actual diagram and input proof") {main {
                    networkWorkspace.visible && MapDocuments.observations(host).any {it.key==locationKey && it.loading} &&
                        entry.controller.report?.let {it.concepts.isEmpty() && !it.complete && it.issues.any {issue->issue.problem==BacklinkProblem.LOADING}}==true
                }}
                main {
                    assertSame(successView,graph(host));assertSame(successDiagram,graph(host)!!.document)
                    assertEquals(stablePositions,positions(graph(host)!!.document));assertEquals(selection,entry.presentation.selectedNode)
                    assertEquals(successGeometry,graph(host)!!.let {it.width to it.height});assertEquals(successViewport,graph(host)!!.viewport())
                    assertTrue(successInput.current());assertTrue(entry.presentation.cameraReady)
                }
            } finally {publication.unlock()}
            waitUntil("actual map with specific T source panel") {main {
                graphWorkspace.visible && !networkWorkspace.visible && graphWorkspace.current?.let {mapEntry->
                    mapEntry.session.document?.id==original.id && mapEntry.session.selected==conceptId && mapEntry.presentation.sourcesFor==conceptId
                }==true
            } && nodes().any {it.isEditable && it.isVisibleToUser && it.text?.toString()==coSource.note}}
            val mapEntry=main {graphWorkspace.current!!};val actor=main {mapEntry.session}
            main {assertFalse(mapEntry.flushNativeOnPresent);assertFalse(actor.canUndo)
                assertEquals(coSource.id,actor.document!!.node(conceptId).sources[1].id)}
            openSourceRow(coSource.note,host.getString(R.string.open))
            waitUntil("actual native T on its permanent second page") {main {
                !graphWorkspace.visible && !networkWorkspace.visible && editor.noteOpen && editor.state.document.identity.documentId==t.identity.documentId &&
                    editor.currentPage?.referenceIdentity?.id==coSource.pageId
            }}
            main {assertEquals(s.identity.documentId,entry.query.focusId);assertEquals(coSource.id,mapEntry.sourceReturn!!.source.sourceId)}
            assertArrayEquals(sourceBytes,source.readBytes());assertArrayEquals(targetBytes,target.readBytes())
            click(host.getString(R.string.graph_return_map))
            waitUntil("returning to same graph actor") {main {graphWorkspace.visible && graphWorkspace.current===mapEntry && mapEntry.presentation.sourcesFor==conceptId}}
            click(host.getString(R.string.network_return))
            waitUntil("same network S, same camera and concept explanation") {main {
                val view=graph(host)
                networkWorkspace.visible && !graphWorkspace.visible && networkWorkspace.current===entry && entry.presentation.cameraReady &&
                    view!=null && view.readOnlyNavigation && kotlin.math.abs(view.viewport().zoom-camera.first.zoom)<.00001f &&
                    kotlin.math.abs(center(view).x-camera.second.x)<.02 && kotlin.math.abs(center(view).y-camera.second.y)<.02
            }}
            main {assertEquals(selection,entry.presentation.selectedNode);assertEquals(s.identity.documentId,entry.query.focusId)
                assertSame(actor,graphWorkspace.current!!.session);assertEquals(t.identity.documentId,editor.state.document.identity.documentId)
                assertEquals(listOf(anchor.id,coSource.id),actor.document!!.node(conceptId).sources.map {it.id})
                assertEquals("A concept connects its two independent files",actor.document!!.node(conceptId).text)
                assertEquals("Unshown LOADING must not clear the builder's incrementally established positions",stablePositions,positions(graph(host)!!.document))}
            waitUntil("returned explanation remains reachable") {visible(coSource.note)}
            assertArrayEquals(sourceBytes,source.readBytes());assertArrayEquals(targetBytes,target.readBytes())
        } finally {
            // Only this test's pure network, graph URI and two freshly-created native identities may be closed.
            networks?.let {main {it.dispose()}}
            maps?.let {workspace->join(main {workspace.current?.takeIf {MapSaves.key(it.uri)==MapSaves.key(mapUri)}?.let(workspace::close)})}
            native?.let {editor->
                main {val panes=editor.openPanes;if(panes.all {it.visibleDocumentId in ownedIds})
                    editor.goHomeAll(panes.map {it.documentOwner.discardForOpen()}.toSet())}
                waitUntil("test-owned native slots close") {main {editor.openPanes.isEmpty()}}
                assertNotNull(runBlocking {withTimeout(15000) {withContext(Dispatchers.Main) {editor.awaitHostParking()}}})
            }
            activity?.let {main {it.finish()};runner.waitForIdleSync()}
            source.delete();target.delete();targetFolder.delete();map.delete()
        }
    }
}
