package com.xnotes.iosapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConversions
import com.xnotes.iosapp.ui.theme.MenuSize
import com.xnotes.iosapp.ui.theme.MinTouchTarget
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.iosapp.ui.theme.TextSize
import com.xnotes.iosapp.ui.theme.ToolbarPalette
import com.xnotes.iosapp.ui.theme.ToolbarSize
import kotlin.math.roundToInt

/** A tool button: arms the tool, and a second tap on an armed pen, highlighter or eraser opens its settings, as on Android. */
@Composable
internal fun ToolButton(editor: InkEditor, button: ToolbarButton, palette: ToolbarPalette) {
    val tool = checkNotNull(button.tool)
    val popup = ToolPopup.of(tool)
    Box {
        ToolbarIcon(button, palette, chosen = tool == editor.tool) {
            if (tool == editor.tool && popup != null) editor.popup = if (editor.popup == popup) null else popup else editor.select(tool)
        }
        if (popup != null && editor.popup == popup) ToolPopupCard(editor, popup, palette)
    }
}

@Composable
internal fun SettingsButton(editor: InkEditor, palette: ToolbarPalette) {
    Box {
        ToolbarIcon(ToolbarButton.SETTINGS, palette, chosen = editor.popup == ToolPopup.SETTINGS, enabled = !editor.active) {
            editor.popup = if (editor.popup == ToolPopup.SETTINGS) null else ToolPopup.SETTINGS
        }
        if (editor.popup == ToolPopup.SETTINGS) ToolPopupCard(editor, ToolPopup.SETTINGS, palette)
    }
}

/** The five ink swatches; the chosen one wears a ring in its own colour. */
@Composable
internal fun InkSwatches(editor: InkEditor, palette: ToolbarPalette) {
    ToolSettings.swatches(editor.dark).forEachIndexed { index, ink ->
        val color = Color(ink.r, ink.g, ink.b)
        val chosen = index == editor.tools.colorIndex
        val label = text("ink_color") + " ${index + 1}"
        Box(
            Modifier.size(ToolbarSize.swatchSlot, ToolbarSize.button)
                .clickable(enabled = !editor.active, role = Role.Button) { editor.tools.pickColor(index) }
                .semantics { contentDescription = label; selected = chosen },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(ToolbarSize.swatch)
                    .then(if (chosen) Modifier.border(ToolbarSize.swatchRing, color, CircleShape) else Modifier)
                    .padding(Space.s4).clip(CircleShape).background(color),
            )
        }
    }
}

@Composable
private fun ToolPopupCard(editor: InkEditor, popup: ToolPopup, palette: ToolbarPalette) {
    val style = TextStyle(color = palette.text, fontSize = TextSize.bodyLarge)
    val edge = with(LocalDensity.current) { MenuSize.edge.roundToPx() }
    Popup(popupPositionProvider = remember(edge) { BelowAnchor(edge) }, onDismissRequest = { if (editor.popup == popup) editor.popup = null },
        properties = PopupProperties(focusable = true)) {
        Column(
            Modifier.width(MenuSize.popupWidth).clip(RoundedCornerShape(MenuSize.corner)).background(palette.menu)
                .border(MenuSize.hairline, palette.border, RoundedCornerShape(MenuSize.corner))
                .padding(horizontal = Space.s16, vertical = Space.s8),
        ) {
            val title = popup.tool?.id ?: "settings"
            BasicText(text(title), Modifier.padding(vertical = Space.s4).semantics { heading() }, style = style.copy(fontWeight = FontWeight.Medium))
            when (popup) {
                ToolPopup.PEN, ToolPopup.HIGHLIGHTER -> {
                    val tool = checkNotNull(popup.tool)
                    WidthSlider("tool_width", editor.tools.width(tool), ToolConversions.widthRange(tool), palette, style) { editor.tools.setWidth(tool, it) }
                }
                ToolPopup.ERASER -> {
                    Row(Modifier.padding(vertical = Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                        ModeChip("eraser_stroke", !editor.tools.area, palette, style) { editor.tools.setArea(false) }
                        ModeChip("eraser_area", editor.tools.area, palette, style) { editor.tools.setArea(true) }
                    }
                    WidthSlider("eraser_size", editor.tools.width(Tool.ERASER), ToolConversions.widthRange(Tool.ERASER), palette, style) { editor.tools.setWidth(Tool.ERASER, it) }
                }
                ToolPopup.SETTINGS -> SwitchRow("finger_drawing", editor.finger, !editor.active, palette, style) { editor.toggleFinger() }
            }
        }
    }
}

/** Opens under its anchor, kept [edge] inside the window. */
private class BelowAnchor(private val edge: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
        IntOffset(anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width - edge).coerceAtLeast(edge), anchorBounds.bottom)
}

