package com.xnotes.iosapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.interop.LocalUIViewController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.ExperimentalComposeUiApi
import com.xnotes.core.util.DocumentKind
import com.xnotes.core.tools.Tool
import com.xnotes.kio.KFiles
import com.xnotes.platform.ios.CgImageCodec
import com.xnotes.platform.ios.CgTextMeasurer
import com.xnotes.storage.lite.*
import com.xnotes.iosapp.ui.theme.MinTouchTarget
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.iosapp.ui.theme.TextSize
import com.xnotes.storage.ios.IosDocumentEntry
import com.xnotes.storage.ios.IosStorageLocations
import com.xnotes.storage.ios.listIosDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okio.Path
import platform.Foundation.*
import platform.UIKit.UISceneDidActivateNotification
import platform.UIKit.UISceneDidEnterBackgroundNotification
import platform.UIKit.UIViewController
import platform.UIKit.UIView

fun rootViewController(): UIViewController = ComposeUIViewController { DocumentsHome() }

internal fun text(key: String) = NSBundle.mainBundle.localizedStringForKey(key, value = null, table = null)

private val store by lazy { LiteStore(KFiles.system, IosStorageLocations.documents, IosStorageLocations.`private`, CgImageCodec, CgTextMeasurer) }

private sealed interface Listing {
    data object Loading : Listing
    data object Failed : Listing
    data class Ready(val entries: List<IosDocumentEntry>, val recoveries: List<LiteRecovery>) : Listing
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
    var recovery by remember { mutableStateOf<LiteRecovery?>(null) }
    var discardFailed by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val token = center.addObserverForName(UISceneDidActivateNotification, null, NSOperationQueue.mainQueue) { revision++ }
        onDispose { center.removeObserver(token) }
    }
    val current = folders.lastOrNull()
    selected?.let { path ->
        key(path, recovery) { Viewer(path, recovery, dark, background, nameStyle, detailStyle) { selected = null; recovery = null; revision++ } }
        return
    }
    val listing by key(current) {
        produceState<Listing>(Listing.Loading, revision) {
            value = withContext(Dispatchers.Default) {
                try {
                    Listing.Ready(listIosDocuments(current ?: IosStorageLocations.documents), store.pendingRecoveries())
                } catch (_: Exception) {
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
            is Listing.Ready -> LazyColumn {
                if (discardFailed) item { BasicText(text("discard_failed"), style = detailStyle) }
                items(state.recoveries, key = { it.record.toString() }) { pending ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(text("pending_recovery") + " " + pending.original.name, Modifier.weight(1f), style = nameStyle)
                        Action("restore", nameStyle) { recovery = pending; selected = pending.original }
                        Action("discard", nameStyle) {
                            discardFailed = runCatching { store.discard(pending) }.isFailure; revision++
                        }
                    }
                }
                if (state.entries.isEmpty()) item {
                    BasicText(text("empty_title"), style = nameStyle)
                    Spacer(Modifier.height(Space.s4)); BasicText(text("empty_hint"), style = detailStyle)
                }
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
private fun Viewer(path: Path, recovery: LiteRecovery?, dark: Boolean, background: Color, nameStyle: TextStyle, detailStyle: TextStyle, back: () -> Unit) {
    var result by remember { mutableStateOf<LiteOpen?>(null) }
    var renderFailed by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<InkEditor?>(null) }
    LaunchedEffect(path, recovery) {
        var owned: LiteOpen? = null
        try {
            withContext(Dispatchers.Default) { owned = if (recovery != null) store.restore(recovery) else store.open(path) }
            result = owned
            editor = (owned as? LiteOpen.Opened)?.let { InkEditor(it.session, back) }
            awaitCancellation()
        } finally { editor?.releaseOwner() ?: (owned as? LiteOpen.Opened)?.session?.close() }
    }
    val model = editor
    if (model != null) {
        val controller = LocalUIViewController.current
        DisposableEffect(model, controller) {
            val center = NSNotificationCenter.defaultCenter
            val token = center.addObserverForName(UISceneDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
                if (it?.`object` == controller.view.window?.windowScene) model.save(background = true)
            }
            onDispose { center.removeObserver(token) }
        }
        LaunchedEffect(model, model.revision, model.active) {
            if (model.session.dirty && !model.active) {
                delay(3000)
                if (!model.active && model.session.dirty && model.warning == null) model.save()
            }
        }
    }
    Column(Modifier.fillMaxSize().background(background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.s12), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
            Action("back", nameStyle) { if (model == null) back() else model.save(back = true) }
            BasicText(path.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = nameStyle)
            if (model != null) Action("settings", nameStyle, chosen = model.settings, enabled = !model.active) { model.settings = !model.settings }
        }
        val opened = result
        if (model != null) {
            model.warning?.let { message ->
                BasicText(message, Modifier.padding(horizontal = Space.s12), style = detailStyle)
                if (model.canLeaveProtected) Action("leave_protected", nameStyle, enabled = !model.active) { model.leaveProtected() }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (tool in listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER))
                    Action(tool.id, nameStyle, chosen = tool == model.tool, enabled = !model.active) { model.select(tool) }
                Action("undo", nameStyle, enabled = !model.active && model.canUndo) { model.replay(true) }
                Action("redo", nameStyle, enabled = !model.active && model.canRedo) { model.replay(false) }
            }
            if (model.settings) Row(Modifier.horizontalScroll(rememberScrollState())) {
                Action("finger_drawing", nameStyle, chosen = model.finger, enabled = !model.active) { model.toggleFinger() }
                Action("area_eraser", nameStyle, chosen = model.area, enabled = !model.active) { model.toggleArea() }
            }
        }
        if (model != null && !renderFailed) key(dark, model) {
            UIKitView(factory = {
                try {
                    val surface: UIView = if (model.session.canvas != null) CanvasView(model, dark) { renderFailed = true }
                        else PagedView(model, dark) { renderFailed = true }
                    model.attachSurface(); surface
                } catch (_: Exception) { renderFailed = true; UIView() }
            }, modifier = Modifier.fillMaxSize(), properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative),
                update = { view -> when (view) { is CanvasView -> view.configure(model.finger); is PagedView -> view.configure(model.finger) } },
                onRelease = { view -> when (view) {
                    is CanvasView -> { view.close(); model.releaseSurface() }
                    is PagedView -> { view.close(); model.releaseSurface() }
                } })
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
private fun Action(label: String, style: TextStyle, chosen: Boolean? = null, enabled: Boolean = true, onPress: () -> Unit) {
    val inverse = if (style.color == Color.Black) Color.White else Color.Black
    Box(Modifier.then(if (chosen == true) Modifier.background(style.color) else Modifier)
        .semantics { if (chosen != null) selected = chosen }.clickable(enabled = enabled, role = Role.Button, onClick = onPress).sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
        .padding(horizontal = Space.s12), contentAlignment = Alignment.Center) {
        BasicText(text(label), style = style.copy(color = if (chosen == true) inverse else if (enabled) style.color else Color.Gray))
    }
}
