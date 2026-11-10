package com.xnotes.mindmap

import android.graphics.Bitmap
import android.graphics.Color
import android.view.MotionEvent
import android.widget.FrameLayout
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.ui.ComposeHostFixture
import org.junit.Assert.*
import org.junit.Test

/** Android's real MotionEvent flags and pointer identity; no PDF pixels or target GPU claim. */
class SourceRegionVisualInputAndroidTest {
    private fun scenario(test:(ComposeHostFixture,SourceRegionVisualView,MutableList<Rect?>)->Unit)=ComposeHostFixture.run {host->
        val regions=mutableListOf<Rect?>()
        val view=host.onMain {
            SourceRegionVisualView(host.activity).also {view->
                host.activity.host.addView(view,FrameLayout.LayoutParams(-1,-1))
                view.bind(Any(),Rect(0.0,0.0,1000.0,800.0),null,null,true,null,Color.WHITE,{true},
                    {regions+=it;true},{},{_,_->})
                view.image(Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888),Rect(0.0,0.0,1000.0,800.0))
            }
        }
        try {host.awaitStableWindowBounds(view);test(host,view,regions)} finally {
            host.onMain {view.retire();host.activity.host.removeView(view)}
        }
    }
    @Test fun cancelingPalmId42KeepsPenId7ButACanceledActivePenUpNeverCommitsItsNextRectangle()=scenario {host,view,regions->
        host.onMain {
            val a=view.viewport.contentToViewport(Pt(100.0,100.0));val b=view.viewport.contentToViewport(Pt(250.0,250.0))
            val pen=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS);val both=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS,MotionEvent.TOOL_TYPE_FINGER)
            SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_DOWN,listOf(a),intArrayOf(7),pen)
            SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_MOVE,listOf(b),intArrayOf(7),pen)
            SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(b,Pt(800.0,700.0)),intArrayOf(7,17),both)
            SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(b,Pt(800.0,700.0)),intArrayOf(7,17),both,MotionEvent.FLAG_CANCELED)
            assertTrue(regions.isEmpty());assertTrue(view.busy)
            SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_UP,listOf(b),intArrayOf(7),pen)
            assertEquals(1,regions.size);assertFalse(view.busy)
            val first=regions.single()
            SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_DOWN,listOf(a),intArrayOf(7),pen)
            SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_MOVE,listOf(b),intArrayOf(7),pen)
            SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_UP,listOf(b),intArrayOf(7),pen,MotionEvent.FLAG_CANCELED)
            assertEquals(listOf(first),regions);assertFalse(view.busy)
        }
    }
    @Test fun aSecondFingerAndThenADetachedOldViewCannotPublishTheInterruptedRectangle()=scenario {host,view,regions->
        host.onMain {
            val a=view.viewport.contentToViewport(Pt(100.0,100.0));val b=view.viewport.contentToViewport(Pt(250.0,250.0))
            val finger=intArrayOf(MotionEvent.TOOL_TYPE_FINGER);val both=intArrayOf(MotionEvent.TOOL_TYPE_FINGER,MotionEvent.TOOL_TYPE_FINGER)
            SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_DOWN,listOf(a),intArrayOf(7),finger)
            SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_MOVE,listOf(b),intArrayOf(7),finger)
            SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(b,Pt(800.0,700.0)),intArrayOf(7,17),both)
            SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_CANCEL,listOf(b,Pt(800.0,700.0)),intArrayOf(7,17),both)
            assertTrue(regions.isEmpty());assertFalse(view.busy)
            SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_DOWN,listOf(a),intArrayOf(7),finger)
            SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_MOVE,listOf(b),intArrayOf(7),finger)
            host.activity.host.removeView(view)
            SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_UP,listOf(b),intArrayOf(7),finger)
            assertTrue(regions.isEmpty());assertFalse(view.busy)
        }
    }
}
