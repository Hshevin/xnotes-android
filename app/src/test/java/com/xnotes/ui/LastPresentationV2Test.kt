package com.xnotes.ui

import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.util.DocumentKind
import com.xnotes.core.model.NativeRestoreHint
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class LastPresentationV2Test {
    private val note=NativeRestoreHint("22222222-2222-4222-8222-222222222222",null,DocumentKind.NOTE)
    private val canvas=NativeRestoreHint("33333333-3333-4333-8333-333333333333",null,DocumentKind.CANVAS)
    private val view=ColdNativeView(Editor.ReadingPosition(1.25,10.0,20.0,ViewOverrides()),true)
    private fun roundTrip(value:LastPresentation) {
        assertEquals(value,LastPresentationCodec.parse(LastPresentationCodec.encode(value)))
    }
    @Test fun nativeNoteSharedAndCanvasHaveExplicitTypedRoundTrips() {
        roundTrip(LastPresentation.Native(listOf(ColdNativeSlot.Note(Pane.SECONDARY,note,view)),ColdNativeLayout(Pane.SECONDARY)))
        roundTrip(LastPresentation.Native(listOf(ColdNativeSlot.Note(Pane.PRIMARY,note,view),
            ColdNativeSlot.Shared(Pane.SECONDARY,Pane.PRIMARY,view.copy(fitWidth=false))),ColdNativeLayout(Pane.SECONDARY,.4f,false,true)))
        roundTrip(LastPresentation.Native(listOf(ColdNativeSlot.Canvas(Pane.PRIMARY,canvas,ColdCanvasView(10.0,30.0,.5))),ColdNativeLayout(Pane.PRIMARY)))
    }
    @Test fun mutableSlotInputsCannotChangeAnAlreadyCapturedDescription() {
        val slots=mutableListOf<ColdNativeSlot>(ColdNativeSlot.Note(Pane.PRIMARY,note,view))
        val description=LastPresentation.Native(slots,ColdNativeLayout(Pane.PRIMARY))
        slots.clear()
        assertEquals(1,description.slots.size);roundTrip(description)
    }
    @Test fun nativeDescriptionRejectsDuplicateOwnersCanvasSplitsAndDanglingSharedViews() {
        val cases=listOf(
            listOf(ColdNativeSlot.Home(Pane.PRIMARY)),
            listOf(ColdNativeSlot.Note(Pane.PRIMARY,note),ColdNativeSlot.Note(Pane.SECONDARY,note)),
            listOf(ColdNativeSlot.Note(Pane.PRIMARY,note),ColdNativeSlot.Canvas(Pane.SECONDARY,canvas)),
            listOf(ColdNativeSlot.Shared(Pane.PRIMARY,Pane.SECONDARY),ColdNativeSlot.Home(Pane.SECONDARY)),
            listOf(ColdNativeSlot.Note(Pane.PRIMARY,note),ColdNativeSlot.Home(Pane.PRIMARY)))
        for(slots in cases) assertThrows(IllegalArgumentException::class.java) {
            LastPresentationCodec.encode(LastPresentation.Native(slots,ColdNativeLayout(Pane.PRIMARY)))
        }
    }
    @Test fun typedWireRejectsNullUnknownFieldsAndFutureVersions() {
        val value=LastPresentation.Native(listOf(ColdNativeSlot.Note(Pane.PRIMARY,note,view)),ColdNativeLayout(Pane.PRIMARY))
        val wire=String(LastPresentationCodec.encode(value),Charsets.UTF_8)
        for(raw in listOf(
            wire.replace("\"version\":2","\"version\":99"),
            wire.replace("\"fitWidth\":true","\"fitWidth\":null"),
            wire.dropLast(1)+",\"native\":{}}",
            wire.replace("\"hint\":{","\"hint\":{\"future\":true,"))) {
            assertNotEquals(wire,raw);assertNull(LastPresentationCodec.parse(raw.toByteArray()))
        }
    }
}
