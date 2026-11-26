package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.window.PopupProperties
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.map.LayoutKind
import com.xnotes.map.NodeShape
import com.xnotes.map.RelationArrow
import com.xnotes.map.ui.MapController
import com.xnotes.map.ui.MapTitles
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.MinTouchTarget
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/**
 * The field a node's title is typed in, laid over the node. It sets the words at canvas scale and
 * zooms them at draw time, exactly as the canvas draws them, so the words do not jump when the
 * field closes. Enter (or the keyboard's Done) finishes, Tab finishes and starts a subtopic,
 * Shift+Enter breaks the line, Escape drops the edit.
 */
@Composable
fun MapTitleField(editor: InfiniteEditor) {
    val f = editor.mapField ?: return
    val map = editor.map
    val palette = editor.overlayPalette ?: LocalPalette.current
    val density = LocalDensity.current
    key(f.node) {
        var value by remember { mutableStateOf(TextFieldValue(f.text, TextRange(f.text.length))) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        val z = f.zoom.toFloat()
        val minW = with(density) { f.width.toFloat().coerceAtLeast(f.textPx.toFloat()).toDp() }
        val maxW = with(density) { f.wrap.toFloat().toDp() }
        val fontSp = with(density) { f.textPx.toFloat().toSp() }
        Layout(content = {
            BasicTextField(
                value = value,
                onValueChange = { next ->
                    value = next
                    map.typing(next.text)
                },
                modifier = Modifier
                    .graphicsLayer(scaleX = z, scaleY = z, transformOrigin = TransformOrigin(0f, 0f))
                    .widthIn(min = minW, max = maxW)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            e.key == Key.Tab -> { map.commitAndAdd(MapController.HandleKind.CHILD); true }
                            (e.key == Key.Enter || e.key == Key.NumPadEnter) && !e.isShiftPressed -> { map.commitTitle(); true }
                            e.key == Key.Escape -> { map.cancelTitle(); true }
                            else -> false
                        }
                    },
                textStyle = TextStyle(
                    color = f.color.toComposeColor(),
                    fontFamily = MapTitles.FACE.toComposeFamily(),
                    fontSize = fontSp,
                    fontWeight = if (f.bold) FontWeight.Bold else FontWeight.Normal,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { map.commitTitle() }),
                cursorBrush = SolidColor(palette.accent.toComposeColor()),
            )
        }) { measurables, constraints ->
            val placeable = measurables[0].measure(Constraints())
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(f.x.roundToInt(), f.y.roundToInt())
            }
        }
    }
}

