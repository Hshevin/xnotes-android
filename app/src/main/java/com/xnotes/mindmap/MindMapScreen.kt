package com.xnotes.mindmap

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.SettingsRepository
import com.xnotes.ui.theme.*

@Composable
fun MindMapScreen(session: MapSession, title: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val appearance = remember { LiveSettings.get(SettingsRepository(context)).prefs.uiAppearance }
    val dark = when (appearance) { "light" -> false; "dark", "oled" -> true; else -> isSystemInDarkTheme() }
    XnotesTheme(monochromePalette(dark)) {
        var sourcesFor by remember { mutableStateOf<String?>(null) }
        var pendingSourceNode by remember { mutableStateOf<String?>(null) }
        var graphTools by remember { mutableStateOf(false) }
        var addFree by remember { mutableStateOf(false) }
        var layoutMenu by remember { mutableStateOf(false) }
        var proposed by remember { mutableStateOf<MindMapDocument?>(null) }
        val sourcePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val id=pendingSourceNode;pendingSourceNode=null
            if(uri!=null && id!=null) session.addSource(uri,id)
        }
        var view by remember { mutableStateOf<MindMapView?>(null) }
        var editing by remember { mutableStateOf<String?>(null) }
        var deleting by remember { mutableStateOf(false) }
        var choosing by remember { mutableStateOf(false) }
        var reparenting by remember { mutableStateOf(false) }
        var help by remember { mutableStateOf(false) }
        val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) session.saveCopy(uri) {}
        }
        val close = { if (session.document == null) onClose() else session.save(onClose) }
        BackHandler { if (!session.closing) close() }
        val palette = LocalPalette.current
        Surface(color = palette.panel.toComposeColor(), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.s8), verticalAlignment = Alignment.CenterVertically) {
                    MapButton(R.string.map_close, enabled = !session.closing) { close() }
                    Text(title.ifBlank { stringResource(R.string.map_title) }, fontSize = TextSize.title, modifier = Modifier.padding(horizontal = Space.s12))
                    Text(stringResource(when {
                        session.loading -> R.string.map_loading
                        session.loadFailed -> R.string.map_open_error
                        session.saving -> R.string.map_saving
                        session.saveFailed -> R.string.map_save_error
                        session.dirty -> R.string.map_unsaved
                        else -> R.string.map_saved
                    }), fontSize = TextSize.caption)
                    MapButton(R.string.save, enabled = session.document != null && !session.saving) { session.save() }
                    MapButton(R.string.map_save_copy, enabled = session.document != null && !session.saving) { saveCopy.launch("${title.ifBlank { context.getString(R.string.map_title) }}.xmap") }
                    MapButton(R.string.map_help) { help = true }
                }
                HorizontalDivider()
                val doc = session.document
                val node = doc?.nodes?.firstOrNull { it.id == session.selected }
                if (doc != null) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.s8)) {
                        fun add(kind: NodeKind, sibling: Boolean) {
                            val parent = if (sibling) node?.parent else node?.id
                            if (parent != null) {
                                var added: String? = null
                                val accepted = session.operate {
                                    val (next, id) = it.add(parent, kind, context.getString(if (kind == NodeKind.TEXT) R.string.map_topic else R.string.map_ink_node))
                                    added = id; next
                                }
                                if (accepted) { session.selected = added; editing = added }
                            }
                        }
                        MapButton(R.string.map_add_text, enabled = node != null && !session.closing) { add(NodeKind.TEXT, false) }
                        MapButton(R.string.map_add_ink, enabled = node != null && !session.closing) { add(NodeKind.INK, false) }
                        MapButton(R.string.map_add_sibling, enabled = node?.parent != null && !session.closing) { add(node?.kind ?: NodeKind.TEXT, true) }
                        MapButton(R.string.map_edit, enabled = node != null && !session.closing) { editing = node?.id }
                        MapButton(if (node?.collapsed == true) R.string.map_expand else R.string.map_collapse, enabled = node != null && doc.children(node.id).isNotEmpty()) {
                            node?.let { session.operate { d -> MapLayout.arrange(d.update(it.id) { n -> n.copy(collapsed = !n.collapsed) }) } }
                        }
                        MapButton(R.string.delete, enabled = node != null && node.id != doc.root.id && !session.closing) { deleting = true }
                        MapButton(R.string.undo, enabled = session.canUndo && !session.closing) { session.undo() }
                        MapButton(R.string.redo, enabled = session.canRedo && !session.closing) { session.redo() }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.s8)) {
                        MapButton(R.string.graph_free) { addFree=true }
                        MapButton(R.string.graph_detach,enabled=node?.parent!=null) { node?.let { n -> session.operate { it.detach(n.id) } } }
                        MapButton(R.string.graph_relations) { graphTools=true }
                        MapButton(R.string.graph_sources,enabled=node!=null) { sourcesFor=node?.id }
                        MapButton(R.string.map_nodes) { choosing = true; reparenting = false }
                        MapButton(R.string.map_change_parent, enabled = node?.parent != null && !session.closing) { choosing = true; reparenting = true }
                        MapButton(R.string.map_layout, enabled = !session.closing) { layoutMenu=true }
                        MapButton(R.string.map_fit) { view?.fit() }
                        MapButton(R.string.map_zoom_in) { view?.zoomBy(1.25f) }
                        MapButton(R.string.map_zoom_out) { view?.zoomBy(.8f) }
                        Text(node?.text.orEmpty().take(80), fontSize = TextSize.body, modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = Space.s12))
                    }
                }
                val problem = when {
                    session.sourceFailed -> R.string.graph_source_error
                    session.conflict -> R.string.map_conflict
                    session.saveFailed -> R.string.map_recovery_notice
                    session.recovered -> R.string.map_recovered
                    session.limitError -> R.string.map_limit
                    session.copySaved -> R.string.map_copy_saved
                    else -> null
                }
                problem?.let {
                    Text(stringResource(it), fontSize = TextSize.body, modifier = Modifier.fillMaxWidth().padding(Space.s12))
                    if (session.saveFailed && session.recoverySafe) MapButton(R.string.map_close_recovery) { onClose() }
                }
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        doc != null -> AndroidView(factory = { c -> MindMapView(c).also { view = it } }, modifier = Modifier.fillMaxSize(), update = { v ->
                            v.document = proposed ?: doc; v.dark = dark; v.selected = session.selected
                            v.contentDescription = context.getString(R.string.map_canvas_description)
                            v.onSelect = { session.selected = it }; v.onSources={sourcesFor=it}; v.onEdit = { if (!session.closing) { if(doc.node(it).kind==NodeKind.REFERENCE) sourcesFor=it else editing = it } }; v.onChange = { session.commit(it) }
                        })
                        session.loading -> Text(stringResource(R.string.map_loading), modifier = Modifier.align(Alignment.Center), fontSize = TextSize.body)
                        else -> Text(stringResource(R.string.map_open_error), modifier = Modifier.align(Alignment.Center).padding(Space.s24), fontSize = TextSize.body)
                    }
                }
                Text(stringResource(R.string.map_hint), fontSize = TextSize.caption, modifier = Modifier.fillMaxWidth().padding(Space.s8))
            }
        }
        editing?.let { id -> session.document?.nodes?.firstOrNull { it.id == id }?.let { node ->
            MapNodeDialog(node, onDismiss = { editing = null }, onConfirm = { edited ->
                session.operate { d -> val updated=d.update(id) { edited }; if(edited.free) updated else MapLayout.arrange(updated) }.also { if (it) editing = null }
            })
        } }
        if(addFree) AlertDialog(onDismissRequest={addFree=false},title={Text(stringResource(R.string.graph_free),fontSize=TextSize.heading)},text={Column {
            for(k in NodeKind.entries) MapButton(when(k){NodeKind.TEXT->R.string.map_text_node;NodeKind.INK->R.string.map_ink_node;NodeKind.REFERENCE->R.string.graph_reference}) {
                var added: String?=null
                val accepted=session.operate { d -> val n=d.addFree(k,if(k==NodeKind.REFERENCE) "" else context.getString(R.string.map_topic),d.root.x+d.root.width+100,d.root.y+300+d.nodes.count { it.free }*100);added=n.second;n.first }
                if(accepted) { session.selected=added;addFree=false;if(k==NodeKind.REFERENCE) sourcesFor=added else editing=added }
            }
        }},confirmButton={MapButton(R.string.cancel){addFree=false}})
        if(layoutMenu) AlertDialog(onDismissRequest={layoutMenu=false},title={Text(stringResource(R.string.map_layout),fontSize=TextSize.heading)},text={Column {
            for(direction in MapDirection.entries) MapButton(when(direction){MapDirection.BOTH->R.string.graph_both;MapDirection.RIGHT->R.string.graph_right;MapDirection.DOWN->R.string.graph_vertical}) {
                session.document?.let { proposed=MapLayout.arrange(it.copy(direction=direction)) };layoutMenu=false
            }
        }},confirmButton={MapButton(R.string.cancel){layoutMenu=false}})
        proposed?.let { preview -> AlertDialog(onDismissRequest={proposed=null},title={Text(stringResource(R.string.graph_preview),fontSize=TextSize.heading)},
            text={Text(stringResource(R.string.graph_preview_notice),fontSize=TextSize.body)},
            confirmButton={MapButton(R.string.map_done){session.commit(preview);proposed=null}},dismissButton={MapButton(R.string.cancel){proposed=null}}) }
        if(graphTools) session.document?.let { GraphRelationsDialog(it,session.selected,session::commit){graphTools=false} }
        sourcesFor?.let { id -> session.document?.nodes?.firstOrNull { it.id==id }?.let { n ->
            GraphSourcesDialog(n, { edited -> session.operate { it.update(id) { edited } } }, { pendingSourceNode=id;sourcePicker.launch(arrayOf("application/octet-stream","application/zip")) }) { sourcesFor=null }
        } }
        if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringResource(R.string.delete), fontSize = TextSize.heading) },
            text = { Text(stringResource(R.string.map_delete_confirm), fontSize = TextSize.body) },
            confirmButton = { MapButton(R.string.delete) { session.selected?.let { id -> session.operate { d -> d.delete(id) } }; deleting = false } },
            dismissButton = { MapButton(R.string.cancel) { deleting = false } })
        if (choosing) {
            val doc = session.document
            val id = session.selected
            val excluded = if (reparenting && doc != null && id != null) doc.descendants(id) else emptySet()
            AlertDialog(onDismissRequest = { choosing = false },
                title = { Text(stringResource(if (reparenting) R.string.map_change_parent else R.string.map_nodes), fontSize = TextSize.heading) },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = MindMapNodeListHeight)) {
                        val nodes = doc?.visible().orEmpty().filter { it.id !in excluded }
                        items(nodes.size, key = { nodes[it].id }) { index ->
                            val n = nodes[index]
                            TextButton(onClick = {
                                if (reparenting && id != null) session.operate { it.reparent(id, n.id) }
                                else { session.selected = n.id; view?.centerOn(n.id) }
                                choosing = false
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) {
                                Text(n.text.ifBlank { stringResource(if (n.kind == NodeKind.INK) R.string.map_ink_node else R.string.map_topic) },
                                    fontSize = TextSize.body, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }, confirmButton = { MapButton(R.string.cancel) { choosing = false } })
        }
        if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(stringResource(R.string.map_help), fontSize = TextSize.heading) },
            text = { Text(stringResource(R.string.map_help_text), fontSize = TextSize.body) },
            confirmButton = { MapButton(R.string.map_done) { help = false } })
    }
}

@Composable
internal fun MapButton(label: Int, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val fg = palette.text.toComposeColor(); val bg = palette.panel.toComposeColor()
    TextButton(onClick, enabled = enabled, modifier = Modifier.heightIn(min = MinTouchTarget).widthIn(min = MinTouchTarget)
        .then(if (selected) Modifier.background(fg) else Modifier),
        colors = ButtonDefaults.textButtonColors(contentColor = if (selected) bg else fg)) {
        Text(stringResource(label), fontSize = TextSize.body)
    }
}
