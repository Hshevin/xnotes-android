package com.xnotes.ui

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.PdfKey
import com.xnotes.core.model.Room
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.storage.OriginalPdfPageInfo
import org.junit.Assert.*
import org.junit.Test

/** Mapping contracts only: controlled geometry does not claim PDFium pixel validation. */
class OriginalPdfRegionMapperTest {
    private fun corners(r:Rect)=listOf(Pt(r.left,r.top),Pt(r.right,r.top),Pt(r.right,r.bottom),Pt(r.left,r.bottom))
    private fun info(rotation:Int):OriginalPdfPageInfo {
        val across=rotation%180!=0
        return OriginalPdfPageInfo(Pt(if(across) 792.0 else 612.0,if(across) 612.0 else 792.0),
            PdfPageGeometry.of(10.0,20.0,622.0,812.0,rotation))
    }
    private fun assertRegion(expected:Rect,actual:Rect?) {
        assertNotNull(actual);actual!!
        assertEquals(expected.x,actual.x,1e-8);assertEquals(expected.y,actual.y,1e-8)
        assertEquals(expected.w,actual.w,1e-8);assertEquals(expected.h,actual.h,1e-8)
    }
    @Test fun nativeRotationExtraPageTurnsCropOriginAndDpiReturnTheSameOriginalUserRectangle() {
        val user=Rect(45.0,80.0,120.0,70.0)
        for(native in listOf(0,90,180,270)) for(turn in 0..3) {
            val actual=info((native+turn*90)%360)
            val displayed=Rect.bounding(corners(user).map {actual.geometry.toDisplay(it.x,it.y)})
            val region=Rect(displayed.x*2,displayed.y*2,displayed.w*2,displayed.h*2)
            val snapshot=OriginalPdfRegionSnapshot(PdfKey.of(7,turn),144,actual.sizePoints.x*2,actual.sizePoints.y*2,emptyList(),region)
            assertRegion(user,requireNotNull(snapshot.map(actual)).userRegion)
        }
    }
    @Test fun aRegionAfterHorizontalAndVerticalInsertedBandsRemovesOnlyTheirTranslation() {
        val actual=info(0);val rooms=listOf(Room(100.0,30.0),Room(80.0,20.0,true))
        val snapshot=OriginalPdfRegionSnapshot(PdfKey.of(0,0),72,612.0,792.0,rooms,Rect(150.0,200.0,50.0,40.0))
        assertRegion(Rect(140.0,602.0,50.0,40.0),requireNotNull(snapshot.map(actual)).userRegion)
    }
    @Test fun crossingAnInsertedBandIsRejectedEvenWhenAllCornersAreOutsideTheGap() {
        val actual=info(0)
        for(rooms in listOf(listOf(Room(100.0,30.0)),listOf(Room(100.0,30.0,true)))) {
            assertNull(OriginalPdfRegionSnapshot(0,72,612.0,792.0,rooms,Rect(80.0,80.0,90.0,90.0)).map(actual))
        }
        assertNull(OriginalPdfRegionSnapshot(0,72,612.0,792.0,listOf(Room(100.0,30.0)),Rect(20.0,110.0,20.0,10.0)).map(actual))
    }
    @Test fun pageMarginsActualPdfCroppingAndPartialOutOfPageRangesAreNotSilentlyClipped() {
        val actual=info(0)
        for(region in listOf(Rect(-1.0,5.0,10.0,10.0),Rect(605.0,5.0,10.0,10.0),Rect(5.0,787.0,10.0,10.0))) {
            assertNull(OriginalPdfRegionSnapshot(0,72,700.0,900.0,emptyList(),region).map(actual))
        }
        // The note can be shorter than the actual PDF by a fractional point on older imports.
        assertNull(OriginalPdfRegionSnapshot(0,72,611.5,791.5,emptyList(),Rect(600.0,780.0,12.0,12.0)).map(actual))
        assertRegion(Rect(610.0,810.0,1.0,1.0),requireNotNull(
            OriginalPdfRegionSnapshot(0,144,1223.0,1583.0,emptyList(),Rect(1200.0,2.0,2.0,2.0)).map(actual)).userRegion)
    }
    @Test fun originalWholePageIgnoresNoteRoomsAndPaperDimensionsButStillRequiresRealInvertiblePdfGeometry() {
        assertNull(requireNotNull(OriginalPdfRegionSnapshot(0,0,0.0,0.0,listOf(Room(0.0,999.0)),null).map(info(0))).userRegion)
        try {
            OriginalPdfPageInfo(Pt(612.0,792.0),PdfPageGeometry(0.0,0.0,0.0,0.0,0.0,0.0))
            fail("A singular actual PDF geometry must be rejected at the engine boundary")
        } catch(_:IllegalArgumentException) {}
    }
}
