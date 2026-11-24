package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.util.DocumentKind
import com.xnotes.settings.Preferences
import com.xnotes.settings.ThumbShape
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize

/**
 * Trash: what was deleted, where it came from, when, and how long it has left before it is permanently deleted. Items
 * go back to their folder on Restore; Empty trash and Delete permanently remove them. Deleted AI chats are listed
 * after the files, kept by the same retention setting, and go back to AI history on Restore.
 */
@Composable
internal fun TrashPane(editor: Editor, sidebarOpen: Boolean, onShowSidebar: () -> Unit, onOpenPreferences: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val root = editor.browseRoot
    val prefs = remember(editor.prefsVersion) { editor.preferences }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TrashItem?>(null) }
    var confirmChat by remember { mutableStateOf<com.xnotes.ai.AiChat?>(null) }
    var busy by remember { mutableStateOf(false) }
    val sessions = com.xnotes.ai.AiSessions.get(context)
    val items by produceState<List<TrashItem>?>(null, root, refresh) {
        sessions.purgeTrash(prefs.trashDays)
        value = if (root == null) emptyList() else withContext(Dispatchers.IO) {
            editor.purgeExpiredTrash(root, prefs.trashDays)
            editor.listTrash(root)
        }
    }
    val rootName by produceState(root?.let { editor.cachedRootName(it) }, root) {
        value = root?.let { r -> withContext(Dispatchers.IO) { editor.browseRootName(r) } }
    }
    val now = remember(items) { System.currentTimeMillis() }
    val clock24 = android.text.format.DateFormat.is24HourFormat(context)
    val zone = ZoneId.systemDefault()
    val shown = items.orEmpty().filter { query.isBlank() || it.entry.name.contains(query.trim(), ignoreCase = true) }
    val chats = sessions.trash.values.sortedByDescending { it.trashedAt }
    val shownChats = chats.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) || it.source.contains(query.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            var emptyWidth by remember { mutableStateOf(0.dp) }
            // Open, the search covers the title: it gets what the menu button and Empty trash leave, up to 300dp.
            val searchRoom = maxWidth - emptyWidth - 12.dp - (if (sidebarOpen) 0.dp else 48.dp)
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!sidebarOpen) {
                    Tooltip(stringResource(R.string.show_sidebar)) {
                        IconButton(onClick = onShowSidebar) {
                            Icon(XnotesIcons.menu, stringResource(R.string.show_sidebar), tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                        }
                    }
                }
                Text(stringResource(R.string.trash), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = TextSize.headline, maxLines = 1, modifier = Modifier.weight(1f))
                ExplorerSearchField(query, { query = it }, expandedWidth = searchRoom.coerceIn(40.dp, 300.dp))
                Spacer(Modifier.width(Space.s12))
                val shape = MaterialTheme.shapes.medium
                val enabled = !busy && (!items.isNullOrEmpty() || chats.isNotEmpty())
                Row(
                    Modifier.onSizeChanged { emptyWidth = with(density) { it.width.toDp() } }
                        .height(40.dp).clip(shape).border(1.dp, palette.border.toComposeColor(), shape)
                        .clickable(enabled = enabled) { confirmEmpty = true }.padding(horizontal = Space.s16),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s8),
                ) {
                    val tint = if (enabled) palette.accent.toComposeColor() else palette.textDim.toComposeColor().copy(alpha = 0.5f)
                    Icon(XnotesIcons.trash, null, tint = tint, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.empty_trash), color = if (enabled) palette.text.toComposeColor() else tint, fontSize = TextSize.bodyLarge, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(Space.s8))
        val bannerShape = MaterialTheme.shapes.medium
        Row(
            Modifier.fillMaxWidth().clip(bannerShape).background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), bannerShape).padding(horizontal = Space.s16, vertical = Space.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s12),
        ) {
            Icon(XnotesIcons.clock, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
            Text(
                when (prefs.trashDays) {
                    0 -> stringResource(R.string.trash_off_hint)
                    Preferences.TRASH_FOREVER -> stringResource(R.string.trash_forever_hint)
                    else -> pluralStringResource(R.plurals.trash_deleted_after, prefs.trashDays, prefs.trashDays)
                },
                color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge, modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.change), color = palette.accent.toComposeColor(), fontSize = TextSize.bodyLarge, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onOpenPreferences).padding(horizontal = Space.s6, vertical = Space.s4),
            )
        }
        Spacer(Modifier.height(Space.s12))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 720.dp
            val list = items
            when {
                root == null && chats.isEmpty() -> EmptyPane(stringResource(R.string.trash_no_root))
                list == null -> EmptyPane(stringResource(R.string.loading))
                list.isEmpty() && chats.isEmpty() -> EmptyPane(stringResource(R.string.trash_empty), EmptyArt.TRASH)
                shown.isEmpty() && shownChats.isEmpty() -> EmptyPane(stringResource(R.string.trash_no_match, query.trim()))
                else -> Column(Modifier.fillMaxSize()) {
                    if (shown.isNotEmpty() || shownChats.isNotEmpty()) Row(Modifier.fillMaxWidth().height(36.dp).padding(start = Space.s8, end = Space.s4), verticalAlignment = Alignment.CenterVertically) {
                        TrashHead(stringResource(R.string.sort_name), Modifier.weight(1f).padding(start = Space.s48))
                        if (wide) {
                            TrashHead(stringResource(R.string.trash_was_in), Modifier.width(170.dp))
                            TrashHead(stringResource(R.string.trash_deleted), Modifier.width(150.dp), on = true)
                            TrashHead(stringResource(R.string.trash_removed_in), Modifier.width(110.dp))
                        }
                        Spacer(Modifier.width(150.dp))
                    }
                    Hairline()
                    // The keyboard slides over the rows; its height joins the end padding so the last ones scroll clear.
                    val endPad = WindowInsets.ime.exclude(WindowInsets.systemBars).add(WindowInsets(bottom = 16.dp)).asPaddingValues()
                    LazyColumn(Modifier.weight(1f), contentPadding = endPad) {
                        items(shown, key = { it.wrapperDocId }) { item ->
                            TrashRow(
                                editor, item, wide, rootName ?: stringResource(R.string.the_top_folder), now, clock24, zone, prefs.trashDays,
                                onRestore = {
                                    val folder = root ?: return@TrashRow
                                    busy = true
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { editor.restoreTrash(folder, item) }
                                        busy = false
                                        refresh++
                                        editor.say(if (ok) context.getString(R.string.restored_item, label(item)) else context.getString(R.string.err_restore_item, label(item)))
                                    }
                                },
                                onDelete = { confirmDelete = item },
                            )
                            Hairline()
                        }
                        if (shownChats.isNotEmpty()) {
                            item(key = "ai-section") {
                                Text(stringResource(R.string.ai_trash_section), color = palette.textDim.toComposeColor(), fontSize = TextSize.body, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(start = Space.s8, top = Space.s16, bottom = Space.s4))
                                Hairline()
                            }
                            items(shownChats, key = { "ai:" + it.id }) { chat ->
                                AiTrashRow(chat, wide, now, clock24, zone, prefs.trashDays,
                                    onRestore = { sessions.restore(chat.id); editor.say(context.getString(R.string.restored_item, chat.displayTitle(context))) },
                                    onDelete = { confirmChat = chat })
                                Hairline()
                            }
                        }
                    }
                    if (shown.isNotEmpty()) Text(
                        stringResource(R.string.restore_hint, rootName ?: stringResource(R.string.the_top_folder)),
                        color = palette.textDim.toComposeColor(), fontSize = TextSize.body, modifier = Modifier.padding(start = Space.s8, top = Space.s12),
                    )
                }
            }
        }
    }

    if (confirmEmpty) {
        SheetDialog(
            title = stringResource(R.string.empty_trash_confirm),
            onDismiss = { confirmEmpty = false },
            text = stringResource(R.string.empty_trash_body),
            foot = {
                ConfirmButtons(stringResource(R.string.empty_trash), { confirmEmpty = false }, ButtonKind.DESTROY) {
                    confirmEmpty = false
                    busy = true
                    sessions.emptyTrash()
                    scope.launch {
                        if (root != null) withContext(Dispatchers.IO) { editor.emptyTrash(root) }
                        busy = false
                        refresh++
                    }
                }
            },
        )
    }
    confirmChat?.let { chat ->
        AlertDialog(
            onDismissRequest = { confirmChat = null },
            title = { Text(stringResource(R.string.delete_permanently_confirm)) },
            text = { Text(stringResource(R.string.delete_permanently_body, chat.displayTitle(context))) },
            confirmButton = { TextButton(onClick = { sessions.deleteForever(chat.id); confirmChat = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmChat = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    confirmDelete?.let { item ->
        if (root == null) return@let
        SheetDialog(
            title = stringResource(R.string.delete_permanently_confirm),
            onDismiss = { confirmDelete = null },
            text = stringResource(R.string.delete_permanently_body, label(item)),
            foot = {
                ConfirmButtons(stringResource(R.string.delete), { confirmDelete = null }, ButtonKind.DESTROY) {
                    confirmDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { editor.deleteTrash(root, item) }
                        refresh++
                    }
                }
            },
        )
    }
}

/** The stored default title is "新会话"; outside composition it is shown in the UI language too. */
private fun com.xnotes.ai.AiChat.displayTitle(context: android.content.Context): String =
    if (title == com.xnotes.ai.AiChat().title) context.getString(R.string.ai_new_chat) else title

/** A deleted AI chat, laid out like a file row: where it came from, when it was deleted, how long it has left. */
@Composable
private fun AiTrashRow(chat: com.xnotes.ai.AiChat, wide: Boolean, now: Long, clock24: Boolean, zone: ZoneId, days: Int, onRestore: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val words = rememberExplorerWords()
    val dim = palette.textDim.toComposeColor()
    val title = chat.displayTitle(context)
    val wasIn = chat.source.ifBlank { stringResource(R.string.ai_label) }
    val deleted = formatWhen(words, chat.trashedAt, now, "day", true, clock24, zone)
    val left = if (days > 0) (days - ((now - chat.trashedAt) / 86_400_000L).toInt()).coerceAtLeast(0) else null
    Row(Modifier.fillMaxWidth().height(60.dp).padding(start = Space.s8, end = Space.s4), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(palette.surface.toComposeColor()), contentAlignment = Alignment.Center) {
            Icon(XnotesIcons.ai, null, tint = dim, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(start = Space.s16)) {
            Text(title, color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (wide) stringResource(R.string.ai_trash_row_sub, wasIn) else stringResource(R.string.ai_trash_row_sub, wasIn) + " · " + deleted,
                color = dim, fontSize = TextSize.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (wide) {
            Row(Modifier.width(170.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
                Icon(XnotesIcons.file, null, tint = dim, modifier = Modifier.size(14.dp))
                Text(wasIn, color = dim, fontSize = TextSize.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(deleted, color = dim, fontSize = TextSize.body, maxLines = 1, modifier = Modifier.width(150.dp))
            Text(
                when (left) { null -> stringResource(R.string.never); 0 -> stringResource(R.string.today); else -> pluralStringResource(R.plurals.days_count, left, left) },
                color = if (left != null && left <= 3) palette.danger.toComposeColor() else palette.text.toComposeColor(),
                fontSize = TextSize.body, maxLines = 1, modifier = Modifier.width(110.dp),
            )
        }
        Row(Modifier.width(150.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            Row(
                Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onRestore).padding(horizontal = Space.s8, vertical = Space.s8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s6),
            ) {
                Icon(XnotesIcons.restore, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.restore), color = palette.accent.toComposeColor(), fontSize = TextSize.bodyLarge, fontWeight = FontWeight.Medium)
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                Tooltip(stringResource(R.string.more)) {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(XnotesIcons.more, stringResource(R.string.more_for, title), tint = dim, modifier = Modifier.size(17.dp))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.restore)) }, onClick = { menu = false; onRestore() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete_permanently)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private fun label(item: TrashItem): String = if (item.entry.isDir) item.entry.name else DocumentKind.stripSuffix(item.entry.name)

@Composable
private fun TrashHead(text: String, modifier: Modifier, on: Boolean = false) {
    val palette = LocalPalette.current
    Text(text, color = (if (on) palette.accent else palette.textDim).toComposeColor(), fontSize = TextSize.body, fontWeight = FontWeight.Medium, maxLines = 1, modifier = modifier)
}

@Composable
private fun TrashRow(
    editor: Editor,
    item: TrashItem,
    wide: Boolean,
    rootName: String,
    now: Long,
    clock24: Boolean,
    zone: ZoneId,
    days: Int,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalPalette.current
    val words = rememberExplorerWords()
    val e = item.entry
    val kind = entryKind(e, editor.cachedMeta(e))
    val dim = palette.textDim.toComposeColor()
    val wasIn = item.path.lastOrNull() ?: rootName
    val deleted = formatWhen(words, item.deleted, now, "day", true, clock24, zone)
    val left = if (days > 0) (days - ((now - item.deleted) / 86_400_000L).toInt()).coerceAtLeast(0) else null
    Row(Modifier.fillMaxWidth().height(60.dp).padding(start = Space.s8, end = Space.s4), verticalAlignment = Alignment.CenterVertically) {
        val shape = MaterialTheme.shapes.small
        if (e.isDir) {
            Box(Modifier.size(40.dp).clip(shape).background(palette.surface.toComposeColor()), contentAlignment = Alignment.Center) {
                Icon(XnotesIcons.folder, null, tint = e.color?.let { codeTint(it, palette) } ?: dim, modifier = Modifier.size(22.dp))
            }
        } else {
            EntryThumb(editor, e, ThumbShape.TOP, Modifier.size(40.dp).clip(shape).border(1.dp, palette.border.toComposeColor(), shape))
        }
        Column(Modifier.weight(1f).padding(start = Space.s16)) {
            Text(label(item), color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                Icon(kindIcon(kind), null, tint = dim, modifier = Modifier.size(12.dp))
                val sub = if (wide) kindLabel(kind) else stringResource(R.string.trash_row_sub, kindLabel(kind), wasIn, deleted)
                Text(sub, color = dim, fontSize = TextSize.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (wide) {
            Row(Modifier.width(170.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
                Icon(XnotesIcons.folder, null, tint = dim, modifier = Modifier.size(14.dp))
                Text(wasIn, color = dim, fontSize = TextSize.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(deleted, color = dim, fontSize = TextSize.body, maxLines = 1, modifier = Modifier.width(150.dp))
            Text(
                when (left) { null -> stringResource(R.string.never); 0 -> stringResource(R.string.today); else -> pluralStringResource(R.plurals.days_count, left, left) },
                color = if (left != null && left <= 3) palette.danger.toComposeColor() else palette.text.toComposeColor(),
                fontSize = TextSize.body, maxLines = 1, modifier = Modifier.width(110.dp),
            )
        }
        Row(Modifier.width(150.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            Row(
                Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onRestore).padding(horizontal = Space.s8, vertical = Space.s8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s6),
            ) {
                Icon(XnotesIcons.restore, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.restore), color = palette.accent.toComposeColor(), fontSize = TextSize.bodyLarge, fontWeight = FontWeight.Medium)
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                Tooltip(stringResource(R.string.more)) {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(XnotesIcons.more, stringResource(R.string.more_for, label(item)), tint = dim, modifier = Modifier.size(17.dp))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(stringResource(R.string.restore), XnotesIcons.restore, onClick = { menu = false; onRestore() })
                    MenuItem(stringResource(R.string.delete_permanently), XnotesIcons.trash, danger = true, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
