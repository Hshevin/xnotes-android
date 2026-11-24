package com.xnotes.iosapp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import com.xnotes.core.tools.Tool
import com.xnotes.iosapp.ui.icons.StrokeIcons
import com.xnotes.iosapp.ui.icons.XnotesIcons
import com.xnotes.iosapp.ui.theme.MinTouchTarget
import com.xnotes.iosapp.ui.theme.ToolbarPalette
import com.xnotes.iosapp.ui.theme.ToolbarSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolbarIconTest {
    @Test fun everyButtonUsesItsExistingLabelAndAndroidIcon() {
        val expected = mapOf(
            "back" to XnotesIcons.prev, "pages" to XnotesIcons.sidebar, "layers" to XnotesIcons.layers,
            "pen" to StrokeIcons.regular, "highlighter" to StrokeIcons.highlighter, "eraser" to XnotesIcons.eraser,
            "select" to XnotesIcons.select, "lasso" to XnotesIcons.lasso, "text" to XnotesIcons.text,
            "undo" to XnotesIcons.undo, "redo" to XnotesIcons.redo, "selection_delete" to XnotesIcons.trash,
            "selection_copy" to XnotesIcons.copy, "selection_paste" to XnotesIcons.paste,
            "selection_duplicate" to XnotesIcons.duplicate, "text_bold" to XnotesIcons.bold,
            "text_italic" to XnotesIcons.italic, "text_list" to XnotesIcons.listBullet, "settings" to XnotesIcons.sliders,
        )
        assertEquals(expected.keys.toList(), ToolbarButton.entries.map { it.labelKey })
        for (button in ToolbarButton.entries) {
            assertSame(expected.getValue(button.labelKey), button.icon)
            assertTrue(button.icon.root.size > 0)
            assertTrue(text(button.labelKey).isNotBlank())
        }
        assertEquals(listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER, Tool.SELECT, Tool.LASSO, Tool.TEXT),
            ToolbarButton.entries.mapNotNull { it.tool })
    }

    @Test fun strokeIconsKeepTheirViewportAndHighlighterCompositing() {
        for (icon in listOf(StrokeIcons.regular, StrokeIcons.highlighter)) {
            assertEquals(100f, icon.viewportWidth)
            assertEquals(100f, icon.viewportHeight)
        }
        assertNotNull((StrokeIcons.regular.root[0] as VectorPath).fill)
        val highlight = StrokeIcons.highlighter.root
        assertEquals(4, highlight.size)
        for (i in 0..2) {
            val rule = highlight[i] as VectorPath
            assertEquals(4.5f, rule.strokeLineWidth)
            assertEquals(StrokeCap.Round, rule.strokeLineCap)
            assertNotNull(rule.stroke)
        }
        val band = highlight[3] as VectorGroup
        assertEquals(-3f, band.rotation)
        assertEquals(50f, band.pivotX)
        assertEquals(50f, band.pivotY)
        val fill = band[0] as VectorPath
        assertNotNull(fill.fill)
        assertEquals(0.32f, fill.fillAlpha)
    }

    @Test fun regularToolbarSizesPreserveTheTouchTarget() {
        assertEquals(52.dp, ToolbarSize.button)
        assertTrue(ToolbarSize.button >= MinTouchTarget)
        assertEquals(38.dp, ToolbarSize.circle)
        assertEquals(22.dp, ToolbarSize.icon)
    }

    @Test fun bothPalettesInvertSelectionAndDimDisabledIcons() {
        for (dark in listOf(false, true)) {
            val palette = ToolbarPalette.forTheme(dark)
            assertEquals(if (dark) Color.White else Color.Black, palette.selectionBackground)
            assertEquals(if (dark) Color.Black else Color.White, palette.tint(selected = true, enabled = true))
            assertEquals(palette.secondary, palette.tint(selected = false, enabled = true))
            for (selected in listOf(false, true)) {
                assertEquals(palette.disabled, palette.tint(selected, enabled = false))
            }
            for (gray in listOf(palette.secondary, palette.disabled)) {
                assertEquals(gray.red, gray.green)
                assertEquals(gray.green, gray.blue)
                assertTrue(gray.red > 0f && gray.red < 1f)
            }
            assertTrue(if (dark) palette.disabled.red < palette.secondary.red else palette.disabled.red > palette.secondary.red)
        }
    }
}
