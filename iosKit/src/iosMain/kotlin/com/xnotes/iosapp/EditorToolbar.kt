package com.xnotes.iosapp

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.xnotes.core.tools.Tool
import com.xnotes.iosapp.ui.icons.StrokeIcons
import com.xnotes.iosapp.ui.icons.XnotesIcons
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.iosapp.ui.theme.ToolbarPalette
import com.xnotes.iosapp.ui.theme.ToolbarSize

internal enum class ToolbarButton(val labelKey: String, val icon: ImageVector, val tool: Tool? = null) {
    BACK("back", XnotesIcons.prev),
    PAGES("pages", XnotesIcons.sidebar),
    LAYERS("layers", XnotesIcons.layers),
    PEN(Tool.PEN, StrokeIcons.regular),
    HIGHLIGHTER(Tool.HIGHLIGHTER, StrokeIcons.highlighter),
    ERASER(Tool.ERASER, XnotesIcons.eraser),
    SELECT(Tool.SELECT, XnotesIcons.select),
    LASSO(Tool.LASSO, XnotesIcons.lasso),
    TEXT(Tool.TEXT, XnotesIcons.text),
    UNDO("undo", XnotesIcons.undo),
    REDO("redo", XnotesIcons.redo),
    DELETE("selection_delete", XnotesIcons.trash),
    COPY("selection_copy", XnotesIcons.copy),
    PASTE("selection_paste", XnotesIcons.paste),
    DUPLICATE("selection_duplicate", XnotesIcons.duplicate),
    BOLD("text_bold", XnotesIcons.bold),
    ITALIC("text_italic", XnotesIcons.italic),
    LIST("text_list", XnotesIcons.listBullet),
    SETTINGS("settings", XnotesIcons.sliders);

    constructor(tool: Tool, icon: ImageVector) : this(tool.id, icon, tool)
}

@Composable
internal fun EditorToolbar(filename: String, editor: InkEditor?, dark: Boolean, titleStyle: TextStyle, back: () -> Unit) {
    val palette = ToolbarPalette.forTheme(dark)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ToolbarIcon(ToolbarButton.BACK, palette, onClick = back)
        val titleWidth = if (editor == null) Modifier.weight(1f) else Modifier.widthIn(max = ToolbarSize.maxFilename)
        BasicText(
            filename, titleWidth.padding(end = Space.s12),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = titleStyle,
        )
        if (editor != null) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                PanelActions(editor, palette)
                for (button in ToolbarButton.entries) {
                    val tool = button.tool ?: continue
                    ToolbarIcon(button, palette, chosen = tool == editor.tool) { editor.select(tool) }
                }
                ToolbarIcon(ToolbarButton.UNDO, palette, enabled = !editor.active && editor.canUndo) { editor.replay(true) }
                ToolbarIcon(ToolbarButton.REDO, palette, enabled = !editor.active && editor.canRedo) { editor.replay(false) }
                SelectionActions(editor, palette)
                if (editor.tool == Tool.TEXT) editor.textSurface?.formatButtons(palette)
            }
            ToolbarIcon(ToolbarButton.SETTINGS, palette, chosen = editor.settings, enabled = !editor.active) {
                editor.settings = !editor.settings
            }
        }
    }
}

@Composable
internal fun ToolbarIcon(button: ToolbarButton, palette: ToolbarPalette, chosen: Boolean? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val label = text(button.labelKey)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.85f else 1f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "toolPress",
    )
    Box(
        Modifier.size(ToolbarSize.button)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (chosen != null) selected = chosen
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(ToolbarSize.circle).drawBehind {
                if (chosen == true) drawCircle(palette.selectionBackground)
            },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                button.icon, contentDescription = null,
                modifier = Modifier.size(ToolbarSize.icon).graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
                colorFilter = ColorFilter.tint(palette.tint(chosen == true, enabled)),
            )
        }
    }
}
