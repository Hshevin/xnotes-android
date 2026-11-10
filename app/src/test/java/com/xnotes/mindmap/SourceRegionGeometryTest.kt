package com.xnotes.mindmap

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class SourceRegionGeometryTest {
    @Test fun newRegionKeepsWorldCoordinatesAndNormalizesEitherDragDirection() {
        assertEquals(Rect(-30.0,-40.0,50.0,70.0),SourceRegionGeometry.drag(null,SourceRegionHandle.NEW,Pt(20.0,30.0),Pt(-30.0,-40.0)))
    }
    @Test fun movingNeverClampsToAnImageOrItsFormerContentBounds() {
        assertEquals(Rect(-200.0,300.0,40.0,50.0),SourceRegionGeometry.drag(Rect(10.0,20.0,40.0,50.0),
            SourceRegionHandle.MOVE,Pt(20.0,30.0),Pt(-190.0,310.0)))
    }
    @Test fun resizingMayCrossTheOppositeEdgeWithoutAMinimumPercentage() {
        assertEquals(Rect(100.0,0.0,0.25,50.0),SourceRegionGeometry.drag(Rect(0.0,0.0,100.0,50.0),
            SourceRegionHandle.LEFT,Pt(0.0,25.0),Pt(100.25,25.0)))
    }
    @Test fun aSmallRectStillHasAnInteriorMoveTargetAndWideOutsideEdgeTargets() {
        val rect=Rect(0.0,0.0,20.0,20.0)
        assertEquals(SourceRegionHandle.MOVE,SourceRegionGeometry.hit(rect,Pt(10.0,10.0),24.0))
        assertEquals(SourceRegionHandle.LEFT,SourceRegionGeometry.hit(rect,Pt(-20.0,10.0),24.0))
        assertEquals(SourceRegionHandle.TOP_LEFT,SourceRegionGeometry.hit(rect,Pt(-10.0,-10.0),24.0))
        assertNull(SourceRegionGeometry.hit(rect,Pt(-30.0,10.0),24.0))
    }
    @Test fun zeroAreaAndNonFiniteOrOversizedWorldRectanglesAreRejected() {
        assertNull(SourceRegionGeometry.drag(null,SourceRegionHandle.NEW,Pt(1.0,1.0),Pt(1.0,10.0)))
        assertFalse(SourceRegionGeometry.valid(Rect(Double.NaN,0.0,1.0,1.0)))
        assertFalse(SourceRegionGeometry.valid(Rect(0.0,0.0,MindMapDocument.MAX_COORD+1,1.0)))
        assertNull(SourceRegionGeometry.drag(Rect(0.0,0.0,1.0,1.0),SourceRegionHandle.MOVE,Pt(0.0,0.0),Pt(Double.POSITIVE_INFINITY,0.0)))
    }
    @Test fun pdfBoundsRejectOutOfPageRegionsWithoutClippingThem() {
        val page=Rect(0.0,0.0,600.0,800.0)
        assertTrue(SourceRegionGeometry.contains(page,Rect(10.0,20.0,30.0,40.0)))
        val outside=Rect(-1.0,20.0,30.0,40.0)
        assertFalse(SourceRegionGeometry.contains(page,outside));assertEquals(-1.0,outside.x,0.0)
    }
    @Test fun cameraRequiresFinitePositiveZoom() {
        assertTrue(SourceRegionCamera(20.0,-30.0,.1).valid())
        assertFalse(SourceRegionCamera(0.0,0.0,0.0).valid())
        assertFalse(SourceRegionCamera(Double.NaN,0.0,1.0).valid())
    }
}
