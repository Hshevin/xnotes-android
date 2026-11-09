package com.xnotes.pen

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.AddCanvasItem
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.DrawStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.gl.GlRenderer
import com.xnotes.gl.InfiniteCanvasView
import com.xnotes.ui.InfiniteEditor
import org.junit.Assert.*
import org.junit.Test
import java.util.Queue

/** Host-lifecycle/state contracts; no compositor, GPU pixels or Compose clipping are simulated. */
abstract class InfiniteSurfaceAttachContract {
    protected abstract fun runOnMain(block: (Context) -> Unit)

    private fun withEditor(block: (InfiniteEditor) -> Unit) = runOnMain { context ->
        val editor = InfiniteEditor(context)
        try { block(editor) } finally { detach(editor.view) }
    }

    @Test fun replacingDocumentRetiresTheOutgoingRestylePreviewBaseline() = withEditor { editor ->
        val old = ShapeItem(ShapeKind.RECTANGLE, Pt(100.0, 100.0), Pt(220.0, 240.0),
            Rgba(0, 0, 0, 255), strokeWidth = 3.0, fillRgba = Rgba(128, 128, 128, 255))
        val oldDocument = editor.document
        oldDocument.add(old)
        editor.selectAll()
        editor.restyleSelection(Rgba(64, 64, 64, 255), 8.0, preview = true)
        val oldPreview = DrawStyle.of(old)
        assertEquals(DrawStyle(Rgba(64, 64, 64, 255), 8.0), oldPreview)
        assertFalse(editor.history.canUndo)

        val fresh = ShapeItem(ShapeKind.RECTANGLE, Pt(400.0, 400.0), Pt(520.0, 540.0),
            Rgba(0, 0, 0, 255), strokeWidth = 3.0, fillRgba = Rgba(128, 128, 128, 255))
        val original = DrawStyle.of(fresh)
        val next = InfiniteDocument().apply { add(fresh) }
        editor.replaceDocument(next)
        editor.selectAll()
        editor.restyleSelection(Rgba(192, 192, 192, 255), 13.0, preview = false)
        val committed = DrawStyle(Rgba(192, 192, 192, 255), 13.0)
        assertEquals(committed, DrawStyle.of(fresh))
        assertEquals(oldPreview, DrawStyle.of(old))
        assertTrue(editor.history.canUndo)
        editor.undo()
        assertEquals(original, DrawStyle.of(fresh))
        assertEquals(oldPreview, DrawStyle.of(old))
        assertSame(fresh, next.items.single())
        assertSame(old, oldDocument.items.single())
        assertFalse(next.itemsIn(old.paintBounds()).contains(old))
        assertFalse(editor.history.canUndo)
        assertTrue(editor.history.canRedo)
        editor.redo()
        assertEquals(committed, DrawStyle.of(fresh))
        assertEquals(oldPreview, DrawStyle.of(old))
        assertSame(fresh, next.items.single())
    }

    @Test fun replacingDocumentRetiresTheOutgoingFillPreviewBaseline() = withEditor { editor ->
        val old = ShapeItem(ShapeKind.RECTANGLE, Pt(100.0, 100.0), Pt(220.0, 240.0),
            Rgba(0, 0, 0, 255), fillRgba = Rgba(32, 32, 32, 255))
        val oldDocument = editor.document
        oldDocument.add(old)
        editor.selectAll()
        editor.refillSelection(Rgba(128, 128, 128, 255), preview = true)
        val oldPreview = old.fillRgba
        assertEquals(Rgba(128, 128, 128, 255), oldPreview)
        assertFalse(editor.history.canUndo)

        val original = Rgba(64, 64, 64, 255)
        val fresh = ShapeItem(ShapeKind.RECTANGLE, Pt(400.0, 400.0), Pt(520.0, 540.0),
            Rgba(0, 0, 0, 255), fillRgba = original)
        val next = InfiniteDocument().apply { add(fresh) }
        val committed = Rgba(192, 192, 192, 255)
        editor.replaceDocument(next)
        editor.selectAll()
        editor.refillSelection(committed, preview = false)
        assertEquals(committed, fresh.fillRgba)
        assertEquals(oldPreview, old.fillRgba)
        assertTrue(editor.history.canUndo)
        editor.undo()
        assertEquals(original, fresh.fillRgba)
        assertEquals(oldPreview, old.fillRgba)
        assertSame(fresh, next.items.single())
        assertSame(old, oldDocument.items.single())
        assertFalse(next.itemsIn(old.paintBounds()).contains(old))
        assertFalse(editor.history.canUndo)
        assertTrue(editor.history.canRedo)
        editor.redo()
        assertEquals(committed, fresh.fillRgba)
        assertEquals(oldPreview, old.fillRgba)
        assertSame(fresh, next.items.single())
    }

