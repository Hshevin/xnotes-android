package com.xnotes.iosapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.xnotes.core.model.Page
import com.xnotes.iosapp.ui.theme.MinTouchTarget
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.iosapp.ui.theme.ToolbarPalette
import com.xnotes.storage.lite.*
import kotlinx.coroutines.*
import okio.Path

internal class CreatedDocument {
    private var pending: LiteOpen.Opened? = null
    fun adopt(opened: LiteOpen.Opened) { close(); pending = opened }
    fun take(path: Path) = pending?.takeIf { it.session.path == path }?.also { pending = null }
    fun close() { pending?.session?.close(); pending = null }
}

@Composable
internal fun CreateDocuments(store: LiteStore, directory: Path, style: TextStyle, open: (LiteOpen.Opened) -> Boolean) {
    var creating by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Row {
        for ((key, kind) in listOf("new_note" to LiteKind.NOTE, "new_canvas" to LiteKind.CANVAS)) {
            Action(key, style, enabled = !creating) {
                if (creating) return@Action
                creating = true; failed = false
                val stem = text(if (kind == LiteKind.NOTE) "untitled_note" else "untitled_canvas")
                scope.launch {
                    var owned: LiteOpen.Opened? = null
                    try {
                        withContext(Dispatchers.Default) { owned = store.create(directory, stem, kind) as? LiteOpen.Opened }
                        val result = owned
                        if (result == null) failed = true else if (open(result)) owned = null
                    } finally {
                        owned?.session?.close(); creating = false
                    }
                }
            }
        }
    }
    if (failed) BasicText(text("create_failed"), style = style)
}

@Composable
internal fun PanelActions(editor: InkEditor, palette: ToolbarPalette) {
    for (panel in Panel.entries) {
        if (panel == Panel.PAGES && editor.session.note == null) continue
        val shown = editor.panels.shown == panel
        val button = when (panel) {
            Panel.PAGES -> ToolbarButton.PAGES
            Panel.LAYERS -> ToolbarButton.LAYERS
        }
        ToolbarIcon(button, palette, chosen = shown, enabled = !editor.active) {
            editor.panels.shown = if (shown) null else panel
        }
    }
}

@Composable
internal fun PanelHost(editor: InkEditor, dark: Boolean, style: TextStyle, surface: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        surface()
        SelectionMenu(editor, ToolbarPalette.forTheme(dark))
        val panel = editor.panels.shown ?: return@Box
        Column(Modifier.align(Alignment.CenterEnd).width(Space.sidebar).fillMaxHeight()
            .background(if (dark) Color.Black else Color.White).border(Dp.Hairline, Color.Gray)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .padding(Space.s12)) {
            editor.revision
            if (panel == Panel.PAGES) PagePanel(editor, dark, style) else key(editor.panels.page) { LayerPanel(editor, style) }
        }
    }
}

@Composable
private fun PagePanel(editor: InkEditor, dark: Boolean, style: TextStyle) {
    val panels = editor.panels; val doc = checkNotNull(editor.session.note)
    val revision = editor.revision
    val rows = remember(doc, revision) { doc.pages.toList() }
    val page = panels.page?.takeIf(rows::contains) ?: rows.first()
    val enabled = editor.canChangePanel
    var deleting by remember(page, revision) { mutableStateOf(false) }
    BasicText(text("pages") + " · ${rows.indexOf(page) + 1}/${rows.size}", style = style)
    Row {
        Action("add_page", style, enabled = enabled) { panels.add(false) }
        Action("duplicate", style, enabled = enabled) { panels.add(true) }
        Action("delete", style, enabled = enabled && rows.size > 1) { deleting = true }
    }
    if (deleting) ConfirmDelete("delete_page_prompt", style, enabled, { deleting = false }) { panels.deletePage(page); deleting = false }
    val painter = remember(doc, dark, revision) { PageImages(doc, dark) }
    DisposableEffect(painter) { onDispose { painter.close() } }
    val numbers = remember(rows) { rows.withIndex().associate { it.value to it.index } }
    PanelList(rows, Page::uid, page, enabled, style, { text("page_label") + " ${numbers.getValue(it) + 1}" }, panels::focus, panels::movePage) {
        PageThumb(painter, numbers.getValue(it), style)
    }
}

@Composable
private fun LayerPanel(editor: InkEditor, style: TextStyle) {
    val panels = editor.panels; editor.revision
    val layers = panels.layers; val rows = layers.entries.asReversed(); val active = rows.first { it.id == layers.activeId }
    val enabled = editor.canChangePanel
    var rename by remember(active.id) { mutableStateOf<String?>(null) }
    var deleting by remember(active.id) { mutableStateOf(false) }
    val layerName = { id: String -> val at = layers.entries.indexOfFirst { it.id == id }; layers.entries[at].name.ifBlank { text("layer_label") + " ${at + 1}" } }
    BasicText(text("layers") + (panels.page?.let { " · " + text("page_label") + " ${editor.session.note!!.pages.indexOf(it) + 1}" } ?: ""), style = style)
    Row {
        Action("add_layer", style, enabled = enabled) { panels.edit { layers.create("") } }
        Action("rename", style, enabled = enabled) { rename = layerName(active.id) }
        Action("delete", style, enabled = enabled && rows.size > 1) { deleting = true }
    }
    val editedName = rename
    if (editedName != null) {
        BasicTextField(editedName, { rename = it }, Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).border(Dp.Hairline, style.color).padding(Space.s12),
            enabled = enabled, singleLine = true, textStyle = style, cursorBrush = SolidColor(style.color))
        Row {
            Action("confirm", style, enabled = enabled) {
                if (editedName.trim() != active.name) panels.edit { layers.update(active.id) { it.copy(name = editedName.trim()) } }
                rename = null
            }
            Action("cancel", style) { rename = null }
        }
    }
    if (deleting) ConfirmDelete("delete_layer_prompt", style, enabled, { deleting = false }) { panels.deleteLayer(active.id); deleting = false }
    Row {
        Action("layer_visible", style, chosen = active.visible, enabled = enabled) { panels.edit { layers.update(active.id) { it.copy(visible = !it.visible) } } }
        Action("layer_locked", style, chosen = active.locked, enabled = enabled) { panels.edit { layers.update(active.id) { it.copy(locked = !it.locked) } } }
        Action("thumbnails", style, chosen = panels.thumbnails) { panels.thumbnails = !panels.thumbnails }
    }
    PanelList(rows, { it.id }, active, enabled, style, {
        listOfNotNull(layerName(it.id), text("hidden").takeUnless { _ -> it.visible }, text("layer_locked").takeIf { _ -> it.locked }).joinToString(" · ")
    }, { panels.selectLayer(it.id) }, { from, to ->
        panels.edit { layers.move(from.id, rows.indexOf(from) - rows.indexOf(to)) }
    }) { layer -> if (panels.thumbnails) LayerThumb(editor, layer.id, style) }
}

@Composable
private fun ConfirmDelete(prompt: String, style: TextStyle, enabled: Boolean, cancel: () -> Unit, confirm: () -> Unit) {
    BasicText(text(prompt), style = style)
    Row { Action("cancel", style, onPress = cancel); Action("confirm", style, enabled = enabled, onPress = confirm) }
}
