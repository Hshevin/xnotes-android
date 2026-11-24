package com.xnotes.iosapp.ui.theme

import androidx.compose.ui.graphics.Color

internal class ToolbarPalette private constructor(
    val selectionBackground: Color,
    val selectionForeground: Color,
    val secondary: Color,
    val disabled: Color,
) {
    fun tint(selected: Boolean, enabled: Boolean): Color = when {
        !enabled -> disabled
        selected -> selectionForeground
        else -> secondary
    }

    companion object {
        // Android monochrome toolbar tones, 62% and 38% foreground on the panel.
        private val light = ToolbarPalette(Color.Black, Color.White, Color(0xFF616161), Color(0xFF9E9E9E))
        private val dark = ToolbarPalette(Color.White, Color.Black, Color(0xFF9E9E9E), Color(0xFF616161))
        fun forTheme(dark: Boolean): ToolbarPalette = if (dark) this.dark else light
    }
}