    @Test fun replacingDocumentCancelsTheOutgoingColorDrag() = withEditor { editor ->
        val old = editor.document
        editor.beginColorDrag(Rgba(64, 64, 64, 255), Offset.Zero)
        assertNotNull(editor.colorDrag)
        val next = InfiniteDocument()
        editor.replaceDocument(next)
        assertNull(editor.colorDrag)
        // A retired pointer's release cannot revive the drag or modify its replacement.
        editor.moveColorDrag(Offset(20f, 30f))
        editor.endColorDrag(Offset(20f, 30f))
        assertNull(editor.colorDrag)
        assertTrue(old.items.isEmpty())
        assertTrue(next.items.isEmpty())
        assertFalse(editor.history.canUndo)
        editor.beginColorDrag(Rgba(128, 128, 128, 255), Offset.Zero)
        assertNotNull(editor.colorDrag)
        editor.cancelColorDrag()
        assertNull(editor.colorDrag)
    }

    @Test fun contentCaptureWaitsForEditedLabelsButNotUnchangedOpenLabels() = withEditor { editor ->
        val shape = ShapeItem(ShapeKind.RECTANGLE, Pt(100.0, 100.0), Pt(220.0, 240.0), Rgba(0, 0, 0, 255))
        editor.document.add(shape)
        editor.selectAll()
        editor.labelSelection()
        assertNotNull(editor.editingField)
        assertFalse(editor.hasPendingContentTransaction)
        val seen = ArrayList<Boolean>()
        editor.onContentTransactionChanged = { seen += editor.hasPendingContentTransaction }
        editor.updateEditingText("pending")
        assertTrue(editor.hasPendingContentTransaction)
        editor.updateEditingText("")
        assertFalse(editor.hasPendingContentTransaction)
        editor.updateEditingText("confirmed")
        editor.commitText(null)
        assertFalse(editor.hasPendingContentTransaction)
        assertEquals(false, seen.last())
        assertEquals("confirmed", shape.label!!.text)
    }

    @Test fun clearingSelectionSettlesTheCanvasFillPreviewExactlyAsShown() = withEditor { editor ->
        val original = Rgba(64, 64, 64, 255)
        val shown = Rgba(128, 128, 128, 255)
        val shape = ShapeItem(ShapeKind.RECTANGLE, Pt(100.0, 100.0), Pt(220.0, 240.0),
            Rgba(0, 0, 0, 255), fillRgba = original)
        editor.document.add(shape)
        editor.selectAll()
        editor.refillSelection(shown, preview = true)
        assertTrue(editor.hasPendingContentTransaction)
        assertFalse(editor.canUndo)
        editor.interaction.clearSelection()
        assertFalse(editor.hasPendingContentTransaction)
        assertEquals(shown, shape.fillRgba)
        assertTrue(editor.canUndo)
        editor.undo()
        assertEquals(original, shape.fillRgba)
        assertFalse(editor.history.canUndo)
        editor.redo()
        assertEquals(shown, shape.fillRgba)
    }

    @Test fun undoSettlesTheCanvasStylePreviewBeforeTakingTheNextHistoryEntry() = withEditor { editor ->
        val shape = ShapeItem(ShapeKind.RECTANGLE, Pt(100.0, 100.0), Pt(220.0, 240.0),
            Rgba(0, 0, 0, 255), strokeWidth = 3.0)
        val original = DrawStyle.of(shape)
        editor.document.add(shape)
        editor.history.push(AddCanvasItem(editor.document, shape))
        editor.selectAll()
        editor.restyleSelection(Rgba(128, 128, 128, 255), 8.0, preview = true)
        val shown = DrawStyle.of(shape)
        assertNotEquals(original, shown)
        editor.undo()
        assertSame(shape, editor.document.items.single())
        assertEquals(original, DrawStyle.of(shape))
        assertTrue(editor.canUndo)
        assertTrue(editor.canRedo)
        editor.redo()
        assertEquals(shown, DrawStyle.of(shape))
        assertTrue(editor.canUndo)
        assertFalse(editor.canRedo)
        editor.undo()
        editor.undo()
        assertTrue(editor.document.items.isEmpty())
        assertFalse(editor.canUndo)
        assertTrue(editor.canRedo)
        editor.redo()
        assertSame(shape, editor.document.items.single())
        assertEquals(original, DrawStyle.of(shape))
        editor.redo()
        assertEquals(shown, DrawStyle.of(shape))
    }

