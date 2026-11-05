package com.xnotes.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.pen.*
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.*

/** Recorded stylus buttons as one outlined list: a row per mapping, recording and naming at its foot. */
@Composable
fun PenShortcutSettings(shortcuts: List<PenShortcut>, onChange: (List<PenShortcut>) -> Boolean) {
    var recording by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<PenTrigger>?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    val defaultName = stringResource(R.string.pen_shortcut_default, shortcuts.size + 1)
    var name by remember { mutableStateOf("") }
    var binding by remember { mutableStateOf(PenBinding()) }
    val actions = listOf(
        "none" to R.string.none, "pen" to R.string.tool_pen,
        "highlighter" to R.string.tool_highlighter, "eraser" to R.string.tool_eraser,
        "pan" to R.string.tool_pan, "select" to R.string.tool_select,
        "lasso" to R.string.tool_lasso, "undo" to R.string.undo, "redo" to R.string.redo,
    ).map { (id, label) -> id to stringResource(label) }
    val palette = LocalPalette.current
    val text = palette.text.toComposeColor()
    val dim = palette.textDim.toComposeColor()
    val border = palette.border.toComposeColor()
    val shape = MaterialTheme.shapes.medium
    Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        FieldLabel(stringResource(R.string.pen_shortcuts_title))
        Text(stringResource(R.string.pen_shortcuts_help), color = dim, fontSize = TextSize.label)
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(shape).border(1.dp, border, shape)) {
            if (shortcuts.isEmpty()) {
                Text(stringResource(R.string.pen_shortcuts_empty), Modifier.padding(Space.s16), color = dim, fontSize = TextSize.body)
            }
            shortcuts.forEachIndexed { index, shortcut ->
                if (index > 0) HorizontalDivider(color = border)
                Column(Modifier.fillMaxWidth().padding(start = Space.s16, end = Space.s4, top = Space.s4, bottom = Space.s12),
                    verticalArrangement = Arrangement.spacedBy(Space.s4)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shortcut.name, Modifier.weight(1f), color = text, fontSize = TextSize.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val remove = stringResource(R.string.pen_shortcut_delete)
                        Tooltip(remove) {
                            IconButton(onClick = { onChange(shortcuts.filterIndexed { i, _ -> i != index }) }, modifier = Modifier.size(MinTouchTarget)) {
                                Icon(XnotesIcons.close, remove, tint = dim, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    if (shortcut.primary.kind == PenTrigger.Kind.MOTION) {
                        Text(stringResource(R.string.pen_shortcut_near_screen), Modifier.padding(end = Space.s12), color = dim, fontSize = TextSize.label)
                    }
                    BindingFields(actions, shortcut.binding) { next ->
                        onChange(shortcuts.toMutableList().apply { set(index, shortcut.copy(binding = next)) })
                    }
                }
            }
            HorizontalDivider(color = border)
            when {
                recording -> Column(Modifier.fillMaxWidth().padding(start = Space.s16, end = Space.s16, top = Space.s12, bottom = Space.s4)) {
                    PenRecorder(onRecorded = { triggers ->
                        recording = false
                        if (shortcuts.any { s -> s.triggers.any { it in triggers } }) message = R.string.pen_record_duplicate
                        else { pending = triggers; name = defaultName; binding = PenBinding() }
                    }, onFailed = { failure ->
                        recording = false
                        message = if (failure == PenRecording.Failure.MULTIPLE_BUTTONS) R.string.pen_record_multiple else R.string.pen_record_failed
                    })
                    TextButton(modifier = Modifier.align(Alignment.End).heightIn(min = MinTouchTarget), onClick = { recording = false }) {
                        Text(stringResource(R.string.cancel), fontSize = TextSize.body)
                    }
                }
                pending != null -> Column(Modifier.fillMaxWidth().padding(start = Space.s16, end = Space.s16, top = Space.s12, bottom = Space.s4),
                    verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                    Text(stringResource(R.string.pen_record_success), color = text, fontSize = TextSize.body)
                    if (pending!!.any { it.kind == PenTrigger.Kind.MOTION }) {
                        Text(stringResource(R.string.pen_shortcut_near_screen), color = dim, fontSize = TextSize.label)
                    }
                    OutlinedTextField(value = name, onValueChange = { if (it.length <= 80) name = it },
                        label = { Text(stringResource(R.string.pen_shortcut_name), fontSize = TextSize.label) }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = TextSize.body), modifier = Modifier.fillMaxWidth())
                    BindingFields(actions, binding) { binding = it }
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                        TextButton(modifier = Modifier.heightIn(min = MinTouchTarget), onClick = { pending = null }) {
                            Text(stringResource(R.string.cancel), fontSize = TextSize.body)
                        }
                        TextButton(modifier = Modifier.heightIn(min = MinTouchTarget), enabled = name.isNotBlank(), onClick = {
                            val next = shortcuts + PenShortcut(name.trim(), pending!!, binding)
                            if (onChange(next)) { pending = null; message = null }
                            else message = R.string.pro_pen_save_failed
                        }) { Text(stringResource(R.string.save), fontSize = TextSize.body) }
                    }
                }
                else -> Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable {
                    if (shortcuts.size >= PenShortcut.MAX_SHORTCUTS) message = R.string.pen_shortcuts_limit
                    else { message = null; recording = true }
                }.padding(horizontal = Space.s16), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                    Icon(XnotesIcons.plus, null, tint = text, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.pen_record_add), color = text, fontSize = TextSize.bodyLarge)
                }
            }
        }
        message?.let { Text(stringResource(it), color = text, fontSize = TextSize.body) }
    }
}

/** The action, then how a tool is held; commands only ever fire once per press. */
@Composable
private fun BindingFields(actions: List<Pair<String, String>>, binding: PenBinding, onChange: (PenBinding) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
        OptionDropdown(actions, binding.action, Modifier.sizeIn(minWidth = 120.dp, minHeight = MinTouchTarget)) { action ->
            onChange(binding.copy(action = action,
                mode = if (action in PenBinding.commands) PenBindingMode.SELECT else binding.mode))
        }
        if (binding.action in PenBinding.tools) {
            OptionDropdown(listOf("HOLD" to stringResource(R.string.pro_pen_hold), "SELECT" to stringResource(R.string.pro_pen_select)), binding.mode.name,
                Modifier.sizeIn(minWidth = 120.dp, minHeight = MinTouchTarget)) {
                onChange(binding.copy(mode = PenBindingMode.valueOf(it)))
            }
        } else if (binding.action in PenBinding.commands) {
            Text(stringResource(R.string.pro_pen_once), color = LocalPalette.current.textDim.toComposeColor(), fontSize = TextSize.label)
        }
    }
}
