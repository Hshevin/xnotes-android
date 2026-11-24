package com.xnotes.iosapp.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xnotes.core.util.DocumentKind
import com.xnotes.explorer.EntryKind
import com.xnotes.explorer.formatWhen
import com.xnotes.iosapp.text
import com.xnotes.iosapp.ui.icons.XnotesIcons
import com.xnotes.iosapp.ui.theme.HomeSize
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.iosapp.ui.theme.TextSize
import com.xnotes.storage.library.TrashItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone

/**
 * Android's Trash: each item with where it was and when it went, how long it has left, Restore, and
 * Delete permanently under ⋮; Empty trash in the bar.
 */
@Composable
internal fun TrashPane(state: HomeState, wide: Boolean) {
    val p = LocalHome.current
    val actions = LocalActions.current
    val sheet = LocalSheet.current
    LaunchedEffect(Unit) { actions.purgeTrash() }
    val items by produceState<List<TrashItem>?>(null, state.revision) {
        value = withContext(Dispatchers.Default) { runCatching { library.listTrash() }.getOrDefault(emptyList()) }
    }
    val list = items
    val now = remember(list) { nowMillis() }
    val clock = remember { clock24() }
    Column(Modifier.fillMaxSize().padding(horizontal = HomeSize.gutter)) {
        Row(Modifier.fillMaxWidth().height(HomeSize.chipRow).padding(top = Space.s8), verticalAlignment = Alignment.CenterVertically) {
            Label(plural("trash_deleted_after", TRASH_DAYS), p.textDim, TextSize.body, Modifier.weight(1f))
            if (!list.isNullOrEmpty()) HomeChip(text("empty_trash"), false, XnotesIcons.trash) { sheet.value = HomeSheet.EmptyTrash }
        }
        when {
            list == null -> EmptyPane(text("loading"), null)
            list.isEmpty() -> EmptyPane(text("trash_empty"), EmptyArt.TRASH)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = HomeSize.bottomClearance)) {
                items(list, key = { it.path.toString() }) { item ->
                    TrashRow(item, wide, now, clock)
                    Box(Modifier.fillMaxWidth().height(HomeSize.hairline).background(p.border))
                }
            }
        }
    }
}

@Composable
private fun TrashRow(item: TrashItem, wide: Boolean, now: Long, clock: Boolean) {
    val p = LocalHome.current
    val actions = LocalActions.current
    val sheet = LocalSheet.current
    val name = item.path.name
    val isDoc = DocumentKind.ofName(name) != null
    val kind = when (DocumentKind.ofName(name)) {
        null -> EntryKind.FOLDER
        DocumentKind.CANVAS -> EntryKind.CANVAS
        DocumentKind.PDF -> EntryKind.PDF
        else -> EntryKind.NOTE
    }
    val wasIn = item.originalFolder.lastOrNull() ?: text("app_name")
    val deleted = formatWhen(HomeWords, item.deleted, now, "day", true, clock, TimeZone.currentSystemDefault())
    val left = (TRASH_DAYS - ((now - item.deleted) / 86_400_000L).toInt()).coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().height(60.dp).padding(start = Space.s8, end = Space.s4), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(HomeSize.listThumb).clip(RoundedCornerShape(HomeSize.chipCorner)).background(p.surface), contentAlignment = Alignment.Center) {
            Icon(kindIcon(kind), item.color?.let { codeTint(it, p.sheet == androidx.compose.ui.graphics.Color.Black) } ?: p.textDim, 22.dp)
        }
        Column(Modifier.weight(1f).padding(start = Space.s16)) {
            Label(if (isDoc) DocumentKind.stripSuffix(name) else name, p.text, TextSize.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                Icon(kindIcon(kind), p.textDim, 12.dp)
                Label(if (wide) kindLabel(kind) else fmt("trash_row_sub", kindLabel(kind), wasIn, deleted), p.textDim, TextSize.label)
            }
        }
        if (wide) {
            Row(Modifier.width(170.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
                Icon(XnotesIcons.folder, p.textDim, 14.dp)
                Label(wasIn, p.textDim, TextSize.body)
            }
            Label(deleted, p.textDim, TextSize.body, Modifier.width(150.dp))
            Label(if (left == 0) text("today") else plural("days_count", left), p.text, TextSize.body, Modifier.width(110.dp),
                if (left <= 3) FontWeight.Bold else null)
        }
        Row(Modifier.clip(RoundedCornerShape(HomeSize.rowCorner)).clickable(role = Role.Button) { actions.restore(item) }
            .padding(horizontal = Space.s8, vertical = Space.s12), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s6)) {
            Icon(XnotesIcons.restore, p.text, 16.dp)
            Label(text("restore"), p.text, TextSize.bodyLarge, weight = FontWeight.Medium)
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            HomeIconButton(XnotesIcons.more, fmt("more_for", shownName(item.path))) { menu = true }
            if (menu) HomeMenu({ menu = false }) {
                MenuRow(text("restore"), XnotesIcons.restore) { menu = false; actions.restore(item) }
                MenuRow(text("delete_permanently"), XnotesIcons.trash) { menu = false; sheet.value = HomeSheet.DeleteForever(item) }
            }
        }
    }
}
