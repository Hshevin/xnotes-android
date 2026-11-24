package com.xnotes.iosapp.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xnotes.iosapp.text
import com.xnotes.iosapp.ui.icons.XnotesIcons
import com.xnotes.iosapp.ui.theme.HomeSize
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.iosapp.ui.theme.TextSize
import com.xnotes.storage.ios.listIosDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path

/** A sidebar destination: what it shows and whether this iPad has it yet. */
private class Destination(val key: String, val icon: ImageVector, val ready: Boolean, val badge: Int = 0, val chosen: Boolean = false, val go: () -> Unit = {})

private fun destinations(state: HomeState, recoveries: Int): Pair<List<Destination>, List<Destination>> {
    val home = Destination("home", XnotesIcons.home, true, chosen = state.pane == HomePane.FILES && state.folders.isEmpty()) { state.go(state.root) }
    val top = listOf(
        home,
        Destination("split_workspace", XnotesIcons.split, false),
        Destination("ai_label", XnotesIcons.ai, false),
        Destination("recovery_materials", XnotesIcons.restore, true, recoveries, state.pane == HomePane.RECOVERY) { state.pane = HomePane.RECOVERY },
        Destination("recent", XnotesIcons.clock, false),
    )
    val bottom = listOf(
        Destination("trash", XnotesIcons.trash, false),
        Destination("preferences", XnotesIcons.sliders, false),
        Destination("about", XnotesIcons.info, false),
    )
    return top to bottom
}

private fun Destination.press(state: HomeState) = if (ready) go() else state.say(text("coming_soon"))

/** Android's floating sidebar: the app's name and a fold button, the places, the folder tree under Home, and the rest below a rule. */
@Composable
internal fun HomeSidebar(state: HomeState, recoveries: Int, modifier: Modifier = Modifier) {
    val p = LocalHome.current
    val (top, bottom) = destinations(state, recoveries)
    SidebarSheet(HomeSize.sidebar, modifier) {
        Row(Modifier.fillMaxWidth().height(HomeSize.header).padding(start = Space.s20, end = Space.s8), verticalAlignment = Alignment.CenterVertically) {
            Label(text("app_name"), p.text, TextSize.heading, Modifier.weight(1f).semantics { heading() }, FontWeight.SemiBold)
            HomeIconButton(XnotesIcons.prev, text("collapse_sidebar")) { state.toggleRail() }
        }
        Spacer(Modifier.height(Space.s8))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            top.forEachIndexed { i, d ->
                SidebarRow(d, state)
                if (i == 0) FolderTree(state, state.root, 1)
            }
        }
        Box(Modifier.padding(horizontal = HomeSize.rowInset).fillMaxWidth().height(HomeSize.hairline).background(p.border))
        Column(Modifier.padding(vertical = Space.s8)) { bottom.forEach { SidebarRow(it, state) } }
    }
}

/** The sidebar folded to its icons, with a menu button to open it again. */
@Composable
internal fun HomeRail(state: HomeState, recoveries: Int, modifier: Modifier = Modifier) {
    val (top, bottom) = destinations(state, recoveries)
    SidebarSheet(HomeSize.rail, modifier) {
        Box(Modifier.fillMaxWidth().height(HomeSize.header), contentAlignment = Alignment.Center) {
            HomeIconButton(XnotesIcons.menu, text("expand_sidebar")) { state.toggleRail() }
        }
        Spacer(Modifier.height(Space.s8))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            top.forEach { RailPill(it, state) }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = Space.s16), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s16)) {
            bottom.forEach { RailPill(it, state) }
        }
    }
}

@Composable
private fun SidebarSheet(width: Dp, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalHome.current
    val shape = RoundedCornerShape(HomeSize.sheetCorner)
    Column(modifier.width(width).fillMaxHeight().padding(HomeSize.sheetMargin).clip(shape).background(p.sheet).border(HomeSize.hairline, p.border, shape),
        content = content)
}

@Composable
private fun SidebarRow(d: Destination, state: HomeState) {
    val p = LocalHome.current
    val fg = if (d.chosen) p.selectionForeground else p.text
    Row(
        Modifier.fillMaxWidth().padding(horizontal = HomeSize.rowInset).height(HomeSize.row + Space.s4).clip(RoundedCornerShape(HomeSize.rowCorner))
            .background(if (d.chosen) p.selectionBackground else Color.Transparent)
            .clickable(role = Role.Button) { d.press(state) }
            .semantics { if (d.chosen) selected = true }
            .padding(horizontal = Space.s12).alpha(if (d.ready) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(d.icon, fg, HomeSize.navIcon + 2.dp)
        Spacer(Modifier.width(Space.s20))
        Label(text(d.key), fg, TextSize.title, Modifier.weight(1f), if (d.chosen) FontWeight.Medium else null)
        if (d.badge > 0) Label("${d.badge}", if (d.chosen) fg else p.textDim, TextSize.body)
    }
}

@Composable
private fun RailPill(d: Destination, state: HomeState) {
    val p = LocalHome.current
    Box(
        Modifier.size(HomeSize.railPill, HomeSize.railPillHeight + Space.s12).clip(RoundedCornerShape(50))
            .background(if (d.chosen) p.selectionBackground else Color.Transparent)
            .clickable(role = Role.Button) { d.press(state) }
            .semantics { contentDescription = text(d.key); if (d.chosen) selected = true }
            .alpha(if (d.ready) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) { Icon(d.icon, if (d.chosen) p.selectionForeground else p.text, HomeSize.navIcon + 4.dp) }
}

/** The folders inside [dir], each opening its own folders under it when expanded. */
@Composable
private fun FolderTree(state: HomeState, dir: Path, depth: Int) {
    val folders by produceState(emptyList<com.xnotes.storage.ios.IosDocumentEntry>(), dir, state.revision) {
        value = withContext(Dispatchers.Default) { runCatching { listIosDocuments(dir).filter { it.isFolder } }.getOrDefault(emptyList()) }
    }
    folders.forEach { f ->
        val open = f.path in state.expanded
        val chosen = state.pane == HomePane.FILES && state.current == f.path
        FolderRow(f.name, depth, open, (f.childCount ?: 0) > 0, chosen,
            onToggle = { state.expanded = if (open) state.expanded - f.path else state.expanded + f.path }) { state.go(f.path) }
        if (open) FolderTree(state, f.path, depth + 1)
    }
}

@Composable
private fun FolderRow(name: String, depth: Int, open: Boolean, hasChildren: Boolean, chosen: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val p = LocalHome.current
    val fg = if (chosen) p.selectionForeground else p.text
    Row(
        Modifier.fillMaxWidth().padding(horizontal = HomeSize.rowInset).height(HomeSize.row).clip(RoundedCornerShape(HomeSize.rowCorner))
            .background(if (chosen) p.selectionBackground else Color.Transparent)
            .clickable(role = Role.Button, onClick = onOpen).semantics { if (chosen) selected = true }
            .padding(start = Space.s20 * depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(HomeSize.row).then(if (hasChildren) Modifier.clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = fmt(if (open) "folder_tree_collapse" else "folder_tree_expand", name) } else Modifier),
            contentAlignment = Alignment.Center) {
            if (hasChildren) Icon(if (open) XnotesIcons.chevronDown else XnotesIcons.next, if (chosen) fg else p.textDim, 14.dp)
        }
        Icon(XnotesIcons.folder, fg, HomeSize.navIcon)
        Spacer(Modifier.width(Space.s12))
        Label(name, fg, TextSize.title, Modifier.weight(1f).padding(end = Space.s12))
    }
}
