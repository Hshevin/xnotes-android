package com.xnotes.pen

import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.HandleId
import com.xnotes.canvas.InteractionController
import com.xnotes.canvas.ResizeMath
import com.xnotes.core.geometry.Obb
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.Command
import com.xnotes.core.history.History
import com.xnotes.core.infinite.CanvasSelection
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.LiftTransform
import com.xnotes.core.infinite.OverlayTessellator
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorEnd
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Glue
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Routing
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.platform.AndroidSurfaceFactory
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.ui.CanvasPointerMode
import com.xnotes.ui.InfiniteInteraction
import com.xnotes.ui.theme.Palette
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The same public MotionEvent streams run on the host and Android, without a GL surface. */
abstract class SelectionCancelContract {
    protected abstract fun runOnMain(block: () -> Unit)

    private val prior = object : Command { override fun redo() = Unit; override fun undo() = Unit }
    private val black = Rgba(0, 0, 0, 255)
    private var clock = 10L

    private fun send(touch: (MotionEvent) -> Boolean, action: Int, at: Pt, second: Pt? = null) {
        val points = listOfNotNull(at, second)
        val properties = points.indices.map { i -> MotionEvent.PointerProperties().apply {
            id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = points.map { p -> MotionEvent.PointerCoords().apply {
            x = p.x.toFloat(); y = p.y.toFloat(); pressure = .5f; size = .1f
        } }.toTypedArray()
        val rawAction = if (action == MotionEvent.ACTION_POINTER_DOWN) action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val event = MotionEvent.obtain(1L, clock++, rawAction, points.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue(touch(event)) } finally { event.recycle() }
    }

    private fun image() = ImageItem(ImageData(File("selection-cancel-photo.png"), 10, 10), Rect(100.0, 100.0, 200.0, 120.0))

    // Stroke's opaque undo snapshot has identity equality. Compare the actual immutable values.
    private fun inkGeometry(stroke: Stroke): Any = Triple(stroke.samples.toList(), stroke.config, stroke.cuts.toList())

    private fun connector(target: CanvasItem): ConnectorItem {
        val point = Glue.point(target, 1)!!
        return ConnectorItem(Routing.STRAIGHT, ConnectorEnd(point.at, target, 1),
            ConnectorEnd(Pt(420.0, 300.0)), black)
    }

    private inner class Paged(pages: List<Page>) {
        val document = Document(pages.toMutableList())
        val history = History().apply { push(prior) }
        var commits = 0
        val state = CanvasState(document, AndroidSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 1000; viewportH = 1000; zoom = 1.0; relayout()
        }
        val controller = InteractionController(state, history, AndroidTextMeasurer(), {},
            onContentChanged = { commits++ }).apply {
            setTool(Tool.SELECT)
            setToolConfig(Tool.SELECT, ToolDefaults.configFor(Tool.SELECT).copy(switchBackAfterSelect = false))
        }
        fun at(page: Int, local: Pt): Pt = state.contentToViewport(state.fromPageSpace(page, local))
        fun downMove(from: Pt, to: Pt) {
            send(controller::onTouch, MotionEvent.ACTION_DOWN, from)
            send(controller::onTouch, MotionEvent.ACTION_MOVE, to)
        }
        fun assertNoCommit(revision: Long) {
            assertSame(prior, history.nextUndo)
            assertFalse(history.canRedo)
            assertEquals(0, commits)
            assertFalse(document.dirty)
            assertEquals(revision, document.bodyRevision)
        }
    }

    @Test fun pagedEndpointResizeCancelRestoresBeforeTheNextCompletedResize() = runOnMain {
        val line = ShapeItem(ShapeKind.LINE, Pt(100.0, 100.0), Pt(300.0, 180.0), black)
        val page = Page(500.0, 500.0, mutableListOf(line))
        val f = Paged(listOf(page))
        f.controller.selectPlaced(0, listOf(line))
        val before = line.snapshotGeometry()
        val revision = f.document.bodyRevision
        val from = f.at(0, line.end)
        val to = f.at(0, Pt(400.0, 330.0))
        f.downMove(from, to)
        assertNotEquals(before, line.snapshotGeometry())
        send(f.controller::onTouch, MotionEvent.ACTION_CANCEL, to)
        assertEquals(before, line.snapshotGeometry())
        assertSame(line, page.items.single())
        f.assertNoCommit(revision)
        f.downMove(from, to)
        send(f.controller::onTouch, MotionEvent.ACTION_UP, to)
        val after = line.snapshotGeometry()
        assertNotEquals(before, after)
        assertEquals(1, f.commits)
        f.history.undo()
        assertEquals(before, line.snapshotGeometry())
        assertSame(prior, f.history.nextUndo)
        f.history.redo()
        assertEquals(after, line.snapshotGeometry())
    }

    @Test fun pagedWholeResizeCancelRestoresSelectedAndGluedGeometry() = runOnMain {
        val item = image()
        val edge = connector(item)
        val page = Page(500.0, 500.0, mutableListOf(item, edge))
        val f = Paged(listOf(page))
        f.controller.selectPlaced(0, listOf(item))
        val before = item.snapshotGeometry()
        val edgeBefore = edge.snapshotGeometry()
        val revision = f.document.bodyRevision
        val from = f.at(0, Pt(item.rect.right, item.rect.bottom))
        val to = f.at(0, Pt(440.0, 410.0))
        f.downMove(from, to)
        assertNotEquals(before, item.snapshotGeometry())
        assertNotEquals(edgeBefore, edge.snapshotGeometry())
        send(f.controller::onTouch, MotionEvent.ACTION_CANCEL, to)
        assertEquals(before, item.snapshotGeometry())
        assertEquals(edgeBefore, edge.snapshotGeometry())
        assertSame(item, edge.start.target)
        f.assertNoCommit(revision)
        f.downMove(from, to)
        send(f.controller::onTouch, MotionEvent.ACTION_UP, to)
        val after = item.snapshotGeometry()
        val edgeAfter = edge.snapshotGeometry()
        f.history.undo()
        assertEquals(before, item.snapshotGeometry())
        assertEquals(edgeBefore, edge.snapshotGeometry())
        assertSame(prior, f.history.nextUndo)
        f.history.redo()
        assertEquals(after, item.snapshotGeometry())
        assertEquals(edgeAfter, edge.snapshotGeometry())
    }

    @Test fun pagedCrossPageRotateCancelRetainsPageMembershipAndLayerIds() = runOnMain {
        val a = image()
        val b = image().apply { rect = Rect(150.0, 120.0, 80.0, 70.0) }
        val pages = listOf(Page(500.0, 500.0, mutableListOf(a)), Page(500.0, 500.0, mutableListOf(b)))
        val f = Paged(pages)
        f.controller.selectAll()
        val before = listOf(a.snapshotGeometry(), b.snapshotGeometry())
        val layers = listOf(a.layerId, b.layerId)
        val revision = f.document.bodyRevision
        val bounds = f.state.fromPageSpaceRect(0, a.bounds()).union(f.state.fromPageSpaceRect(1, b.bounds()))
        val box = Obb.fromAabb(bounds)
        val from = f.state.contentToViewport(ResizeMath.obbRotateGrip(box, InteractionController.ROTATE_ARM / f.state.zoom))
        val to = f.state.contentToViewport(Pt(box.center.x + box.halfH + 100.0, box.center.y))
        f.downMove(from, to)
        assertNotEquals(before, listOf(a.snapshotGeometry(), b.snapshotGeometry()))
        send(f.controller::onTouch, MotionEvent.ACTION_CANCEL, to)
        assertEquals(before, listOf(a.snapshotGeometry(), b.snapshotGeometry()))
        assertSame(a, pages[0].items.single())
        assertSame(b, pages[1].items.single())
        assertEquals(layers, listOf(a.layerId, b.layerId))
        f.assertNoCommit(revision)
    }

    @Test fun pagedSecondFingerRollsBackResizeBeforePinching() = runOnMain {
        val item = image()
        val f = Paged(listOf(Page(500.0, 500.0, mutableListOf(item))))
        f.controller.selectPlaced(0, listOf(item))
        val before = item.snapshotGeometry()
        val revision = f.document.bodyRevision
        val from = f.at(0, Pt(item.rect.right, item.rect.bottom))
        val to = f.at(0, Pt(440.0, 410.0))
        f.downMove(from, to)
        assertNotEquals(before, item.snapshotGeometry())
        send(f.controller::onTouch, MotionEvent.ACTION_POINTER_DOWN, to, to + Pt(100.0, 0.0))
        assertEquals(before, item.snapshotGeometry())
        send(f.controller::onTouch, MotionEvent.ACTION_CANCEL, to)
        assertEquals(before, item.snapshotGeometry())
        f.assertNoCommit(revision)
    }

    private inner class Canvas(selected: List<CanvasItem>, extra: List<CanvasItem> = emptyList()) {
        val document = InfiniteDocument().apply { addAll(selected + extra); dirty = false }
        val selection = CanvasSelection(document).apply { select(selected) }
        val history = History().apply { push(prior) }
        var commits = 0
        var lifted = emptyList<CanvasItem>()
        var lift = LiftTransform.NONE
        var afterCommit: () -> Unit = {}
        val viewport = CanvasViewport().apply { widthPx = 2000; heightPx = 2000 }
        val interaction = InfiniteInteraction(viewport, {}, selection = { selection },
            onCommitSelection = { command -> command?.let {
                history.push(it); commits++; document.dirty = true; afterCommit()
            } },
            onLiftSelection = { items, transform -> lifted = items; lift = transform }).apply { tool = Tool.SELECT }
        fun downMove(from: Pt, to: Pt) {
            send(interaction::onTouch, MotionEvent.ACTION_DOWN, from)
            send(interaction::onTouch, MotionEvent.ACTION_MOVE, to)
        }
        fun assertNoCommit(revision: Long) {
            assertSame(prior, history.nextUndo)
            assertFalse(history.canRedo)
            assertEquals(0, commits)
            assertFalse(document.dirty)
            assertEquals(revision, document.bodyRevision)
            assertTrue(lifted.isEmpty())
            assertEquals(LiftTransform.NONE, lift)
            assertEquals(CanvasPointerMode.IDLE, interaction.mode)
        }
    }

    @Test fun canvasPhotoResizeCancelRestoresGeometryConnectorsAndSpatialIndex() = runOnMain {
        val item = image()
        val edge = connector(item)
        val f = Canvas(listOf(item), listOf(edge))
        val before = item.snapshotGeometry()
        val edgeBefore = edge.snapshotGeometry()
        val box = f.selection.box
        val revision = f.document.bodyRevision
        val from = f.selection.handles().single { it.id == HandleId.BR }.content
        val to = Pt(1400.0, 1400.0)
        f.downMove(from, to)
        assertNotEquals(before, item.snapshotGeometry())
        assertNotEquals(edgeBefore, edge.snapshotGeometry())
        // Corner handles keep aspect: derive a point actually inside the result, past the old box.
        val previewBounds = item.bounds()
        val previewOnly = Rect(previewBounds.right - 5.0, previewBounds.bottom - 5.0, 1.0, 1.0)
        assertTrue(previewOnly.left > 300.0 || previewOnly.top > 220.0)
        assertTrue(f.document.itemsIn(previewOnly).contains(item))
        send(f.interaction::onTouch, MotionEvent.ACTION_CANCEL, to)
        assertEquals(before, item.snapshotGeometry())
        assertEquals(edgeBefore, edge.snapshotGeometry())
        assertSame(item, edge.start.target)
        assertEquals(box, f.selection.box)
        assertTrue(f.document.itemsIn(Rect(150.0, 150.0, 1.0, 1.0)).contains(item))
        assertFalse(f.document.itemsIn(previewOnly).contains(item))
        f.assertNoCommit(revision)
        f.downMove(from, to)
        send(f.interaction::onTouch, MotionEvent.ACTION_UP, to)
        val after = item.snapshotGeometry()
        val edgeAfter = edge.snapshotGeometry()
        assertEquals(1, f.commits)
        f.history.undo()
        assertEquals(before, item.snapshotGeometry())
        assertEquals(edgeBefore, edge.snapshotGeometry())
        assertSame(prior, f.history.nextUndo)
        f.history.redo()
        assertEquals(after, item.snapshotGeometry())
        assertEquals(edgeAfter, edge.snapshotGeometry())
    }

    @Test fun canvasPhotoRotateCancelRestoresExactBoxAndConnectorBindings() = runOnMain {
        val item = image()
        val edge = connector(item)
        val f = Canvas(listOf(item), listOf(edge))
        val before = item.snapshotGeometry()
        val edgeBefore = edge.snapshotGeometry()
        val box = f.selection.box!!
        val revision = f.document.bodyRevision
        val from = f.selection.rotateGrip(OverlayTessellator.GRIP_ARM_PX)!!
        val to = Pt(box.center.x + 150.0, box.center.y)
        f.downMove(from, to)
        assertNotEquals(before, item.snapshotGeometry())
        send(f.interaction::onTouch, MotionEvent.ACTION_CANCEL, to)
        assertEquals(before, item.snapshotGeometry())
        assertEquals(edgeBefore, edge.snapshotGeometry())
        assertSame(item, edge.start.target)
        assertEquals(box, f.selection.box)
        f.assertNoCommit(revision)
    }

    @Test fun canvasLiftedMixedResizeCancelRestoresBakedShapeAndLiftedInk() = runOnMain {
        val shape = ShapeItem(ShapeKind.RECTANGLE, Pt(100.0, 100.0), Pt(250.0, 250.0), black)
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN),
            mutableListOf(Sample(150.0, 300.0, .7), Sample(300.0, 350.0, .4)))
        val edge = connector(shape)
        val f = Canvas(listOf(shape, stroke), listOf(edge))
        val before = listOf(shape.snapshotGeometry(), inkGeometry(stroke), edge.snapshotGeometry())
        val box = f.selection.box
        val revision = f.document.bodyRevision
        val from = f.selection.handles().single { it.id == HandleId.BR }.content
        val to = Pt(600.0, 650.0)
        f.downMove(from, to)
        assertEquals(listOf<CanvasItem>(stroke), f.lifted)
        assertNotEquals(before[0], shape.snapshotGeometry())
        assertEquals(before[1], inkGeometry(stroke))
        send(f.interaction::onTouch, MotionEvent.ACTION_CANCEL, to)
        assertEquals(before, listOf(shape.snapshotGeometry(), inkGeometry(stroke), edge.snapshotGeometry()))
        assertEquals(box, f.selection.box)
        f.assertNoCommit(revision)
    }

