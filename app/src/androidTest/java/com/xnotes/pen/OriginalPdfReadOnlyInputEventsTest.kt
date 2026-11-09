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

/** Android MotionEvent contract; compiled only until an explicit device run is authorized. */
class OriginalPdfReadOnlyInputEventsTest {
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
