package com.xnotes.ui

import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.util.DocumentKind
import com.xnotes.mindmap.MapNativeRestoreHint
import com.xnotes.mindmap.MapSplitLayout
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class LastPresentationV2Test {
    private val note=MapNativeRestoreHint("22222222-2222-4222-8222-222222222222",null,DocumentKind.NOTE)
    private val canvas=MapNativeRestoreHint("33333333-3333-4333-8333-333333333333",null,DocumentKind.CANVAS)
    private val map=SplitSourceRef("11111111-1111-4111-8111-111111111111","content://map/one","Map")
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
    @Test fun mapBesideHomeRetainsVisibleMapAndLayoutWithoutANativeHint() {
        roundTrip(LastPresentation.MapOnly(map,null,MapSplitLayout(Pane.SECONDARY,Pane.PRIMARY,.3f,true,true,true)))
        roundTrip(LastPresentation.MapOnly(map))
    }
    @Test fun mixedNativeViewRetainsExplicitFitWidthAndLegacyV1StillLoads() {
        val legacy=LastPresentation.Mixed(ColdMixedPresentation(map,note,MapSplitLayout(Pane.PRIMARY,Pane.SECONDARY,.5f,null,false)))
        roundTrip(legacy)
        roundTrip(LastPresentation.Mixed(legacy.value.copy(nativeView=view)))
    }
    @Test fun anOpaqueObservationMatchesItsActualHeadButNotANewerIdenticalDescriptor()=runBlocking {
        val root=Files.createTempDirectory("marker-observation-").toFile()
        try {
            val store=LastPresentationStore(root)
            val absent=store.observe();assertTrue(store.matches(absent))
            val first=store.begin(store.reserve(),LastPresentation.Home)
            try {assertEquals(LastPresentationPrepare.Ready,first.prepare());assertEquals(LastPresentationWrite.Written,first.commit {true})}
            finally {first.close()}
            assertFalse(store.matches(absent))
            val before=store.observe();assertTrue(store.matches(before))
            val second=store.begin(store.reserve(),LastPresentation.Home)
            try {assertEquals(LastPresentationPrepare.Ready,second.prepare());assertEquals(LastPresentationWrite.Written,second.commit {true})}
            finally {second.close()}
            assertFalse("Rewritten same DTO is a different actual head",store.matches(before))
        } finally {root.deleteRecursively()}
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
        assertThrows(IllegalArgumentException::class.java) {
            LastPresentationCodec.encode(LastPresentation.MapOnly(map,null,MapSplitLayout(Pane.PRIMARY,Pane.SECONDARY,.5f,null,false,false)))
        }
    }
    @Test fun typedWireRejectsNullUnknownFieldsAndFutureVersions() {
        val value=LastPresentation.Native(listOf(ColdNativeSlot.Note(Pane.PRIMARY,note,view)),ColdNativeLayout(Pane.PRIMARY))
        val wire=String(LastPresentationCodec.encode(value),Charsets.UTF_8)
        for(raw in listOf(
            wire.replace("\"version\":2","\"version\":3"),
            wire.replace("\"fitWidth\":true","\"fitWidth\":null"),
            wire.dropLast(1)+",\"native\":{}}",
            wire.replace("\"hint\":{","\"hint\":{\"future\":true,"))) {
            assertNotEquals(wire,raw);assertNull(LastPresentationCodec.parse(raw.toByteArray()))
        }
    }
    @Test fun observationCannotMoveBetweenRootsOrSurviveARecreatedDirectory() {
        val parent=Files.createTempDirectory("marker-root-proof-").toFile()
        try {
            val directory=java.io.File(parent,"files").apply {mkdir()}
            val first=LastPresentationStore(directory)
            val observed=first.observe()
            val other=LastPresentationStore(java.io.File(parent,"other").apply {mkdir()})
            assertFalse(other.matches(observed))
            assertTrue(directory.renameTo(java.io.File(parent,"retired")));assertTrue(directory.mkdir())
            assertFalse(first.matches(observed));assertFalse(LastPresentationStore(directory).matches(observed))
        } finally {parent.deleteRecursively()}
    }
}
