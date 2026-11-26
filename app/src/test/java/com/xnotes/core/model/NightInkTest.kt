package com.xnotes.core.model

import com.xnotes.core.FakeRenderer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.pal.*
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.*
import org.junit.Test

class NightInkTest {
    private val black = Rgba(0, 0, 0, 255)
    private val white = Rgba(255, 255, 255, 255)

    @Test fun onlyDarkBlankPaperEnablesAdaptation() {
        assertTrue(NightInk.enabled(true, black))
        assertFalse(NightInk.enabled(false, black))
        assertFalse(NightInk.enabled(true, white))
        assertFalse(NightInk.enabled(true, black, pdf = true))
    }

    @Test fun toolbarSwatchTracksDisplayWithoutReplacingStoredColors() {
        val stored = listOf(black, white, Rgba(0, 80, 200), black.copy(a = 83))
        val night = stored.map { NightInk.displayColor(it, enabled = true) }
        assertEquals(listOf(white, white, stored[2], white.copy(a = 83)), night)
        assertEquals(listOf(black, white, stored[2], black.copy(a = 83)), stored)
        val day = stored.map { NightInk.displayColor(it, enabled = false) }
        assertEquals(stored, day)
        assertEquals(white, day[1]) // A separately chosen white is still white.
        assertEquals(black, NightInk.displayColor(black, NightInk.enabled(true, black, pdf = true)))
        assertEquals(black, NightInk.displayColor(black, NightInk.enabled(true, white)))
    }

    @Test fun onlyExactBlackChangesAndAlphaSurvives() {
        assertEquals(white, NightInk.color(black))
        assertEquals(white.copy(a = 83), NightInk.color(black.copy(a = 83)))
        for (color in listOf(white, Rgba(1, 1, 1, 255), Rgba(255, 0, 0, 255))) {
            assertEquals(color, NightInk.color(color))
        }
    }

    @Test fun liveAndFinishedInkBecomeWhiteWithoutChangingSavedOrExportedColor() {
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN).copy(rgba = black))
        stroke.finished = false
        repeat(12) { stroke.addSample(Sample(it * 10.0, 20.0, 1.0, it * 6.0)) }
        val colors = mutableListOf<Rgba>()
        val recorder = object : Renderer by FakeRenderer() {
            override fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, color: Rgba) { colors += color }
            override fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, from: Int, count: Int, color: Rgba) { colors += color }
            override fun fillCircle(center: Pt, radius: Double, color: Rgba) { colors += color }
            override fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule) { colors += color }
        }
        for (finished in listOf(false, true)) {
            stroke.finished = finished
            colors.clear()
            stroke.paint(NightInkRenderer(recorder))
            assertTrue(colors.isNotEmpty())
            assertTrue(colors.all { it == white })
            assertEquals(black, stroke.config.rgba)
        }
        colors.clear()
        stroke.paint(recorder)
        assertTrue(colors.isNotEmpty())
        assertTrue(colors.all { it == black })
    }
}
