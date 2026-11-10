package com.xnotes.pen

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.storage.NativeReferenceTarget
import com.xnotes.ui.Editor
import com.xnotes.ui.NativeReferenceLocator
import com.xnotes.ui.NativeReferencePreparation
import com.xnotes.ui.NativeReferenceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real paged View cannot replay a navigation after leaving an entered host. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PagedReferenceLocatorHostTest {
    private fun locator(editor:Editor)=(Editor::class.java.getDeclaredField("referenceLocator\$delegate").apply {isAccessible=true}
        .get(editor) as Lazy<*>).value as NativeReferenceLocator
    private fun outlines(editor:Editor):Int {
        var count=0
        locator(editor).draw(object:Renderer by FakeRenderer() {
            override fun strokeRect(rect:Rect,pen:Pen) {count++}
        })
        return count
    }
    @Test fun aRegionThatEnteredItsHostIsRetiredOnDetachBeforeItsSecondStableFrame()=runParkingScenario {f->
        withContext(Dispatchers.IO) {f.seed()}
        withContext(Dispatchers.Main) {
            val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
            val holder=FrameLayout(activity.get());activity.get().setContentView(holder)
            fun attach() {
                f.editor.pad.visibility=View.GONE
                holder.addView(f.editor.surfacesForAttach(),FrameLayout.LayoutParams(-1,-1))
                holder.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY))
                holder.layout(0,0,1000,1000)
                assertTrue(f.editor.view.isAttachedToWindow)
            }
            try {
                f.editor.openAsync(f.sourceUri,"Source.xnote");attach()
                val model=f.editor.state.document;val target=model.pages[1];val history=f.editor.history
                f.editor.state.verticalScroll=false;f.editor.state.relayout();f.editor.state.goToPage(0)
                val camera=Triple(f.editor.state.zoom,f.editor.state.scrollX,f.editor.state.scrollY)
                val revision=model.bodyRevision;val dirty=model.dirty
                val prepared=f.editor.prepareReference(NativeReferenceTarget(model.identity.documentId,requireNotNull(target.referenceIdentity.id)),Rect(20.0,40.0,160.0,100.0))
                assertTrue(prepared is NativeReferencePreparation.Ready)
                val opened=f.editor.openReference((prepared as NativeReferencePreparation.Ready).request)
                assertTrue(opened is NativeReferenceResult.Opened)
                assertEquals(com.xnotes.ui.ReferenceRegionReveal.SHOWN,(opened as NativeReferenceResult.Opened).region)
                f.editor.view.viewTreeObserver.dispatchOnPreDraw();assertEquals(0,outlines(f.editor))
                holder.removeView(f.editor.surfaces)
                attach();repeat(3) {f.editor.view.viewTreeObserver.dispatchOnPreDraw()}
                // Camera coordinates are numeric; a layout's -0.0 is the same position as 0.0.
                assertEquals(camera.first,f.editor.state.zoom,0.0)
                assertEquals("Leaving an entered host must not move the camera",camera.second,f.editor.state.scrollX,0.0)
                assertEquals(camera.third,f.editor.state.scrollY,0.0)
                assertEquals(0,f.editor.state.currentPageIndex());assertEquals(0,outlines(f.editor))
                assertSame(model,f.editor.state.document);assertSame(history,f.editor.history)
                assertEquals(revision,model.bodyRevision);assertEquals(dirty,model.dirty);assertFalse(history.canUndo)
            } finally {holder.removeAllViews();activity.pause().stop().destroy()}
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
}
