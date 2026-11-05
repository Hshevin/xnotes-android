package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.R
import com.xnotes.core.model.DrawingLayers
import com.xnotes.core.model.LayerPreview
import com.xnotes.ui.theme.LayerThumbnailSize
import com.xnotes.core.model.LayerPreviewMode
import com.xnotes.ui.theme.MinTouchTarget
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize
import kotlinx.coroutines.delay

@Composable
fun LayersButton(editor: Editor) {
    ToolbarIcon(XnotesIcons.layers, stringResource(R.string.layers_title),
        active = editor.sidebarVisible && editor.sidePanelTab == SidePanelTab.LAYERS) { editor.toggleLayersSidebar() }
}

@Composable
fun LayersButton(editor: InfiniteEditor) {
    ToolbarIcon(XnotesIcons.layers, stringResource(R.string.layers_title), active = editor.layersSidebarVisible) {
        editor.layersSidebarVisible = !editor.layersSidebarVisible
    }
}

@Composable
fun LayersSidebar(editor: Editor) {
    val page = editor.state.document.pages.getOrNull(editor.pageIndex) ?: return
    key(page) {
        val preview = remember(editor.contentVersion) { LayerPreview(page.items, editor.state.footprint(page)) }
        LayersPanel(page.layers, preview, editor.layerPreviewMode, { editor.layerPreviewMode = it }, editor.contentVersion, { editor.selectLayer(page, it) }, { edit -> editor.editLayers(page, edit) },
            { id -> page.items.removeAll { it.layerId == id }; page.layers.delete(id) },
            { id -> editor.moveSelectionToLayer(page, id) },
            editor.hasSelection && editor.controller.layerSelection(page).isNotEmpty(), { editor.sidebarVisible = false })
    }
}

@Composable
fun LayersSidebar(editor: InfiniteEditor) {
    key(editor.document) {
        val preview = remember(editor.layerContentVersion) { LayerPreview(editor.document.items) }
        LayersPanel(editor.document.layers, preview, editor.layerPreviewMode, { editor.layerPreviewMode = it }, editor.layerContentVersion, editor::selectLayer, editor::editLayers,
            { id -> editor.document.removeAll(editor.document.items.filter { it.layerId == id }); editor.document.layers.delete(id) },
            editor::moveSelectionToLayer, editor.hasSelection, { editor.layersSidebarVisible = false })
    }
}

