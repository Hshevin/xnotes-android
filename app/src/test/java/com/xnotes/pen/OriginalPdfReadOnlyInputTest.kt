package com.xnotes.pen

import android.view.MotionEvent
import com.xnotes.canvas.CanvasState
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.mindmap.OriginalPdfReadOnlyInput
import com.xnotes.platform.AndroidSurfaceFactory
import com.xnotes.ui.theme.Palette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class OriginalPdfReadOnlyInputTest {
    @Test fun actualPointerPanAndCancelChangeOnlyThePrivateCameraAndRetiredInputIsInert() {
        val page=Page(600.0,900.0,pdfPage=0)
        val document=Document(mutableListOf(page),dpi=72)
        val state=CanvasState(document,AndroidSurfaceFactory(),Palette.DEFAULT)
        state.viewportW=600;state.viewportH=600;state.freeMovement=true;state.relayout();state.establishInitialView()
        val before=state.snapshotView();val revision=document.bodyRevision
        var alive=true;var freshContacts=0
        val input=OriginalPdfReadOnlyInput(state,{alive},{},{freshContacts++})
        fun event(action:Int,x:Float,y:Float) {
            val event=MotionEvent.obtain(1,2,action,x,y,0)
            try {assertTrue(input.onTouch(event))} finally {event.recycle()}
        }
        event(MotionEvent.ACTION_DOWN,300f,300f);event(MotionEvent.ACTION_MOVE,300f,180f)
        event(MotionEvent.ACTION_CANCEL,300f,180f)
        assertNotEquals(before.scrollY,state.scrollY);assertFalse(input.busy)
        assertEquals(1,freshContacts);assertSame(page,document.pages.single());assertTrue(page.items.isEmpty())
        assertFalse(document.dirty);assertEquals(revision,document.bodyRevision);assertNull(document.path);assertNull(document.pdfFile)
        val camera=state.snapshotView();alive=false
        event(MotionEvent.ACTION_DOWN,300f,300f);event(MotionEvent.ACTION_MOVE,90f,40f);event(MotionEvent.ACTION_UP,90f,40f)
        assertEquals(camera.scrollX,state.scrollX,0.0);assertEquals(camera.scrollY,state.scrollY,0.0)
    }
    @Test fun actualTwoFingerScaleChangesOnlyTheReadOnlyViewport() {
        val page=Page(600.0,900.0,pdfPage=0);val document=Document(mutableListOf(page),dpi=72)
        val state=CanvasState(document,AndroidSurfaceFactory(),Palette.DEFAULT)
        state.viewportW=600;state.viewportH=600;state.relayout();state.establishInitialView()
        val before=state.zoom
        val input=OriginalPdfReadOnlyInput(state,{true},{},{})
        fun touch(action:Int,distance:Float) {
            val pointers=Array(2) {i->MotionEvent.PointerProperties().apply {id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            val coords=Array(2) {i->MotionEvent.PointerCoords().apply {x=300f+(if(i==0) -distance else distance);y=300f;pressure=1f}}
            val event=MotionEvent.obtain(1,2,action,2,pointers,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
            try {assertTrue(input.onTouch(event))} finally {event.recycle()}
        }
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),40f)
        touch(MotionEvent.ACTION_MOVE,100f)
        assertTrue(state.zoom>before);assertTrue(state.zoomingInProgress)
        touch(MotionEvent.ACTION_CANCEL,100f)
        assertFalse(state.zoomingInProgress);assertFalse(document.dirty);assertTrue(page.items.isEmpty());assertEquals(0L,document.bodyRevision)
    }

    @Test fun simultaneousTwoFingerTranslationAndScaleCarryThePreviousCenterContentToTheNewCenter() {
        val page=Page(3000.0,3000.0,pdfPage=0);val document=Document(mutableListOf(page),dpi=72)
        val state=CanvasState(document,AndroidSurfaceFactory(),Palette.DEFAULT)
        state.viewportW=600;state.viewportH=600;state.relayout();state.establishInitialView();state.setView(1.0,0.0,0.0)
        val anchor=state.viewportToContent(com.xnotes.core.geometry.Pt(100.0,100.0))
        val revision=document.bodyRevision
        val input=OriginalPdfReadOnlyInput(state,{true},{},{})
        fun touch(action:Int,centerX:Float,centerY:Float,radius:Float,count:Int=2) {
            val pointers=Array(count) {i->MotionEvent.PointerProperties().apply {id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            val coords=Array(count) {i->MotionEvent.PointerCoords().apply {
                x=centerX+(if(i==0) -radius else radius);y=centerY;pressure=1f
            }}
            val event=MotionEvent.obtain(1,2,action,count,pointers,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
            try {assertTrue(input.onTouch(event))} finally {event.recycle()}
        }
        touch(MotionEvent.ACTION_DOWN,100f,100f,40f,count=1)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),100f,100f,40f)
        touch(MotionEvent.ACTION_MOVE,200f,200f,80f)
        val carried=state.contentToViewport(anchor)
        assertEquals(2.0,state.zoom,1e-9)
        assertEquals(200.0,carried.x,1e-9);assertEquals(200.0,carried.y,1e-9)
        touch(MotionEvent.ACTION_CANCEL,200f,200f,80f)
        assertFalse(input.busy);assertFalse(state.zoomingInProgress)
        val afterCancel=state.contentToViewport(anchor)
        assertEquals(carried.x,afterCancel.x,0.0);assertEquals(carried.y,afterCancel.y,0.0)
        assertSame(page,document.pages.single());assertTrue(page.items.isEmpty());assertFalse(document.dirty)
        assertEquals(revision,document.bodyRevision);assertNull(document.path);assertNull(document.pdfFile)
    }

}
