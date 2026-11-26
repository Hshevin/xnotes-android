package com.xnotes.core

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LayerPreviewTest {
    private fun item(layer: String?, box: Rect) = ImageItem(ImageData(File("unused"), 10, 10), box).also { it.layerId = layer }

    @Test fun includesHiddenLayerContentsAndLegacyDefaultWithoutOtherLayers() {
        val legacy = item(null, Rect(0.0, 0.0, 10.0, 10.0))
        val hidden = item("hidden", Rect(20.0, 20.0, 10.0, 10.0))
        val preview = LayerPreview(listOf(legacy, hidden), Rect(0.0, 0.0, 100.0, 200.0))
        assertEquals(listOf(legacy), preview.itemsFor(DrawingLayers.DEFAULT))
        assertEquals(listOf(hidden), preview.itemsFor("hidden"))
        assertTrue(preview.itemsFor("missing").isEmpty())
        assertEquals(Rect(0.0, 0.0, 100.0, 200.0), preview.frame)
        hidden.layerId = "moved"
        assertEquals(listOf(hidden), preview.itemsFor("hidden")) // membership snapshot
    }

    @Test fun canvasUsesOneSharedFrameIncludingNegativeAndHiddenContent() {
        val preview = LayerPreview(listOf(item("a", Rect(-30.0, -20.0, 10.0, 10.0)),
            item("b", Rect(70.0, 80.0, 10.0, 10.0))))
        assertEquals(Rect(-30.0, -20.0, 110.0, 110.0), preview.frame)
    }

    @Test fun contentModeFitsOnlyThisLayerWhilePageModeKeepsPageCoordinates() {
        val first = item("a", Rect(12.0, 34.0, 10.0, 20.0))
        val second = item("b", Rect(80.0, 90.0, 5.0, 5.0))
        val page = Rect(0.0, 0.0, 100.0, 200.0)
        val preview = LayerPreview(listOf(first, second), page)
        assertEquals(first.paintBounds(), preview.frameFor("a", LayerPreviewMode.CONTENT))
        assertEquals(second.paintBounds(), preview.frameFor("b", LayerPreviewMode.CONTENT))
        assertEquals(LayerPreviewMode.entries.toList(), preview.modes)
        assertEquals(page, preview.frameFor("a", LayerPreviewMode.PAGE))
        assertNull(preview.frameFor("a", LayerPreviewMode.NONE))
    }

    @Test fun canvasOffersOnlyHiddenAndPerLayerContentModes() {
        val a = item("a", Rect(-40.0, -30.0, 10.0, 20.0))
        val b = item("b", Rect(40.0, 30.0, 10.0, 20.0))
        val preview = LayerPreview(listOf(a, b))
        assertEquals(listOf(LayerPreviewMode.NONE, LayerPreviewMode.CONTENT), preview.modes)
        assertNull(preview.frameFor("a", LayerPreviewMode.PAGE))
        assertEquals(a.paintBounds(), preview.frameFor("a", LayerPreviewMode.CONTENT))
        assertEquals(Rect(0.0, 0.0, 1.0, 1.0), preview.frameFor("empty", LayerPreviewMode.CONTENT))
    }

    @Test fun hiddenModeDoesNotMeasureContent() {
        val base = item("a", Rect(0.0, 0.0, 10.0, 10.0))
        val neverMeasure = object : CanvasItem by base {
            override fun paintBounds(): Rect = error("Hidden preview must not measure geometry")
        }
        val preview = LayerPreview(listOf(neverMeasure))
        assertNull(preview.frameFor("a", LayerPreviewMode.NONE))
    }

    @Test fun invalidBoundsCannotPoisonTheCanvasFrame() {
        val valid = item("a", Rect(5.0, 6.0, 10.0, 20.0))
        val invalid = item("b", Rect(Double.NaN, 0.0, 1.0, 1.0))
        assertEquals(valid.paintBounds(), LayerPreview(listOf(valid, invalid)).frame)
        assertEquals(Rect(0.0, 0.0, 1.0, 1.0), LayerPreview(emptyList(), Rect(0.0, 0.0, Double.POSITIVE_INFINITY, 1.0)).frame)
    }

    @Test fun emptyAndDegenerateFramesStayFinite() {
        assertEquals(Rect(0.0, 0.0, 1.0, 1.0), LayerPreview(emptyList()).frame)
        val preview = LayerPreview(listOf(item("a", Rect(-5.0, 6.0, 0.0, 0.0))))
        assertEquals(Rect(-5.0, 6.0, 1.0, 1.0), preview.frame)
    }
}
