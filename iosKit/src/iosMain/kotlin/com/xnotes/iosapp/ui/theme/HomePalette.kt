package com.xnotes.iosapp.ui.theme

import androidx.compose.ui.graphics.Color

/** Android's monochrome home palette: a faintly grey backdrop under white (or black) sheets, hairlines and dim text as shares of the foreground. */
internal class HomePalette private constructor(
    val backdrop: Color,
    val sheet: Color,
    val surface: Color,
    val border: Color,
    val text: Color,
    val textDim: Color,
    val selectionBackground: Color,
    val selectionForeground: Color,
    val paper: Color,
    val scrim: Color,
    val outline: Color,
) {
    companion object {
        // Backdrop 4%/12%, fill 6%/18%, hairline 14%/30%, secondary 62%, outline 54% of the foreground.
        private val light = HomePalette(Color(0xFFF5F5F5), Color.White, Color(0xFFF0F0F0), Color(0xFFDBDBDB),
            Color.Black, Color(0xFF616161), Color.Black, Color.White, Color.White, Color(0x52000000), Color(0xFF757575))
        private val dark = HomePalette(Color(0xFF1F1F1F), Color.Black, Color(0xFF2E2E2E), Color(0xFF4D4D4D),
            Color.White, Color(0xFF9E9E9E), Color.White, Color.Black, Color.Black, Color(0x52000000), Color(0xFF8A8A8A))
        fun forTheme(dark: Boolean): HomePalette = if (dark) this.dark else light
    }
}
