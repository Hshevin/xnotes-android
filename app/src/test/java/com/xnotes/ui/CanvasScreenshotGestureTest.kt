package com.xnotes.ui

import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.canvas.CanvasState
import com.xnotes.canvas.InteractionController
import com.xnotes.canvas.ResizeMath
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.History
import com.xnotes.core.infinite.CanvasViewport
import com.xnotes.core.model.Document
import com.xnotes.core.tools.Tool
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unifies infinite-canvas screenshot interaction with paged notes: frozen captures expose 8
 * handles that can be resized or dragged inside by stylus or finger (even with finger-draw off),
 * cancelled adjusts restore the prior capture, and screenshot actions switch back to the prior tool.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CanvasScreenshotGestureTest {

    private fun event(
        action: Int,
        x: Float,
        y: Float,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
        time: Long = 10L,
    ): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f })
        return MotionEvent.obtain(1L, time, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun twoPointerDown(
        x0: Float,
        y0: Float,
        toolType0: Int,
        x1: Float,
        y1: Float,
        toolType1: Int = MotionEvent.TOOL_TYPE_FINGER,
        time: Long = 30L,
    ): MotionEvent {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType0 },
            MotionEvent.PointerProperties().apply { id = 1; this.toolType = toolType1 },
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply { x = x0; y = y0; pressure = 0.5f },
            MotionEvent.PointerCoords().apply { x = x1; y = y1; pressure = 0.5f },
        )
        val action = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        return MotionEvent.obtain(1L, time, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun dispatch(input: InfiniteInteraction, e: MotionEvent) {
        try { input.onTouch(e) } finally { e.recycle() }
    }

    private fun drag(
        input: InfiniteInteraction,
        from: Pt,
        to: Pt,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
    ) {
        dispatch(input, event(MotionEvent.ACTION_DOWN, from.x.toFloat(), from.y.toFloat(), toolType, 10L))
        dispatch(input, event(MotionEvent.ACTION_MOVE, to.x.toFloat(), to.y.toFloat(), toolType, 20L))
        dispatch(input, event(MotionEvent.ACTION_UP, to.x.toFloat(), to.y.toFloat(), toolType, 30L))
    }

    private fun makeCanvas(fingerDraws: Boolean = false): Pair<InfiniteInteraction, MutableList<Rect?>> {
        val menus = mutableListOf<Rect?>()
        val viewport = CanvasViewport().apply { widthPx = 1000; heightPx = 800 }
        val input = InfiniteInteraction(viewport, {}, onScreenshot = { menus += it }).apply {
            this.fingerDraws = fingerDraws
            this.tool = Tool.SCREENSHOT
        }
        return input to menus
    }

    @Test fun stylusDragFreezesCaptureAndFingerHandleDragResizesEvenWhenFingerDrawIsOff() {
        val (input, menus) = makeCanvas(fingerDraws = false)
        drag(input, Pt(100.0, 100.0), Pt(300.0, 250.0), MotionEvent.TOOL_TYPE_STYLUS)
        assertTrue(input.isScreenshotFrozen)
        assertEquals(Rect(100.0, 100.0, 200.0, 150.0), input.captureRect)
        assertNotNull(menus.last())

        // Grab bottom-right handle with a finger while fingerDraws is off.
        val br = ResizeMath.boxHandles(input.captureRect!!).first { it.id == com.xnotes.canvas.HandleId.BR }.content
        dispatch(input, event(MotionEvent.ACTION_DOWN, br.x.toFloat(), br.y.toFloat(), MotionEvent.TOOL_TYPE_FINGER, 40L))
        assertNull("menu hides during handle resize", menus.last())
        dispatch(input, event(MotionEvent.ACTION_MOVE, 360f, 320f, MotionEvent.TOOL_TYPE_FINGER, 50L))
        dispatch(input, event(MotionEvent.ACTION_UP, 360f, 320f, MotionEvent.TOOL_TYPE_FINGER, 60L))

        assertTrue(input.isScreenshotFrozen)
        assertEquals(Rect(100.0, 100.0, 260.0, 220.0), input.captureRect)
        assertEquals(Rect(100.0, 100.0, 260.0, 220.0), menus.last())

        // Dragging right edge past left edge clamps at minimum size instead of flipping.
        val rightMid = ResizeMath.boxHandles(input.captureRect!!).first { it.id == com.xnotes.canvas.HandleId.R }.content
        drag(input, rightMid, Pt(20.0, rightMid.y), MotionEvent.TOOL_TYPE_STYLUS)
        assertTrue(input.captureRect!!.w >= 8.0)
        assertEquals(100.0, input.captureRect!!.left, 0.01)
    }

    @Test fun draggingInsideFrozenCaptureMovesWholeBoxWithFingerOrStylus() {
        val (input, menus) = makeCanvas(fingerDraws = false)
        drag(input, Pt(100.0, 120.0), Pt(260.0, 220.0), MotionEvent.TOOL_TYPE_STYLUS)
        val before = input.captureRect!!

        dispatch(input, event(MotionEvent.ACTION_DOWN, 180f, 170f, MotionEvent.TOOL_TYPE_FINGER, 40L))
        assertNull("menu hides during move", menus.last())
        dispatch(input, event(MotionEvent.ACTION_MOVE, 230f, 210f, MotionEvent.TOOL_TYPE_FINGER, 50L))
        dispatch(input, event(MotionEvent.ACTION_UP, 230f, 210f, MotionEvent.TOOL_TYPE_FINGER, 60L))

        val moved = input.captureRect!!
        assertEquals(before.left + 50.0, moved.left, 0.01)
        assertEquals(before.top + 40.0, moved.top, 0.01)
        assertEquals(before.w, moved.w, 0.01)
        assertEquals(before.h, moved.h, 0.01)
        assertEquals(moved, menus.last())
    }

    @Test fun cancelledOrPinchInterruptedAdjustRestoresOriginalCaptureAndMenu() {
        val (input, menus) = makeCanvas(fingerDraws = false)
        drag(input, Pt(100.0, 100.0), Pt(260.0, 220.0), MotionEvent.TOOL_TYPE_STYLUS)
        val original = input.captureRect!!

        // Cancel mid-move restores the frozen rect and menu.
        dispatch(input, event(MotionEvent.ACTION_DOWN, 180f, 160f, MotionEvent.TOOL_TYPE_STYLUS, 40L))
        dispatch(input, event(MotionEvent.ACTION_MOVE, 240f, 210f, MotionEvent.TOOL_TYPE_STYLUS, 50L))
        dispatch(input, event(MotionEvent.ACTION_CANCEL, 240f, 210f, MotionEvent.TOOL_TYPE_STYLUS, 60L))
        assertEquals(original, input.captureRect)
        assertEquals(original, menus.last())

        // Finger resize interrupted by second finger (pinch) restores original rect and menu.
        val br = ResizeMath.boxHandles(original).first { it.id == com.xnotes.canvas.HandleId.BR }.content
        dispatch(input, event(MotionEvent.ACTION_DOWN, br.x.toFloat(), br.y.toFloat(), MotionEvent.TOOL_TYPE_FINGER, 70L))
        dispatch(input, event(MotionEvent.ACTION_MOVE, 350f, 320f, MotionEvent.TOOL_TYPE_FINGER, 80L))
        dispatch(input, twoPointerDown(350f, 320f, MotionEvent.TOOL_TYPE_FINGER, 500f, 500f, MotionEvent.TOOL_TYPE_FINGER, 90L))
        assertEquals(original, input.captureRect)
        assertEquals(original, menus.last())
    }

    @Test fun stylusAdjustIgnoresPalmSecondFingerAndPressOutsideRedraws() {
        val (input, menus) = makeCanvas(fingerDraws = false)
        drag(input, Pt(100.0, 100.0), Pt(260.0, 220.0), MotionEvent.TOOL_TYPE_STYLUS)
        val br = ResizeMath.boxHandles(input.captureRect!!).first { it.id == com.xnotes.canvas.HandleId.BR }.content

        dispatch(input, event(MotionEvent.ACTION_DOWN, br.x.toFloat(), br.y.toFloat(), MotionEvent.TOOL_TYPE_STYLUS, 40L))
        dispatch(input, event(MotionEvent.ACTION_MOVE, 320f, 280f, MotionEvent.TOOL_TYPE_STYLUS, 50L))
        dispatch(input, twoPointerDown(320f, 280f, MotionEvent.TOOL_TYPE_STYLUS, 500f, 500f, MotionEvent.TOOL_TYPE_FINGER, 60L))
        dispatch(input, event(MotionEvent.ACTION_UP, 320f, 280f, MotionEvent.TOOL_TYPE_STYLUS, 70L))
        assertEquals(Rect(100.0, 100.0, 220.0, 180.0), input.captureRect)
        assertEquals(Rect(100.0, 100.0, 220.0, 180.0), menus.last())

        // Dragging outside the frozen box replaces it with a fresh capture.
        drag(input, Pt(500.0, 400.0), Pt(650.0, 520.0), MotionEvent.TOOL_TYPE_STYLUS)
        assertEquals(Rect(500.0, 400.0, 150.0, 120.0), input.captureRect)
        assertFalse(input.captureRect == Rect(100.0, 100.0, 220.0, 180.0))
    }

    @Test fun pagedScreenshotSwitchesBackToAnyPriorToolNotOnlyStrokes() {
        val state = CanvasState(Document.blank(1), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 1000
            viewportH = 800
            relayout()
        }
        val controller = InteractionController(state, History(), FakeTextMeasurer(), requestRender = {})
        for (prior in listOf(Tool.PEN, Tool.PAN, Tool.SELECT, Tool.ERASER)) {
            controller.setTool(prior)
            controller.setTool(Tool.SCREENSHOT)
            assertEquals(Tool.SCREENSHOT, controller.tool)
            controller.switchBackAfterScreenshot()
            assertEquals("should return to $prior after screenshot", prior, controller.tool)
        }
    }

    @Test fun screenshotMenuKeepsFrameToolAndCaptureModeWhenAiRejectsFullAttachments() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("ai-shot-menu").toFile()
        val job = kotlinx.coroutines.SupervisorJob()
        try {
            val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + job)
            val sessions = com.xnotes.ai.AiSessions(com.xnotes.ai.AiHistoryStore(dir), scope, { com.xnotes.ai.AiConfig("https://example.com/v1", "m", "k") })
            kotlinx.coroutines.withTimeout(5000) { while (!sessions.ready) kotlinx.coroutines.delay(5) }
            val (input, menus) = makeCanvas(fingerDraws = false)
            var currentTool = Tool.PEN
            var toolBeforeAi: Tool? = null
            fun armTool(next: Tool) {
                currentTool = next
                input.tool = next
            }
            sessions.registerCapture(
                Any(),
                com.xnotes.ai.AiCaptureTarget(
                    arm = {
                        if (currentTool != Tool.SCREENSHOT) toolBeforeAi = currentTool
                        armTool(Tool.SCREENSHOT)
                    },
                    release = {
                        val before = toolBeforeAi; toolBeforeAi = null
                        if (currentTool == Tool.SCREENSHOT) {
                            input.clearCapture()
                            before?.let(::armTool)
                        }
                    },
                ),
            )

            val chatId = sessions.newChat("Canvas", "uri-c", "doc-c", "doc-c")
            repeat(com.xnotes.ai.AiProtocol.MAX_IMAGES) {
                assertTrue(sessions.addScreenshot(chatId, byteArrayOf((it + 1).toByte())))
            }
            sessions.startCapture()
            assertEquals(Tool.SCREENSHOT, currentTool)
            drag(input, Pt(100.0, 100.0), Pt(260.0, 220.0), MotionEvent.TOOL_TYPE_STYLUS)
            val frozen = input.captureRect
            assertNotNull(frozen)
            assertNotNull(menus.last())

            val fakePng = java.io.File(dir, "shot.png").apply { writeBytes(byteArrayOf(7, 8, 9)) }
            val rejected = runScreenshotAiAction(
                export = { fakePng },
                accept = { f -> sessions.addScreenshotAndExpand(f.readBytes(), "Canvas", "uri-c", "doc-c", "doc-c") },
                finish = { input.clearCapture(); armTool(Tool.PEN) },
            )
            assertFalse("full attachments must reject", rejected)
            assertEquals("capture frame must stay on screen", frozen, input.captureRect)
            assertEquals("tool must remain SCREENSHOT", Tool.SCREENSHOT, currentTool)
            assertTrue("capture mode must remain active", sessions.capturing)
            assertTrue("floating card must stay collapsed", sessions.floatingCollapsed)

            // Failed copy/share also leaves capture mode untouched.
            assertFalse(runScreenshotOtherAction(false) { sessions.endCapture() })
            assertTrue(sessions.capturing)

            // Removing one screenshot lets the menu accept, finish, expand, and restore the prior tool.
            sessions.removeScreenshot(chatId, 0)
            val accepted = runScreenshotAiAction(
                export = { fakePng },
                accept = { f -> sessions.addScreenshotAndExpand(f.readBytes(), "Canvas", "uri-c", "doc-c", "doc-c") },
                finish = { input.clearCapture(); if (currentTool == Tool.SCREENSHOT) armTool(Tool.PEN) },
            )
            assertTrue(accepted)
            assertNull("frame cleared after accept", input.captureRect)
            assertEquals("tool restored after accept", Tool.PEN, currentTool)
            assertFalse("capture mode ended", sessions.capturing)
            assertFalse("floating card expanded", sessions.floatingCollapsed)
            assertEquals(com.xnotes.ai.AiProtocol.MAX_IMAGES, sessions.attachments[chatId]!!.size)
        } finally {
            job.cancel()
            dir.deleteRecursively()
        }
    }
}
