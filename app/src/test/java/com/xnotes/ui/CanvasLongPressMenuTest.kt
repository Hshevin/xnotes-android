package com.xnotes.ui

import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import com.xnotes.canvas.InteractionController
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.CanvasViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Device test2: a finger held on empty canvas opens the canvas menu even with nothing copied, so
 * "Copy canvas link" and "Insert image" can be reached. The stylus still never pops it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CanvasLongPressMenuTest {
    private fun hold(toolType: Int): List<Pt> {
        val opened = ArrayList<Pt>()
        val viewport = CanvasViewport().apply { widthPx = 1000; heightPx = 800 }
        val input = InfiniteInteraction(viewport, {}, onContextMenu = { vp, _, _ -> opened += vp })
        val pointer = MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType }
        val at = MotionEvent.PointerCoords().apply { x = 400f; y = 300f; pressure = .5f }
        val down = MotionEvent.obtain(1L, 1L, MotionEvent.ACTION_DOWN, 1, arrayOf(pointer), arrayOf(at),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { input.onTouch(down) } finally { down.recycle() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(InteractionController.LONG_PRESS_MS + 1L))
        return opened
    }

    @Test fun aFingerHeldOnEmptyCanvasOpensTheMenuWithNothingCopied() {
        assertEquals(listOf(Pt(400.0, 300.0)), hold(MotionEvent.TOOL_TYPE_FINGER))
    }

    @Test fun aStylusHeldOnEmptyCanvasStillNeverOpensIt() {
        assertTrue(hold(MotionEvent.TOOL_TYPE_STYLUS).isEmpty())
    }
}
