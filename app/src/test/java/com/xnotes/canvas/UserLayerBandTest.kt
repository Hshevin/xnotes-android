package com.xnotes.canvas

import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.FakeRenderer
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.audio.InkFade
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.DrawingLayers
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.SyncMark
import com.xnotes.core.model.TapeItem
import com.xnotes.core.model.TapeMode
import com.xnotes.core.model.TapeShape
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.ui.theme.Palette
import org.junit.Assert.*
import org.junit.Test

class UserLayerBandTest {
    private val page = Page(300.0, 3000.0)
    private val bottom = DrawingLayers.DEFAULT
    private val top = page.layers.create("Top").id
    private val factory = FakeSurfaceFactory()
    private val st = CanvasState(Document(mutableListOf(page)), factory, Palette.DEFAULT).apply {
        viewportW = 800
        viewportH = 350
        relayout()
    }
    private val seen = Rect(0.0, 0.0, 300.0, 350.0)

    private fun stroke(id: String, x: Double, y: Double = 70.0, tool: Tool = Tool.PEN): Stroke =
        Stroke(tool, ToolDefaults.configFor(tool), listOf(Sample(x, y, 1.0), Sample(x + 10, y + 4, 1.0))).also {
            it.layerId = id
            page.items.add(it)
        }

    private class Frame(val vectors: FakeRenderer = FakeRenderer()) : Renderer by vectors {
        val blits = mutableListOf<FakeRasterSurface>()
        val blends = mutableListOf<Pair<Double, BlendMode>>()
        val cuts = mutableListOf<List<Pt>>()
        val previewDepths = mutableListOf<Int>()

        override fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect?) {
            val surface = raster as FakeRasterSurface
            assertFalse("a discarded build reached the display", surface.recycled)
            blits += surface
            vectors.drawRaster(raster, dest, src)
        }

