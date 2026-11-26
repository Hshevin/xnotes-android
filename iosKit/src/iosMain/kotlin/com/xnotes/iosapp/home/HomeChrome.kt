package com.xnotes.iosapp.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.xnotes.iosapp.text
import com.xnotes.iosapp.ui.theme.HomePalette
import com.xnotes.iosapp.ui.theme.HomeSize
import com.xnotes.iosapp.ui.theme.MinTouchTarget
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.iosapp.ui.theme.TextSize

/** The home palette for everything under the home screen. */
internal val LocalHome = staticCompositionLocalOf { HomePalette.forTheme(false) }

@Composable
internal fun Icon(icon: ImageVector, tint: Color, size: Dp, modifier: Modifier = Modifier) =
    Image(icon, null, modifier.size(size), colorFilter = ColorFilter.tint(tint))

@Composable
internal fun Label(text: String, color: Color, size: TextUnit, modifier: Modifier = Modifier, weight: FontWeight? = null, lines: Int = 1, align: TextAlign? = null) =
    BasicText(text, modifier, style = TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = align ?: TextAlign.Unspecified),
        maxLines = lines, overflow = TextOverflow.Ellipsis)

/** A plain icon button in the explorer's header and bars, 40 points round. */
@Composable
internal fun HomeIconButton(icon: ImageVector, desc: String, tint: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalHome.current
    Box(
        Modifier.size(HomeSize.headerButton).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, (tint ?: p.text).copy(alpha = if (enabled) 1f else 0.35f), HomeSize.navIcon) }
}

/** A toggle or menu chip in the explorer's chip row; [on] fills it with the foreground. */
@Composable
internal fun HomeChip(label: String, on: Boolean, icon: ImageVector? = null, trailing: ImageVector? = null, onClick: () -> Unit) {
    val p = LocalHome.current
    val shape = RoundedCornerShape(HomeSize.chipCorner)
    val fg = if (on) p.selectionForeground else p.text
    val iconTint = if (on) p.selectionForeground else p.textDim
    Row(
        Modifier.height(HomeSize.chip).clip(shape)
            .background(if (on) p.selectionBackground else p.surface)
            .border(HomeSize.hairline, if (on) p.text else p.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (icon != null) Space.s8 else Space.s12, end = if (trailing != null) Space.s8 else Space.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s6, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, iconTint, 16.dp)
        Label(label, fg, TextSize.bodyLarge)
        if (trailing != null) Icon(trailing, iconTint, 14.dp)
    }
}

/** Opens under its anchor, kept clear of the window's edges; above it when there is no room below. */
private class AnchorBelow(private val edge: Int, private val above: Boolean) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width - edge).coerceAtLeast(edge)
        val below = anchorBounds.bottom + edge / 2
        val y = if (above || below + popupContentSize.height > windowSize.height - edge) anchorBounds.top - popupContentSize.height - edge / 2 else below
        return IntOffset(x, y.coerceAtLeast(edge))
    }
}

/** Android's menu: a rounded sheet of inset rows under (or over) the control that opened it. */
@Composable
internal fun HomeMenu(onDismiss: () -> Unit, above: Boolean = false, width: Dp = HomeSize.menuWidth, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalHome.current
    val edge = with(LocalDensity.current) { HomeSize.sheetMargin.roundToPx() }
    val shape = RoundedCornerShape(HomeSize.sheetCorner)
    Popup(popupPositionProvider = remember(edge, above) { AnchorBelow(edge, above) }, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(Modifier.width(width).clip(shape).background(p.sheet).border(HomeSize.hairline, p.border, shape).padding(vertical = Space.s8), content = content)
    }
}

@Composable
internal fun MenuRow(label: String, icon: ImageVector? = null, chosen: Boolean = false, trailing: ImageVector? = null, height: Dp = MinTouchTarget, onClick: () -> Unit) {
    val p = LocalHome.current
    val fg = if (chosen) p.selectionForeground else p.text
    Row(
        Modifier.fillMaxWidth().padding(horizontal = HomeSize.rowInset).height(height).clip(RoundedCornerShape(HomeSize.rowCorner))
            .background(if (chosen) p.selectionBackground else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).semantics { if (chosen) selected = true }
            .padding(start = Space.s12, end = Space.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, if (chosen) fg else p.textDim, 21.dp); Spacer(Modifier.width(Space.s12)) }
        Label(label, fg, TextSize.bodyLarge, Modifier.weight(1f))
        if (trailing != null) Icon(trailing, fg, 18.dp)
    }
}

