package com.xnotes.iosapp.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xnotes.core.model.Rgba
import com.xnotes.core.util.DocumentKind
import com.xnotes.iosapp.text
import com.xnotes.iosapp.ui.icons.XnotesIcons
import com.xnotes.iosapp.ui.theme.HomeSize
import com.xnotes.iosapp.ui.theme.MinTouchTarget
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.iosapp.ui.theme.TextSize
import com.xnotes.storage.ios.IosDocumentEntry
import com.xnotes.storage.ios.listIosDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.xnotes.storage.library.TrashItem
import okio.Path
import kotlin.math.roundToInt

internal val LocalActions = staticCompositionLocalOf<HomeActions> { error("No home actions") }
internal val LocalHomeState = staticCompositionLocalOf<HomeState> { error("No home state") }

/** What the home screen is asking about through a sheet: a name, a folder to move into, a colour's name, or whether to delete for good. */
internal sealed interface HomeSheet {
    class Rename(val item: Path) : HomeSheet
    class MoveTo(val items: List<Path>) : HomeSheet
    class NameColor(val color: Rgba) : HomeSheet
    data object EmptyTrash : HomeSheet
    class DeleteForever(val item: TrashItem) : HomeSheet
}

internal val LocalSheet = staticCompositionLocalOf<MutableState<HomeSheet?>> { error("No sheet") }

/** A colour code drawn on the explorer: as stored in the dark theme, deepened in the light one, as Android does. */
internal fun codeTint(c: Rgba, dark: Boolean): Color {
    if (dark) return Color(c.r, c.g, c.b)
    val lum = (0.299 * c.r + 0.587 * c.g + 0.114 * c.b) / 255.0
    val k = if (lum <= 0.35) 1.0 else 0.35 / lum
    return Color((c.r * k).toInt().coerceIn(0, 255), (c.g * k).toInt().coerceIn(0, 255), (c.b * k).toInt().coerceIn(0, 255))
}

/** Android's swatch grid: thirteen hues from pale tint to near-black, then a row of greys. */
internal object SwatchGrid {
    private val hues = (0 until 13).map { it * 360.0 / 13.0 }
    private val shades = listOf(0.18 to 1.0, 0.34 to 1.0, 0.52 to 1.0, 0.72 to 1.0, 0.88 to 1.0, 1.0 to 1.0,
        1.0 to 0.86, 1.0 to 0.72, 1.0 to 0.58, 1.0 to 0.44, 1.0 to 0.30, 1.0 to 0.18)
    val rows: List<List<Rgba>> = shades.map { (s, v) -> hues.map { hsv(it, s, v) } } +
        listOf((0..12).map { i -> ((1.0 - i / 12.0) * 255).roundToInt().let { g -> Rgba(g, g, g) } })

    private fun hsv(h: Double, s: Double, v: Double): Rgba {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60.0) % 2 - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0); h < 120 -> Triple(x, c, 0.0); h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c); h < 300 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
        }
        fun ch(v0: Double) = ((v0 + m) * 255).toInt().coerceIn(0, 255)
        return Rgba(ch(r), ch(g), ch(b))
    }
}

/** The ⋮ on a tile or row and the menu it opens: Android's entry menu, less what iPad does not do yet. */
@Composable
internal fun EntryMenuButton(e: IosDocumentEntry, tint: Color, width: androidx.compose.ui.unit.Dp = 32.dp) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(width, 40.dp).clip(RoundedCornerShape(HomeSize.rowCorner)).clickable(role = Role.Button) { open = true }
            .semantics { contentDescription = fmt("more_for", shownName(e)) }, contentAlignment = Alignment.Center) {
            Icon(XnotesIcons.more, tint, 17.dp)
        }
        if (open) EntryMenu(e) { open = false }
    }
}

@Composable
private fun EntryMenu(e: IosDocumentEntry, dismiss: () -> Unit) {
    val state = LocalHomeState.current
    val actions = LocalActions.current
    val sheet = LocalSheet.current
    var colors by remember { mutableStateOf(false) }
    val notePinned = !e.isFolder && e.path in actions.notePins
    HomeMenu(dismiss, width = if (colors) HomeSize.colorMenuWidth else HomeSize.menuWidth) {
        if (colors) {
            MenuRow(text("colour_code"), XnotesIcons.prev) { colors = false }
            ColorCodeChoices { c -> dismiss(); actions.color(listOf(e.path), c) }
            return@HomeMenu
        }
        MenuRow(text("rename"), XnotesIcons.edit) { dismiss(); sheet.value = HomeSheet.Rename(e.path) }
        MenuRow(text("move_to_folder_ellipsis"), XnotesIcons.moveToFolder) { dismiss(); sheet.value = HomeSheet.MoveTo(listOf(e.path)) }
        MenuRow(text("copy"), XnotesIcons.copy) { dismiss(); actions.copy(listOf(e.path), cut = false) }
        MenuRow(text("cut"), XnotesIcons.cut) { dismiss(); actions.copy(listOf(e.path), cut = true) }
        MenuRow(text("colour_code"), XnotesIcons.palette, trailing = XnotesIcons.next) { colors = true }
        e.color?.let { c -> MenuRow(text("name_colour_ellipsis"), XnotesIcons.text) { dismiss(); sheet.value = HomeSheet.NameColor(c) } }
        if (e.isFolder) {
            val pinned = e.path in state.pinnedFolders
            MenuRow(text(if (pinned) "unpin_from_sidebar" else "pin_to_sidebar"), XnotesIcons.pin) { dismiss(); state.togglePinned(e.path) }
        } else {
            MenuRow(text(if (notePinned) "unpin_from_top" else "pin_to_top"), XnotesIcons.pin) { dismiss(); actions.setNotePinned(e.path, !notePinned) }
        }
        MenuRow(text("move_to_trash"), XnotesIcons.trash) { dismiss(); actions.trash(listOf(e.path)) }
    }
}

