package com.xnotes.mindmap

import android.content.Context
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.junit.Test
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapNodeSelectionHostTest:MapNodeSelectionContract() {
    override fun ui(block:(Context)->Unit)=block(RuntimeEnvironment.getApplication())
    @Test fun nodeSelectionNeverSchedulesBodyLongPressAndPenContactFreezesKeyboardSelection()=ui {context->
        val f=fixture(context);val p=f.at("root")
        send(f.view,MotionEvent.ACTION_DOWN,p,MotionEvent.TOOL_TYPE_STYLUS)
        assertTrue(f.busy)
        f.view.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_DOWN,android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_DOWN))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong()+200))
        assertEquals(emptySet<String>(),f.selection);assertEquals(0,f.forbidden)
        send(f.view,MotionEvent.ACTION_UP,p,MotionEvent.TOOL_TYPE_STYLUS)
        assertEquals(setOf("root"),f.selection);f.clean()
    }
    @Test fun theBackCancellationEntryReleasesPenAndDiscardsItsMoveAndRemainingPointers()=ui {context->
        val f=fixture(context);f.select(setOf("free"));val p=f.at("root")
        send(f.view,MotionEvent.ACTION_DOWN,p,MotionEvent.TOOL_TYPE_STYLUS)
        send(f.view,MotionEvent.ACTION_MOVE,p+com.xnotes.core.geometry.Pt(70.0,30.0),MotionEvent.TOOL_TYPE_STYLUS)
        assertTrue(f.busy);f.view.cancelNodeSelectionGesture();assertFalse(f.busy)
        send(f.view,MotionEvent.ACTION_UP,p+com.xnotes.core.geometry.Pt(70.0,30.0),MotionEvent.TOOL_TYPE_STYLUS)
        assertEquals(setOf("free"),f.selection);f.clean()
    }
}
