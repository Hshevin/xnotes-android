package com.xnotes.mindmap

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.xnotes.MainActivity
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.tools.Tool
import com.xnotes.format.DocumentCodec
import com.xnotes.pen.PenTestRunner
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.ui.Editor
import com.xnotes.ui.Pane
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual Main's two constrained surfaces and Android MotionEvents after staged pair adoption.
 * Source compilation is not execution or SAF/Surface/GPU/MovinkPad acceptance. */
class MapPairOpenEditingHostTest {
    private val runner get()=PenTestRunner.instance
    private fun <T> main(action:()->T):T {
        var result:Result<T>?=null;runner.runOnMainSync {result=runCatching(action)}
        return requireNotNull(result).getOrThrow()
    }
    private fun await(label:String,check:()->Boolean) {
        repeat(1500) {runner.waitForIdleSync();if(check()) return;Thread.sleep(10)}
        fail("Pair host did not settle: $label")
    }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun graph(activity:MainActivity)=views(activity.window.decorView).filterIsInstance<MindMapView>().singleOrNull()
    private inner class Scene(val activity:MainActivity,val folder:File,val noteFile:File,val mapFile:File,val invalidFile:File) {
        val editor=main {requireNotNull(activity.documentEditor)}
        val maps=main {requireNotNull(activity.mapWorkspace)}
        var ownedId:String?=null
        fun uri(file:File)=android.provider.DocumentsContract.buildDocumentUriUsingTree(AppStorageDocumentsProvider.treeUri(activity),
            "root/"+file.relativeTo(AppStorageDocumentsProvider.rootDir(activity)).invariantSeparatorsPath)
        fun pair(mapFirst:Boolean,native:File=noteFile):Boolean {
            var finished:Boolean?=null
            main {assertTrue(maps.openPair(uri(mapFile),mapFile.name,mapFirst,
                prepareNative={valid->editor.preparePagedPairOpen(uri(native).toString(),native.name,stillCurrent=valid)},
                onPresented={entry,candidate->
                    assertSame(entry,maps.current);assertTrue(candidate.target.noteOpen)
                    assertEquals(candidate.nativePane,maps.splitPresentation.layout!!.nativePane)
                    ownedId=candidate.target.state.document.identity.documentId;editor.mixedPresentationActive=true
                },onFinished={finished=it}))}
            await("pair completion") {main {finished!=null}}
            return main {finished!!}
        }
        fun geometry():Pair<Rect,Rect>? {
            val layout=maps.splitPresentation.layout ?: return null
            val actualNative=if(layout.nativePane==Pane.PRIMARY) editor else editor.secondary ?: return null
            val map=graph(activity) ?: return null
            val m=Rect();val n=Rect()
            if(!map.getGlobalVisibleRect(m) || !actualNative.view.getGlobalVisibleRect(n) || m.isEmpty || n.isEmpty || Rect.intersects(m,n)) return null
            return m to n
        }
        fun awaitMixed()=await("both actual Views are visible and disjoint") {main {maps.visible && geometry()!=null}}
        fun native()=main {if(maps.splitPresentation.layout!!.nativePane==Pane.PRIMARY) editor else requireNotNull(editor.secondary)}
        fun stroke(target:Editor) {
            val start=android.os.SystemClock.uptimeMillis()
            for((index,action) in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP).withIndex()) {
                val page=Pt(100.0+index*35.0,120.0+index*20.0)
                val point=target.state.contentToViewport(target.state.fromPageSpace(0,page))
                val prop=MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS}
                val coord=MotionEvent.PointerCoords().apply {x=point.x.toFloat();y=point.y.toFloat();pressure=.61f;setAxisValue(MotionEvent.AXIS_TILT,.37f)}
                val raw=MotionEvent.obtain(start,start+index,action,1,arrayOf(prop),arrayOf(coord),0,0x64,1f,1f,23,0,InputDevice.SOURCE_STYLUS,0)
                try {assertTrue(target.view.onTouchEvent(raw));assertEquals(0x64,raw.buttonState);assertEquals(.61f,raw.pressure,0f)} finally {raw.recycle()}
            }
        }
    }
    private fun scenario(test:(Scene)->Unit) {
        val context=runner.targetContext
        val slots=listOf("session","session-b").flatMap {slot->listOf("document.xnote","session.json","canvas.xcanvas","canvas-session.json").map {File(context.filesDir,"$slot/$it")}}
        val split=File(context.filesDir,"split-workspace.json")
        val secondOpen=split.exists() && runCatching {org.json.JSONObject(split.readText()).optBoolean("secondOpen",false)}.getOrDefault(true)
        assertTrue("Use an empty instrumentation profile; unrelated recovery must stay untouched",slots.none {it.exists()} && !secondOpen)
        val folder=File(AppStorageDocumentsProvider.rootDir(context),"pair-host-${UUID.randomUUID()}").apply {mkdirs()}
        val note=File(folder,"note.xnote");val map=File(folder,"map.xmap");val invalid=File(folder,"invalid.xnote")
        val doc=Document(mutableListOf(Page(500.0,500.0))).apply {enableReferenceAnchors()}
        note.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(doc.fixedWriteSnapshot(),it)}
        map.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Pair map"),MapNode(id="n",parent="r",text="Child")))))
        invalid.writeText("This is deliberately not a native note")
        var scene:Scene?=null
        try {
            val activity=runner.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            await("Main owners ready") {main {activity.documentEditor!=null && activity.mapWorkspace!=null && activity.window.decorView.width>0}}
            val f=Scene(activity,folder,note,map,invalid);scene=f
            main {assertTrue(f.editor.openPanes.isEmpty());assertNull(f.maps.current)}
            test(f)
        } finally {
            scene?.let {f->
                val release=main {f.maps.current?.let {f.maps.close(it)}}
                if(release!=null) runBlocking {withTimeout(15000) {release.join()}}
                main {
                    f.maps.splitPresentation.layout?.let {f.maps.removeSplitHome(it.mapPane)}
                    val panes=f.editor.openPanes
                    if(panes.all {it.visibleDocumentId==f.ownedId}) f.editor.goHomeAll(panes.map {it.documentOwner.discardForOpen()}.toSet())
                }
                await("only this test's native note closes") {main {f.editor.openPanes.isEmpty()}}
                main {f.activity.finish()};runner.waitForIdleSync()
            }
            folder.deleteRecursively()
        }
    }
    @Test fun bothFileOrdersCreateDisjointRealViewsAndTheRetainedNativeKeepsItsStrokeHistory()=scenario {f->
        assertTrue(f.pair(true));f.awaitMixed()
        await("Map-first gives the actual graph keyboard focus without a pointer event") {main {graph(f.activity)?.hasFocus()==true}}
        val note=f.native();val entry=main {requireNotNull(f.maps.current)}
        val model=main {note.state.document};val history=main {note.history}
        main {
            val layout=f.maps.splitPresentation.layout!!
            assertEquals(layout.mapPane,layout.focusedPane);assertTrue((layout.mapPane==Pane.PRIMARY)!=layout.reversed)
            note.selectTool(Tool.PEN);f.stroke(note)
            assertEquals(1,model.pages.first().items.filterIsInstance<Stroke>().size);assertTrue(history.canUndo)
            (Editor::class.java.getDeclaredField("noteDebounceJob").apply {isAccessible=true}.get(note) as? Job)?.cancel()
        }
        val nativeRevision=main {model.bodyRevision};val nativeTool=main {note.tool}
        main {
            f.maps.focusSplit(f.maps.splitPresentation.layout!!.nativePane)
            assertTrue(note.view.requestFocus());assertTrue(note.view.hasFocus());entry.session.selected="r"
        }
        assertTrue(f.pair(true));f.awaitMixed()
        await("Map-first takes focus from the previously focused actual native View") {main {graph(f.activity)?.hasFocus()==true}}
        main {
            val time=android.os.SystemClock.uptimeMillis()
            val down=KeyEvent(time,time,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_DOWN,0,0,31,0,0,InputDevice.SOURCE_KEYBOARD)
            val up=KeyEvent(time,time+1,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_DOWN,0,0,31,0,0,InputDevice.SOURCE_KEYBOARD)
            assertTrue(f.activity.dispatchKeyEvent(down));f.activity.dispatchKeyEvent(up)
            assertSame(model,note.state.document);assertEquals(nativeRevision,model.bodyRevision);assertEquals(nativeTool,note.tool)
        }
        await("the normal Window key reaches the Map rather than the old native focus") {main {entry.session.selected=="n"}}
        assertTrue(f.pair(false));f.awaitMixed()
        val retained=f.native()
        main {
            assertSame(entry,f.maps.current);assertSame(model,retained.state.document);assertSame(history,retained.history)
            assertEquals(1,model.pages.first().items.filterIsInstance<Stroke>().size)
            val layout=f.maps.splitPresentation.layout!!;assertEquals(layout.nativePane,layout.focusedPane)
            assertFalse((layout.mapPane==Pane.PRIMARY)!=layout.reversed)
            note.history.undo();assertTrue(model.pages.first().items.isEmpty());note.history.redo()
            assertEquals(1,model.pages.first().items.filterIsInstance<Stroke>().size)
        }
    }
    @Test fun anInvalidSecondFileNeverReplacesEitherRenderedSurfaceOrTheirActualOwners()=scenario {f->
        assertTrue(f.pair(true));f.awaitMixed()
        val entry=main {f.maps.current};val mapView=main {graph(f.activity)};val note=f.native()
        val nativeModel=main {note.state.document};val nativeHistory=main {note.history};val layout=main {f.maps.splitPresentation.layout}
        assertFalse(f.pair(false,f.invalidFile));f.awaitMixed()
        main {
            assertSame(entry,f.maps.current);assertSame(mapView,graph(f.activity));assertSame(nativeModel,note.state.document)
            assertSame(nativeHistory,note.history);assertEquals(layout,f.maps.splitPresentation.layout)
            assertTrue(note.noteOpen);assertNotNull(f.geometry())
        }
    }
}
