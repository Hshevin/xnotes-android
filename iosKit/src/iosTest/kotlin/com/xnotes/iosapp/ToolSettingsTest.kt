package com.xnotes.iosapp

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.*
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.DocumentCodec
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import com.xnotes.pen.pencil.PencilGesture
import com.xnotes.pen.pencil.PencilPreferredAction
import com.xnotes.platform.ios.CgImageCodec
import com.xnotes.platform.ios.CgTextMeasurer
import com.xnotes.storage.lite.*
import platform.Foundation.NSUserDefaults
import kotlin.test.*

class ToolSettingsTest {
    private val suite = "xnotes-tools-${Uuids.random()}"
    private val store = NSUserDefaults(suiteName = suite)
    private val fs = KFiles.system
    private val root = KFiles.tempDirectory() / "tools-${Uuids.random()}"
    private var editor: InkEditor? = null

    @AfterTest fun cleanup() {
        editor?.releaseOwner(); fs.deleteRecursively(root)
        NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suite)
    }

    @Test fun swatchesAreAndroidsFirstFiveForTheTheme() {
        assertEquals(InkPalette.light.take(5), ToolSettings.swatches(dark = false))
        assertEquals(InkPalette.dark.take(5), ToolSettings.swatches(dark = true))
        val tools = ToolSettings(store)
        assertEquals(0, tools.colorIndex)
        tools.pickColor(3)
        assertEquals(InkPalette.light[3], tools.ink(dark = false))
        assertEquals(InkPalette.dark[3], tools.ink(dark = true), "a mode switch keeps the slot, as on Android")
        tools.pickColor(99); assertEquals(4, tools.colorIndex)
    }

    @Test fun widthsColourAndEraserModeSurviveARelaunch() {
        val tools = ToolSettings(store)
        assertEquals(ToolDefaults.configFor(Tool.PEN).baseWidth, tools.width(Tool.PEN))
        tools.setWidth(Tool.PEN, 7.3); tools.setWidth(Tool.HIGHLIGHTER, 500.0); tools.setWidth(Tool.ERASER, 40.0)
        tools.setArea(true); tools.pickColor(2)
        val again = ToolSettings(store)
        assertEquals(7.5, again.width(Tool.PEN), "snapped to the Android half-point grid")
        assertEquals(120.0, again.width(Tool.HIGHLIGHTER), "held inside the highlighter's range")
        assertEquals(40.0, again.width(Tool.ERASER))
        assertTrue(again.area); assertEquals(2, again.colorIndex)

        val pen = again.config(Tool.PEN, dark = false)
        assertEquals(7.5, pen.baseWidth); assertEquals(InkPalette.light[2], pen.rgba); assertTrue(pen.pressureEnabled)
        val marker = again.config(Tool.HIGHLIGHTER, dark = true)
        assertEquals(120.0, marker.baseWidth); assertEquals(InkPalette.dark[2], marker.rgba)
        assertEquals(ToolDefaults.configFor(Tool.HIGHLIGHTER).highlighterAlpha, marker.highlighterAlpha)
        val eraser = again.config(Tool.ERASER, dark = false)
        assertEquals(40.0, eraser.baseWidth); assertEquals(EraseMode.AREA, eraser.eraseMode)
        assertEquals(ToolDefaults.configFor(Tool.LASSO), again.config(Tool.LASSO, dark = false))
    }

    @Test fun aStrokeTakesThePickedInkAndWidthAndThePencilPaletteOpensTheToolsPopup() {
        fs.createDirectories(root / "Documents")
        val path = root / "Documents" / "ink.xnote"
        fs.write(path) { DocumentCodec(CgImageCodec, CgTextMeasurer).write(Document(mutableListOf(Page(100.0, 100.0))), this) }
        val opened = assertIs<LiteOpen.Opened>(LiteStore(fs, root / "Documents", root / "Private", CgImageCodec, CgTextMeasurer).open(path))
        val model = InkEditor(opened.session) {}.also { editor = it }
        model.dark = true
        val before = model.tools.colorIndex to model.tools.width(Tool.PEN)
        try {
            model.tools.pickColor(1); model.tools.setWidth(Tool.PEN, 6.0)
            val space = object : InkSpace {
                override val origin = Pt.ZERO
                override val zoom = 1.0
                override fun paper(page: Int) = Rect(0.0, 0.0, 100.0, 100.0)
                override fun pageAt(content: Pt) = 0.takeIf { paper(0).contains(content) }
                override fun hasPage(page: Int) = page == 0
                override fun writable(page: Int) = page == 0
                override fun toPage(page: Int, content: Pt) = content
                override fun fromPage(page: Int, local: Pt) = local
                override fun night(page: Int) = false
            }
            fun sample(y: Double) = com.xnotes.pen.pencil.PencilSample(10.0, y, y / 1000, 1.0, 1.0, null, false)
            InkGesture(model, space, false).apply { begin(sample(10.0)); finish(listOf(sample(60.0)), false) }
            val stroke = model.session.note!!.pages[0].items.single() as Stroke
            assertEquals(InkPalette.dark[1], stroke.config.rgba); assertEquals(6.0, stroke.config.baseWidth)

            model.gesture(PencilGesture.DOUBLE_TAP, PencilPreferredAction.SHOW_CONTEXTUAL_PALETTE)
            assertEquals(ToolPopup.PEN, model.popup)
            model.gesture(PencilGesture.DOUBLE_TAP, PencilPreferredAction.SHOW_CONTEXTUAL_PALETTE)
            assertNull(model.popup)
            model.select(Tool.LASSO)
            model.gesture(PencilGesture.DOUBLE_TAP, PencilPreferredAction.SHOW_CONTEXTUAL_PALETTE)
            assertEquals(ToolPopup.SETTINGS, model.popup)
            model.select(Tool.ERASER); assertNull(model.popup, "changing tools closes the popup")
        } finally {
            model.tools.pickColor(before.first); model.tools.setWidth(Tool.PEN, before.second)
        }
    }
}
