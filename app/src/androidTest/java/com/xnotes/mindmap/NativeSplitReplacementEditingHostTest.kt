package com.xnotes.mindmap

import android.content.Intent
import android.graphics.Rect
import android.view.InputDevice
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
import com.xnotes.ui.NativeSplitSource
import com.xnotes.ui.Pane
import com.xnotes.ui.PreparedNativeSplitOpen
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual Main/Android Views after the two-native coordinator consumes a mixed workspace.
 * Compilation alone does not establish Android, DocumentsUI, PDFium, Surface or GPU acceptance. */
class NativeSplitReplacementEditingHostTest {
    private val runner get()=PenTestRunner.instance
    private fun <T> main(action:()->T):T {
        var value:Result<T>?=null;runner.runOnMainSync {value=runCatching(action)}
        return requireNotNull(value).getOrThrow()
    }
    private fun await(label:String,check:()->Boolean) {
        repeat(1500) {runner.waitForIdleSync();if(check()) return;Thread.sleep(10)}
        fail("Native split replacement did not settle: $label")
    }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun graph(activity:MainActivity)=views(activity.window.decorView).filterIsInstance<MindMapView>().singleOrNull()
    private inner class Scene(val activity:MainActivity,val folder:File,val files:List<File>,val ids:List<String>,val mapFile:File) {
        val editor=main {requireNotNull(activity.documentEditor)}
        val maps=main {requireNotNull(activity.mapWorkspace)}
        lateinit var origin:MapWorkspace.Entry
        fun uri(file:File)=android.provider.DocumentsContract.buildDocumentUriUsingTree(AppStorageDocumentsProvider.treeUri(activity),
            "root/"+file.relativeTo(AppStorageDocumentsProvider.rootDir(activity)).invariantSeparatorsPath)
        fun replace():Boolean {
            var result:Boolean?=null
            main {assertTrue(maps.replaceWithNativeSplit(
                prepareNative={valid,settleMaps->editor.prepareNativePairOpen(NativeSplitSource(uri(files[1]).toString(),files[1].name),
                    NativeSplitSource(uri(files[2]).toString(),files[2].name),stillCurrent=valid,
                    guardOwners={_,_->if(settleMaps()) emptySet() else null})},
                onPresented={candidate->
                    assertNull(maps.current);assertFalse(maps.visible);assertNull(maps.splitPresentation.layout)
                    assertEquals(ids[1],candidate.first.state.document.identity.documentId);assertEquals(ids[2],candidate.second.state.document.identity.documentId)
                    editor.mixedPresentationActive=false
                },onFinished={result=it}))}
            await("two-file result") {main {result!=null}};return main {result!!}
        }
        fun nativeGeometry():Pair<Rect,Rect>? {
            val second=editor.secondary ?: return null
            val a=Rect();val b=Rect()
            if(!editor.view.getGlobalVisibleRect(a) || !second.view.getGlobalVisibleRect(b) || a.isEmpty || b.isEmpty || Rect.intersects(a,b)) return null
            return a to b
        }
        fun stroke(target:Editor) {
            val start=android.os.SystemClock.uptimeMillis()
            for((index,action) in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP).withIndex()) {
                val p=target.state.contentToViewport(target.state.fromPageSpace(0,Pt(100.0+index*35.0,120.0+index*20.0)))
                val prop=MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS}
                val at=MotionEvent.PointerCoords().apply {x=p.x.toFloat();y=p.y.toFloat();pressure=.6f}
                val event=MotionEvent.obtain(start,start+index,action,1,arrayOf(prop),arrayOf(at),0,0x64,1f,1f,21,0,InputDevice.SOURCE_STYLUS,0)
                try {assertTrue(target.view.onTouchEvent(event));assertEquals(0x64,event.buttonState)} finally {event.recycle()}
            }
        }
    }
    private fun scenario(test:(Scene)->Unit) {
        val context=runner.targetContext
        val slots=listOf("session","session-b").flatMap {slot->listOf("document.xnote","session.json","canvas.xcanvas","canvas-session.json").map {File(context.filesDir,"$slot/$it")}}
        val split=File(context.filesDir,"split-workspace.json")
        val secondOpen=split.exists() && runCatching {org.json.JSONObject(split.readText()).optBoolean("secondOpen",false)}.getOrDefault(true)
        assertTrue("Use an empty instrumentation profile; unrelated recovery is preserved",slots.none {it.exists()} && !secondOpen)
        val folder=File(AppStorageDocumentsProvider.rootDir(context),"native-replace-host-${UUID.randomUUID()}").apply {mkdirs()}
        val ids=mutableListOf<String>()
        val files=(0..2).map {index->File(folder,"note-$index.xnote").apply {
            val doc=Document(mutableListOf(Page(500.0,500.0))).apply {enableReferenceAnchors()};ids+=doc.identity.documentId
            outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(doc.fixedWriteSnapshot(),it)}
        }}
        val map=File(folder,"map.xmap").apply {writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Old mixed map"))))) }
        var scene:Scene?=null
        try {
            val host=runner.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            await("Main owners ready") {main {host.documentEditor!=null && host.mapWorkspace!=null && host.window.decorView.width>0}}
            val f=Scene(host,folder,files,ids,map);scene=f
            runBlocking {withTimeout(15000) {withContext(Dispatchers.Main) {assertTrue(f.editor.openSingleFromHome(f.uri(files[0]).toString(),files[0].name))}}}
            main {assertTrue(f.maps.open(f.uri(map),map.name,flushNativeOnPresent=false,preparePresentation={
                f.editor.awaitMapSourceParking()?.let {proof->({proof.current()})}
            }))}
            await("initial Map") {main {f.maps.visible && graph(host)?.height?.let {it>0}==true}}
            main {
                f.origin=f.maps.current!!
                assertTrue(f.maps.showSplit(f.origin,MapSplitLayout(Pane.SECONDARY,Pane.SECONDARY,.4f,true,false)))
                f.editor.mixedPresentationActive=true
            }
            await("mixed actual Views") {main {graph(host)?.width?.let {it>0}==true && f.editor.view.width>0}}
            test(f)
        } finally {
            scene?.let {f->
                val release=main {f.maps.current?.let {f.maps.close(it)}}
                if(release!=null) runBlocking {withTimeout(15000) {release.join()}}
                main {
                    f.maps.splitPresentation.layout?.let {f.maps.removeSplitHome(it.mapPane)}
                    val panes=f.editor.openPanes
                    if(panes.all {it.visibleDocumentId in f.ids}) f.editor.goHomeAll(panes.map {it.documentOwner.discardForOpen()}.toSet())
                }
                await("only owned native notes close") {main {f.editor.openPanes.isEmpty()}}
                main {f.activity.finish()};runner.waitForIdleSync()
            }
            folder.deleteRecursively()
        }
    }
    @Test fun actualMixedSurfacesBecomeTwoNativeSurfacesOnlyAfterBothFilesAreReady()=scenario {f->
        val oldMap=f.origin;val source=f.files[0].readBytes()
        assertTrue(f.replace())
        await("Map removed and both Native Views constrained") {main {graph(f.activity)==null && f.editor.inSplit && f.nativeGeometry()!=null}}
        main {
            assertFalse(oldMap.lease.alive);assertNull(f.maps.current);assertNull(f.maps.splitPresentation.layout)
            val second=requireNotNull(f.editor.secondary)
            assertEquals(setOf(f.ids[1],f.ids[2]),setOf(f.editor.state.document.identity.documentId,second.state.document.identity.documentId))
            second.selectTool(Tool.PEN);f.stroke(second)
            assertEquals(1,second.state.document.pages.first().items.filterIsInstance<Stroke>().size);assertTrue(second.history.canUndo)
            (Editor::class.java.getDeclaredField("noteDebounceJob").apply {isAccessible=true}.get(second) as? Job)?.cancel()
        }
        assertArrayEquals(source,f.files[0].readBytes())
    }
    @Test fun invalidSecondFileKeepsTheExistingMixedViewNativeOwnerAndMapRoute()=scenario {f->
        val oldGraph=main {graph(f.activity)};val note=main {f.editor.state.document};val history=main {f.editor.history}
        val layout=main {f.maps.splitPresentation.layout};val source=f.files[0].readBytes();val map=f.mapFile.readBytes()
        f.files[2].writeText("Invalid second note")
        assertFalse(f.replace())
        main {
            assertSame(f.origin,f.maps.current);assertTrue(f.origin.lease.alive);assertTrue(f.maps.visible)
            assertSame(oldGraph,graph(f.activity));assertEquals(layout,f.maps.splitPresentation.layout)
            assertSame(note,f.editor.state.document);assertSame(history,f.editor.history);assertTrue(f.editor.noteOpen)
        }
        assertArrayEquals(source,f.files[0].readBytes());assertArrayEquals(map,f.mapFile.readBytes())
    }
}
