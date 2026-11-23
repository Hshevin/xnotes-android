package com.xnotes.iosapp

import androidx.compose.runtime.*
import androidx.compose.ui.text.TextStyle
import com.xnotes.core.model.CanvasItem

internal class SelectionClipboard {
    var items by mutableStateOf(emptyList<CanvasItem>())
}

@Composable
internal fun SelectionActions(editor: InkEditor, style: TextStyle) {
    val selection = editor.selection ?: return
    if (!editor.tool.selectsItems) return
    val picked = !editor.active && selection.members.isNotEmpty()
    Action("selection_delete", style, enabled = picked) { selection.delete() }
    Action("selection_copy", style, enabled = picked) { selection.copy() }
    Action("selection_paste", style, enabled = !editor.active && selection.canPaste) { selection.insert(false) }
    Action("selection_duplicate", style, enabled = picked) { selection.insert(true) }
}