    @Test fun redoSettlesTheNewCanvasPreviewAndDoesNotReplayItsAbandonedRedo() = withEditor { editor ->
        val shape = ShapeItem(ShapeKind.RECTANGLE, Pt(100.0, 100.0), Pt(220.0, 240.0),
            Rgba(0, 0, 0, 255), strokeWidth = 3.0)
        val original = DrawStyle.of(shape)
        editor.document.add(shape)
        editor.history.push(AddCanvasItem(editor.document, shape))
        editor.selectAll()
        editor.restyleSelection(Rgba(64, 64, 64, 255), 4.0, preview = false)
        editor.undo()
        assertEquals(original, DrawStyle.of(shape))
        assertTrue(editor.canRedo)
        editor.selectAll()
        editor.restyleSelection(Rgba(128, 128, 128, 255), 8.0, preview = true)
        val shown = DrawStyle.of(shape)
        editor.redo()
        assertSame(shape, editor.document.items.single())
        assertEquals(shown, DrawStyle.of(shape))
        assertTrue(editor.canUndo)
        assertFalse(editor.canRedo)
        editor.undo()
        assertEquals(original, DrawStyle.of(shape))
        assertTrue(editor.canUndo)
    }

    @Test fun firstHostKeepsTheNeverAttachedSurfaces() = withEditor { editor ->
        val view = editor.view
        val pad = editor.pad
        assertSame(editor.surfaces, editor.surfacesForAttach())
        assertSame(view, editor.view)
        assertSame(pad, editor.pad)
        assertEquals(3, editor.surfaces.childCount)
    }

    @Test fun reattachmentKeepsTheDocumentViewportAppearanceAndInputBindings() = withEditor { editor ->
        val old = editor.view
        old.layout(0, 0, 320, 800)
        val viewport = editor.viewport
        viewport.zoom = 2.0
        viewport.scrollX = 19.0
        viewport.scrollY = -31.0
        viewport.insetRight = 48.0
        val document = editor.document
        val history = editor.history
        val stroke = Stroke(Tool.PEN, ToolConfig()).apply {
            addSample(Sample(10.0, 20.0, 1.0))
            addSample(Sample(30.0, 40.0, 1.0))
        }
        document.add(stroke)
        history.push(AddCanvasItem(document, stroke))
        old.background = CanvasBackground(spacing = 43.0)
        old.paperColor = Rgba(0, 0, 0, 255)
        old.darkAppearance = true
        old.accent = Rgba(255, 255, 255, 255)
        old.minimapVisible = true
        old.contentBounds = Rect(10.0, 20.0, 30.0, 40.0)
        old.setEraserCursor(Pt(70.0, 80.0), 9.0)
        var taps = 0
        old.onTwoFingerTap = { taps += 2 }
        old.onThreeFingerTap = { taps += 3 }
        old.onFourFingerTap = { taps += 4 }
        old.onKey = { true }
        val oldPad = editor.pad
        oldPad.frontBuffering = false
        oldPad.lightBlackInk = true
        val scene = old.scene

        detach(old)
        assertSame(editor.surfaces, editor.surfacesForAttach())
        val next = editor.view
        assertNotSame(old, next)
        assertNotSame(oldPad, editor.pad)
        assertNull(old.parent)
        assertNull(oldPad.parent)
        assertNull(oldPad.onSurfaceLost)
        assertFalse(editor.pad.frontBuffering)
        assertTrue(editor.pad.lightBlackInk)
        assertSame(next, editor.surfaces.getChildAt(0))
        assertSame(editor.pad, editor.surfaces.getChildAt(1))
        assertSame(editor.toolOverlay, editor.surfaces.getChildAt(2))
        assertSame(document, editor.document)
        assertSame(history, editor.history)
        assertSame(scene, next.scene)
        assertSame(viewport, editor.viewport)
        assertSame(old.input, next.input)
        assertSame(old.hover, next.hover)
        assertSame(old.genericMotion, next.genericMotion)
        assertSame(old.onInputLost, next.onInputLost)
        assertSame(old.afterLayout, next.afterLayout)
        assertSame(old.onContextReady, next.onContextReady)
        assertSame(old.crossPaneTouchGate.shouldBlock, next.crossPaneTouchGate.shouldBlock)
        next.onTwoFingerTap!!()
        next.onThreeFingerTap!!()
        next.onFourFingerTap!!()
        assertEquals(9, taps)
        assertTrue(next.onKey!!(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))

        // The new host expands from one third to the full width. The same viewport object that
        // InfiniteInteraction captured must receive the layout and publish the full-size frame.
        next.layout(0, 0, 960, 800)
        assertEquals(960, viewport.widthPx)
        assertEquals(800, viewport.heightPx)
        assertEquals(2.0, viewport.zoom, 0.0)
        assertEquals(19.0, viewport.scrollX, 0.0)
        assertEquals(-31.0, viewport.scrollY, 0.0)
        val frame = renderer(next).frame
        assertEquals(960, frame.widthPx)
        assertEquals(800, frame.heightPx)
        assertEquals(old.background, frame.background)
        assertEquals(old.paperColor, frame.paper)
        assertEquals(old.accent, frame.accent)
        assertEquals(old.contentBounds, frame.contentBounds)
        assertTrue(frame.lightBlackInk)
        assertTrue(frame.minimapVisible)
        assertEquals(48.0, frame.insetRight, 0.0)
        assertEquals(70.0, frame.cursorX, 0.0)
        assertEquals(80.0, frame.cursorY, 0.0)
        assertEquals(9.0, frame.cursorRadius, 0.0)
        assertTrue(frame.cursorVisible)
        editor.undo()
        assertTrue(document.isEmpty)
        editor.redo()
        assertSame(stroke, document.items.single())
    }

