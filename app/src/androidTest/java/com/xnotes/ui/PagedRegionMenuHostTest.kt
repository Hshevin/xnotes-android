package com.xnotes.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.xnotes.R
import com.xnotes.canvas.PageSourceRegion
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.pen.PenTestRunner
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.requestPagedRegionReference
import com.xnotes.ui.theme.XnotesTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real shared menus and Main's candidate-bound callback. Compiled, not Android/device accepted. */
class PagedRegionMenuHostTest {
    private val runner get()=PenTestRunner.instance
    private fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null) emptyList() else
        listOf(node)+(0 until node.childCount).flatMap {nodes(node.getChild(it))}
    private fun action(label:String):AccessibilityNodeInfo? {
        for(node in nodes(runner.uiAutomation.rootInActiveWindow).filter {it.isVisibleToUser &&
            (it.text?.toString()==label || it.contentDescription?.toString()==label)}) {
            var at:AccessibilityNodeInfo?=node
            repeat(6) {val current=at;if(current?.isClickable==true) return current;at=current?.parent}
        }
        return null
    }
    private fun click(label:String) {
        repeat(200) {runner.waitForIdleSync();action(label)?.takeIf {it.isEnabled}?.let {
            assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync();return
        };Thread.sleep(10)}
        fail("Paged region action did not appear: $label")
    }
    private fun scenario(block:(ComposeHostFixture,Editor)->Unit)=ComposeHostFixture.run {host->
        val root=File(host.activity.cacheDir,"paged-region-menu-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextThemeWrapper(host.activity,R.style.Theme_Xnotes) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val editor=host.onMain {Editor(context).apply {newNote()}}
        try {host.await("actual unsaved note") {editor.noteOpen && !editor.canvasOpen};block(host,editor)}
        finally {
            host.disposeContent()
            val autosave=host.onMain {
                editor.pageReferenceRequest?.let(editor::clearPageReference);editor.controller.resetGestureState()
                (Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(editor) as CoroutineScope).also {it.cancel()}
            }
            runBlocking {withTimeout(15000) {
                autosave.coroutineContext[Job]?.join()
                (Editor::class.java.getDeclaredField("sessionOwner").apply {isAccessible=true}.get(editor) as LegacySessionQueue.Owner).awaitAccepted()
            }}
            root.deleteRecursively()
        }
    }
    private var time=100L
    private fun pen(editor:Editor,action:Int,content:Pt) {
        val point=editor.state.contentToViewport(content)
        val properties=MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS}
        val coords=MotionEvent.PointerCoords().apply {x=point.x.toFloat();y=point.y.toFloat();pressure=.7f}
        val event=MotionEvent.obtain(100,time++,action,1,arrayOf(properties),arrayOf(coords),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
        try {assertTrue(editor.view.onTouchEvent(event))} finally {event.recycle()}
    }
    @Test fun aCrossPageMenuRefusesThenASinglePageMenuCapturesActualOwnersBeforeDismissing()=scenario {host,editor->
        val first=Page(500.0,400.0);val second=Page(500.0,400.0)
        val a=ShapeItem(ShapeKind.RECTANGLE,Pt(50.0,70.0),Pt(150.0,160.0),Rgba(0,0,0))
        val b=ShapeItem(ShapeKind.RECTANGLE,Pt(40.0,60.0),Pt(130.0,140.0),Rgba(0,0,0))
        val doc=host.onMain {
            val doc=editor.state.document;doc.pages.clear();doc.pages.addAll(listOf(first,second));first.items+=a;second.items+=b
            doc.enableReferenceAnchors();editor.view.layout(0,0,1000,800);editor.state.relayout();editor.selectTool(Tool.SELECT);editor.controller.selectAll();doc
        }
        val captures=mutableListOf<PageSourceRegion?>()
        host.setContent {
            val model=editor.state.document
            val selected=editor.selectionMenuRect?.let {editor.sourceSelectionRegion()}
            XnotesTheme(editor.palette) {Box(Modifier.fillMaxSize()) {
                SelectionMenu(editor,onReferenceSelection={captures+=selected;requestPagedRegionReference(editor,model,selected)})
            }}
        }
        click(host.activity.getString(R.string.more));click(host.activity.getString(R.string.graph_reference_this_region))
        host.onMain {
            assertEquals(1,captures.size);assertNull(captures.single());assertNull(editor.pageReferenceRequest)
            assertNotNull(editor.selectionMenuRect);assertEquals(2,editor.controller.selectedItems().size)
            assertEquals(host.activity.getString(R.string.graph_region_single_page_required),editor.message)
        }
        host.change {editor.controller.selectPlaced(1,listOf(b))}
        val expected=host.onMain {editor.sourceSelectionRegion()!!}
        click(host.activity.getString(R.string.more));click(host.activity.getString(R.string.graph_reference_this_region))
        host.onMain {
            val actual=captures.last()!!;val request=editor.pageReferenceRequest!!
            assertSame(doc,actual.document);assertSame(second,actual.page);assertEquals(expected.region,actual.region)
            assertSame(doc,request.model);assertSame(second,request.page);assertEquals(expected.region,request.region)
            assertEquals(second.referenceIdentity.id,request.initialPageId);assertNull(editor.selectionMenuRect)
            assertEquals(1,editor.controller.selectedItems().size);assertFalse(editor.history.canUndo)
        }
    }
    @Test fun aNormalScreenshotUpProvidesItsOriginalPageLocalCandidateBeforeTheAcceptedMenuClearsIt()=scenario {host,editor->
        val local=Rect(-40.0,-10.0,130.0,80.0)
        val page=Page(500.0,400.0).apply {margins=PageMargins(left=.2,top=.1)}
        val doc=host.onMain {
            val doc=editor.state.document;doc.pages.clear();doc.pages+=page;doc.enableReferenceAnchors()
            editor.view.layout(0,0,1000,800);editor.state.rotationDeg=270;editor.state.relayout();editor.state.zoom=1.4
            editor.selectTool(Tool.SCREENSHOT)
            val world=editor.state.fromPageSpaceRect(0,local)
            pen(editor,MotionEvent.ACTION_DOWN,world.topLeft);pen(editor,MotionEvent.ACTION_MOVE,Pt(world.right,world.bottom))
            assertNull(editor.sourceCaptureRegion());pen(editor,MotionEvent.ACTION_UP,Pt(world.right,world.bottom));doc
        }
        val expected=host.onMain {editor.sourceCaptureRegion()!!};val captures=mutableListOf<PageSourceRegion?>()
        var accept=false
        host.setContent {
            val model=editor.state.document
            val captured=editor.screenshotMenu?.let {editor.sourceCaptureRegion()}
            XnotesTheme(editor.palette) {Box(Modifier.fillMaxSize()) {
                ScreenshotMenu(editor,onShare={},onReferenceRegion={captures+=captured;accept && requestPagedRegionReference(editor,model,captured)})
            }}
        }
        click(host.activity.getString(R.string.graph_reference_this_region))
        host.onMain {assertSame(expected,captures.single());assertNotNull(editor.screenshotMenu);assertNotNull(editor.sourceCaptureRegion());accept=true}
        click(host.activity.getString(R.string.graph_reference_this_region))
        host.onMain {
            val captured=captures.last()!!;val request=editor.pageReferenceRequest!!
            assertSame(doc,captured.document);assertSame(page,captured.page);assertSame(page,request.page);assertSame(doc,request.model)
            assertEquals(expected.region,request.region);assertEquals(page.referenceIdentity.id,request.initialPageId)
            assertNull(editor.sourceCaptureRegion());assertNull(editor.controller.screenshotRect);assertNull(editor.screenshotMenu)
            assertFalse(editor.history.canUndo)
        }
    }
}
