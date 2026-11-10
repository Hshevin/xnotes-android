package com.xnotes.mindmap

import android.app.Activity
import android.content.Intent
import android.graphics.Rect as AndroidRect
import android.graphics.drawable.AnimationDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.ImageView
import com.xnotes.MainActivity
import com.xnotes.R
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.DocumentCodec
import com.xnotes.format.CanvasCodec
import com.xnotes.format.BundleHeader
import com.xnotes.persistNativeForMapPresentation
import com.xnotes.storage.NotebookStorage
import com.xnotes.pen.PenTestRunner
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.ui.Editor
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real Main, native owners, source guard dialogs and platform IME. Compiled, not device-accepted. */
class AndroidMainMapSourceNavigationHostTest {
    private val runner get()=PenTestRunner.instance
    private fun <T> main(action:()->T):T {
        var result:Result<T>?=null;runner.runOnMainSync {result=runCatching(action)}
        return requireNotNull(result).getOrThrow()
    }
    private fun waitUntil(label:String,check:()->Boolean) {
        repeat(1200) {runner.waitForIdleSync();if(check()) return;Thread.sleep(10)}
        fail("Actual Main source navigation did not settle: $label")
    }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun graph(activity:MainActivity)=views(activity.window.decorView).filterIsInstance<MindMapView>().singleOrNull()
    private fun all(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null) emptyList() else
        listOf(node)+(0 until node.childCount).flatMap {all(node.getChild(it))}
    private fun nodes()=all(runner.uiAutomation.rootInActiveWindow)
    private fun button(label:String)=nodes().firstOrNull {it.isClickable && it.isVisibleToUser && it.text?.toString()==label}
    private fun visible(label:String)=nodes().any {it.isVisibleToUser && it.text?.toString()?.contains(label)==true}
    private fun click(label:String) {
        var target:AccessibilityNodeInfo?=null
        waitUntil(label) {target=button(label);target?.isEnabled==true}
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync()
    }
    private fun field(value:String)=nodes().firstOrNull {it.isEditable && it.isVisibleToUser && it.text?.toString()==value}
    private fun scroll(forward:Boolean):Boolean = nodes().firstOrNull {it.isScrollable && it.isVisibleToUser}
        ?.performAction(if(forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)==true
    private fun showField(value:String):AccessibilityNodeInfo {
        repeat(15) {field(value)?.let {return it};assertTrue("Source row is not reachable: $value",scroll(true));runner.waitForIdleSync();Thread.sleep(40)}
        return requireNotNull(field(value)) {"Source note did not appear: $value"}
    }
    private fun openRow(activity:MainActivity,note:String) {
        val anchor=showField(note);val bounds=AndroidRect().also(anchor::getBoundsInScreen)
        val choices=nodes().filter {it.isClickable && it.isVisibleToUser && it.isEnabled && it.text?.toString()==activity.getString(R.string.open)}
        val selected=choices.minByOrNull {node->kotlin.math.abs(AndroidRect().also(node::getBoundsInScreen).centerY()-bounds.centerY())}
        assertNotNull("The specific source row has no reachable Open action",selected)
        assertTrue(selected!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync()
    }
    private fun center(view:MindMapView):Pt {
        val camera=view.viewport();val scale=view.contentScale()
        return Pt((view.width/2.0-camera.panX)/scale,(view.height/2.0-camera.panY)/scale)
    }
    private fun join(job:Job?) {if(job!=null) runBlocking {withTimeout(15000) {job.join()}}}
    private class Scene(val activity:MainActivity,val source:File,val map:File,val native:Editor,val maps:MapWorkspace,
        val sourceModel:Document,val first:MapSource,val second:MapSource,val other:Editor?) {
        lateinit var entry:MapWorkspace.Entry
        fun entryOrNull()=if(::entry.isInitialized) entry else null
        val sourceBytes=source.readBytes()
        val ownedIds=setOfNotNull(sourceModel.identity.documentId,other?.state?.document?.identity?.documentId)
    }
    private fun scenario(scratch:Boolean=false,block:(Scene)->Unit) {
        val base=runner.targetContext
        val existing=listOf("session","session-b").flatMap {slot->listOf("document.xnote","session.json","canvas.xcanvas","canvas-session.json").map {File(base.filesDir,"$slot/$it")}}
        val split=File(base.filesDir,"split-workspace.json")
        val splitOpen=split.exists() && runCatching {org.json.JSONObject(split.readText()).optBoolean("secondOpen",false)}.getOrDefault(true)
        assertTrue("Run on an empty instrumentation profile; no existing recovery files are changed",existing.none {it.exists()} && !splitOpen)
        val source=File.createTempFile("main-map-source-",".xnote",base.cacheDir)
        val map=File.createTempFile("main-source-graph-",".xmap",base.cacheDir)
        var activity:MainActivity?=null;var scene:Scene?=null
        try {
            val host=runner.startActivitySync(Intent(base,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=host
            waitUntil("Main finishes restoring its real profile") {main {
                host.mapWorkspace!=null && host.documentEditor!=null && views(host.window.decorView).let {tree->
                    tree.any {it.javaClass.name=="androidx.compose.ui.platform.AndroidComposeView" && it.width>0} &&
                        tree.filterIsInstance<ImageView>().none {it.drawable is AnimationDrawable}
                }
            }}
            val native=main {requireNotNull(host.documentEditor)};val maps=main {requireNotNull(host.mapWorkspace)}
            // Never replace/discard an existing user's document or map just to establish this fixture.
            main {assertTrue("Run on an isolated instrumentation profile; existing native documents are preserved",native.openPanes.isEmpty())
                assertNull("An existing non-test map must not be closed by this fixture",maps.current)}
            val seeded=Document(mutableListOf(Page(500.0,500.0),Page(500.0,500.0))).apply {enableReferenceAnchors()}
            source.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(seeded.fixedWriteSnapshot(),it)}
            runBlocking {withTimeout(15000) {withContext(Dispatchers.Main) {
                assertTrue(native.openSingleFromHome(Uri.fromFile(source).toString(),source.name))
            }}}
            val model=main {native.state.document}
            val raw=MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") {"%02x".format(it)}
            val first=MapSource(documentId=model.identity.documentId,fileName=source.name,scope=SourceScope.PAGE,
                pageId=model.pages[0].referenceIdentity.id,lastKnownPage=1,note="First remark",sourceVersion=raw)
            val second=first.copy(id=UUID.randomUUID().toString(),pageId=model.pages[1].referenceIdentity.id,lastKnownPage=2,note="Second remark")
            map.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Source navigation"),
                MapNode(id="n",parent="r",text="Concept body",sources=listOf(first,second)),MapNode(id="f",parent=null,free=true,text="Other selection",x=1000.0)))))
            val other=if(scratch) main {native.secondaryPane().apply {newNote();addBookmark("Temporary test guard")}} else null
            if(other!=null) main {assertTrue(other.noteOpen);assertTrue(other.dirty);assertNull(other.currentUri)}
            val f=Scene(host,source,map,native,maps,model,first,second,other);scene=f
            main {assertTrue(maps.open(Uri.fromFile(map),"Source navigation",preparePresentation={native.awaitHostParking()?.let {proof->({proof.current()})}}))}
            waitUntil("full-window map ready") {main {maps.visible && maps.current!=null && graph(host)?.let {it.width>0 && it.height>0 && it.isEnabled}==true}}
            f.entry=main {requireNotNull(maps.current)}
            main {f.entry.session.selected="n"}
            block(f)
        } finally {
            scene?.let {f->
                join(main {
                    f.maps.cancelOpen()
                    val entry=f.entryOrNull() ?: f.maps.current?.takeIf {MapSaves.key(it.uri)==MapSaves.key(Uri.fromFile(f.map))}
                    entry?.let {f.maps.close(it)}
                })
                // Only models created by this test can be explicitly discarded during cleanup.
                main {
                    val panes=f.native.openPanes
                    if(panes.all {it.visibleDocumentId in f.ownedIds}) f.native.goHomeAll(panes.map {it.documentOwner.discardForOpen()}.toSet())
                }
                waitUntil("test-owned native documents close") {main {f.native.openPanes.isEmpty()}}
                // noteOpen changes before the accepted FIFO clear has finished. Do not let the
                // following scene inspect a previous scene's still-pending recovery files.
                val parked=runBlocking {withTimeout(15000) {withContext(Dispatchers.Main) {f.native.awaitHostParking()}}}
                assertNotNull("Closed native working-session prefixes must settle",parked)
            }
            activity?.let {main {it.finish()};runner.waitForIdleSync()}
            source.delete();map.delete()
        }
    }
    private fun sources(f:Scene) {
        click(f.activity.getString(R.string.graph_sources))
        waitUntil("sources have retained previews") {main {f.entry.session.document!!.node("n").sources.all {it.lastPreview!=null}}}
    }
    @Test fun theSecondSourceOpensItsPermanentPageAndReturnsToItsPannedGraphAndEditedRemark()=scenario {f->
        val old=main {requireNotNull(graph(f.activity))}
        val camera=main {old.zoomBy(1.3f);old.panText(91f,-57f);old.viewport() to center(old)}
        sources(f)
        val note=showField("Second remark")
        assertTrue(note.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        val imm=f.activity.getSystemService(InputMethodManager::class.java)
        waitUntil("actual source-note input connection") {main {imm.isAcceptingText && WindowInspector.getGlobalWindowViews().any {it.hasWindowFocus() && it.findFocus()!=null}}}
        val connection=main {
            val focused=WindowInspector.getGlobalWindowViews().first {it.hasWindowFocus() && it.findFocus()!=null}.findFocus()!!
            imm.showSoftInput(focused,InputMethodManager.SHOW_IMPLICIT)
            focused.onCreateInputConnection(EditorInfo())
        }
        assertNotNull(connection)
        main {connection!!.setSelection(0,"Second remark".length);assertTrue(connection.commitText("Edited second remark",1))}
        waitUntil("remark committed without replacing the concept") {main {f.entry.session.document!!.node("n").sources.single {it.id==f.second.id}.note=="Edited second remark"}}
        // Exercise the real View's IME-size transition without assuming a docked installed keyboard.
        main {old.layout(old.left,old.top,old.right,old.top+(old.height/2).coerceAtLeast(1))}
        openRow(f.activity,"Edited second remark")
        waitUntil("actual source document and permanent second page") {main {
            !f.maps.visible && f.native.noteOpen && f.native.currentPage?.referenceIdentity?.id==f.second.pageId
        }}
        main {
            assertSame(f.sourceModel,f.native.state.document);assertFalse(f.native.inSplit)
            assertEquals(f.second.id,f.entry.sourceReturn!!.source.sourceId)
            assertEquals("Concept body",f.entry.session.document!!.node("n").text)
        }
        assertArrayEquals(f.sourceBytes,f.source.readBytes())
        click(f.activity.getString(R.string.graph_return_map))
        waitUntil("same actor with restored camera") {main {
            val current=graph(f.activity)
            f.maps.visible && f.maps.current===f.entry && current!=null && current!==old && current.isEnabled &&
                kotlin.math.abs(current.viewport().zoom-camera.first.zoom)<.00001f &&
                kotlin.math.abs(center(current).x-camera.second.x)<.02 && kotlin.math.abs(center(current).y-camera.second.y)<.02
        }}
        waitUntil("specific second source row restored") {field("Edited second remark")!=null}
        main {
            assertEquals("n",f.entry.session.selected);assertEquals("n",f.entry.presentation.sourcesFor)
            f.entry.session.undo()
            assertEquals("Second remark",f.entry.session.document!!.node("n").sources.single {it.id==f.second.id}.note)
        }
        assertArrayEquals(f.sourceBytes,f.source.readBytes())
    }
    @Test fun cancellingTheRealOtherOwnerGuardKeepsTheMapAndDiscardThenOpensOnlyTheRequestedSource()=scenario(scratch=true) {f->
        val scratch=requireNotNull(f.other);val otherModel=main {scratch.state.document}
        sources(f);openRow(f.activity,"Second remark")
        waitUntil("actual unsaved-other-document guard") {visible(f.activity.getString(R.string.unsaved_changes))}
        main {f.entry.session.selected="f"}
        click(f.activity.getString(R.string.cancel))
        waitUntil("cancel returned to the exact source panel") {main {f.maps.visible && f.entry.presentation.sourcesFor=="n"} && field("Second remark")!=null}
        main {
            assertEquals("f",f.entry.session.selected);assertSame(f.sourceModel,f.native.state.document)
            assertSame(otherModel,scratch.state.document);assertTrue(f.native.noteOpen);assertTrue(scratch.noteOpen)
            assertNull(f.entry.sourceReturn)
        }
        assertArrayEquals(f.sourceBytes,f.source.readBytes())
        openRow(f.activity,"Second remark")
        waitUntil("same real guard before explicit discard") {visible(f.activity.getString(R.string.unsaved_changes))}
        click(f.activity.getString(R.string.discard))
        waitUntil("only the kept actual source is visible") {main {!f.maps.visible && f.native.noteOpen && !scratch.noteOpen && f.native.currentPage?.referenceIdentity?.id==f.second.pageId}}
        assertSame(f.sourceModel,main {f.native.state.document});assertArrayEquals(f.sourceBytes,f.source.readBytes())
        click(f.activity.getString(R.string.graph_return_map))
        waitUntil("explicit return preserved the selection captured after cancelled navigation") {main {f.maps.visible && f.entry.session.selected=="f"} && field("Second remark")!=null}
        assertSame(f.entry,main {f.maps.current});assertArrayEquals(f.sourceBytes,f.source.readBytes())
    }
    private class CanvasScene(val activity:MainActivity,val source:File,val map:File,val native:Editor,val maps:MapWorkspace,
        val documentId:String,val sourceId:String) {
        lateinit var entry:MapWorkspace.Entry
        val sourceBytes=source.readBytes()
    }
    private fun canvasScenario(block:(CanvasScene)->Unit) {
        val base=runner.targetContext
        val recovery=listOf("session","session-b").flatMap {slot->listOf("document.xnote","session.json","canvas.xcanvas","canvas-session.json")
            .map {File(base.filesDir,"$slot/$it")}}
        val split=File(base.filesDir,"split-workspace.json")
        val splitOpen=split.exists() && runCatching {org.json.JSONObject(split.readText()).optBoolean("secondOpen",false)}.getOrDefault(true)
        assertTrue("Run Canvas source navigation on an empty instrumentation profile",recovery.none {it.exists()} && !splitOpen)
        val source=File.createTempFile("main-canvas-source-",".xcanvas",base.cacheDir)
        val map=File.createTempFile("main-canvas-graph-",".xmap",base.cacheDir)
        var activity:MainActivity?=null;var scene:CanvasScene?=null
        try {
            val seeded=InfiniteDocument(lastView=Waypoint("Canvas source",200.0,100.0,1.2))
            seeded.add(Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(100.0,100.0,.5),Sample(160.0,140.0,.7))))
            source.outputStream().use {CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(seeded,it)}
            runBlocking {withContext(Dispatchers.IO) {
                NotebookStorage.get(base).registry.registerObserved(BundleHeader.read(source),"xcanvas",source.name,Uri.fromFile(source).toString())
            }}
            val raw=MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") {"%02x".format(it)}
            val target=MapSource(documentId=seeded.identity.documentId,fileName=source.name,scope=SourceScope.FILE,
                note="Whole Canvas remark",sourceVersion=raw)
            map.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Canvas sources"),
                MapNode(id="n",parent="r",kind=NodeKind.REFERENCE,text="Canvas display alias",sources=listOf(target))))))
            val host=runner.startActivitySync(Intent(base,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=host
            waitUntil("Main restored before Canvas source navigation") {main {
                host.mapWorkspace!=null && host.documentEditor!=null && views(host.window.decorView).let {tree->
                    tree.any {it.javaClass.name=="androidx.compose.ui.platform.AndroidComposeView" && it.width>0} &&
                        tree.filterIsInstance<ImageView>().none {it.drawable is AnimationDrawable}
                }
            }}
            val native=main {requireNotNull(host.documentEditor)};val maps=main {requireNotNull(host.mapWorkspace)}
            main {assertTrue("Existing native documents must not be replaced by a Canvas test",native.openPanes.isEmpty());assertNull(maps.current)}
            val f=CanvasScene(host,source,map,native,maps,seeded.identity.documentId,target.id);scene=f
            main {assertTrue(maps.open(Uri.fromFile(map),"Canvas sources",preparePresentation={native.awaitHostParking()?.let {proof->({proof.current()})}}))}
            waitUntil("Canvas source map's actual full-window View") {main {maps.visible && maps.current!=null && graph(host)?.let {it.width>0 && it.height>0 && it.isEnabled}==true}}
            f.entry=main {maps.current!!};main {f.entry.session.selected="n"}
            block(f)
        } finally {
            scene?.let {f->
                join(main {f.maps.cancelOpen();f.maps.current?.takeIf {MapSaves.key(it.uri)==MapSaves.key(Uri.fromFile(map))}?.let {f.maps.close(it)}})
                main {
                    val panes=f.native.openPanes
                    if(panes.all {it.visibleDocumentId==f.documentId}) f.native.goHomeAll(panes.map {it.documentOwner.discardForOpen()}.toSet())
                }
                waitUntil("test-owned Canvas closes") {main {f.native.openPanes.isEmpty()}}
                val parked=runBlocking {withTimeout(15000) {withContext(Dispatchers.Main) {f.native.awaitHostParking()}}}
                assertNotNull(parked)
            }
            activity?.let {main {it.finish()};runner.waitForIdleSync()}
            source.delete();map.delete()
        }
    }
    private fun doubleTapReference(view:MindMapView,id:String) {
        val at=view.screenPoint(view.document.node(id).bounds.center);val now=SystemClock.uptimeMillis()
        for((delay,action) in listOf(0L to MotionEvent.ACTION_DOWN,40L to MotionEvent.ACTION_UP,
            140L to MotionEvent.ACTION_DOWN,180L to MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(now,now+delay,action,at.x.toFloat(),at.y.toFloat(),0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            try {assertTrue(view.onTouchEvent(event))} finally {event.recycle()}
        }
    }
    @Test fun aSingleReferenceDoubleTapColdOpensItsWholeCanvasAndReturnsWithoutPersistingTheNewCanvasStroke()=canvasScenario {f->
        val old=main {requireNotNull(graph(f.activity))}
        val camera=main {old.zoomBy(1.35f);old.panText(74f,-49f);old.viewport() to center(old)}
        main {
            assertFalse(f.native.noteOpen)
            val source=f.entry.session.document!!.node("n").sources.single()
            assertEquals(SourceScope.FILE,source.scope);assertNull(source.pageId);assertNull(source.region)
            doubleTapReference(old,"n")
        }
        waitUntil("single-source double tap opened the actual Canvas editor") {main {
            !f.maps.visible && f.native.canvasOpen && f.native.visibleDocumentId==f.documentId &&
                f.native.infinite.view.width>0 && f.native.infinite.view.height>0
        }}
        val model=main {f.native.infinite.document};val history=main {f.native.infinite.history}
        main {
            assertFalse(f.native.inSplit);assertEquals(f.sourceId,f.entry.sourceReturn!!.source.sourceId)
            val origin=f.native.infinite.viewport.centerContent;val now=SystemClock.uptimeMillis()
            for((index,action) in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP).withIndex()) {
                val point=f.native.infinite.viewport.contentToViewport(Pt(origin.x+index*25,origin.y+index*18))
                val pointer=MotionEvent.PointerProperties().apply {id=6;toolType=MotionEvent.TOOL_TYPE_STYLUS}
                val coordinates=MotionEvent.PointerCoords().apply {x=point.x.toFloat();y=point.y.toFloat();pressure=.7f}
                val event=MotionEvent.obtain(now,now+index*25L,action,1,arrayOf(pointer),arrayOf(coordinates),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
                try {assertTrue(f.native.infinite.view.onTouchEvent(event))} finally {event.recycle()}
            }
            assertTrue(model.dirty);assertTrue(history.canUndo);assertEquals(2,model.items.filterIsInstance<Stroke>().size)
            // Make an erroneous presentation flush observable even for this cache-backed source.
            Editor::class.java.getDeclaredField("canvasAutosaveUri").apply {isAccessible=true}.set(f.native,Uri.fromFile(f.source).toString())
        }
        val undo=main {history.nextUndo}
        click(f.activity.getString(R.string.graph_return_map))
        waitUntil("Canvas return restored the real graph camera") {main {
            val shown=graph(f.activity)
            f.maps.visible && f.maps.current===f.entry && shown!=null && shown!==old && shown.isEnabled &&
                kotlin.math.abs(shown.viewport().zoom-camera.first.zoom)<.00001f &&
                kotlin.math.abs(center(shown).x-camera.second.x)<.02 && kotlin.math.abs(center(shown).y-camera.second.y)<.02
        }}
        waitUntil("the exact whole-Canvas source row returned") {field("Whole Canvas remark")!=null}
        main {persistNativeForMapPresentation(f.native,f.entry)}
        val parked=runBlocking {withTimeout(15000) {withContext(Dispatchers.Main) {f.native.awaitHostParking()}}}
        assertNotNull(parked)
        main {
            assertSame(model,f.native.infinite.document);assertSame(history,f.native.infinite.history);assertSame(undo,history.nextUndo)
            assertTrue(model.dirty);assertTrue(history.canUndo);assertEquals("n",f.entry.session.selected)
            assertEquals("n",f.entry.presentation.sourcesFor)
        }
        assertArrayEquals(f.sourceBytes,f.source.readBytes())
    }

}