    @Test fun replacementRetiresPendingInkAndDoesNotReuseOldRendererCompletions() = withEditor { editor ->
        val old = editor.view
        val oldRenderer = renderer(old)
        // Seed a context history without creating EGL in the state-contract test.
        field(GlRenderer::class.java, "contextGen").setInt(oldRenderer, 7)
        oldRenderer.runAfterFrame { fail("old surface completion must not reach its replacement") }
        val stroke = Stroke(Tool.PEN, ToolConfig()).apply {
            addSample(Sample(10.0, 20.0, 1.0))
            addSample(Sample(30.0, 40.0, 1.0))
        }
        field(InfiniteEditor::class.java, "heldItems").set(editor, listOf(stroke))
        editor.document.add(stroke)
        editor.history.push(AddCanvasItem(editor.document, stroke))
        field(InfiniteEditor::class.java, "awaitingPublish").setBoolean(editor, true)
        val handoff = field(InfiniteEditor::class.java, "handoffGen").getInt(editor)

        detach(old)
        editor.surfacesForAttach()

        val nextRenderer = renderer(editor.view)
        assertNotSame(oldRenderer, nextRenderer)
        assertEquals(7, nextRenderer.contextGen)
        assertTrue((field(GlRenderer::class.java, "afterFrame").get(nextRenderer) as Queue<*>).isEmpty())
        assertTrue((field(InfiniteEditor::class.java, "heldItems").get(editor) as List<*>).isEmpty())
        assertFalse(field(InfiniteEditor::class.java, "awaitingPublish").getBoolean(editor))
        assertTrue(field(InfiniteEditor::class.java, "handoffGen").getInt(editor) > handoff)
        assertSame(stroke, editor.document.items.single())
        editor.undo()
        assertTrue(editor.document.isEmpty)
        editor.redo()
        assertSame(stroke, editor.document.items.single())
    }

    @Test fun liveStrokeFinishesInTheSameContentCoordinatesAfterReattachment() = withEditor { editor ->
        editor.view.layout(0, 0, 320, 800)
        editor.viewport.zoom = 2.0
        editor.viewport.scrollX = 19.0
        editor.viewport.scrollY = -31.0
        editor.pad.frontBuffering = false
        editor.armTool(Tool.PEN)
        val downAt = android.os.SystemClock.uptimeMillis()
        stylus(editor.view, downAt, 0, MotionEvent.ACTION_DOWN, 100f, 200f)
        stylus(editor.view, downAt, 20, MotionEvent.ACTION_MOVE, 150f, 250f)
        detach(editor.view)
        editor.surfacesForAttach()
        editor.view.layout(0, 0, 960, 800)
        stylus(editor.view, downAt, 40, MotionEvent.ACTION_UP, 200f, 300f)
        val stroke = editor.document.items.single() as Stroke
        assertEquals(69.0, stroke.samples.first().x, 0.01)
        assertEquals(69.0, stroke.samples.first().y, 0.01)
        assertEquals(119.0, stroke.samples.last().x, 0.01)
        assertEquals(119.0, stroke.samples.last().y, 0.01)
        editor.undo()
        assertTrue(editor.document.isEmpty)
        editor.redo()
        assertSame(stroke, editor.document.items.single())
    }

    private fun stylus(view: InfiniteCanvasView, downAt: Long, elapsed: Long, action: Int, x: Float, y: Float) {
        val pointer = MotionEvent.PointerProperties().apply { id = 7; toolType = MotionEvent.TOOL_TYPE_STYLUS }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = .5f }
        val event = MotionEvent.obtain(downAt, downAt + elapsed, action, 1, arrayOf(pointer), arrayOf(coords),
            0, 0, 1f, 1f, 17, 0, InputDevice.SOURCE_STYLUS, 0)
        try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
    }

    private fun renderer(view: InfiniteCanvasView): GlRenderer =
        field(InfiniteCanvasView::class.java, "glRenderer").get(view) as GlRenderer

    private fun field(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }

    private fun detach(view: InfiniteCanvasView) {
        InfiniteCanvasView::class.java.getDeclaredMethod("onDetachedFromWindow").apply {
            isAccessible = true
        }.invoke(view)
    }
}
