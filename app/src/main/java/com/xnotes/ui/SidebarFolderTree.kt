package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.xnotes.ui.theme.Space

/** Query one level below visible folders to discover leaves; never scan collapsed subtrees. */
@Composable
internal fun SidebarFolderTree(
    editor: Editor, root: String, selectedId: String?, revision: Int,
    expanded: MutableMap<String, Boolean>, onOpen: (String) -> Unit,
) {
    val rootId = remember(root) { editor.browseRootDocId(root) }
    val folders by produceState<List<BrowseEntry>>(emptyList(), root, revision, editor.treeVersion, editor.noteOpen) {
        value = withContext(Dispatchers.IO) { editor.browseChildren(root, rootId).filter { it.isDir }.sortedBy { it.name.lowercase() } }
    }
    for (folder in folders) key(folder.documentUri) {
        SidebarFolderBranch(editor, root, folder, selectedId, revision, expanded, onOpen, 0, setOf(rootId))
    }
}

@Composable
private fun SidebarFolderBranch(
    editor: Editor, root: String, folder: BrowseEntry, selectedId: String?, revision: Int,
    expanded: MutableMap<String, Boolean>, onOpen: (String) -> Unit, depth: Int, ancestors: Set<String>,
) {
    val id = remember(folder.documentUri) { editor.browseDocId(folder.documentUri) }
    if (id in ancestors) return // A provider must not create a cycle in the visible tree.
    val open = expanded[id] == true
    var children by remember(root, id) { mutableStateOf<List<BrowseEntry>?>(null) }
    LaunchedEffect(root, id, revision, editor.treeVersion, editor.noteOpen) {
        // Keep the old visible branch until its replacement arrives, avoiding collapse/flicker.
        children = withContext(Dispatchers.IO) {
            editor.browseChildren(root, id).filter { it.isDir }.sortedBy { it.name.lowercase() }
        }
    }
    val palette = LocalPalette.current
    val selected = id == selectedId
    val foreground = (if (selected) palette.selectionForeground else palette.text).toComposeColor()
    Row(Modifier.fillMaxWidth().height(48.dp)
        .background((if (selected) palette.selectionBackground else palette.panel).toComposeColor())
        .padding(start = (8 + depth.coerceAtMost(6) * 12).dp), verticalAlignment = Alignment.CenterVertically) {
        if (!children.isNullOrEmpty()) {
            val label = stringResource(if (open) R.string.folder_tree_collapse else R.string.folder_tree_expand, folder.name)
            IconButton(onClick = { expanded[id] = !open }, modifier = Modifier.size(40.dp).semantics { contentDescription = label }) {
                Text(if (open) "▾" else "▸", color = foreground)
            }
        } else Spacer(Modifier.width(Space.s40))
        Row(Modifier.weight(1f).fillMaxHeight().semantics { this.selected = selected }
            .clickable { onOpen(folder.documentUri) }.padding(end = Space.s12),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(XnotesIcons.folder, null, tint = foreground, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.s8))
            Text(folder.name, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (open) {
        if (children == null) Text(stringResource(R.string.split_loading), color = palette.textDim.toComposeColor(),
            modifier = Modifier.padding(start = (48 + depth.coerceAtMost(6) * 12).dp, top = Space.s6, bottom = Space.s6))
        for (child in children.orEmpty()) key(child.documentUri) {
            SidebarFolderBranch(editor, root, child, selectedId, revision, expanded, onOpen, depth + 1, ancestors + id)
        }
    }
}
