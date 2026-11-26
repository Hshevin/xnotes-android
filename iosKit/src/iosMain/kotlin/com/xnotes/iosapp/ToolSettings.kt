package com.xnotes.iosapp

import androidx.compose.runtime.*
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.InkPalette
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolConversions
import com.xnotes.core.tools.ToolDefaults
import platform.Foundation.NSUserDefaults

/** What a tool's popup or the settings popup holds open, so the Pencil's palette gesture can open one too. */
internal enum class ToolPopup(val tool: Tool?) {
    PEN(Tool.PEN), HIGHLIGHTER(Tool.HIGHLIGHTER), ERASER(Tool.ERASER), SETTINGS(null);

    companion object { fun of(tool: Tool) = entries.firstOrNull { it.tool == tool } }
}

/** Ink colour and per-tool width and eraser mode, kept across launches; the swatches follow the Android set for the theme. */
internal class ToolSettings(private val store: NSUserDefaults = NSUserDefaults.standardUserDefaults) {
    var colorIndex by mutableIntStateOf(store.integerForKey(COLOR_KEY).toInt().coerceIn(0, SWATCHES - 1)); private set
    var area by mutableStateOf(store.boolForKey(AREA_KEY)); private set
    private val widths = mutableStateMapOf<Tool, Double>().apply {
        for (tool in ToolPopup.entries.mapNotNull { it.tool }) store.objectForKey(widthKey(tool))?.let { put(tool, ToolConversions.snapWidth(tool, store.doubleForKey(widthKey(tool)))) }
    }

    fun pickColor(index: Int) { colorIndex = index.coerceIn(0, SWATCHES - 1); store.setInteger(colorIndex.toLong(), COLOR_KEY) }
    fun ink(dark: Boolean): Rgba = swatches(dark)[colorIndex]
    fun width(tool: Tool): Double = widths[tool] ?: ToolDefaults.configFor(tool).baseWidth
    fun setWidth(tool: Tool, value: Double) { val snapped = ToolConversions.snapWidth(tool, value); widths[tool] = snapped; store.setDouble(snapped, widthKey(tool)) }
    fun setArea(on: Boolean) { area = on; store.setBool(on, AREA_KEY) }

    fun config(tool: Tool, dark: Boolean): ToolConfig = ToolDefaults.configFor(tool).let { base ->
        when (tool) {
            Tool.PEN, Tool.HIGHLIGHTER -> base.copy(baseWidth = width(tool), rgba = ink(dark))
            Tool.ERASER -> base.copy(baseWidth = width(tool), eraseMode = if (area) EraseMode.AREA else EraseMode.STROKE)
            else -> base
        }
    }

    companion object {
        /** As many swatches as Android's bar shows by default. */
        const val SWATCHES = 5
        private const val COLOR_KEY = "activeColor"
        private const val AREA_KEY = "areaEraser"
        private fun widthKey(tool: Tool) = "width." + tool.id
        fun swatches(dark: Boolean): List<Rgba> = (if (dark) InkPalette.dark else InkPalette.light).take(SWATCHES)
    }
}