    @Test fun canvasSecondFingerRollsBackPhotoResizeBeforePinching() = runOnMain {
        val item = image()
        val f = Canvas(listOf(item))
        val before = item.snapshotGeometry()
        val box = f.selection.box
        val revision = f.document.bodyRevision
        val from = f.selection.handles().single { it.id == HandleId.BR }.content
        val to = Pt(600.0, 650.0)
        f.downMove(from, to)
        assertNotEquals(before, item.snapshotGeometry())
        send(f.interaction::onTouch, MotionEvent.ACTION_POINTER_DOWN, to, to + Pt(100.0, 0.0))
        assertEquals(CanvasPointerMode.PINCH, f.interaction.mode)
        assertEquals(before, item.snapshotGeometry())
        assertEquals(box, f.selection.box)
        send(f.interaction::onTouch, MotionEvent.ACTION_CANCEL, to)
        f.assertNoCommit(revision)
    }

    @Test fun pagedClearingSelectionDuringResizeRestoresBeforeDroppingBaseline() = runOnMain {
        val item = image()
        val edge = connector(item)
        val f = Paged(listOf(Page(500.0, 500.0, mutableListOf(item, edge))))
        f.controller.selectPlaced(0, listOf(item))
        val before = listOf(item.snapshotGeometry(), edge.snapshotGeometry())
        val revision = f.document.bodyRevision
        val from = f.at(0, Pt(item.rect.right, item.rect.bottom))
        val to = f.at(0, Pt(440.0, 410.0))
        f.downMove(from, to)
        assertNotEquals(before[0], item.snapshotGeometry())
        f.controller.clearSelection()
        assertFalse(f.controller.hasSelection)
        assertEquals(before, listOf(item.snapshotGeometry(), edge.snapshotGeometry()))
        send(f.controller::onTouch, MotionEvent.ACTION_UP, to)
        assertEquals(before, listOf(item.snapshotGeometry(), edge.snapshotGeometry()))
        f.assertNoCommit(revision)
    }