/**
 * Android's name sheet: a title, one field already holding [initial] (selected), and Cancel / [confirm].
 * A blank name is refused unless [allowEmpty]; [error] shows under the field.
 */
@Composable
internal fun NameDialog(title: String, initial: String, confirm: String, placeholder: String?, allowEmpty: Boolean, error: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalHome.current
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ready = allowEmpty || value.text.isNotBlank()
    val done = { if (ready) onConfirm(value.text.trim()) }
    val shape = RoundedCornerShape(HomeSize.sheetCorner)
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(HomeSize.dialogWidth).clip(shape).background(p.sheet).border(HomeSize.hairline, p.border, shape).padding(Space.s24)) {
            Label(title, p.text, TextSize.heading, Modifier.semantics { heading() }, FontWeight.Medium)
            Spacer(Modifier.height(Space.s16))
            val fieldShape = RoundedCornerShape(HomeSize.chipCorner)
            Box(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clip(fieldShape).background(p.surface)
                .border(HomeSize.hairline, if (error != null) p.text else p.border, fieldShape).padding(horizontal = Space.s12),
                contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty() && placeholder != null) Label(placeholder, p.textDim, TextSize.title)
                BasicTextField(value, { value = it }, Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = placeholder ?: title },
                    singleLine = true, textStyle = TextStyle(color = p.text, fontSize = TextSize.title), cursorBrush = SolidColor(p.text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { done() }))
            }
            if (error != null) { Spacer(Modifier.height(Space.s8)); Label(error, p.text, TextSize.label, lines = 3) }
            Spacer(Modifier.height(Space.s24))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                DialogButton(text("cancel"), primary = false, enabled = true, onClick = onDismiss)
                DialogButton(confirm, primary = true, enabled = ready, onClick = done)
            }
        }
    }
}

@Composable
internal fun DialogButton(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalHome.current
    val shape = RoundedCornerShape(HomeSize.chipCorner)
    Box(
        Modifier.heightIn(min = MinTouchTarget).clip(shape)
            .background(if (primary && enabled) p.selectionBackground else Color.Transparent)
            .border(HomeSize.hairline, if (primary && enabled) p.selectionBackground else p.border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = Space.s20),
        contentAlignment = Alignment.Center,
    ) { Label(label, if (!enabled) p.textDim else if (primary) p.selectionForeground else p.text, TextSize.bodyLarge, weight = FontWeight.Medium) }
}

/** A line glyph over a label in a bordered box that fills while pressed, as Android's empty-folder actions. */
@Composable
internal fun PrimaryButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val p = LocalHome.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(HomeSize.tileCorner)
    Column(
        Modifier.clip(shape).background(if (pressed) p.selectionBackground else Color.Transparent)
            .border(HomeSize.hairline, if (pressed) p.text else p.border, shape)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = Space.s16, horizontal = Space.s24),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, if (pressed) p.selectionForeground else p.text, 22.dp)
        Spacer(Modifier.height(Space.s8))
        Label(label, if (pressed) p.selectionForeground else p.text, TextSize.bodyLarge, weight = FontWeight.Medium, align = TextAlign.Center)
    }
}

/** An empty body: a picture, what is (not) here, and ways to fill it. */
@Composable
internal fun EmptyPane(message: String, art: EmptyArt?, actions: (@Composable RowScope.() -> Unit)? = null) {
    val p = LocalHome.current
    Column(Modifier.fillMaxSize().padding(horizontal = Space.s24, vertical = Space.s16),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (art != null) { EmptyIllustration(art, p); Spacer(Modifier.height(Space.s20)) }
        Label(message, p.textDim, TextSize.bodyLarge, Modifier.widthIn(max = HomeSize.dialogWidth), lines = 4, align = TextAlign.Center)
        if (actions != null) {
            Spacer(Modifier.height(Space.s20))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s12), content = actions)
        }
    }
}
