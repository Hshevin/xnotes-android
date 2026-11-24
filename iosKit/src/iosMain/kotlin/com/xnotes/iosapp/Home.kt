package com.xnotes.iosapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.ExperimentalComposeUiApi
import com.xnotes.core.util.DocumentKind
import com.xnotes.kio.KFiles
import com.xnotes.platform.ios.CgImageCodec
import com.xnotes.platform.ios.CgTextMeasurer
import com.xnotes.storage.lite.*
import com.xnotes.iosapp.ui.theme.MinTouchTarget
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.iosapp.ui.theme.TextSize
import com.xnotes.iosapp.home.HomeScreen
import com.xnotes.iosapp.home.HomeState
import com.xnotes.storage.ios.IosStorageLocations
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

internal val store by lazy { LiteStore(KFiles.system, IosStorageLocations.documents, IosStorageLocations.`private`, CgImageCodec, CgTextMeasurer) }

@Composable
private fun DocumentsHome() {
    val dark = isSystemInDarkTheme()
    val foreground = if (dark) Color.White else Color.Black
    val background = if (dark) Color.Black else Color.White
    val secondary = if (dark) Color.LightGray else Color.DarkGray
    val nameStyle = TextStyle(color = foreground, fontSize = TextSize.title)
    val detailStyle = TextStyle(color = secondary, fontSize = TextSize.bodyLarge)
    val home = remember { HomeState() }
    var selected by remember { mutableStateOf<Path?>(null) }
    val created = remember { CreatedDocument() }
    var recovery by remember { mutableStateOf<LiteRecovery?>(null) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val token = center.addObserverForName(UISceneDidActivateNotification, null, NSOperationQueue.mainQueue) { home.revision++ }
        onDispose { center.removeObserver(token); created.close() }
    }
    selected?.let { path ->
        key(path, recovery) { Viewer(path, recovery, created, dark, background, nameStyle, detailStyle) { selected = null; recovery = null; home.revision++ } }
        return
    }
    val now = { (NSDate().timeIntervalSince1970 * 1000).toLong() }
    HomeScreen(home, dark, open = { home.opened(it, now()); selected = it }, restore = { recovery = it; selected = it.original }) { opened ->
        if (selected != null) false else { created.adopt(opened); home.opened(opened.session.path, now()); selected = opened.session.path; true }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Viewer(path: Path, recovery: LiteRecovery?, created: CreatedDocument, dark: Boolean, background: Color, nameStyle: TextStyle, detailStyle: TextStyle, back: () -> Unit) {
    var result by remember { mutableStateOf<LiteOpen?>(null) }
    var renderFailed by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<InkEditor?>(null) }
    LaunchedEffect(path, recovery) {
        var owned: LiteOpen? = created.take(path)
        try {
            if (owned == null) withContext(Dispatchers.Default) { owned = if (recovery != null) store.restore(recovery) else store.open(path) }
            result = owned
            editor = (owned as? LiteOpen.Opened)?.let { InkEditor(it.session, back) }
            awaitCancellation()
        } finally { editor?.releaseOwner() ?: (owned as? LiteOpen.Opened)?.session?.close() }
    }
    val model = editor
    if (model != null) {
        SideEffect { model.dark = dark }
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
        EditorToolbar(path.name, model, dark, nameStyle) { if (model == null) back() else model.save(back = true) }
        val opened = result
        if (model != null) {
            model.warning?.let { message ->
                BasicText(message, Modifier.padding(horizontal = Space.s12), style = detailStyle)
                if (model.canLeaveProtected) Action("leave_protected", nameStyle, enabled = !model.active) { model.leaveProtected() }
            }
        }
        if (model != null && !renderFailed) key(dark, model) {
            PanelHost(model, dark, nameStyle) {
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
            }
        } else {
            val message = when ((opened as? LiteOpen.Refused)?.reason) {
                LiteRefusal.NOT_FOUND -> "open_missing"
                LiteRefusal.UNSUPPORTED_KIND -> if (DocumentKind.ofName(path.name) == DocumentKind.MIND_MAP) "open_legacy_map" else "open_unsupported"
                LiteRefusal.FUTURE_VERSION -> "open_future"
                LiteRefusal.CORRUPT, LiteRefusal.PDF_UNREADABLE -> "open_corrupt"
                null -> if (renderFailed) "render_failed" else "loading"
            }
            BasicText(text(message), Modifier.padding(Space.s24), style = detailStyle)
        }
    }
}

@Composable
internal fun Action(label: String, style: TextStyle, chosen: Boolean? = null, enabled: Boolean = true, onPress: () -> Unit) {
    val inverse = if (style.color == Color.Black) Color.White else Color.Black
    Box(Modifier.then(if (chosen == true) Modifier.background(style.color) else Modifier)
        .semantics { if (chosen != null) selected = chosen }.clickable(enabled = enabled, role = Role.Button, onClick = onPress).sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
        .padding(horizontal = Space.s12), contentAlignment = Alignment.Center) {
        BasicText(text(label), style = style.copy(color = if (chosen == true) inverse else if (enabled) style.color else Color.Gray))
    }
}
