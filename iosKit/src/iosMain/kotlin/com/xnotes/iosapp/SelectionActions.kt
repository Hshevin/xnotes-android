package com.xnotes.iosapp

import androidx.compose.runtime.*
import com.xnotes.core.model.CanvasItem
import com.xnotes.iosapp.ui.theme.ToolbarPalette

internal class SelectionClipboard {
    var items by mutableStateOf(emptyList<CanvasItem>())
}

@Composable
internal fun SelectionActions(editor: InkEditor, palette: ToolbarPalette) {
    val selection = editor.selection ?: return
    if (!editor.tool.selectsItems) return
    if (selection.members.isEmpty()) {
        // Tapping blank paper clears the selection and sets a paste destination.
        if (selection.canPaste) ToolbarIcon(ToolbarButton.PASTE, palette, enabled = !editor.active) { selection.insert(false) }
        return
    }
    ToolbarIcon(ToolbarButton.DELETE, palette, enabled = !editor.active) { selection.delete() }
    ToolbarIcon(ToolbarButton.COPY, palette, enabled = !editor.active) { selection.copy() }
    ToolbarIcon(ToolbarButton.PASTE, palette, enabled = !editor.active && selection.canPaste) { selection.insert(false) }
    ToolbarIcon(ToolbarButton.DUPLICATE, palette, enabled = !editor.active) { selection.insert(true) }
}
