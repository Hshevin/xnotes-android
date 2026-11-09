package com.xnotes.mindmap

import android.view.MotionEvent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.xnotes.R
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.ui.theme.TextSize
import com.xnotes.ui.theme.XnotesTheme
import com.xnotes.ui.theme.monochromePalette
import org.junit.Assert.*
import org.junit.Test

/** Actual GraphSourcesDialog disposal and visual AndroidView replacement. Compile-only until run. */
class SourcePanelCompositionRecreateAndroidTest {
    @Test fun unmountingTheParentCompositionKeepsTheTargetRequestAndRejectsTheOldVisualUp()=SourceTargetAndroidFixture.run {f->
        val session=f.host.onMain {f.session}
        val presentation=f.host.onMain {f.workspace.current!!.presentation}
        var mounted by mutableStateOf(true)
        f.host.setContent {
            val owner=object:ActivityResultRegistryOwner {override val activityResultRegistry=f.registry}
            CompositionLocalProvider(LocalContext provides f.context,LocalActivityResultRegistryOwner provides owner) {
                XnotesTheme(monochromePalette(false)) {
                    if(mounted) MindMapScreen(session,"Source host recreation",{},presentation=presentation)
                    else Text("Parent host detached",fontSize=TextSize.body)
                }
            }
        }
        f.openSources();f.ui.click(f.label(R.string.source_target_adjust));f.awaitVisual()
        f.ui.click(f.label(R.string.source_target_region));f.awaitVisual()
        val old=f.host.onMain {requireNotNull(f.visual())};f.host.awaitStableWindowBounds(old)
        val region=f.host.onMain {
            val a=Pt(old.width*.25,old.height*.25);val b=Pt(old.width*.65,old.height*.65)
            val first=old.viewport.viewportToContent(a);val last=old.viewport.viewportToContent(b)
            val ids=intArrayOf(7);val tools=intArrayOf(MotionEvent.TOOL_TYPE_STYLUS)
            SourceTargetAndroidInput.touch(old,MotionEvent.ACTION_DOWN,listOf(a),ids,tools)
            SourceTargetAndroidInput.touch(old,MotionEvent.ACTION_MOVE,listOf(b),ids,tools)
            SourceTargetAndroidInput.touch(old,MotionEvent.ACTION_UP,listOf(b),ids,tools)
            Rect(first.x,first.y,last.x-first.x,last.y-first.y).also {assertRect(it,f.controller.state!!.region)}
        }
        f.host.await("settled source camera") {f.controller.state?.camera!=null && !f.controller.visualBusy}
        val fixed=f.host.onMain {f.controller.state!!}
        val lateUp=f.host.onMain {
            val center=old.viewport.contentToViewport(Pt(region.centerX,region.centerY));val next=Pt(center.x+80,center.y+70)
            SourceTargetAndroidInput.touch(old,MotionEvent.ACTION_DOWN,listOf(center),intArrayOf(7),intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
            SourceTargetAndroidInput.touch(old,MotionEvent.ACTION_MOVE,listOf(next),intArrayOf(7),intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
            assertTrue(f.controller.visualBusy);assertRect(region,f.controller.state!!.region);next
        }
        f.host.change {mounted=false}
        f.host.onMain {
            assertEquals(fixed.id,f.controller.state?.id);assertSame(fixed.frame,f.controller.state?.frame)
            assertRect(region,f.controller.state?.region);assertEquals(fixed.camera,f.controller.state?.camera)
            assertFalse(f.controller.visualBusy);assertFalse(session.canUndo)
        }
        f.host.change {mounted=true};f.awaitVisual()
        val next=f.host.onMain {requireNotNull(f.visual())};f.host.awaitStableWindowBounds(next)
        f.host.onMain {
            assertNotSame(old,next)
            assertEquals(fixed.id,f.controller.state?.id);assertSame(fixed.frame,f.controller.state?.frame)
            assertEquals(fixed.camera,next.camera())
            SourceTargetAndroidInput.touch(old,MotionEvent.ACTION_UP,listOf(lateUp),intArrayOf(7),intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
            assertRect(region,f.controller.state?.region);assertNull(f.currentSource().region);assertFalse(session.canUndo)
        }
        f.ui.click(f.label(R.string.cancel));f.host.await("new host canceled its retained target") {f.controller.state==null}
        f.ui.click(f.label(R.string.map_done));f.host.await("new source host explicitly closed") {presentation.sourcesFor==null}
        f.assertSourceUnchanged()
    }
    private fun assertRect(expected:Rect,actual:Rect?) {
        assertNotNull(actual);assertEquals(expected.x,actual!!.x,1e-3);assertEquals(expected.y,actual.y,1e-3)
        assertEquals(expected.w,actual.w,1e-3);assertEquals(expected.h,actual.h,1e-3)
    }
}
