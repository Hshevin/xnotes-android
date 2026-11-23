package com.xnotes.iosapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.ExperimentalComposeUiApi
import com.xnotes.core.util.DocumentKind
import com.xnotes.iosapp.ui.theme.MinTouchTarget
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.iosapp.ui.theme.TextSize
import com.xnotes.storage.ios.IosDocumentEntry
import com.xnotes.storage.ios.IosStorageLocations
import com.xnotes.storage.ios.listIosDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import okio.IOException
import okio.Path
import platform.Foundation.*
import platform.UIKit.UISceneDidActivateNotification
import platform.UIKit.UIViewController
import platform.UIKit.UIView

fun rootViewController(): UIViewController = ComposeUIViewController { DocumentsHome() }

private fun text(key: String) = NSBundle.mainBundle.localizedStringForKey(key, value = null, table = null)

private sealed interface Listing {
    data object Loading : Listing
    data object Failed : Listing
    data class Ready(val entries: List<IosDocumentEntry>) : Listing
}

@Composable
private fun DocumentsHome() {
    val dark = isSystemInDarkTheme()
    val foreground = if (dark) Color.White else Color.Black
    val background = if (dark) Color.Black else Color.White
    val secondary = if (dark) Color.LightGray else Color.DarkGray
    val titleStyle = TextStyle(color = foreground, fontSize = TextSize.display, fontWeight = FontWeight.Bold)
    val nameStyle = TextStyle(color = foreground, fontSize = TextSize.title)
    val detailStyle = TextStyle(color = secondary, fontSize = TextSize.bodyLarge)
    val formatter = remember { NSDateFormatter().apply {
        dateStyle = NSDateFormatterMediumStyle
        timeStyle = NSDateFormatterShortStyle
    } }
    var folders by remember { mutableStateOf(emptyList<Path>()) }
    var selected by remember { mutableStateOf<Path?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val token = center.addObserverForName(UISceneDidActivateNotification, null, NSOperationQueue.mainQueue) { revision++ }
        onDispose { center.removeObserver(token) }
    }
    val current = folders.lastOrNull()
    selected?.let { path ->
        key(path) { Viewer(path, dark, background, nameStyle, detailStyle) { selected = null } }
        return
    }
    val listing by key(current) {
        produceState<Listing>(Listing.Loading, revision) {
            value = withContext(Dispatchers.Default) {
                try {
                    Listing.Ready(listIosDocuments(current ?: IosStorageLocations.documents))
                } catch (_: IOException) {
                    Listing.Failed
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(background).windowInsetsPadding(WindowInsets.safeDrawing).padding(Space.s24)) {
        BasicText(text("app_name"), style = detailStyle)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (current != null) Action("back", nameStyle) { folders = folders.dropLast(1) }
            BasicText(current?.name ?: text("documents"), Modifier.weight(1f).semantics { heading() },
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = titleStyle)
            Action("refresh", nameStyle) { revision++ }
        }
        Spacer(Modifier.height(Space.s16))
        when (val state = listing) {
            Listing.Loading -> BasicText(text("loading"), style = detailStyle)
            Listing.Failed -> BasicText(text("list_failed"), style = detailStyle)
            is Listing.Ready -> if (state.entries.isEmpty()) {
                BasicText(text("empty_title"), style = nameStyle)
                Spacer(Modifier.height(Space.s4))
                BasicText(text("empty_hint"), style = detailStyle)
            } else LazyColumn {
                items(state.entries, key = { it.path.toString() }) { entry ->
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        if (entry.isFolder) folders = folders + entry.path else selected = entry.path
                    }.heightIn(min = MinTouchTarget).padding(vertical = Space.s12),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s16)) {
                        Column(Modifier.weight(1f)) {
                            BasicText(entry.path.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = nameStyle)
                            BasicText(text(when (entry.kind) {
                                null -> "kind_folder"
                                DocumentKind.NOTE -> "kind_note"
                                DocumentKind.CANVAS -> "kind_canvas"
                                DocumentKind.SPLIT -> "kind_split"
                                DocumentKind.MIND_MAP -> "kind_map"
                                DocumentKind.PDF -> "kind_pdf"
                            }), style = detailStyle)
                        }
                        val modified = entry.modifiedAtMillis?.let { formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(it / 1000.0)) }
                        BasicText(modified ?: text("unknown_time"), style = detailStyle)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Viewer(path: Path, dark: Boolean, background: Color, nameStyle: TextStyle, detailStyle: TextStyle, back: () -> Unit) {
    var result by remember { mutableStateOf<LiteOpen?>(null) }
    var renderFailed by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        var owned: LiteOpen? = null
        try {
            withContext(Dispatchers.Default) { owned = ReadOnlyLiteStore.open(path) }
            result = owned
            awaitCancellation()
        } finally { (owned as? LiteOpen.Opened)?.session?.close() }
    }
    Column(Modifier.fillMaxSize().background(background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.s12), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
            Action("back", nameStyle, back)
            BasicText(path.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = nameStyle)
            BasicText(text("read_only"), style = detailStyle)
        }
        val opened = result
        if (opened is LiteOpen.Opened && !renderFailed) key(dark, opened.session) {
            UIKitView(factory = {
                try {
                    val session = opened.session
                    session.canvas?.let { CanvasView(it, dark) { renderFailed = true } }
                        ?: PagedView(session, dark) { renderFailed = true }
                } catch (_: Exception) { renderFailed = true; UIView() }
            }, modifier = Modifier.fillMaxSize(), properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative),
                onRelease = { (it as? PagedView)?.close() })
        } else {
            val message = when ((opened as? LiteOpen.Refused)?.reason) {
                LiteRefusal.NOT_FOUND -> "open_missing"
                LiteRefusal.UNSUPPORTED_KIND -> if (DocumentKind.ofName(path.name) == DocumentKind.MIND_MAP) "open_legacy_map" else "open_unsupported"
                LiteRefusal.FUTURE_VERSION -> "open_future"
                LiteRefusal.CORRUPT -> "open_corrupt"
                null -> if (renderFailed) "render_failed" else "loading"
            }
            BasicText(text(message), Modifier.padding(Space.s24), style = detailStyle)
        }
    }
}

@Composable
private fun Action(label: String, style: TextStyle, onPress: () -> Unit) {
    Box(Modifier.clickable(role = Role.Button, onClick = onPress).sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
        .padding(horizontal = Space.s12), contentAlignment = Alignment.Center) {
        BasicText(text(label), style = style)
    }
}
