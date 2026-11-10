package com.xnotes.mindmap

import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.AnimationDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import com.xnotes.MainActivity
import com.xnotes.R
import com.xnotes.core.history.AddItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.DocumentCodec
import com.xnotes.pen.PenTestRunner
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.storage.NotebookStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual Main's split button, constrained native/Map Views, source row and slot Home close.
 * Recreate covers a real pending actor request, not a delivered system chooser result.
 * Compiling this source does not establish Android execution, Surface clipping or MovinkPad GPU. */
class MainMixedMapEditingHostTest {
    private val runner get()=PenTestRunner.instance
    private fun <T> main(action:()->T):T {
        var result:Result<T>?=null;runner.runOnMainSync {result=runCatching(action)}
        return requireNotNull(result).getOrThrow()
    }
    private fun await(label:String,check:()->Boolean) {
        repeat(1500) {runner.waitForIdleSync();if(check()) return;Thread.sleep(10)}
        fail("Main mixed map did not settle: $label")
    }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun graph(activity:MainActivity)=views(activity.window.decorView).filterIsInstance<MindMapView>().singleOrNull()
    private fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null) emptyList() else
        listOf(node)+(0 until node.childCount).flatMap {nodes(node.getChild(it))}
    private fun labelMatches(node:AccessibilityNodeInfo,label:String)=node.text?.toString()==label || node.contentDescription?.toString()==label
    private fun shown(label:String)=nodes(runner.uiAutomation.rootInActiveWindow).any {it.isVisibleToUser && labelMatches(it,label)}
    private fun click(label:String,inside:Rect?=null) {
        var target:AccessibilityNodeInfo?=null
        await(label) {
            target=nodes(runner.uiAutomation.rootInActiveWindow).firstOrNull {node->
                node.isVisibleToUser && node.isEnabled && node.isClickable && labelMatches(node,label) &&
                    (inside==null || Rect().also(node::getBoundsInScreen).let {inside.contains(it.centerX(),it.centerY())})
            };target!=null
        }
        assertTrue(requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync()
    }
    private fun ready(activity:MainActivity)=activity.mapWorkspace!=null && activity.documentEditor!=null &&
        views(activity.window.decorView).let {tree->tree.any {it.javaClass.name=="androidx.compose.ui.platform.AndroidComposeView" && it.width>0} &&
            tree.filterIsInstance<ImageView>().none {it.drawable is AnimationDrawable}}
    private fun join(job:Job?) {if(job!=null) runBlocking {withTimeout(15000) {job.join()}}}
    private inner class Scene(var activity:MainActivity,val folder:File,val sourceFile:File,val a:File,val b:File,
        val aId:String,val bId:String,val aSource:MapSource,val noteSource:MapSource) {
        val maps=main {requireNotNull(activity.mapWorkspace)}
        val note=main {requireNotNull(activity.documentEditor)}
        lateinit var origin:MapWorkspace.Entry
        fun originOrNull()=if(::origin.isInitialized) origin else null
        val nativeId=main {note.state.document.identity.documentId}
        val bytes=sourceFile.readBytes()
        fun uri(file:File)=android.provider.DocumentsContract.buildDocumentUriUsingTree(AppStorageDocumentsProvider.treeUri(activity),
            "root/"+file.relativeTo(AppStorageDocumentsProvider.rootDir(activity)).invariantSeparatorsPath)
        fun label(id:Int)=activity.getString(id)
        fun mixedGeometry():Pair<Rect,Rect>? {
            val native=requireNotNull(activity.documentEditor)
            val map=graph(activity) ?: return null
            val nativeView=if(maps.splitPresentation.layout?.nativePane==com.xnotes.ui.Pane.SECONDARY) native.secondary?.view else native.view
            val mapRect=Rect();val noteRect=Rect()
            if(nativeView==null || !map.getGlobalVisibleRect(mapRect) || !nativeView.getGlobalVisibleRect(noteRect) || mapRect.isEmpty || noteRect.isEmpty) return null
            if(Rect.intersects(mapRect,noteRect)) return null
            return mapRect to noteRect
        }
        fun awaitMixed() {await("two constrained actual views") {main {maps.splitPresentation.layout!=null && maps.visible && mixedGeometry()!=null}}}
        fun splitThroughButton() {click(label(R.string.map_split_open_beside));awaitMixed()}
        fun openB() {
            main {assertSame(origin,maps.current);origin.session.selected="an"}
            if(!shown(label(R.string.open))) click(label(R.string.graph_sources))
            click(label(R.string.open));await("actual B in Map slot") {main {maps.current?.session?.document?.id==bId && maps.visible && mixedGeometry()!=null}}
        }
        fun editNode(id:String,text:String) {
            val s=main {maps.current!!.session}
            main {graph(activity)!!.onEdit(id)}
            await("real slot text input") {main {s.textDraft!=null && activity.currentFocus!=null}}
            val connection=main {activity.currentFocus!!.onCreateInputConnection(EditorInfo())};assertNotNull(connection)
            main {connection!!.setSelection(0,s.textDraft!!.value.text.length);assertTrue(connection.commitText(text,1));connection.finishComposingText()}
            click(label(R.string.map_done));await("slot edit accepted") {main {s.textDraft==null && s.textRestore==null && s.document!!.node(id).text==text}}
            main {s.cancelPendingAutosave()}
        }
        fun mapSlotBounds():Rect=main {
            val (map,native)=requireNotNull(mixedGeometry())
            val window=Rect().also {activity.window.decorView.getGlobalVisibleRect(it)}
            if(map.right<=native.left || native.right<=map.left) Rect(map.left,window.top,map.right,window.bottom)
            else if(map.bottom<=native.top) Rect(window.left,window.top,window.right,map.bottom)
            else Rect(window.left,native.bottom,window.right,window.bottom)
        }
    }
    private fun scenario(test:(Scene)->Unit) {
        val base=runner.targetContext
        val existing=listOf("session","session-b").flatMap {slot->listOf("document.xnote","session.json","canvas.xcanvas","canvas-session.json").map {File(base.filesDir,"$slot/$it")}}
        val split=File(base.filesDir,"split-workspace.json")
        val splitOpen=split.exists() && runCatching {org.json.JSONObject(split.readText()).optBoolean("secondOpen",false)}.getOrDefault(true)
        assertTrue("Use an empty instrumentation profile; existing recovery is not replaced",existing.none {it.exists()} && !splitOpen)
        val folder=File(AppStorageDocumentsProvider.rootDir(base),"main-mixed-${UUID.randomUUID()}").apply {mkdirs()}
        val sourceFile=File.createTempFile("main-mixed-note-",".xnote",base.cacheDir)
        val model=Document(mutableListOf(Page(500.0,500.0),Page(500.0,500.0))).apply {enableReferenceAnchors()}
        sourceFile.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(model.fixedWriteSnapshot(),it)}
        val aId=UUID.randomUUID().toString();val bId=UUID.randomUUID().toString()
        val aSource=MapSource(documentId=bId,fileName="B.xmap")
        val noteSource=MapSource(documentId=model.identity.documentId,fileName=sourceFile.name,scope=SourceScope.PAGE,pageId=model.pages[1].referenceIdentity.id)
        val a=File(folder,"A.xmap").apply {writeBytes(MapCodec.encode(MindMapDocument(id=aId,nodes=listOf(MapNode(id="ar",parent=null),MapNode(id="an",parent="ar",text="A source",sources=listOf(aSource),primarySource=aSource.id)))))}
        val b=File(folder,"B.xmap").apply {writeBytes(MapCodec.encode(MindMapDocument(id=bId,nodes=listOf(MapNode(id="br",parent=null),MapNode(id="bn",parent="br",text="B note source",sources=listOf(noteSource),primarySource=noteSource.id)))))}
        var activity:MainActivity?=null;var scene:Scene?=null
        try {
            val host=runner.startActivitySync(Intent(base,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=host;await("Main ready") {main {ready(host)}}
            val native=main {requireNotNull(host.documentEditor)}
            main {assertTrue(native.openPanes.isEmpty());assertNull(requireNotNull(host.mapWorkspace).current)}
            runBlocking {withTimeout(15000) {withContext(Dispatchers.Main) {assertTrue(native.openSingleFromHome(Uri.fromFile(sourceFile).toString(),sourceFile.name))}}}
            val f=Scene(host,folder,sourceFile,a,b,aId,bId,aSource,noteSource);scene=f
            main {
                val doc=native.state.document;val stroke=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(100.0,100.0,.5),Sample(180.0,160.0,.7)))
                doc.pages[0].items+=stroke;native.history.push(AddItem(doc.pages[0],stroke));doc.dirty=true
                for(name in listOf("noteDebounceJob","canvasDebounceJob")) (com.xnotes.ui.Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(native) as? Job)?.cancel()
            }
            runBlocking(Dispatchers.IO) {
                val registry=NotebookStorage.get(base).registry
                registry.registerMapObserved(aId,a.name,f.uri(a).toString());registry.registerMapObserved(bId,b.name,f.uri(b).toString())
            }
            main {assertTrue(f.maps.open(f.uri(a),"A",flushNativeOnPresent=false,preparePresentation={native.awaitMapSourceParking()?.let {proof->({proof.current()})}}))}
            await("full Map setup") {main {f.maps.visible && !f.maps.opening && graph(host)?.height?.let {it>0}==true}}
            f.origin=main {f.maps.current!!};test(f);activity=f.activity
        } finally {
            scene?.let {f->
                activity=f.activity;join(main {f.maps.current?.let {f.maps.close(it)}})
                main {f.maps.splitPresentation.layout?.let {f.maps.removeSplitHome(it.mapPane)}}
                val native=main {requireNotNull(f.activity.documentEditor)}
                main {val panes=native.openPanes;if(panes.all {it.visibleDocumentId==f.nativeId}) native.goHomeAll(panes.map {it.documentOwner.discardForOpen()}.toSet())}
                await("only the owned native closes") {main {native.openPanes.isEmpty()}}
                assertNotNull(runBlocking {withTimeout(15000) {withContext(Dispatchers.Main) {native.awaitHostParking()}}})
            }
            activity?.let {main {it.finish()};runner.waitForIdleSync()};folder.deleteRecursively();sourceFile.delete()
        }
    }
    @Test fun theActualBesideButtonShowsTwoViewsAndMapSourceChildAndPageReturnsKeepTheNoteBeforeRootHomeClose()=scenario {f->
        val noteModel=main {f.note.state.document};val history=main {f.note.history};val undo=main {history.nextUndo}
        f.splitThroughButton();val layout=main {f.maps.splitPresentation.layout!!};val mapSlot=f.mapSlotBounds()
        main {assertSame(noteModel,f.note.state.document);assertSame(history,f.note.history);assertTrue(noteModel.dirty)}
        f.openB();val child=main {f.maps.current!!}
        f.editNode("bn","B edited in its own pane")
        main {child.session.undo();assertEquals("B note source",child.session.document!!.node("bn").text);child.session.redo();child.session.save()}
        await("actual B write") {main {!child.session.saving && !child.session.dirty}}
        assertEquals("B edited in its own pane",MapCodec.decode(f.b.readBytes()).node("bn").text)
        click(f.label(R.string.graph_sources));click(f.label(R.string.open))
        await("existing NOTE page stays beside Map") {main {f.maps.visible && f.maps.current===child && child.sourceReturn!=null && f.mixedGeometry()!=null}}
        main {
            assertEquals(layout.nativePane,f.maps.splitPresentation.layout!!.focusedPane)
            assertSame(noteModel,f.note.state.document);assertSame(history,f.note.history);assertSame(undo,history.nextUndo);assertTrue(noteModel.dirty)
        }
        click(f.label(R.string.graph_return_map))
        await("explicit source return focuses Map") {main {child.sourceReturn==null && f.maps.splitPresentation.layout?.focusedPane==layout.mapPane}}
        click(f.label(R.string.map_close))
        await("child Back returns A in the same slot") {main {f.maps.current===f.origin && f.maps.visible && f.mixedGeometry()!=null}}
        main {assertTrue(child.lease.alive);assertSame(noteModel,f.note.state.document);assertSame(history,f.note.history);assertEquals(f.aSource.id,f.origin.presentation.sourceFocus?.sourceId)}
        click(f.label(R.string.map_close))
        await("root Back leaves its slot Home") {main {f.maps.current==null && f.maps.splitPresentation.layout!=null && f.note.noteOpen}}
        click(f.label(R.string.split_close_document),mapSlot)
        await("closing Map Home removes only its slot") {main {f.maps.splitPresentation.layout==null && f.note.noteOpen && f.note.view.width>0}}
        main {assertSame(noteModel,f.note.state.document);assertSame(history,f.note.history);assertSame(undo,history.nextUndo)}
        assertArrayEquals(f.bytes,f.sourceFile.readBytes())
    }
    @Test fun actualMainRecreationRetainsTheMixedMapActorParentLayoutHistoryAndPendingPickerRequest()=scenario {f->
        f.splitThroughButton();f.openB();val child=main {f.maps.current!!}
        f.editNode("bn","Dirty mixed B")
        click(f.label(R.string.mixed_split_swap));click(f.label(R.string.split_change_direction));f.awaitMixed()
        val layout=main {f.maps.splitPresentation.layout!!};val model=main {child.session.document};val epoch=main {child.session.interactionEpoch}
        val token=main {child.session.beginExistingSource("bn")!!};val route=main {f.maps.sourceReturnToken}
        val monitor=runner.addMonitor(MainActivity::class.java.name,null,false)
        val next=try {main {f.activity.recreate()};runner.waitForMonitorWithTimeout(monitor,15000) as? MainActivity}
            finally {runner.removeMonitor(monitor)}
        assertNotNull(next);f.activity=requireNotNull(next)
        await("recreated mixed actual Main") {main {ready(f.activity) && f.activity.mapWorkspace===f.maps && f.maps.current===child && f.mixedGeometry()!=null}}
        main {
            assertSame(model,child.session.document);assertEquals(epoch,child.session.interactionEpoch);assertTrue(child.session.canUndo)
            assertEquals(layout,f.maps.splitPresentation.layout);assertSame(route,f.maps.sourceReturnToken)
            assertTrue(child.session.receiveExistingSource(token,null))
            val native=requireNotNull(f.activity.documentEditor)
            assertEquals(f.nativeId,native.openPanes.single().state.document.identity.documentId)
        }
        click(f.label(R.string.graph_return_parent_map));await("retained parent in mixed slot") {main {f.maps.current===f.origin && f.maps.splitPresentation.layout==layout && f.mixedGeometry()!=null}}
    }
}
