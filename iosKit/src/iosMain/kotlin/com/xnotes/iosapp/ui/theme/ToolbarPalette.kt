package com.xnotes.iosapp.ui.theme

import androidx.compose.ui.graphics.Color

internal class ToolbarPalette private constructor(
    val selectionBackground: Color,
    val selectionForeground: Color,
    val secondary: Color,
    val disabled: Color,
    /** Floating menus and popups: their face, hairline edge, text and slider track. */
    val menu: Color,
    val border: Color,
    val text: Color,
    val fill: Color,
) {
    fun tint(selected: Boolean, enabled: Boolean): Color = when {
        !enabled -> disabled
        selected -> selectionForeground
        else -> secondary
    }

    companion object {
        // Android monochrome tones: 62% and 38% foreground for icons, 14%/30% hairlines, 6%/18% fills.
        private val light = ToolbarPalette(Color.Black, Color.White, Color(0xFF616161), Color(0xFF9E9E9E),
            Color.White, Color(0xFFDBDBDB), Color.Black, Color(0xFFF0F0F0))
        private val dark = ToolbarPalette(Color.White, Color.Black, Color(0xFF9E9E9E), Color(0xFF616161),
            Color.Black, Color(0xFF4D4D4D), Color.White, Color(0xFF2E2E2E))
        fun forTheme(dark: Boolean): ToolbarPalette = if (dark) this.dark else light
    }
}
