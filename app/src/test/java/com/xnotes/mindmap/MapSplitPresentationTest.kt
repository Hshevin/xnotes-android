package com.xnotes.mindmap

import com.xnotes.ui.Pane
import org.junit.Assert.*
import org.junit.Test

class MapSplitPresentationTest {
    private fun layout(mapPane:Pane=Pane.SECONDARY)=MapSplitLayout(mapPane,Pane.PRIMARY,.37f,true,false)

    @Test fun onlyAnExplicitRestoreConsumesTheParkedLayoutAndOrdinarySingleDiscardsIt() {
        val presentation=MapSplitPresentation();val original=layout()
        presentation.present(MapOpenPresentation.Split(original));val generation=presentation.generation
        presentation.park();assertNull(presentation.layout);assertEquals(original,presentation.returnLayout)
        assertTrue(presentation.generation>generation)
        presentation.present(MapOpenPresentation.RestoreSplit)
        assertEquals(original,presentation.layout);assertNull(presentation.returnLayout)
        presentation.park();presentation.present(MapOpenPresentation.Single)
        assertNull(presentation.layout);assertNull(presentation.returnLayout)
    }

    @Test fun aCanvasAdoptedIntoTheMapLogicalSlotReturnsToHomeWithoutMovingTheMapPhysicalArea() {
        for(mapPane in Pane.entries) for(reversed in listOf(false,true)) {
            val presentation=MapSplitPresentation()
            val original=layout(mapPane).copy(reversed=reversed)
            presentation.present(MapOpenPresentation.Split(original))
            presentation.prepareCanvasReturn(mapPane)
            val prepared=requireNotNull(presentation.returnLayout)
            assertNull(presentation.layout);assertTrue(prepared.nativeHome)
            assertEquals(mapPane,prepared.nativePane);assertNotEquals(mapPane,prepared.mapPane)
            assertEquals(original.ratio,prepared.ratio)
            assertEquals((original.mapPane==Pane.PRIMARY)!=original.reversed,(prepared.mapPane==Pane.PRIMARY)!=prepared.reversed)
            presentation.park();assertEquals(prepared,presentation.returnLayout)
            presentation.present(MapOpenPresentation.RestoreSplit);assertEquals(prepared,presentation.layout)
        }
    }

    @Test fun geometryUpdatesPreserveLogicalOwnersAndNormalizeInvalidRatios() {
        val presentation=MapSplitPresentation();presentation.present(MapOpenPresentation.Split(layout()))
        assertTrue(presentation.updateLayout {it.copy(ratio=Float.NaN,nativeHome=true,focusedPane=it.mapPane)})
        assertEquals(.5f,presentation.layout!!.ratio,0f);assertTrue(presentation.layout!!.nativeHome)
        assertFalse(presentation.updateLayout {it.copy(mapPane=it.nativePane)})
        assertEquals(Pane.SECONDARY,presentation.layout!!.mapPane)
        presentation.clear();assertFalse(presentation.updateLayout {it})
    }

    @Test fun nativeRestoreHintsSurviveParkingButCannotTurnAChangedSessionIntoAVisibleNativeDocument() {
        val presentation=MapSplitPresentation();presentation.present(MapOpenPresentation.Split(layout()))
        val hint=MapNativeRestoreHint("native-id","content://notes/original",com.xnotes.core.util.DocumentKind.NOTE)
        assertTrue(presentation.rememberNativeHint(hint))
        presentation.park();assertEquals(hint,presentation.nativeHint)
        assertTrue(presentation.nativeRestoreFailed());assertTrue(presentation.returnLayout!!.nativeHome)
        presentation.present(MapOpenPresentation.RestoreSplit)
        assertTrue(presentation.layout!!.nativeHome);assertEquals(hint,presentation.nativeHint)
        presentation.present(MapOpenPresentation.Single)
        assertNull(presentation.nativeHint);assertFalse(presentation.rememberNativeHint(hint))
    }
}