/**
 * The bar over the selected node or relation line: the node's own actions in reach, the rest
 * under More. Hidden while a node is dragged or typed into.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapMenu(editor: InfiniteEditor) {
    editor.mapVersion
    val rect = editor.mapMenuRect ?: return
    val map = editor.map
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val node = map.selected != null
    val count = if (node) 7 + (if (map.selectedHasChildren) 1 else 0) else 5
    val barHeightPx = with(density) { MinTouchTarget.toPx() }
    val barWidthPx = with(density) { (MinTouchTarget * count).toPx() }
    val gap = with(density) { Space.s12.toPx() }
    val xPx = ((rect.left + rect.right) / 2.0).toFloat() - barWidthPx / 2f
    val barAbove = rect.top.toFloat() - barHeightPx - gap > 0f
    val yPx = if (barAbove) rect.top.toFloat() - barHeightPx - gap else rect.bottom.toFloat() + gap
    val xDp = with(density) { xPx.coerceAtLeast(Space.s8.toPx()).toDp() }
    val yDp = with(density) { yPx.toDp() }
    var open by remember { mutableStateOf<MapMenuOpen?>(null) }
    // The "+N" chip on the canvas asks for the whole source list.
    if (editor.mapSourcesRequested) {
        editor.mapSourcesRequested = false
        open = MapMenuOpen.SOURCES
    }
    // The panels are non-focusable popups, so Back reaches the activity: close the panel, not the document.
    androidx.activity.compose.BackHandler(enabled = open != null) { open = null }

    CompositionLocalProvider(LocalTipSide provides if (barAbove) TipSide.ABOVE else TipSide.BELOW) {
        Row(
            Modifier
                .offset(xDp, yDp)
                .clip(MaterialTheme.shapes.medium)
                .background(palette.menuBg.toComposeColor())
                .border(Dp.Hairline, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
        ) {
            if (node) {
                MapAction(XnotesIcons.edit, stringResource(R.string.map2_edit_text)) { map.selected?.let(map::editTitle) }
                MapAction(XnotesIcons.plus, stringResource(R.string.map2_add_child)) { map.commitAndAdd(MapController.HandleKind.CHILD) }
                if (map.selectedHasChildren) {
                    val collapsed = map.selectedCollapsed
                    MapAction(
                        if (collapsed) XnotesIcons.chevronDown else XnotesIcons.chevronUp,
                        stringResource(if (collapsed) R.string.map2_expand else R.string.map2_collapse),
                    ) { map.toggleCollapsed() }
                }
                Box {
                    MapAction(XnotesIcons.layoutGrid, stringResource(R.string.map2_layout)) { open = MapMenuOpen.LAYOUT }
                    DropdownMenu(open == MapMenuOpen.LAYOUT, { open = null }, properties = PopupProperties(focusable = false)) {
                        val current = map.selectedLayout
                        for ((kind, label) in LAYOUTS) {
                            DropdownMenuItem(
                                text = { Text(stringResource(label), fontSize = TextSize.body) },
                                leadingIcon = { if (kind == current) Icon(XnotesIcons.check, null) },
                                onClick = { open = null; map.setLayout(kind) },
                            )
                        }
                    }
                }
                Box {
                    MapAction(XnotesIcons.link, stringResource(R.string.map2_sources)) { open = MapMenuOpen.SOURCES }
                }
                Box {
                    MapAction(XnotesIcons.palette, stringResource(R.string.map2_style)) { open = MapMenuOpen.STYLE }
                    if (open == MapMenuOpen.STYLE) MapStylePopup(editor) { open = null }
                }
                MapAction(XnotesIcons.trash, stringResource(R.string.delete)) { map.deleteSelected() }
                Box {
                    MapAction(XnotesIcons.more, stringResource(R.string.more)) { open = MapMenuOpen.MORE }
                    DropdownMenu(open == MapMenuOpen.MORE, { open = null }, properties = PopupProperties(focusable = false)) {
                        if (!map.selectedIsRoot) {
                            MapMenuItem(R.string.map2_add_sibling) { open = null; map.commitAndAdd(MapController.HandleKind.SIBLING) }
                        }
                        MapMenuItem(R.string.copy) { open = null; editor.copyMapSelection(cut = false) }
                        MapMenuItem(R.string.cut) { open = null; editor.copyMapSelection(cut = true) }
                        HorizontalDivider(color = palette.border.toComposeColor())
                        MapMenuItem(R.string.map2_reset_node, enabled = map.selectedHasOffset) { open = null; map.resetPosition() }
                        MapMenuItem(R.string.map2_reset_tree, enabled = map.selectedTreeHasOffsets) { open = null; map.resetTree() }
                        if (!map.selectedIsRoot) MapMenuItem(R.string.map2_detach) { open = null; map.detachSelected() }
                    }
                }
            } else {
                Box {
                    MapAction(XnotesIcons.arrowUp, stringResource(R.string.map2_arrows)) { open = MapMenuOpen.ARROWS }
                    DropdownMenu(open == MapMenuOpen.ARROWS, { open = null }, properties = PopupProperties(focusable = false)) {
                        val current = map.selectedArrow
                        for ((arrow, label) in ARROWS) {
                            DropdownMenuItem(
                                text = { Text(stringResource(label), fontSize = TextSize.body) },
                                leadingIcon = { if (arrow == current) Icon(XnotesIcons.check, null) },
                                onClick = { open = null; map.setArrow(arrow) },
                            )
                        }
                    }
                }
                MapAction(XnotesIcons.rotateRight, stringResource(R.string.map2_relation_reverse)) { map.reverseRelation() }
                MapAction(XnotesIcons.text, stringResource(R.string.map2_relation_label)) { open = MapMenuOpen.LABEL }
                Box {
                    MapAction(XnotesIcons.palette, stringResource(R.string.map2_color)) { open = MapMenuOpen.COLOR }
                    if (open == MapMenuOpen.COLOR) {
                        DropdownMenu(true, { open = null }, properties = PopupProperties(focusable = false)) {
                            Column(Modifier.padding(horizontal = Space.s16, vertical = Space.s8)) {
                                ColorRow(editor, null, allowNone = false) { c -> c?.let(map::setRelationColor) }
                            }
                        }
                    }
                }
                MapAction(XnotesIcons.trash, stringResource(R.string.delete)) { map.deleteSelected() }
            }
        }
    }
    if (open == MapMenuOpen.SOURCES && node) {
        val nodeRect = IntRect(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
        val barX = xPx.coerceAtLeast(with(density) { Space.s8.toPx() }).roundToInt()
        val barRect = IntRect(barX, yPx.roundToInt(), barX + barWidthPx.roundToInt(), yPx.roundToInt() + barHeightPx.roundToInt())
        MapSourcesPanel(editor, nodeRect, barRect) { open = null }
    }
    if (open == MapMenuOpen.LABEL) {
        RelationLabelDialog(map.selectedRelationLabel.orEmpty(), onConfirm = { open = null; map.setRelationLabel(it) }, onDismiss = { open = null })
    }
}

private enum class MapMenuOpen { LAYOUT, STYLE, SOURCES, MORE, ARROWS, COLOR, LABEL }

private val LAYOUTS = listOf(
    LayoutKind.RIGHT to R.string.map2_layout_right,
    LayoutKind.BALANCED to R.string.map2_layout_balanced,
    LayoutKind.ORG to R.string.map2_layout_org,
    LayoutKind.INDENT to R.string.map2_layout_indent,
)

private val ARROWS = listOf(
    RelationArrow.NONE to R.string.map2_arrow_none,
    RelationArrow.FORWARD to R.string.map2_arrow_forward,
    RelationArrow.BACKWARD to R.string.map2_arrow_backward,
    RelationArrow.BOTH to R.string.map2_arrow_both,
)

@Composable
private fun MapAction(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val tint = palette.text.toComposeColor().let { if (enabled) it else it.copy(alpha = 0.35f) }
    Tooltip(desc) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(MinTouchTarget)) {
            Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(Space.s24))
        }
    }
}

@Composable
private fun MapMenuItem(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label), fontSize = TextSize.body) }, enabled = enabled, onClick = onClick)
}

/** A node's look: its shape, its border, fill and text colours, and its type. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapStylePopup(editor: InfiniteEditor, onDismiss: () -> Unit) {
    val map = editor.map
    editor.mapVersion
    val style = map.selectedStyle ?: return onDismiss()
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, properties = PopupProperties(focusable = false)) {
        Column(Modifier.width(MAP_STYLE_WIDTH).padding(horizontal = Space.s16, vertical = Space.s8)) {
            PopupTitle(stringResource(R.string.map2_style))
            StyleCaption(stringResource(R.string.map2_shape))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s6)) {
                for ((shape, label) in SHAPES) ModeChip(stringResource(label), selected = style.shape == shape) { map.setShape(shape) }
            }
            StyleCaption(stringResource(R.string.map2_border))
            ColorRow(editor, style.border, allowNone = false) { c -> c?.let(map::setBorder) }
            StyleCaption(stringResource(R.string.map2_fill))
            ColorRow(editor, style.fill, allowNone = true) { map.setFill(it) }
            StyleCaption(stringResource(R.string.map2_text_color))
            ColorRow(editor, style.text, allowNone = false) { c -> c?.let(map::setTextColor) }
            StyleCaption(stringResource(R.string.map2_text_size))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s6)) {
                for ((size, label) in SIZES) ModeChip(stringResource(label), selected = style.textSize == size) { map.setTextSize(size) }
                ModeChip(stringResource(R.string.map2_bold), selected = style.bold) { map.setBold(!style.bold) }
            }
            Spacer(Modifier.size(Space.s4))
        }
    }
}

private val MAP_STYLE_WIDTH = Space.s48 * 6

private val SHAPES = listOf(
    NodeShape.ROUNDED to R.string.map2_shape_rounded,
    NodeShape.ELLIPSE to R.string.map2_shape_ellipse,
    NodeShape.UNDERLINE to R.string.map2_shape_underline,
)

/** Title sizes in canvas units: the default topic size and a step either side. */
private val SIZES = listOf(
    20.0 to R.string.map2_size_small,
    27.0 to R.string.map2_size_medium,
    36.0 to R.string.map2_size_large,
)

/**
 * The canvas's swatches, then the picker; with [allowNone] a "no fill" chip first. Colours are
 * content, so they are shown as stored, not as the theme would show ink.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorRow(editor: InfiniteEditor, current: Rgba?, allowNone: Boolean, onPick: (Rgba?) -> Unit) {
    val swatches = editor.mapSwatches
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s6)) {
        if (allowNone) ModeChip(stringResource(R.string.map2_no_fill), selected = current == null) { onPick(null) }
        val presets = (listOf(Rgba(0, 0, 0), Rgba(255, 255, 255)) + swatches.shownSwatches).distinct()
        for (c in presets) ColorDot(c.toComposeColor(), selected = current == c) { onPick(c) }
        ColorPickerDot(current, custom = current != null && current !in presets, onPick = { onPick(it) }, dismissOnPick = true) { dismiss, pick ->
            ColorPickerPopup(initial = current, recents = swatches.hostRecentColors, onDismiss = dismiss, onPick = pick)
        }
    }
}

@Composable
private fun RelationLabelDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map2_relation_label), fontSize = TextSize.heading) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.map2_relation_label_hint), fontSize = TextSize.body) },
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.done), fontSize = TextSize.body) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), fontSize = TextSize.body) } },
    )
}
