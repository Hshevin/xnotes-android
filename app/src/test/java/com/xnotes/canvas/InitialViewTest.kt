package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the per-document initial view: opening a document never inherits the previous
 *  one's zoom/scroll, and a remembered view is reapplied even under a zoom lock. */
class InitialViewTest {

    private fun state(pages: Int): CanvasState =
        CanvasState(Document.blank(pages), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 1000
            viewportH = 1400
            relayout()
        }

    @Test fun restoreReappliesViewEvenWhenZoomLocked() {
        val st = state(pages = 3)
        st.zoomLocked = true // fitWidth would no-op here — the bug that leaked the last view
        st.pendingInitialView = InitialView.Restore(zoom = 2.5, scrollX = 0.0, scrollY = 4000.0)
        st.establishInitialView()
        assertEquals(2.5, st.zoom, 1e-9)
        assertEquals(4000.0.coerceIn(0.0, st.maxScrollY()), st.scrollY, 1e-6)
        assertTrue(st.didInitialFit)
    }

    @Test fun openingAnotherDocumentDoesNotInheritTheView() {
        val st = state(pages = 5)
        st.pendingInitialView = InitialView.Restore(zoom = 4.0, scrollX = 0.0, scrollY = 9000.0)
        st.establishInitialView()

        // Open a fresh document with no remembered view: must fit width at the top, not keep 4.0×.
        st.document = Document.blank(2)
        st.relayout()
        st.pendingInitialView = InitialView.FitWidth
        st.didInitialFit = false
        st.establishInitialView()

        val fitWidth = (st.viewportW / st.contentW).coerceIn(CanvasState.MIN_ZOOM, CanvasState.MAX_ZOOM)
        assertEquals(fitWidth, st.zoom, 1e-9)
        assertNotEquals(4.0, st.zoom, 1e-6)
    }

    @Test fun setViewIgnoresZoomLock() {
        val st = state(pages = 1)
        st.zoomLocked = true
        st.setView(1.7, 0.0, 50.0)
        assertEquals(1.7, st.zoom, 1e-9)
    }

    /** U-3: a view remembered at fit width in a full-width pane refits a narrower split pane. */
    @Test fun fitWidthRestoreRefitsANarrowerViewportAndKeepsTheContentPosition() {
        val wide = state(pages = 3)
        wide.pendingInitialView = InitialView.FitWidth
        wide.establishInitialView()
        val wideZoom = wide.zoom
        wide.scrollY = 1200.0 // reading somewhere down the first page, at fit width
        assertTrue(wide.fitWidthActive)

        val narrow = state(pages = 3).apply { viewportW = 500; relayout() }
        narrow.pendingInitialView = InitialView.Restore(wideZoom, wide.scrollX, wide.scrollY, fitWidth = true)
        narrow.establishInitialView()

        val narrowFit = (narrow.viewportW / narrow.contentW).coerceIn(CanvasState.MIN_ZOOM, CanvasState.MAX_ZOOM)
        assertEquals(narrowFit, narrow.zoom, 1e-9)
        assertTrue(narrow.fitWidthActive)
        assertEquals(narrow.minScrollX(), narrow.scrollX, 1e-9) // the page spans the pane, not pinned to one side
        // The same content point stays under the top edge: scroll scales with the zoom.
        assertEquals((1200.0 * narrowFit / wideZoom).coerceIn(0.0, narrow.maxScrollY()), narrow.scrollY, 1e-6)
        assertTrue(narrow.didInitialFit)
    }

    @Test fun plainRestoreStillReappliesTheRememberedZoomInAnotherWidth() {
        val narrow = state(pages = 3).apply { viewportW = 500; relayout() }
        narrow.pendingInitialView = InitialView.Restore(zoom = 2.5, scrollX = 0.0, scrollY = 100.0)
        narrow.establishInitialView()
        assertEquals(2.5, narrow.zoom, 1e-9) // a user-chosen zoom is kept as before
    }
}