/** No colour, then Android's swatch grid: thirteen hues in five shades, and a row of greys. */
@Composable
internal fun ColorCodeChoices(pick: (Rgba?) -> Unit) {
    val p = LocalHome.current
    MenuRow(text("none"), null) { pick(null) }
    Column(Modifier.padding(horizontal = Space.s12 + HomeSize.rowInset, vertical = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        SwatchGrid.rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                row.forEach { c ->
                    Box(Modifier.size(18.dp).clip(RoundedCornerShape(2.dp)).background(Color(c.r, c.g, c.b))
                        .border(0.5.dp, p.border, RoundedCornerShape(2.dp)).clickable(role = Role.Button) { pick(c) })
                }
            }
        }
    }
}

/** The bar over the files while items are picked: how many, select all, and what to do with them. */
@Composable
internal fun SelectionBar(all: List<Path>) {
    val p = LocalHome.current
    val state = LocalHomeState.current
    val actions = LocalActions.current
    val sheet = LocalSheet.current
    val picked = state.selected.toList()
    var colors by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(HomeSize.chipRow).padding(top = Space.s8), verticalAlignment = Alignment.CenterVertically) {
        HomeIconButton(XnotesIcons.close, text("clear_selection")) { state.selected = emptySet() }
        Label(fmt("n_selected", picked.size), p.text, TextSize.bodyLarge, Modifier.padding(horizontal = Space.s8), FontWeight.Medium)
        if (picked.size < all.size) HomeChip(text("select_all"), false) { state.selected = all.toSet() }
        Spacer(Modifier.weight(1f))
        HomeIconButton(XnotesIcons.moveToFolder, text("move_to_folder_ellipsis")) { sheet.value = HomeSheet.MoveTo(picked) }
        HomeIconButton(XnotesIcons.copy, text("copy")) { actions.copy(picked, cut = false) }
        HomeIconButton(XnotesIcons.cut, text("cut")) { actions.copy(picked, cut = true) }
        Box {
            HomeIconButton(XnotesIcons.palette, text("colour_code")) { colors = true }
            if (colors) HomeMenu({ colors = false }, width = HomeSize.colorMenuWidth) { ColorCodeChoices { c -> colors = false; actions.color(picked, c) } }
        }
        HomeIconButton(XnotesIcons.trash, text("move_to_trash")) { actions.trash(picked) }
    }
}

/** The ring a tile or row shows in select mode, filled with a check once picked. */
@Composable
internal fun CheckRing(on: Boolean, modifier: Modifier = Modifier) {
    val p = LocalHome.current
    Box(modifier.size(24.dp).clip(CircleShape).background(if (on) p.selectionBackground else p.sheet.copy(alpha = 0.9f))
        .border(1.5.dp, if (on) p.selectionBackground else p.outline, CircleShape), contentAlignment = Alignment.Center) {
        if (on) Icon(XnotesIcons.check, p.selectionForeground, 16.dp)
    }
}

/** The sheets the explorer opens: rename, move to a folder, name a colour, empty Trash. */
@Composable
internal fun HomeSheets(state: HomeState) {
    val sheet = LocalSheet.current
    val actions = LocalActions.current
    when (val s = sheet.value) {
        null -> Unit
        is HomeSheet.Rename -> {
            var error by remember(s) { mutableStateOf<String?>(null) }
            val isDoc = DocumentKind.ofName(s.item.name) != null
            val shown = if (isDoc) DocumentKind.stripSuffix(s.item.name) else s.item.name
            NameDialog(text("rename"), shown, text("rename"), null, allowEmpty = false, error, onConfirm = { name ->
                val full = if (isDoc) name + s.item.name.removePrefix(shown) else name
                if (full == s.item.name) sheet.value = null
                else actions.rename(s.item, full) { e -> if (e == null) sheet.value = null else error = e }
            }, onDismiss = { sheet.value = null })
        }
        is HomeSheet.NameColor -> NameDialog(text("name_this_colour"), actions.colorNames[s.color].orEmpty(), text("save"), null, allowEmpty = true, null,
            onConfirm = { name -> sheet.value = null; actions.nameColor(s.color, name.ifBlank { null }) }, onDismiss = { sheet.value = null })
        is HomeSheet.MoveTo -> MoveDialog(state, s.items, onMove = { target -> sheet.value = null; actions.moveInto(s.items, target) }) { sheet.value = null }
        HomeSheet.EmptyTrash -> ConfirmDialog(text("empty_trash_confirm"), text("empty_trash_body"), text("empty_trash"),
            onConfirm = { sheet.value = null; actions.emptyTrash() }) { sheet.value = null }
        is HomeSheet.DeleteForever -> ConfirmDialog(text("delete_permanently_confirm"), fmt("delete_permanently_body", shownName(s.item.path)), text("delete"),
            onConfirm = { sheet.value = null; actions.deleteForever(s.item) }) { sheet.value = null }
    }
}