        override fun drawRasterBlended(raster: RasterSurface, dest: Rect, alpha: Double, blend: BlendMode, src: Rect?) {
            blends += alpha to blend
            drawRaster(raster, dest, src)
        }
    }

    private fun frame(rect: Rect = seen, preview: Boolean = true): Frame = Frame().also { frame ->
        if (preview) st.paintLayerPreview = { _, _ -> frame.previewDepths += frame.blits.size }
        st.drawUserLayers(frame, page, rect) { frame.cuts.addAll(it) }
    }

    private fun FakeRasterSurface.ribbons() = painter.ribbonRuns.size

    @Test fun warmFramesAndStackChangesOnlyCompositeInVisibleOrder() {
        stroke(bottom, 30.0)
        stroke(top, 90.0)
        val (a, b) = frame().blits
        val paints = factory.created.sumOf { it.ribbons() }
        fun check(order: List<FakeRasterSurface>, depth: Int?) {
            val f = frame()
            assertEquals(order, f.blits)
            assertEquals(listOfNotNull(depth), f.previewDepths)
            assertTrue(f.vectors.ribbonRuns.isEmpty())
            assertEquals(2, factory.created.size)
            assertEquals(paints, factory.created.sumOf { it.ribbons() })
        }
        check(listOf(a, b), 2)
        page.layers.select(bottom)
        st.layerStackChanged(page)
        check(listOf(a, b), 1)
        page.layers.move(bottom, 1)
        st.layerStackChanged(page)
        check(listOf(b, a), 2)
        page.layers.update(bottom) { it.copy(visible = false) }
        st.layerStackChanged(page)
        check(listOf(b), null)
        page.layers.update(bottom) { it.copy(visible = true, locked = true, name = "Renamed") }
        st.layerStackChanged(page)
        check(listOf(b, a), 2)
    }

    @Test fun appendPaintsOnlyIntersectingBandsOfItsLayerIncludingBothGutters() {
        for (layer in listOf(bottom, top)) {
            stroke(layer, 30.0)
            stroke(layer, 60.0, 700.0)
        }
        val bands = frame(Rect(0.0, 0.0, 300.0, 1000.0)).blits
        assertEquals(listOf(1, 1, 1, 1), bands.map { it.ribbons() })
        st.appendToCache(page, stroke(top, 150.0, 510.0))
        assertEquals(listOf(1, 1, 2, 2), bands.map { it.ribbons() })
        st.appendToCache(page, stroke(bottom, 150.0, 700.0))
        assertEquals(listOf(1, 2, 2, 2), bands.map { it.ribbons() })
        assertEquals(4, factory.created.size)
    }

    @Test fun repairUsesOnlyTheItemsAndBandsOfEachLayer() {
        for (layer in listOf(bottom, top)) stroke(layer, 40.0, 700.0)
        stroke(bottom, 30.0)
        stroke(top, 60.0)
        stroke(top, 110.0)
        val bands = frame(Rect(0.0, 0.0, 300.0, 1000.0)).blits
        assertEquals(listOf(1, 1, 2, 1), bands.map { it.ribbons() })
        assertTrue(st.repairRegion(page, seen))
        assertEquals(listOf(2, 1, 4, 1), bands.map { it.ribbons() })
        assertEquals(listOf(1, 0, 1, 0), bands.map { it.painter.clips.size })
    }

    @Test fun appendDuringAnEmptyBuildReplaysOnlyOntoTheRightLayer() {
        // Both workers snapshot ink outside this band. Their results have no pixels until the append.
        stroke(top, 30.0, 1800.0)
        stroke(bottom, 80.0, 1800.0)
        val jobs = ArrayDeque<() -> Unit>()
        st.runAsync = { jobs.addLast(it) }
        assertTrue(frame().blits.isEmpty())
        assertEquals(2, jobs.size)
        st.appendToCache(page, stroke(top, 110.0))
        while (jobs.isNotEmpty()) jobs.removeFirst()()
        assertEquals(1, factory.created.size)
        assertEquals(1, frame().blits.single().ribbons())
        assertTrue(jobs.isEmpty())
    }

    @Test fun emptyAndPrunedHiddenLayersOwnNoPixels() {
        stroke(bottom, 40.0)
        stroke(top, 80.0)
        page.layers.create("Empty")
        val (a, b) = frame().blits
        st.prefetchAndPrune()
        assertEquals(2, factory.created.size)
        page.layers.update(top) { it.copy(visible = false) }
        st.layerStackChanged(page)
        st.prefetchAndPrune()
        assertTrue(b.recycled)
        assertEquals(listOf(a), frame().blits)
        assertEquals(4L * a.width * a.height, st.cacheSnapshot().ink.bytes)
        page.layers.update(top) { it.copy(visible = true) }
        st.layerStackChanged(page)
        assertEquals(2, factory.created.size)
        val shown = frame().blits
        assertEquals(listOf(a, factory.created.last()), shown)
        assertNotSame(b, shown.last())
        assertEquals(3, factory.created.size)
    }

    @Test fun aHiddenLayerIsNotResurrectedByAPendingBuild() {
        stroke(bottom, 20.0)
        stroke(top, 80.0)
        val jobs = ArrayDeque<() -> Unit>()
        st.runAsync = { jobs.addLast(it) }
        frame()
        page.layers.update(top) { it.copy(visible = false) }
        st.layerStackChanged(page)
        st.prefetchAndPrune()
        while (jobs.isNotEmpty()) jobs.removeFirst()()
        assertEquals(1, frame().blits.size)
        assertEquals(1, factory.created.count { !it.recycled })
        assertEquals(1, factory.created.count { it.recycled })
    }

    @Test fun aColdZoomUsesOnlyTheSameLayersPreviousBandsIncludingEmptyOnes() {
        stroke(bottom, 30.0, 600.0)
        stroke(top, 80.0)
        val area = Rect(0.0, 0.0, 300.0, 800.0)
        val old = frame(area).blits
        val jobs = ArrayDeque<() -> Unit>()
        st.runAsync = { jobs.addLast(it) }
        st.setZoomAnchored(Pt.ZERO, 0.5)
        val waiting = frame(area)
        assertEquals(old, waiting.blits)
        assertEquals(2, waiting.vectors.clips.size)
        assertEquals(2, jobs.size)
        while (jobs.isNotEmpty()) jobs.removeFirst()()
        val fresh = frame(area)
        assertEquals(2, fresh.blits.size)
        assertTrue(fresh.blits.none { it in old })
        assertTrue(fresh.vectors.clips.isEmpty())
    }

    @Test fun aTallLayeredPageEvictsItsFarthestBandsUnderTheSharedBudget() {
        for (layer in listOf(bottom, top)) for (band in 0 until 6) stroke(layer, 20.0, 70.0 + band * 512.0)
        val all = frame(Rect(0.0, 0.0, 300.0, page.height)).blits
        assertEquals(12, all.size)
        st.cacheLimit = { 3L * 300 * 520 * 4 }
        st.prefetchAndPrune()
        val held = all.filterNot { it.recycled }
        assertTrue(held.size < all.size)
        assertTrue(all[5].recycled && all[11].recycled)
        assertEquals(listOf(all[0], all[6]), frame().blits)
        val snapshot = st.cacheSnapshot()
        assertEquals(held.sumOf { it.width.toLong() * it.height * 4 }, snapshot.ink.bytes)
        assertEquals(snapshot.ink.bytes, snapshot.bytes)
        assertTrue(snapshot.bytes <= snapshot.budget)
    }

    @Test fun incompatibleRepresentationsAndLateBuildsDisappearAtBothTransitions() {
        page.layers.delete(top)
        stroke(bottom, 20.0)
        val flat = st.cacheFor(page).bands[0]!!.surface as FakeRasterSurface
        val newLayer = page.layers.create("New").id
        st.layerStackChanged(page)
        assertTrue(flat.recycled)
        assertNull(st.inkLayer(page))
        val jobs = ArrayDeque<() -> Unit>()
        st.runAsync = { jobs.addLast(it) }
        stroke(newLayer, 80.0)
        assertTrue(frame().blits.isEmpty())
        jobs.removeFirst()()
        val layered = frame().blits.single()
        page.items.removeAll { it.layerId == newLayer }
        page.layers.delete(newLayer)
        st.refreshInk(listOf(page))
        assertTrue(layered.recycled)
        while (jobs.isNotEmpty()) jobs.removeFirst()()
        assertTrue(factory.created.all { it.recycled })
        val fresh = st.cacheFor(page).bands[0]!!.surface as FakeRasterSurface
        assertEquals(1, fresh.ribbons())
        assertNotSame(flat, fresh)
    }

    @Test fun deepZoomPaintsOnlyVisibleVectorsWithoutAskingForBandsOrSharpViewport() {
        stroke(bottom, 30.0)
        stroke(top, 90.0)
        stroke(top, 90.0, 1700.0)
        st.zoom = 4.0
        assertTrue(st.isPastResolutionCap())
        val jobs = ArrayDeque<() -> Unit>()
        st.runAsync = { jobs.addLast(it) }
        val f = frame()
        assertEquals(2, f.vectors.ribbonRuns.size)
        assertTrue(f.blits.isEmpty())
        st.askFor(page, seen)
        st.prefetchAndPrune()
        st.requestSharpViewport()
        assertTrue(jobs.isEmpty())
        assertTrue(factory.created.isEmpty())
    }

    @Test fun aHighlighterBlendsAtLayerDepthWithPlaybackAlphaAndOnlyLaterTapeCutOut() {
        fun tape(id: String): TapeItem = TapeItem(
            TapeShape(TapeMode.STRAIGHT, listOf(Pt(10.0, 70.0), Pt(100.0, 70.0)), 20.0), Rgba(40, 40, 40, 255),
        ).also { it.layerId = id; page.items.add(it) }
        tape(bottom)
        val hl = stroke(bottom, 40.0, tool = Tool.HIGHLIGHTER)
        hl.sync = SyncMark(1, 500)
        val cover = tape(top)
        st.fade = InkFade(1, 100)
        page.layers.select(bottom)
        val f = frame()
        assertEquals(3, f.blits.size)
        assertSame(st.highlighterCacheFor(hl, page).surface, f.blits[1])
        assertEquals(0, f.blits.first().ribbons())
        assertEquals(0, f.blits.last().ribbons())
        assertEquals(listOf(2), f.previewDepths)
        assertEquals(listOf(hl.renderColor.a / 255.0 * InkFade.ALPHA to hl.blendMode), f.blends)
        assertEquals(cover.shape.body, f.cuts)
        val paints = factory.created.sumOf { it.ribbons() }
        st.appendToCache(page, hl)
        assertEquals(f.blits, frame().blits)
        assertEquals(paints, factory.created.sumOf { it.ribbons() })
    }

    @Test fun aCommittedRoomCutsEachOldLayerUntilItsOwnResizedBandsAreReady() {
        stroke(bottom, 30.0)
        stroke(top, 80.0)
        val old = frame().blits
        val drag = RoomView(page, sideways = false, split = 130.0, resume = 130.0, shift = 80.0,
            anchored = emptyList(), cover = st.footprint(page))
        page.height += drag.shift
        st.settleRoom(drag)
        val jobs = ArrayDeque<() -> Unit>()
        st.runAsync = { jobs.addLast(it) }
        st.askFor(page, seen)
        val pending = Frame()
        st.drawUserLayers(pending, page, seen, st.roomView) {}
        assertEquals(listOf(old[0], old[0], old[1], old[1]), pending.blits)
        assertTrue(pending.vectors.ribbonRuns.isEmpty())
        assertFalse(st.roomSettled(page))
        while (jobs.isNotEmpty()) jobs.removeFirst()()
        assertTrue(st.roomSettled(page))
        val fresh = frame().blits
        assertEquals(2, fresh.size)
        assertTrue(fresh.none { it in old })
    }

    @Test fun selectionLiftsFromItsBandAndReturnsOnDismissal() {
        stroke(bottom, 30.0)
        val selected = stroke(top, 90.0)
        val ctrl = InteractionController(st, History(), FakeTextMeasurer(), requestRender = {})
        val (a, b) = frame(preview = false).blits
        ctrl.selectPlaced(0, listOf(selected))
        val lifted = frame(preview = false)
        assertEquals(listOf(a), lifted.blits)
        assertEquals(1, lifted.vectors.ribbonRuns.size)
        assertTrue(b.recycled)
        ctrl.clearSelection()
        val back = frame(preview = false)
        assertEquals(2, back.blits.size)
        assertEquals(0, back.vectors.ribbonRuns.size)
        assertEquals(1, back.blits.last().ribbons())
    }

    @Test fun movingInkBetweenLayersRebuildsInBackgroundShowingOldPixelsMeanwhile() {
        val moved = stroke(bottom, 40.0)
        stroke(top, 80.0)
        val before = frame().blits
        val jobs = ArrayDeque<() -> Unit>()
        st.runAsync = { jobs.addLast(it) }
        moved.layerId = top
        st.refreshInk(listOf(page))
        assertEquals(before, frame().blits)
        while (jobs.isNotEmpty()) jobs.removeFirst()()
        val after = frame().blits.single()
        assertEquals(2, after.ribbons())
        assertTrue(before.all { it.recycled })
    }

    @Test fun reorderingLeadingFillsAcrossTextReusesTheBodyAndNeverDoublePaintsTheFill() {
        val fill = ShapeItem.fill(listOf(listOf(Pt(0.0, 0.0), Pt(80.0, 0.0), Pt(80.0, 80.0))), Rgba(5, 6, 7, 255))
        fill.layerId = top
        page.items.add(fill)
        stroke(top, 90.0)
        stroke(bottom, 30.0)
        val over = frame().blits
        assertEquals(3, over.size)
        assertTrue(over[1].painter.pathFills.isNotEmpty())
        page.layers.move(top, -1)
        st.layerStackChanged(page)
        val under = FakeRenderer()
        st.paintUnderText(page, under, seen)
        assertEquals(over[1].painter.pathFills, under.pathFills)
        assertEquals(listOf(over.last(), over.first()), frame().blits)
        page.layers.move(top, 1)
        st.layerStackChanged(page)
        assertEquals(over, frame().blits)
        assertEquals(3, factory.created.size)
    }
}