/** Docked, nonmodal layer stack. Dragging previews a destination; only dropping edits the model. */
@Composable
private fun LayersPanel(
    layers: DrawingLayers,
    preview: LayerPreview,
    previewMode: LayerPreviewMode,
    onPreviewMode: (LayerPreviewMode) -> Unit,
    version: Int,
    select: (String) -> Boolean,
    edit: (() -> Unit) -> Boolean,
    delete: (String) -> Unit,
    moveSelection: (String) -> Boolean,
    hasSelection: Boolean,
    dismiss: () -> Unit,
) {
    val thumbnails = remember(preview, previewMode) {
        if (previewMode == LayerPreviewMode.NONE) null else LayerThumbnailCache()
    }
    var previewMenu by remember { mutableStateOf(false) }
    val thumbnailSize = LayerThumbnailSize
    val rowHeight = if (previewMode == LayerPreviewMode.NONE) MinTouchTarget else thumbnailSize + Space.s8
    var rename by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    val applyEdit by rememberUpdatedState(edit)
    fun change(action: () -> Unit) { applyEdit(action) }
    val entries = layers.entries.asReversed()
    val ids = entries.map { it.id }
    val list = rememberLazyListState()
    var dragging by remember { mutableStateOf<String?>(null) }
    var startIds by remember { mutableStateOf(emptyList<String>()) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var grabY by remember { mutableFloatStateOf(0f) }
    var beforeId by remember { mutableStateOf<String?>(null) }
    fun updateTarget() {
        val rows = list.layoutInfo.visibleItemsInfo.filter { it.key is String && it.key in ids && it.key != dragging }
        beforeId = rows.firstOrNull { pointerY < it.offset + it.size / 2f }?.key as? String
        // At an off-screen lower edge, insert after the last visible row, not at document end.
        if (beforeId == null && rows.isNotEmpty()) {
            val last = ids.indexOf(rows.last().key)
            beforeId = ids.drop(last + 1).firstOrNull { it != dragging }
        }
    }
    LaunchedEffect(version) { dragging = null }
    LaunchedEffect(dragging) {
        while (dragging != null) {
            val info = list.layoutInfo
            val edge = info.visibleItemsInfo.firstOrNull()?.size?.toFloat() ?: 0f
            val scroll = when {
                pointerY < info.viewportStartOffset + edge -> -edge / 6f
                pointerY > info.viewportEndOffset - edge -> edge / 6f
                else -> 0f
            }
            if (scroll != 0f) { list.scrollBy(scroll); updateTarget() }
            delay(16)
        }
    }
    val dragDescription = stringResource(R.string.layers_drag)
    val palette = LocalPalette.current
    val text = palette.text.toComposeColor()
    val dim = palette.textDim.toComposeColor()
    val off = palette.disabled.toComposeColor()
    Column(Modifier.fillMaxSize().background(palette.panel.toComposeColor())) {
        Row(Modifier.fillMaxWidth().height(MinTouchTarget).padding(start = Space.s16, end = Space.s4), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.layers_title), Modifier.weight(1f), color = text, fontSize = TextSize.title, fontWeight = FontWeight.Bold)
            Box {
                Tooltip(stringResource(R.string.layers_preview_mode)) {
                    IconButton(enabled = dragging == null, onClick = { previewMenu = true }, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(XnotesIcons.thumbnails, stringResource(R.string.layers_preview_mode), tint = dim, modifier = Modifier.size(20.dp))
                    }
                }
                DropdownMenu(expanded = previewMenu, onDismissRequest = { previewMenu = false }) {
                    for (mode in preview.modes) {
                        DropdownMenuItem(
                            text = { Text(stringResource(when (mode) {
                                LayerPreviewMode.NONE -> R.string.layers_preview_none
                                LayerPreviewMode.CONTENT -> R.string.layers_preview_content
                                LayerPreviewMode.PAGE -> R.string.layers_preview_page
                            }), fontSize = TextSize.body) },
                            leadingIcon = { RadioButton(selected = mode == previewMode, onClick = null) },
                            onClick = { previewMenu = false; onPreviewMode(mode) },
                        )
                    }
                }
            }
            Tooltip(stringResource(R.string.layers_close)) {
                IconButton(onClick = dismiss, modifier = Modifier.size(MinTouchTarget)) {
                    Icon(XnotesIcons.close, stringResource(R.string.layers_close), tint = dim, modifier = Modifier.size(20.dp))
                }
            }
        }
        Text(stringResource(R.string.layers_drag_hint), Modifier.padding(start = Space.s16, end = Space.s16, bottom = Space.s8),
            fontSize = TextSize.caption, color = dim)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, userScrollEnabled = dragging == null,
            contentPadding = PaddingValues(horizontal = Space.s8)) {
            itemsIndexed(entries, key = { _, layer -> layer.id }) { index, layer ->
                val pinnable = LocalPinnableContainer.current
                var pinned by remember { mutableStateOf<PinnableContainer.PinnedHandle?>(null) }
                DisposableEffect(Unit) { onDispose { pinned?.release() } }
                LaunchedEffect(dragging) {
                    if (dragging != layer.id) { pinned?.release(); pinned = null }
                }
                val selected = layers.activeId == layer.id
                // Selection is a full inversion; resting states (visible, unlocked) step back to grey.
                val fg = if (selected) palette.selectionForeground.toComposeColor() else text
                val quiet = if (selected) fg.copy(alpha = 0.6f) else dim
                val shape = MaterialTheme.shapes.small
                val offset = if (dragging == layer.id) {
                    val first = list.layoutInfo.visibleItemsInfo.firstOrNull()
                    val rowOffset = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == layer.id }?.offset
                        ?: first?.let { it.offset + (index - it.index) * it.size } ?: 0
                    pointerY - grabY - rowOffset
                } else 0f
                val startDrag: (Offset) -> Unit = { at ->
                    val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == layer.id }
                    if (row != null) {
                        // Keep the source row alive while edge scrolling moves it off screen.
                        pinned?.release()
                        pinned = pinnable?.pin()
                        startIds = ids
                        dragging = layer.id
                        grabY = at.y
                        pointerY = row.offset + at.y
                        updateTarget()
                    }
                }
                val endDrag: () -> Unit = {
                    val id = dragging
                    val target = beforeId
                    dragging = null
                    if (id != null && layers.entries.asReversed().map { it.id } == startIds &&
                        layers.copy().moveBefore(id, target)) change { layers.moveBefore(id, target) }
                }
                Row(Modifier.fillMaxWidth().zIndex(if (dragging == layer.id) 1f else 0f)
                    .graphicsLayer { translationY = offset }
                    .drawWithContent {
                        drawContent()
                        if (dragging != null && beforeId == layer.id)
                            drawLine(text, Offset.Zero, Offset(size.width, 0f), Space.s2.toPx())
                    }
                    .padding(vertical = Space.s2).clip(shape)
                    .background(if (selected) palette.selectionBackground.toComposeColor() else palette.panel.toComposeColor())
                    .then(if (dragging == layer.id && !selected) Modifier.border(1.dp, palette.border.toComposeColor(), shape) else Modifier)
                    .selectable(selected, enabled = dragging == null) {
                        if (!selected) select(layer.id)
                    }, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = dragging == null, onClick = { change { layers.update(layer.id) { it.copy(visible = !it.visible) } } },
                        modifier = Modifier.size(MinTouchTarget)) {
                        Icon(if (layer.visible) XnotesIcons.layerVisible else XnotesIcons.layerHidden,
                            stringResource(if (layer.visible) R.string.layers_hide else R.string.layers_show),
                            tint = if (layer.visible) quiet else fg, modifier = Modifier.size(20.dp))
                    }
                    // A hidden layer recedes; its controls keep full contrast so it can be shown again.
                    val content = Modifier.alpha(if (layer.visible) 1f else 0.4f)
                    if (thumbnails != null) Box(Modifier.size(rowHeight).semantics { contentDescription = dragDescription }.pointerInput(layer.id, ids) {
                        detectDragGestures(onDragStart = startDrag, onDragEnd = endDrag,
                            onDragCancel = { dragging = null },
                            onDrag = { event, amount -> event.consume(); pointerY += amount.y; updateTarget() })
                    }, contentAlignment = Alignment.Center) {
                        key(previewMode) { LayerThumbnail(preview, layer.id, previewMode, thumbnails, content.size(thumbnailSize)) }
                    }
                    Text(layer.name.ifEmpty { stringResource(R.string.layers_default, entries.size - index) },
                        Modifier.weight(1f).height(rowHeight)
                            .combinedClickable(enabled = dragging == null, onClick = { if (!selected) select(layer.id) },
                                onDoubleClick = { rename = layer.id; name = layer.name })
                            .pointerInput(layer.id, ids) {
                                detectDragGestures(onDragStart = startDrag, onDragEnd = endDrag,
                                    onDragCancel = { dragging = null },
                                    onDrag = { event, amount -> event.consume(); pointerY += amount.y; updateTarget() })
                            }.then(content).padding(start = Space.s8).wrapContentHeight(Alignment.CenterVertically),
                        color = fg, fontSize = TextSize.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(enabled = dragging == null, onClick = { change { layers.update(layer.id) { it.copy(locked = !it.locked) } } },
                        modifier = Modifier.size(MinTouchTarget)) {
                        Icon(if (layer.locked) XnotesIcons.lock else XnotesIcons.unlock,
                            stringResource(if (layer.locked) R.string.layers_unlock else R.string.layers_lock),
                            tint = if (layer.locked) fg else quiet, modifier = Modifier.size(20.dp))
                    }
                    Box {
                        IconButton(enabled = dragging == null, onClick = { menu = layer.id }, modifier = Modifier.size(MinTouchTarget)) {
                            Icon(XnotesIcons.more, stringResource(R.string.layers_actions), tint = quiet, modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = menu == layer.id, onDismissRequest = { menu = null }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.layers_rename), fontSize = TextSize.body) },
                                onClick = { menu = null; rename = layer.id; name = layer.name })
                            DropdownMenuItem(text = { Text(stringResource(R.string.layers_up), fontSize = TextSize.body) }, enabled = index > 0,
                                onClick = { menu = null; change { layers.move(layer.id, 1) } })
                            DropdownMenuItem(text = { Text(stringResource(R.string.layers_down), fontSize = TextSize.body) }, enabled = index < entries.lastIndex,
                                onClick = { menu = null; change { layers.move(layer.id, -1) } })
                            if (hasSelection) DropdownMenuItem(text = { Text(stringResource(R.string.layers_move_selection), fontSize = TextSize.body) },
                                enabled = layer.visible && !layer.locked, onClick = { menu = null; moveSelection(layer.id) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.layers_delete), fontSize = TextSize.body) }, enabled = entries.size > 1 && !layer.locked && layer.visible,
                                onClick = { menu = null; deleting = layer.id })
                        }
                    }
                }
            }
        }
        val dropAtEnd = dragging != null && beforeId == null
        HorizontalDivider(thickness = if (dropAtEnd) Space.s2 else Dp.Hairline, color = if (dropAtEnd) text else palette.border.toComposeColor())
        val active = layers.entries.first { it.id == layers.activeId }
        val activeIndex = layers.entries.indexOf(active)
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.s8, vertical = Space.s4), verticalAlignment = Alignment.CenterVertically) {
            LayerBarButton(XnotesIcons.plus, stringResource(R.string.layers_add), dragging == null) { change { layers.create("") } }
            LayerBarButton(XnotesIcons.arrowUp, stringResource(R.string.layers_up), dragging == null && activeIndex < layers.entries.lastIndex) { change { layers.move(active.id, 1) } }
            LayerBarButton(XnotesIcons.arrowDown, stringResource(R.string.layers_down), dragging == null && activeIndex > 0) { change { layers.move(active.id, -1) } }
            Spacer(Modifier.weight(1f))
            LayerBarButton(XnotesIcons.trash, stringResource(R.string.layers_delete), dragging == null && entries.size > 1 && !active.locked && active.visible) { deleting = active.id }
        }
    }
    rename?.let { id ->
        AlertDialog(onDismissRequest = { rename = null }, title = { Text(stringResource(R.string.layers_rename), fontSize = TextSize.heading) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = TextSize.body)) },
            confirmButton = { TextButton(onClick = { if (layers.entries.any { it.id == id }) change { layers.update(id) { it.copy(name = name.trim()) } }; rename = null }) {
                Text(stringResource(R.string.layers_save), fontSize = TextSize.body)
            } }, dismissButton = { TextButton(onClick = { rename = null }) { Text(stringResource(R.string.layers_cancel), fontSize = TextSize.body) } })
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.layers_delete), fontSize = TextSize.heading) },
            text = { Text(stringResource(R.string.layers_delete_confirm), fontSize = TextSize.body) },
            confirmButton = { TextButton(onClick = { if (layers.entries.size > 1 && layers.entries.any { it.id == id && it.visible && !it.locked }) change { delete(id) }; deleting = null }) { Text(stringResource(R.string.layers_delete), fontSize = TextSize.body) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.layers_cancel), fontSize = TextSize.body) } })
    }
}

@Composable
private fun LayerBarButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Tooltip(label) {
        IconButton(enabled = enabled, onClick = onClick, modifier = Modifier.size(MinTouchTarget)) {
            Icon(icon, label, tint = (if (enabled) palette.text else palette.disabled).toComposeColor(), modifier = Modifier.size(20.dp))
        }
    }
}