@Composable
private fun WidthSlider(label: String, value: Double, range: ClosedFloatingPointRange<Double>, palette: ToolbarPalette, style: TextStyle, onChange: (Double) -> Unit) {
    val step = ToolConversions.WIDTH_STEP
    val number = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    val name = text(label)
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).toFloat().coerceIn(0f, 1f)
    val latest by rememberUpdatedState(onChange)
    BasicText("$name  $number", Modifier.padding(top = Space.s8).clearAndSetSemantics {}, style = style)
    Box(
        Modifier.fillMaxWidth().height(MinTouchTarget)
            .pointerInput(range) {
                fun at(x: Float) = range.start + (x / size.width).coerceIn(0f, 1f) * (range.endInclusive - range.start)
                detectTapGestures { latest(at(it.x)) }
            }
            .pointerInput(range) {
                fun at(x: Float) = range.start + (x / size.width).coerceIn(0f, 1f) * (range.endInclusive - range.start)
                detectHorizontalDragGestures { change, _ -> change.consume(); latest(at(change.position.x)) }
            }
            .semantics {
                contentDescription = name
                stateDescription = number
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.start.toFloat()..range.endInclusive.toFloat(),
                    ((range.endInclusive - range.start) / step).roundToInt() - 1)
                setProgress { latest(it.toDouble()); true }
            }
            .drawBehind {
                val y = size.height / 2; val knob = MenuSize.knob.toPx() / 2
                val left = knob; val right = size.width - knob; val x = left + (right - left) * fraction
                drawLine(palette.fill, Offset(left, y), Offset(right, y), MenuSize.track.toPx(), StrokeCap.Round)
                drawLine(palette.text, Offset(left, y), Offset(x, y), MenuSize.track.toPx(), StrokeCap.Round)
                drawCircle(palette.text, knob, Offset(x, y))
            },
    )
}

@Composable
private fun ModeChip(label: String, chosen: Boolean, palette: ToolbarPalette, style: TextStyle, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Space.s4)
    Box(
        Modifier.heightIn(min = MinTouchTarget).clip(shape)
            .background(if (chosen) palette.selectionBackground else palette.fill)
            .border(MenuSize.hairline, if (chosen) palette.selectionBackground else palette.border, shape)
            .clickable(role = Role.Button, onClick = onClick).semantics { selected = chosen }
            .padding(horizontal = Space.s16),
        contentAlignment = Alignment.Center,
    ) { BasicText(text(label), style = style.copy(color = if (chosen) palette.selectionForeground else palette.text, fontWeight = FontWeight.Medium)) }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, enabled: Boolean, palette: ToolbarPalette, style: TextStyle, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).toggleable(on, enabled = enabled, role = Role.Switch) { onToggle() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(text(label), Modifier.weight(1f), style = if (enabled) style else style.copy(color = palette.disabled))
        Box(Modifier.size(MenuSize.switchWidth, MenuSize.switchHeight).drawBehind {
            val r = size.height / 2
            drawRoundRect(if (on) palette.text else palette.fill, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            if (!on) drawRoundRect(palette.border, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), style = androidx.compose.ui.graphics.drawscope.Stroke(MenuSize.hairline.toPx()))
            val inset = Space.s2.toPx() + MenuSize.hairline.toPx()
            drawCircle(if (on) palette.menu else palette.secondary, r - inset, Offset(if (on) size.width - r else r, r))
        })
    }
}
