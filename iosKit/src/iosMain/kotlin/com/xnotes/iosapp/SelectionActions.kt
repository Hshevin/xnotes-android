package com.xnotes.iosapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xnotes.core.model.CanvasItem
import com.xnotes.iosapp.ui.theme.MenuSize
import com.xnotes.iosapp.ui.theme.ToolbarPalette
import com.xnotes.iosapp.ui.theme.ToolbarSize
import kotlin.math.roundToInt

internal class SelectionClipboard {
    var items by mutableStateOf(emptyList<CanvasItem>())
}

/** What the floating bar offers: Android's delete, cut, copy and duplicate over a selection, or paste where blank paper was tapped. */
internal fun selectionMenuButtons(members: Boolean, canPaste: Boolean): List<ToolbarButton> = when {
    members -> listOf(ToolbarButton.DELETE, ToolbarButton.CUT, ToolbarButton.COPY, ToolbarButton.DUPLICATE)
    canPaste -> listOf(ToolbarButton.PASTE)
    else -> emptyList()
}

/** Floats above the settled selection (below it when there is no room), clear of the rotate grip; hidden while dragging. */
@Composable
internal fun SelectionMenu(editor: InkEditor, palette: ToolbarPalette) {
    val selection = editor.selection ?: return
    if (!editor.tool.selectsItems || editor.active) return
    val anchor = selection.anchor ?: return
    val buttons = selectionMenuButtons(selection.members.isNotEmpty(), selection.canPaste)
    if (buttons.isEmpty()) return
    val shape = RoundedCornerShape(MenuSize.corner)
    Layout({
        Row(Modifier.clip(shape).background(palette.menu).border(MenuSize.hairline, palette.border, shape)) {
            for (button in buttons) MenuIcon(button, palette) {
                when (button) {
                    ToolbarButton.DELETE -> selection.delete()
                    ToolbarButton.CUT -> selection.cut()
                    ToolbarButton.COPY -> selection.copy()
                    ToolbarButton.DUPLICATE -> selection.insert(true)
                    ToolbarButton.PASTE -> selection.insert(false)
                    else -> error("not a selection action: $button")
                }
            }
        }
    }, Modifier.fillMaxSize()) { measurables, constraints ->
        val bar = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = MenuSize.gap.roundToPx(); val edge = MenuSize.edge.roundToPx()
        fun px(points: Double) = points.toFloat().dp.toPx().roundToInt()
        val x = (px(anchor.x + anchor.w / 2) - bar.width / 2).coerceIn(edge, maxOf(edge, constraints.maxWidth - bar.width - edge))
        val above = px(anchor.y) - gap - bar.height
        val y = if (above >= edge) above else minOf(px(anchor.y + anchor.h) + gap, constraints.maxHeight - bar.height - edge)
        layout(constraints.maxWidth, constraints.maxHeight) { bar.place(x, y) }
    }
}

@Composable
private fun MenuIcon(button: ToolbarButton, palette: ToolbarPalette, onClick: () -> Unit) {
    val label = text(button.labelKey)
    Box(
        Modifier.size(MenuSize.button).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Image(button.icon, null, Modifier.size(ToolbarSize.icon), colorFilter = ColorFilter.tint(palette.text)) }
}
