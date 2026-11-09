package com.xnotes.mindmap

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.R
import com.xnotes.ui.theme.*

@Composable
internal fun MapNodeDialog(node: MapNode, onDismiss: () -> Unit, onConfirm: (MapNode) -> Boolean,
                           title:Int?=null,notice:String?=null,problemLabel:Int=R.string.map_limit,onDraftChange:(MapNode)->Unit={},
                           previewAssets:Map<String,MapPreviewAsset> = emptyMap()) {
    var text by remember(node.id) { mutableStateOf(node.text) }
    val history = remember(node.id) { MapHistory(MindMapDocument(nodes = listOf(node.copy(parent = null, free = false)),previewAssets=previewAssets)) }
    var current by remember(node.id) { mutableStateOf(node) }
    var mode by remember { mutableStateOf(InkMode.PEN) }
    var width by remember { mutableStateOf(3f) }
    var undo by remember { mutableStateOf(false) }
    var redo by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf(false) }
    val painter = remember { MapPainter() }
    val alive=remember(node.id) {java.util.concurrent.atomic.AtomicBoolean(true)}
    DisposableEffect(node.id) {onDispose {alive.set(false)}}
    fun dismiss() {if(alive.get()) onDismiss()}
    fun refresh() { current = history.document.root.copy(parent = node.parent, free = node.free); undo = history.canUndo; redo = history.canRedo }
    fun commit(next: MapNode) {
        if(!alive.get()) return
        try {
            history.commit(history.document.update(node.id) { next.copy(parent = null, free = false) })
            problem = false; refresh();onDraftChange(current.copy(text=text))
        } catch (_: IllegalArgumentException) { problem = true }
    }
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(.94f).imePadding()) {
            Column(Modifier.fillMaxSize().padding(Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                Text(stringResource(title ?: if (current.kind == NodeKind.INK) R.string.map_ink_node else R.string.map_edit), fontSize = TextSize.heading)
                notice?.let {Text(it,fontSize=TextSize.body,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)}
                if(current.fileCardId!=null) Text(stringResource(R.string.graph_file_card_notice),fontSize=TextSize.caption)
                else Row {
                    MapButton(R.string.map_text_node, selected = current.kind == NodeKind.TEXT, enabled = current.ink.isEmpty()) {
                        commit(current.copy(kind = NodeKind.TEXT))
                    }
                    MapButton(R.string.map_ink_node, selected = current.kind == NodeKind.INK) {
                        commit(current.copy(kind = NodeKind.INK, width = maxOf(320.0, current.width), height = maxOf(200.0, current.height)))
                    }
                }
                OutlinedTextField(value = text, onValueChange = { if(alive.get()) {if (it.length <= 20_000) {text=it;onDraftChange(current.copy(text=it))} else problem = true} },
                    label = { Text(stringResource(R.string.map_node_text), fontSize = TextSize.body) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = TextSize.bodyLarge),
                    modifier = if (current.kind == NodeKind.TEXT) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
                    maxLines = if (current.kind == NodeKind.TEXT) Int.MAX_VALUE else 2)
                if(current.kind==NodeKind.INK) {
                    Text(stringResource(R.string.ink_properties_hint),fontSize=TextSize.body)
                    MapButton(R.string.map_larger,enabled=current.width<4096 && current.height<4096) {
                        commit(current.copy(width=(current.width*1.5).coerceAtMost(4096.0),height=(current.height*1.5).coerceAtMost(4096.0)))
                    }
                    Spacer(Modifier.weight(1f))
                }
                if (problem) Text(stringResource(problemLabel), fontSize = TextSize.body)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MapButton(R.string.cancel, onClick = ::dismiss)
                    MapButton(R.string.map_done) {
                        if(alive.get()) {
                            val next = current.copy(text = text, height = if (current.kind == NodeKind.TEXT) painter.measuredHeight(text, current.width) else current.height)
                            try { history.document.update(node.id) {next.copy(parent = null, free = false)}.validate(); if (!onConfirm(next)) problem = true }
                            catch (_: IllegalArgumentException) { problem = true }
                        }
                    }
                }
            }
        }
    }
}
