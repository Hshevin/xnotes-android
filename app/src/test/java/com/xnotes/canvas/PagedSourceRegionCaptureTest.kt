package com.xnotes.canvas

import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.ui.theme.Palette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual controller events and model ownership; coordinates never come from floating menu chrome. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PagedSourceRegionCaptureTest {
    private class Fixture(rotation:Int=0,margins:Boolean=false) {
        val first=Page(500.0,400.0)
        val second=Page(500.0,400.0)
        val document=Document(mutableListOf(first,second))
        val state=CanvasState(document,FakeSurfaceFactory(),Palette.DEFAULT).apply {
            viewportW=1000;viewportH=1200;verticalScroll=true;rotationDeg=rotation
            if(margins) second.margins=PageMargins(left=.2,top=.15,right=.1,bottom=.1)
            relayout();zoom=1.35;scrollX=31.0;scrollY=57.0
        }
        val history=History()
        var selectionMenu:Rect?=null
        val controller=InteractionController(state,history,FakeTextMeasurer(),{},onSelectionMenu={selectionMenu=it})
        var time=100L
        fun send(action:Int,content:Pt,buttons:Int=0) {
            val point=state.contentToViewport(content)
            val properties=MotionEvent.PointerProperties().apply {id=7;toolType=MotionEvent.TOOL_TYPE_STYLUS}
            val coordinates=MotionEvent.PointerCoords().apply {x=point.x.toFloat();y=point.y.toFloat();pressure=.67f;setAxisValue(MotionEvent.AXIS_TILT,.3f)}
            val event=MotionEvent.obtain(100,time++,action,1,arrayOf(properties),arrayOf(coordinates),0,buttons,1f,1f,0,0,InputDevice.SOURCE_STYLUS,0)
            val before=listOf(event.x,event.y,event.pressure,event.buttonState.toFloat(),event.getAxisValue(MotionEvent.AXIS_TILT))
            try {
                assertTrue(controller.onTouch(event))
                assertEquals("Capturing a region must not rewrite the original input",before,
                    listOf(event.x,event.y,event.pressure,event.buttonState.toFloat(),event.getAxisValue(MotionEvent.AXIS_TILT)))
            } finally {event.recycle()}
        }
        fun screenshot(content:Rect) {
            controller.setTool(Tool.SCREENSHOT)
            send(MotionEvent.ACTION_DOWN,content.topLeft)
            send(MotionEvent.ACTION_MOVE,Pt(content.right,content.bottom))
            send(MotionEvent.ACTION_UP,Pt(content.right,content.bottom))
        }
        fun shape()=ShapeItem(ShapeKind.RECTANGLE,Pt(-60.0,-25.0),Pt(130.0,90.0),Rgba(0,0,0),rotation=Math.PI/4)
    }
    private fun rect(expected:Rect,actual:Rect) {
        assertEquals(expected.x,actual.x,.001);assertEquals(expected.y,actual.y,.001)
        assertEquals(expected.w,actual.w,.001);assertEquals(expected.h,actual.h,.001)
    }
    @Test fun settledSelectionUsesTheActualPagesLocalShapeFrameAcrossAllViewRotationsAndMargins() {
        for(rotation in listOf(0,90,180,270)) {
            val f=Fixture(rotation,margins=true);val item=f.shape();f.second.items+=item
            f.controller.setTool(Tool.SELECT);f.controller.selectPlaced(1,listOf(item))
            val revision=f.document.bodyRevision;val dirty=f.document.dirty;val selected=f.controller.selectedItems()
            val captured=requireNotNull(f.controller.sourceSelectionRegion())
            assertSame(f.document,captured.document);assertSame(f.second,captured.page);assertTrue(captured.current())
            rect(Rect.bounding(item.frameObb()!!.corners()),captured.region)
            assertNotEquals(f.selectionMenu,captured.region)
            f.state.zoom=2.1;f.state.scrollX=-300.0;f.state.scrollY=201.0
            rect(captured.region,requireNotNull(f.controller.sourceSelectionRegion()).region)
            assertEquals(selected,f.controller.selectedItems());assertEquals(revision,f.document.bodyRevision)
            assertEquals(dirty,f.document.dirty);assertFalse(f.history.canUndo)
        }
    }
    @Test fun selectionRechecksUniqueObjectOwnershipInsteadOfAnOldSelectedPageIndex() {
        val f=Fixture();val item=f.shape();f.second.items+=item
        f.controller.setTool(Tool.SELECT);f.controller.selectPlaced(1,listOf(item))
        f.document.pages.remove(f.second);f.document.pages.add(0,f.second);f.state.relayout()
        val region=requireNotNull(f.controller.sourceSelectionRegion())
        assertSame(f.second,region.page);rect(Rect.bounding(item.frameObb()!!.corners()),region.region)
        f.first.items+=item
        assertNull("An object found in two actual pages has no unique ownership proof",f.controller.sourceSelectionRegion())
        f.first.items.remove(item);f.second.items.remove(item)
        assertNull("A removed selected object cannot authorize a page region",f.controller.sourceSelectionRegion())
    }
    @Test fun crossPageSelectionAndAReplacementModelCannotBorrowAnOldSelectionProof() {
        val f=Fixture();val a=f.shape();val b=f.shape();f.first.items+=a;f.second.items+=b
        f.controller.setTool(Tool.SELECT);f.controller.selectAll()
        assertNull(f.controller.sourceSelectionRegion());assertEquals(2,f.controller.selectedItems().size);assertFalse(f.history.canUndo)
        f.controller.selectPlaced(1,listOf(b))
        f.state.document=Document(mutableListOf(Page(500.0,400.0,mutableListOf(b))))
        f.state.relayout()
        assertNull("Even reusing the same mutable item cannot transfer an old selection to a different model",f.controller.sourceSelectionRegion())
    }
    @Test fun anIdleStylePreviewCannotBeConfirmedByReadingItsRegion() {
        val f=Fixture();val item=f.shape();f.second.items+=item
        f.controller.setTool(Tool.SELECT);f.controller.selectPlaced(1,listOf(item))
        f.controller.restyleSelection(Rgba(70,70,70),null,preview=true)
        assertTrue(f.controller.hasPendingContentTransaction)
        assertNull(f.controller.sourceSelectionRegion());assertFalse(f.history.canUndo)
        assertEquals(Rgba(70,70,70),item.strokeRgba)
        assertTrue(f.controller.hasPendingContentTransaction);assertTrue(f.controller.hasSelection)
    }
    @Test fun aNormalScreenshotUpFreezesItsActualPageAndLocalFrameBeforeTheOldIndexNamesAnotherPage() {
        for(rotation in listOf(0,90,180,270)) {
            val f=Fixture(rotation,margins=true);val local=Rect(-40.0,-20.0,160.0,100.0)
            val content=f.state.fromPageSpaceRect(1,local)
            f.screenshot(content)
            val captured=requireNotNull(f.controller.sourceCaptureRegion())
            assertSame(f.document,captured.document);assertSame(f.second,captured.page);rect(local,captured.region)
            f.document.pages.remove(f.second);f.document.pages.add(0,f.second);f.state.relayout()
            assertSame(f.first,f.document.pages[1])
            val after=requireNotNull(f.controller.sourceCaptureRegion())
            assertSame(f.second,after.page);assertSame(captured.document,after.document);rect(local,after.region)
            f.state.zoom=3.0;f.state.scrollX=200.0;f.state.scrollY=-50.0
            rect(local,requireNotNull(f.controller.sourceCaptureRegion()).region)
            assertFalse(f.history.canUndo);assertFalse(f.document.dirty)
        }
    }
    @Test fun deletingOrReplacingTheFrozenScreenshotPageNeverRebindsItToItsCopyOrOrdinalNeighbour() {
        val f=Fixture();val local=Rect(30.0,40.0,120.0,90.0)
        f.screenshot(f.state.fromPageSpaceRect(1,local));assertNotNull(f.controller.sourceCaptureRegion())
        val copy=Page(500.0,400.0)
        val captured=f.controller.sourceCaptureRegion()!!
        f.document.pages[1]=copy;f.state.relayout();f.controller.sourceRegionsChanged()
        assertNull(f.controller.sourceCaptureRegion());assertNotNull(f.controller.screenshotRect);assertFalse(captured.current())
        f.document.pages[1]=f.second;f.state.relayout();f.controller.sourceRegionsChanged()
        assertFalse("Delete/undo cannot revive a previously rendered screenshot action",captured.current())
        assertNull(f.controller.sourceCaptureRegion())
        f.state.document=Document(mutableListOf(f.first,f.second));f.state.relayout()
        assertNull(f.controller.sourceCaptureRegion())
    }
    @Test fun aRemovedPageObservationRetiresOldSelectionDtosAcrossUndoButPureReorderingKeepsThemCurrent() {
        val f=Fixture();val item=f.shape();f.second.items+=item
        f.controller.setTool(Tool.SELECT);f.controller.selectPlaced(1,listOf(item))
        val captured=f.controller.sourceSelectionRegion()!!
        f.document.pages.reverse();f.state.relayout();f.controller.sourceRegionsChanged()
        assertTrue(captured.current());assertSame(f.second,captured.page)
        f.document.pages.remove(f.second);f.state.relayout();f.controller.sourceRegionsChanged()
        f.document.pages.add(f.second);f.state.relayout();f.controller.sourceRegionsChanged()
        assertFalse(captured.current());assertSame(f.second,f.controller.sourceSelectionRegion()!!.page)
        assertFalse(f.history.canUndo);assertFalse(f.document.dirty)
    }
    @Test fun screenshotsAcrossPagesInGapsOrPartlyOutsidePaperDoNotGuessOrSilentlyCrop() {
        val f=Fixture();val first=f.state.pageRects[0];val second=f.state.pageRects[1]
        val across=Rect(first.left+40,first.bottom-20,100.0,second.top-first.bottom+40)
        f.screenshot(across);assertNull(f.controller.sourceCaptureRegion());assertNotNull(f.controller.screenshotRect)
        val gap=Rect(first.left+40,first.bottom+1,100.0,(second.top-first.bottom-2).coerceAtLeast(1.0))
        f.screenshot(gap);assertNull(f.controller.sourceCaptureRegion())
        f.screenshot(Rect(first.right-20,first.top+40,50.0,100.0));assertNull(f.controller.sourceCaptureRegion())
        f.screenshot(Rect(first.left+20,first.top+30,InteractionController.SHOT_MIN-1,50.0))
        assertNull(f.controller.sourceCaptureRegion());assertNull(f.controller.screenshotRect)
        assertFalse(f.history.canUndo);assertFalse(f.document.dirty)
    }
    @Test fun oldScreenshotDtosStayRetiredAfterClearCancellationOrASecondCompletedScreenshot() {
        val f=Fixture();val firstArea=f.state.fromPageSpaceRect(0,Rect(20.0,30.0,100.0,80.0))
        f.screenshot(firstArea);val first=f.controller.sourceCaptureRegion()!!;assertTrue(first.current())
        f.controller.clearScreenshot();assertFalse(first.current())
        f.screenshot(firstArea);val second=f.controller.sourceCaptureRegion()!!
        f.send(MotionEvent.ACTION_DOWN,firstArea.topLeft);assertFalse(second.current())
        f.send(MotionEvent.ACTION_CANCEL,firstArea.topLeft);assertFalse(second.current())
        val other=f.state.fromPageSpaceRect(0,Rect(170.0,130.0,110.0,90.0))
        f.screenshot(other);val third=f.controller.sourceCaptureRegion()!!
        assertTrue(third.current());assertFalse(first.current());assertFalse(second.current())
        f.controller.resetGestureState();assertFalse(third.current())
        assertFalse(f.history.canUndo);assertFalse(f.document.dirty)
    }
    @Test fun clearingOrChangingASelectionRetiresItsOldDtosEvenWhenTheSameObjectsAreSelectedAgain() {
        val f=Fixture();val item=f.shape();f.second.items+=item
        f.controller.setTool(Tool.SELECT);f.controller.selectPlaced(1,listOf(item))
        val first=f.controller.sourceSelectionRegion()!!;assertTrue(first.current())
        f.controller.clearSelection();assertFalse(first.current())
        f.controller.selectPlaced(1,listOf(item));val second=f.controller.sourceSelectionRegion()!!
        assertTrue(second.current());assertFalse(first.current())
        f.controller.selectPlaced(1,listOf(item));assertFalse(second.current())
        val third=f.controller.sourceSelectionRegion()!!;assertTrue(third.current())
        f.controller.resetGestureState();assertFalse(third.current())
        assertFalse(f.history.canUndo);assertFalse(f.document.dirty)
    }
    @Test fun liveScreenshotCancelNewCaptureClearAndResetHaveNoRegionOrContentSideEffects() {
        val f=Fixture();val content=f.state.fromPageSpaceRect(0,Rect(20.0,30.0,100.0,80.0))
        f.controller.setTool(Tool.SCREENSHOT);f.controller.penButtonTool=null
        f.send(MotionEvent.ACTION_DOWN,content.topLeft,0x20);f.send(MotionEvent.ACTION_MOVE,Pt(content.right,content.bottom),0x20)
        assertNull(f.controller.sourceCaptureRegion())
        f.send(MotionEvent.ACTION_CANCEL,Pt(content.right,content.bottom),0x20);assertNull(f.controller.sourceCaptureRegion())
        f.screenshot(content);assertNotNull(f.controller.sourceCaptureRegion())
        f.send(MotionEvent.ACTION_DOWN,content.topLeft);assertNull(f.controller.sourceCaptureRegion())
        f.send(MotionEvent.ACTION_CANCEL,content.topLeft);assertNull(f.controller.sourceCaptureRegion())
        f.screenshot(content);f.controller.clearScreenshot();assertNull(f.controller.sourceCaptureRegion())
        f.screenshot(content);f.controller.resetGestureState();assertNull(f.controller.sourceCaptureRegion());assertNull(f.controller.screenshotRect)
        assertFalse(f.history.canUndo);assertFalse(f.document.dirty)
    }
}
