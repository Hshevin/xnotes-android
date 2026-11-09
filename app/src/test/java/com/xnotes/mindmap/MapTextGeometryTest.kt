package com.xnotes.mindmap

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.xnotes.canvas.EditingField
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapTextGeometryTest {
    @Test fun caretBoundsApplyContentZoomAndRotationExactlyOnce() {
        val f=EditingField(100.0,200.0,160.0,60.0,18.0,2.0,FontFace.SANS,Rgba(0,0,0,255),"",rotation=90.0)
        val r=f.screenBounds(Rect(10.0,20.0,1.0,18.0))
        assertEquals(24.0,r.left,1e-8);assertEquals(220.0,r.top,1e-8)
        assertEquals(36.0,r.w,1e-8);assertEquals(2.0,r.h,1e-8)
        val all=f.screenBounds()
        assertEquals(-20.0,all.left,1e-8);assertEquals(200.0,all.top,1e-8)
        assertEquals(120.0,all.w,1e-8);assertEquals(320.0,all.h,1e-8)
    }
    private fun graph(width:Int=800,height:Int=600)=MindMapView(RuntimeEnvironment.getApplication()).apply {layout(0,0,width,height)}
    @Test fun caretPanOnlyMovesCameraAndSettlesAfterOneCorrection() {
        val g=graph();val doc=g.document;val before=g.viewport()
        val caret=Rect(790.0,590.0,2.0,20.0)
        g.revealCaret(caret);val after=g.viewport()
        assertTrue(after.panX<before.panX);assertTrue(after.panY<before.panY)
        val shown=Rect(caret.x+after.panX-before.panX,caret.y+after.panY-before.panY,caret.w,caret.h)
        g.revealCaret(shown)
        assertEquals(after,g.viewport());assertEquals(doc,g.document)
    }
    @Test fun oversizedCaretInTinyHostDoesNotOscillateOrProduceNonfiniteCamera() {
        val g=graph(10,10);val before=g.viewport();val caret=Rect(100.0,100.0,30.0,60.0)
        g.revealCaret(caret);val after=g.viewport()
        g.revealCaret(Rect(caret.x+after.panX-before.panX,caret.y+after.panY-before.panY,caret.w,caret.h))
        assertEquals(after,g.viewport());assertTrue(after.panX.isFinite());assertTrue(after.panY.isFinite())
        g.layout(0,0,0,0);g.revealCaret(caret);assertEquals(after,g.viewport())
    }
    @Test fun restoreRetainsOldViewportCenterAtNewHostSizeAndDensity() {
        val g=graph();val oldDensity=g.resources.displayMetrics.density*1.5f
        val saved=MindMapView.Viewport(1.25f,130f,-40f)
        val oldWidth=1200;val oldHeight=900
        g.restoreViewport(saved,oldWidth,oldHeight,oldDensity)
        val content=Pt(((oldWidth/2f-saved.panX)/(oldDensity*saved.zoom)).toDouble(),((oldHeight/2f-saved.panY)/(oldDensity*saved.zoom)).toDouble())
        val shown=g.screenPoint(content)
        assertEquals(400.0,shown.x,.001);assertEquals(300.0,shown.y,.001);assertEquals(saved.zoom,g.viewport().zoom,0f)
        g.restoreViewport(saved,oldWidth,oldHeight,oldDensity)
        assertEquals(shown,g.screenPoint(content))
    }
    @Test fun preparingEditCancelsDragButDoesNotEditDocument() {
        val g=graph();val doc=g.document
        g.zoomBy(.2f);g.prepareTextEditing(doc.root.id)
        assertTrue(g.viewport().zoom>=1f);assertEquals(doc,g.document);assertFalse(g.inkInput.active)
    }
}