    @Test fun canvasClearingSelectionDuringResizeRestoresBeforeDroppingBaseline() = runOnMain {
        val item = image()
        val edge = connector(item)
        val f = Canvas(listOf(item), listOf(edge))
        val before = listOf(item.snapshotGeometry(), edge.snapshotGeometry())
        val revision = f.document.bodyRevision
        val from = f.selection.handles().single { it.id == HandleId.BR }.content
        val to = Pt(600.0, 650.0)
        f.downMove(from, to)
        assertNotEquals(before[0], item.snapshotGeometry())
        f.interaction.clearSelection()
        assertTrue(f.selection.isEmpty)
        assertEquals(before, listOf(item.snapshotGeometry(), edge.snapshotGeometry()))
        send(f.interaction::onTouch, MotionEvent.ACTION_UP, to)
        assertEquals(before, listOf(item.snapshotGeometry(), edge.snapshotGeometry()))
        f.assertNoCommit(revision)
    }

    @Test fun canvasSecondFingerRollsBackLiftedResizeBeforePinching() = runOnMain {
        val shape = ShapeItem(ShapeKind.RECTANGLE, Pt(100.0, 100.0), Pt(250.0, 250.0), black)
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN),
            mutableListOf(Sample(150.0, 300.0, .7), Sample(300.0, 350.0, .4)))
        val f = Canvas(listOf(shape, stroke))
        val before = listOf(shape.snapshotGeometry(), inkGeometry(stroke))
        val box = f.selection.box
        val revision = f.document.bodyRevision
        val from = f.selection.handles().single { it.id == HandleId.BR }.content
        val to = Pt(600.0, 650.0)
        f.downMove(from, to)
        assertNotEquals(before[0], shape.snapshotGeometry())
        assertEquals(listOf<CanvasItem>(stroke), f.lifted)
        send(f.interaction::onTouch, MotionEvent.ACTION_POINTER_DOWN, to, to + Pt(100.0, 0.0))
        assertEquals(CanvasPointerMode.PINCH, f.interaction.mode)
        assertEquals(before, listOf(shape.snapshotGeometry(), inkGeometry(stroke)))
        assertEquals(box, f.selection.box)
        assertTrue(f.lifted.isEmpty())
        send(f.interaction::onTouch, MotionEvent.ACTION_CANCEL, to)
        f.assertNoCommit(revision)
    }

    @Test fun canvasCommittedResizeCanClearSelectionSynchronouslyWithoutRollingBack() = runOnMain {
        val item = image()
        val edge = connector(item)
        val f = Canvas(listOf(item), listOf(edge))
        val before = listOf(item.snapshotGeometry(), edge.snapshotGeometry())
        val from = f.selection.handles().single { it.id == HandleId.BR }.content
        val to = Pt(600.0, 650.0)
        f.downMove(from, to)
        val shown = listOf(item.snapshotGeometry(), edge.snapshotGeometry())
        assertNotEquals(before[0], shown[0])
        f.afterCommit = {
            assertEquals(CanvasPointerMode.IDLE, f.interaction.mode)
            f.interaction.clearSelection()
        }
        send(f.interaction::onTouch, MotionEvent.ACTION_UP, to)
        assertTrue(f.selection.isEmpty)
        assertEquals(CanvasPointerMode.IDLE, f.interaction.mode)
        assertEquals(1, f.commits)
        assertEquals(shown, listOf(item.snapshotGeometry(), edge.snapshotGeometry()))
        f.history.undo()
        assertEquals(before, listOf(item.snapshotGeometry(), edge.snapshotGeometry()))
        assertSame(prior, f.history.nextUndo)
        f.history.redo()
        assertEquals(shown, listOf(item.snapshotGeometry(), edge.snapshotGeometry()))
    }

    @Test fun canvasCommittedRotateCanClearSelectionBeforeReturningToTheHost() = runOnMain {
        val item = image()
        val edge = connector(item)
        val f = Canvas(listOf(item), listOf(edge))
        val before = listOf(item.snapshotGeometry(), edge.snapshotGeometry())
        val from = f.selection.rotateGrip(OverlayTessellator.GRIP_ARM_PX)!!
        val center = f.selection.box!!.center
        val to = Pt(center.x + 150.0, center.y)
        f.downMove(from, to)
        assertEquals(CanvasPointerMode.ROTATE, f.interaction.mode)
        val shown = listOf(item.snapshotGeometry(), edge.snapshotGeometry())
        assertNotEquals(before[0], shown[0])
        f.afterCommit = {
            assertEquals(CanvasPointerMode.IDLE, f.interaction.mode)
            f.interaction.clearSelection()
        }
        send(f.interaction::onTouch, MotionEvent.ACTION_UP, to)
        assertTrue(f.selection.isEmpty)
        assertEquals(1, f.commits)
        assertEquals(shown, listOf(item.snapshotGeometry(), edge.snapshotGeometry()))
        f.history.undo()
        assertEquals(before, listOf(item.snapshotGeometry(), edge.snapshotGeometry()))
        assertSame(prior, f.history.nextUndo)
        f.history.redo()
        assertEquals(shown, listOf(item.snapshotGeometry(), edge.snapshotGeometry()))
    }

    @Test fun canvasCommittedMoveCanClearSelectionAndRetainJoinedConnectors() = runOnMain {
        val item = image()
        val edge = connector(item)
        val f = Canvas(listOf(item), listOf(edge))
        val before = listOf(item.snapshotGeometry(), edge.snapshotGeometry())
        val from = item.rect.center
        val to = from + Pt(200.0, 250.0)
        val expectedRect = item.rect.translate(200.0, 250.0)
        f.downMove(from, to)
        assertEquals(CanvasPointerMode.MOVE, f.interaction.mode)
        // The photo is renderer-lifted; its joined connector is previewed in the model.
        assertEquals(before[0], item.snapshotGeometry())
        val shownEdge = edge.snapshotGeometry()
        assertNotEquals(before[1], shownEdge)
        f.afterCommit = {
            assertEquals(CanvasPointerMode.IDLE, f.interaction.mode)
            f.interaction.clearSelection()
        }
        send(f.interaction::onTouch, MotionEvent.ACTION_UP, to)
        assertTrue(f.selection.isEmpty)
        assertEquals(1, f.commits)
        assertEquals(expectedRect, item.rect)
        assertEquals(shownEdge, edge.snapshotGeometry())
        assertSame(item, edge.start.target)
        val after = listOf(item.snapshotGeometry(), edge.snapshotGeometry())
        f.history.undo()
        assertEquals(before, listOf(item.snapshotGeometry(), edge.snapshotGeometry()))
        assertSame(prior, f.history.nextUndo)
        f.history.redo()
        assertEquals(after, listOf(item.snapshotGeometry(), edge.snapshotGeometry()))
    }

    @Test fun canvasCommittedJointCanClearSelectionWithoutRestoringTheEditedEndpoint() = runOnMain {
        val item = image()
        val edge = connector(item)
        val f = Canvas(listOf(edge), listOf(item))
        val before = edge.snapshotGeometry()
        val from = edge.end.at
        val to = Pt(600.0, 650.0)
        f.downMove(from, to)
        assertEquals(CanvasPointerMode.JOINT, f.interaction.mode)
        val shown = edge.snapshotGeometry()
        assertNotEquals(before, shown)
        assertEquals(to, edge.end.at)
        f.afterCommit = {
            assertEquals(CanvasPointerMode.IDLE, f.interaction.mode)
            f.interaction.clearSelection()
        }
        send(f.interaction::onTouch, MotionEvent.ACTION_UP, to)
        assertTrue(f.selection.isEmpty)
        assertEquals(1, f.commits)
        assertEquals(shown, edge.snapshotGeometry())
        assertSame(item, edge.start.target)
        f.history.undo()
        assertEquals(before, edge.snapshotGeometry())
        assertSame(prior, f.history.nextUndo)
        f.history.redo()
        assertEquals(shown, edge.snapshotGeometry())
    }

    @Test fun canvasCancelledResizePreservesExistingDirtyRevisionAndRedo() = runOnMain {
        val item = image()
        val edge = connector(item)
        val f = Canvas(listOf(item), listOf(edge))
        f.history.undo()
        assertNull(f.history.nextUndo)
        assertSame(prior, f.history.nextRedo)
        f.document.dirty = true
        val revision = f.document.bodyRevision
        val before = listOf(item.snapshotGeometry(), edge.snapshotGeometry())
        val box = f.selection.box
        val from = f.selection.handles().single { it.id == HandleId.BR }.content
        val to = Pt(600.0, 650.0)
        f.downMove(from, to)
        assertNotEquals(before[0], item.snapshotGeometry())
        send(f.interaction::onTouch, MotionEvent.ACTION_CANCEL, to)
        assertEquals(before, listOf(item.snapshotGeometry(), edge.snapshotGeometry()))
        assertEquals(box, f.selection.box)
        assertTrue(f.document.dirty)
        assertEquals(revision, f.document.bodyRevision)
        assertEquals(0, f.commits)
        assertNull(f.history.nextUndo)
        assertSame(prior, f.history.nextRedo)
        assertTrue(f.history.canRedo)
    }
}
