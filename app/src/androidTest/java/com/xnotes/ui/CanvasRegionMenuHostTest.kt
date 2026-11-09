package com.xnotes.ui

import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.xnotes.R
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.pen.PenTestRunner
import com.xnotes.ui.theme.XnotesTheme
import com.xnotes.ui.theme.monochromePalette
import org.junit.Assert.*
import org.junit.Test

/** Real shared Compose menu actions; compiled separately from platform/device execution. */
class CanvasRegionMenuHostTest {
    private val runner get()=PenTestRunner.instance
    private fun all(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null) emptyList() else
        listOf(node)+(0 until node.childCount).flatMap {all(node.getChild(it))}
    private fun action(label:String):AccessibilityNodeInfo? {
        for(node in all(runner.uiAutomation.rootInActiveWindow).filter {it.isVisibleToUser &&
            (it.text?.toString()==label || it.contentDescription?.toString()==label)}) {
            var candidate:AccessibilityNodeInfo?=node
            repeat(6) {val current=candidate;if(current?.isClickable==true) return current;candidate=current?.parent}
        }
        return null
    }
    private fun click(label:String) {
        var target:AccessibilityNodeInfo?=null
        repeat(200) {runner.waitForIdleSync();target=action(label);if(target?.isEnabled==true) {
            assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync();return
        };Thread.sleep(10)}
        fail("Canvas region menu action did not appear: $label")
    }
    private var time=100L
    private fun pen(canvas:InfiniteEditor,action:Int,point:Pt) {
        val at=canvas.viewport.contentToViewport(point)
        val properties=MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS}
        val coordinates=MotionEvent.PointerCoords().apply {x=at.x.toFloat();y=at.y.toFloat();pressure=.7f}
        val event=MotionEvent.obtain(100,time++,action,1,arrayOf(properties),arrayOf(coordinates),0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
        try {assertTrue(canvas.view.onTouchEvent(event))} finally {event.recycle()}
    }
    @Test fun selectionReferenceCapturesBeforeDismissAndARejectedRequestKeepsTheSameSelectionMenu()=ComposeHostFixture.run {host->
        val canvas=host.onMain {InfiniteEditor(host.activity).apply {
            document.add(ShapeItem(ShapeKind.RECTANGLE,Pt(-100.0,70.0),Pt(100.0,170.0),Rgba(0,0,0)))
            view.layout(0,0,1000,800);selectAll()
        }}
        var accept=false;val captured=mutableListOf<Rect?>()
        val expected=host.onMain {canvas.sourceSelectionRegion()!!}
        val revision=host.onMain {canvas.document.bodyRevision}
        host.setContent {
            XnotesTheme(monochromePalette(false)) {Box(Modifier.fillMaxSize()) {
                SelectionMenu(canvas,onReferenceSelection={captured+=canvas.sourceSelectionRegion();accept})
            }}
        }
        click(host.activity.getString(R.string.more));click(host.activity.getString(R.string.graph_reference_this_region))
        host.onMain {assertEquals(listOf(expected),captured);assertNotNull(canvas.selectionMenuRect);assertEquals(1,canvas.selection.items.size);accept=true}
        click(host.activity.getString(R.string.more));click(host.activity.getString(R.string.graph_reference_this_region))
        host.onMain {
            assertEquals(listOf(expected,expected),captured);assertNull(canvas.selectionMenuRect);assertEquals(1,canvas.selection.items.size)
            assertEquals(revision,canvas.document.bodyRevision);assertFalse(canvas.document.dirty);assertFalse(canvas.history.canUndo)
        }
    }
    @Test fun screenshotReferenceReceivesItsFrozenWorldRectBeforeASuccessClearsCapture()=ComposeHostFixture.run {host->
        val canvas=host.onMain {InfiniteEditor(host.activity).apply {
            view.layout(0,0,1000,800);armTool(Tool.SCREENSHOT)
            pen(this,MotionEvent.ACTION_DOWN,Pt(-100.0,-80.0));pen(this,MotionEvent.ACTION_MOVE,Pt(120.0,90.0))
            pen(this,MotionEvent.ACTION_UP,Pt(120.0,90.0))
        }}
        val expected=host.onMain {canvas.sourceCaptureRegion()!!}
        var accept=false;val captured=mutableListOf<Rect?>()
        host.setContent {
            XnotesTheme(monochromePalette(false)) {Box(Modifier.fillMaxSize()) {
                ScreenshotMenu(canvas,onShare={},onReferenceRegion={captured+=canvas.sourceCaptureRegion();accept})
            }}
        }
        click(host.activity.getString(R.string.graph_reference_this_region))
        host.onMain {assertEquals(listOf(expected),captured);assertEquals(expected,canvas.sourceCaptureRegion());assertNotNull(canvas.screenshotMenu);accept=true}
        click(host.activity.getString(R.string.graph_reference_this_region))
        host.onMain {
            assertEquals(listOf(expected,expected),captured);assertNull(canvas.sourceCaptureRegion());assertNull(canvas.screenshotMenu)
            assertTrue(canvas.document.isEmpty);assertFalse(canvas.document.dirty);assertFalse(canvas.history.canUndo)
        }
    }
}