/** Android's Move to folder: the folder tree from the top, the folders being moved left out, and Move here. */
@Composable
private fun MoveDialog(state: HomeState, items: List<Path>, onMove: (Path) -> Unit, onDismiss: () -> Unit) {
    val p = LocalHome.current
    var target by remember { mutableStateOf(state.current) }
    var open by remember { mutableStateOf(setOf(state.root)) }
    val shape = RoundedCornerShape(HomeSize.sheetCorner)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(HomeSize.dialogWidth + 80.dp).heightIn(max = 560.dp).clip(shape).background(p.sheet).border(HomeSize.hairline, p.border, shape).padding(vertical = Space.s20)) {
            Label(plural("move_items_to", items.size), p.text, TextSize.heading, Modifier.padding(horizontal = Space.s24).semantics { heading() }, FontWeight.Medium)
            Spacer(Modifier.height(Space.s12))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                MoveRow(text("app_name"), 0, state.root == target, null) { target = state.root }
                MoveTree(state.root, 1, items, open, target, { d -> open = if (d in open) open - d else open + d }) { target = it }
            }
            Spacer(Modifier.height(Space.s16))
            Row(Modifier.align(Alignment.End).padding(horizontal = Space.s24), horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                DialogButton(text("cancel"), primary = false, enabled = true, onClick = onDismiss)
                DialogButton(text("move_here"), primary = true, enabled = true) { onMove(target) }
            }
        }
    }
}

@Composable
private fun MoveTree(dir: Path, depth: Int, moving: List<Path>, open: Set<Path>, target: Path, toggle: (Path) -> Unit, pick: (Path) -> Unit) {
    val folders by produceState(emptyList<IosDocumentEntry>(), dir) {
        value = withContext(Dispatchers.Default) { runCatching { listIosDocuments(dir).filter { it.isFolder } }.getOrDefault(emptyList()) }
    }
    folders.filter { it.path !in moving }.forEach { f ->
        val expanded = f.path in open
        MoveRow(f.name, depth, f.path == target, if ((f.childCount ?: 0) > 0) expanded else null, { toggle(f.path) }) { pick(f.path) }
        if (expanded) MoveTree(f.path, depth + 1, moving, open, target, toggle, pick)
    }
}

@Composable
private fun MoveRow(name: String, depth: Int, chosen: Boolean, expanded: Boolean?, toggle: () -> Unit = {}, pick: () -> Unit) {
    val p = LocalHome.current
    val fg = if (chosen) p.selectionForeground else p.text
    Row(Modifier.fillMaxWidth().padding(horizontal = HomeSize.rowInset).height(HomeSize.row).clip(RoundedCornerShape(HomeSize.rowCorner))
        .background(if (chosen) p.selectionBackground else Color.Transparent).clickable(role = Role.Button, onClick = pick)
        .semantics { if (chosen) selected = true }.padding(start = Space.s20 * depth), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(HomeSize.row).then(if (expanded != null) Modifier.clickable(role = Role.Button, onClick = toggle)
            .semantics { contentDescription = fmt(if (expanded) "folder_tree_collapse" else "folder_tree_expand", name) } else Modifier),
            contentAlignment = Alignment.Center) {
            if (expanded != null) Icon(if (expanded) XnotesIcons.chevronDown else XnotesIcons.next, if (chosen) fg else p.textDim, 14.dp)
        }
        Icon(if (depth == 0) XnotesIcons.home else XnotesIcons.folder, fg, HomeSize.navIcon)
        Spacer(Modifier.width(Space.s12))
        Label(name, fg, TextSize.title, Modifier.weight(1f).padding(end = Space.s12))
    }
}

/** A question with Cancel and one answer that cannot be taken back. */
@Composable
internal fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalHome.current
    val shape = RoundedCornerShape(HomeSize.sheetCorner)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(HomeSize.dialogWidth).clip(shape).background(p.sheet).border(HomeSize.hairline, p.border, shape).padding(Space.s24)) {
            Label(title, p.text, TextSize.heading, Modifier.semantics { heading() }, FontWeight.Medium)
            Spacer(Modifier.height(Space.s12))
            Label(body, p.textDim, TextSize.bodyLarge, lines = 6)
            Spacer(Modifier.height(Space.s24))
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                DialogButton(text("cancel"), primary = false, enabled = true, onClick = onDismiss)
                DialogButton(confirm, primary = true, enabled = true, onClick = onConfirm)
            }
        }
    }
}
