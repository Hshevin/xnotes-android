package com.xnotes.ui

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.MinTouchTarget
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize
import com.xnotes.ui.theme.toComposeColor
import com.xnotes.zotero.UrlConnectionZoteroHttp
import com.xnotes.zotero.ZoteroAttachment
import com.xnotes.zotero.ZoteroClient
import com.xnotes.zotero.ZoteroCollection
import com.xnotes.zotero.ZoteroConfig
import com.xnotes.zotero.ZoteroConfigStore
import com.xnotes.zotero.ZoteroException
import com.xnotes.zotero.ZoteroFailure
import com.xnotes.zotero.ZoteroItem
import com.xnotes.zotero.ZoteroLibrary
import com.xnotes.zotero.ZoteroLink
import com.xnotes.zotero.ZoteroLinkStore
import com.xnotes.zotero.ZoteroWebDav
import com.xnotes.zotero.zoteroNoteName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val KEYS_PAGE = "https://www.zotero.org/settings/keys/new"

/** One config store and one link file per process, so every Zotero pane sees the same links. */
private object ZoteroLocal {
    @Volatile private var links: ZoteroLinkStore? = null
    fun links(context: Context): ZoteroLinkStore = links ?: synchronized(this) {
        links ?: ZoteroLinkStore(java.io.File(context.applicationContext.noBackupFilesDir, "zotero-links.json")).also { links = it }
    }
    fun store(context: Context) = ZoteroConfigStore(context.applicationContext)
}

private fun zoteroMessage(context: Context, error: Throwable, group: Boolean = false): String = context.getString(
    when ((error as? ZoteroException)?.failure) {
        ZoteroFailure.BAD_KEY -> R.string.zotero_err_bad_key
        ZoteroFailure.NO_FILE_ACCESS -> R.string.zotero_err_no_file_access
        ZoteroFailure.NOT_STORED -> R.string.zotero_err_not_stored
        ZoteroFailure.FILE_MISSING -> if (group) R.string.zotero_err_file_missing_group else R.string.zotero_err_file_missing
        ZoteroFailure.WEBDAV_AUTH -> R.string.zotero_err_webdav_auth
        ZoteroFailure.WEBDAV_MISSING -> R.string.zotero_err_webdav_missing
        ZoteroFailure.CHECKSUM -> R.string.zotero_err_checksum
        ZoteroFailure.RATE_LIMITED -> R.string.zotero_err_rate_limited
        ZoteroFailure.NETWORK -> R.string.zotero_err_network
        ZoteroFailure.SERVER, ZoteroFailure.CANCELLED, null -> R.string.zotero_err_server
    },
)

/**
 * The Zotero library inside xnotes: connect with an API key once, then browse libraries and
 * collections, search, and tap a paper to open its PDF as a note. A paper opened before goes
 * back to the same note, wherever it was moved; one sent to Trash or deleted is imported anew.
 */
@Composable
internal fun ZoteroPane(editor: Editor, sidebarOpen: Boolean, onShowSidebar: () -> Unit, openFile: (String) -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    val loaded by produceState<Pair<ZoteroConfig?, Int>?>(null, revision) {
        value = withContext(Dispatchers.IO) { runCatching { ZoteroLocal.store(context).load() }.getOrNull() } to revision
    }
    val config = loaded?.first
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!sidebarOpen) {
                Tooltip(stringResource(R.string.show_sidebar)) {
                    IconButton(onClick = onShowSidebar) {
                        Icon(XnotesIcons.menu, stringResource(R.string.show_sidebar), tint = palette.text.toComposeColor(), modifier = Modifier.size(24.dp))
                    }
                }
            }
            Text(stringResource(R.string.zotero_label), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = TextSize.headline, maxLines = 1, modifier = Modifier.weight(1f))
            if (config != null && !editing) {
                Tooltip(stringResource(R.string.zotero_settings)) {
                    IconButton(onClick = { editing = true }) {
                        Icon(XnotesIcons.sliders, stringResource(R.string.zotero_settings), tint = palette.text.toComposeColor(), modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        when {
            loaded == null -> Unit
            config == null || editing -> ZoteroSetup(
                initial = config,
                onSaved = { editing = false; revision++ },
                onCancel = if (config != null) ({ editing = false }) else null,
                onDisconnect = if (config != null) ({ editing = false; revision++ }) else null,
            )
            else -> key(revision) { ZoteroLibraryView(editor, config, openFile) }
        }
    }
}

/** Connecting, or changing the connection: the API key, and WebDAV when the PDFs sync there. */
@Composable
private fun ZoteroSetup(initial: ZoteroConfig?, onSaved: () -> Unit, onCancel: (() -> Unit)?, onDisconnect: (() -> Unit)?) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(initial?.apiKey.orEmpty()) }
    var useDav by remember { mutableStateOf(initial?.webdav != null) }
    var davUrl by remember { mutableStateOf(initial?.webdav?.url.orEmpty()) }
    var davUser by remember { mutableStateOf(initial?.webdav?.username.orEmpty()) }
    var davPassword by remember { mutableStateOf(initial?.webdav?.password.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    fun connect() {
        val key = apiKey.trim()
        // Zotero adds the `zotero/` folder itself; an address copied with it would point one level too deep.
        val url = davUrl.trim().trimEnd('/').removeSuffix("/zotero").trimEnd('/')
        val dav = if (useDav) ZoteroWebDav(url, davUser.trim(), davPassword) else null
        error = when {
            key.isEmpty() -> context.getString(R.string.zotero_need_key)
            dav != null && !dav.url.startsWith("https://", ignoreCase = true) -> context.getString(R.string.zotero_need_https)
            dav != null && dav.username.isEmpty() -> context.getString(R.string.zotero_need_webdav_user)
            else -> null
        }
        if (error != null) return
        busy = true
        scope.launch {
            error = try {
                val account = withContext(Dispatchers.IO) { ZoteroClient(UrlConnectionZoteroHttp(), key).account() }
                when {
                    !account.libraryAccess -> context.getString(R.string.zotero_err_bad_key)
                    !account.fileAccess && dav == null -> context.getString(R.string.zotero_err_no_file_access)
                    else -> {
                        withContext(Dispatchers.IO) { ZoteroLocal.store(context).save(ZoteroConfig(key, account.userId, account.username, dav)) }
                        null
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { zoteroMessage(context, e) }
            busy = false
            if (error == null) onSaved()
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.zotero_disconnect_title)) },
            text = { Text(stringResource(R.string.zotero_disconnect_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    scope.launch { withContext(Dispatchers.IO) { runCatching { ZoteroLocal.store(context).clear() } }; onDisconnect?.invoke() }
                }) { Text(stringResource(R.string.zotero_disconnect)) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = Space.s8),
        verticalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        if (initial == null) {
            Text(stringResource(R.string.zotero_intro), color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge)
        } else {
            Text(stringResource(R.string.zotero_connected_as, initial.username), color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge)
        }
        Text(stringResource(R.string.zotero_key_steps), color = palette.textDim.toComposeColor(), fontSize = TextSize.body)
        OutlinedButton(onClick = {
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(KEYS_PAGE))) }
                .onFailure { error = context.getString(R.string.zotero_open_keys_failed, KEYS_PAGE) }
        }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
            Text(stringResource(R.string.zotero_open_keys_page), fontSize = TextSize.body)
        }
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it; error = null },
            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
            label = { Text(stringResource(R.string.zotero_api_key)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clip(MaterialTheme.shapes.small).clickable(enabled = !busy) { useDav = !useDav; error = null },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = useDav, onCheckedChange = null)
            Spacer(Modifier.width(Space.s8))
            Text(stringResource(R.string.zotero_use_webdav), color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge)
        }
        if (useDav) {
            Text(stringResource(R.string.zotero_webdav_hint), color = palette.textDim.toComposeColor(), fontSize = TextSize.body)
            OutlinedTextField(
                value = davUrl, onValueChange = { davUrl = it; error = null }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                label = { Text(stringResource(R.string.zotero_webdav_url)) }, placeholder = { Text("https://") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = davUser, onValueChange = { davUser = it; error = null }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                label = { Text(stringResource(R.string.zotero_webdav_user)) },
            )
            OutlinedTextField(
                value = davPassword, onValueChange = { davPassword = it; error = null }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                label = { Text(stringResource(R.string.zotero_webdav_password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        }
        error?.let { Text(it, color = palette.text.toComposeColor(), fontSize = TextSize.body, fontWeight = FontWeight.Bold) }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s8), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = ::connect, enabled = !busy, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(if (busy) R.string.zotero_connecting else if (initial == null) R.string.zotero_connect else R.string.save), fontSize = TextSize.body)
            }
            onCancel?.let { TextButton(onClick = it, enabled = !busy, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.cancel)) } }
            Spacer(Modifier.weight(1f))
            if (onDisconnect != null) {
                TextButton(onClick = { confirmDisconnect = true }, enabled = !busy, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.zotero_disconnect))
                }
            }
        }
        Text(stringResource(R.string.zotero_privacy), color = palette.textDim.toComposeColor(), fontSize = TextSize.label)
    }
}

/** Collections in tree order with their depth. */
private fun collectionTree(all: List<ZoteroCollection>): List<Pair<ZoteroCollection, Int>> {
    val children = all.groupBy { c -> c.parentKey?.takeIf { p -> all.any { it.key == p } } }
    val out = ArrayList<Pair<ZoteroCollection, Int>>()
    fun walk(parent: String?, depth: Int) {
        for (c in children[parent].orEmpty()) { out += c to depth; walk(c.key, depth + 1) }
    }
    walk(null, 0)
    return out
}

@Composable
private fun creatorsLine(item: ZoteroItem): String {
    val names = item.creators
    val who = when (names.size) {
        0 -> null
        1 -> names[0]
        2 -> stringResource(R.string.zotero_two_authors, names[0], names[1])
        else -> stringResource(R.string.zotero_many_authors, names[0])
    }
    return listOfNotNull(who, item.year).joinToString(" · ")
}

private class ItemList(val items: List<ZoteroItem>, val nextStart: Int?)

@Composable
private fun ZoteroLibraryView(editor: Editor, config: ZoteroConfig, openFile: (String) -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(config) { ZoteroClient(UrlConnectionZoteroHttp(), config.apiKey) }
    val links = remember { ZoteroLocal.links(context) }
    var reload by remember { mutableIntStateOf(0) }
    var libraries by remember { mutableStateOf<List<ZoteroLibrary>?>(null) }
    var library by remember { mutableStateOf<ZoteroLibrary?>(null) }
    var collections by remember { mutableStateOf<List<Pair<ZoteroCollection, Int>>>(emptyList()) }
    var collection by remember { mutableStateOf<ZoteroCollection?>(null) }
    var query by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<ItemList?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var linked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var opening by remember { mutableStateOf<ZoteroItem?>(null) }
    var openJob by remember { mutableStateOf<Job?>(null) }
    val openCancelled = remember { AtomicBoolean(false) }
    var choosing by remember { mutableStateOf<Pair<ZoteroItem, List<ZoteroAttachment>>?>(null) }

    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    LaunchedEffect(reload) {
        error = null
        try {
            val libs = io { client.libraries(config.userId, config.username) }
            libraries = libs
            if (library == null || libs.none { it.key == library?.key }) library = libs.first()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = zoteroMessage(context, e) }
    }
    LaunchedEffect(library, reload) {
        val lib = library ?: return@LaunchedEffect
        collections = runCatching { io { collectionTree(client.collections(lib)) } }.getOrElse { if (it is CancellationException) throw it; emptyList() }
        if (collections.none { it.first.key == collection?.key }) collection = null
    }
    LaunchedEffect(library, collection, query, reload) {
        val lib = library ?: return@LaunchedEffect
        list = null
        if (query.isNotBlank()) delay(400)
        try {
            val page = io { client.items(lib, collection?.key, query, start = 0) }
            linked = io { links.linkedItems(lib.key) }
            list = ItemList(page.items, page.nextStart)
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = zoteroMessage(context, e) }
    }

    fun loadMore() {
        val lib = library ?: return
        val current = list ?: return
        val start = current.nextStart ?: return
        if (loadingMore) return
        loadingMore = true
        scope.launch {
            try {
                val page = io { client.items(lib, collection?.key, query, start) }
                if (list === current) list = ItemList(current.items + page.items, page.nextStart)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { editor.message = zoteroMessage(context, e) }
            finally { loadingMore = false }
        }
    }

    fun openAttachment(lib: ZoteroLibrary, item: ZoteroItem, attachment: ZoteroAttachment) {
        val root = editor.browseRoot ?: run { editor.message = context.getString(R.string.zotero_need_folder); return }
        opening = item
        openCancelled.set(false)
        openJob = scope.launch {
            try {
                val existing = io { links.find(lib.key, attachment.key)?.let { editor.zoteroNoteLocation(root, it.documentId) } }
                if (existing != null) { openFile(existing); return@launch }
                val made = io {
                    editor.importZoteroPdf(root, zoteroNoteName(item, context.getString(R.string.zotero_untitled))) { out ->
                        client.download(lib, attachment, if (lib.group) null else config.webdav, out) { openCancelled.get() }
                    }?.also { (_, id) -> links.put(ZoteroLink(lib.key, item.key, attachment.key, id, attachment.md5)) }
                }
                if (made == null) {
                    if (!openCancelled.get()) editor.message = context.getString(R.string.zotero_err_import)
                    return@launch
                }
                linked = linked + item.key
                editor.notifyTreeChanged()
                openFile(made.first)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (!openCancelled.get() && (e as? ZoteroException)?.failure != ZoteroFailure.CANCELLED) editor.message = zoteroMessage(context, e, lib.group)
            } finally { opening = null; openJob = null }
        }
    }

    fun openItem(item: ZoteroItem) {
        val lib = library ?: return
        if (opening != null) return
        if (editor.browseRoot == null) { editor.message = context.getString(R.string.zotero_need_folder); return }
        opening = item
        openCancelled.set(false)
        openJob = scope.launch {
            var handedOn = false
            try {
                val pdfs = io { client.pdfs(lib, item) }
                when (pdfs.size) {
                    0 -> editor.message = context.getString(R.string.zotero_no_pdf)
                    1 -> { handedOn = true; opening = null; openAttachment(lib, item, pdfs[0]) }
                    else -> choosing = item to pdfs
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { if (!openCancelled.get()) editor.message = zoteroMessage(context, e) }
            finally { if (!handedOn) { opening = null; openJob = null } }
        }
    }

    opening?.let { item ->
        ProgressDialog(
            title = stringResource(R.string.zotero_opening),
            detail = item.title,
            onCancel = { openCancelled.set(true); editor.cancelImportInProgress(); openJob?.cancel(); opening = null },
        )
    }
    choosing?.let { (item, pdfs) ->
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text(stringResource(R.string.zotero_choose_pdf)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    pdfs.forEach { pdf ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clip(MaterialTheme.shapes.small)
                                .clickable { choosing = null; library?.let { openAttachment(it, item, pdf) } }
                                .padding(horizontal = Space.s8),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.padding(vertical = Space.s6)) {
                                Text(pdf.title.ifBlank { pdf.filename }, color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (pdf.title.isNotBlank() && pdf.filename.isNotBlank() && pdf.filename != pdf.title) {
                                    Text(pdf.filename, color = palette.textDim.toComposeColor(), fontSize = TextSize.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s8), verticalAlignment = Alignment.CenterVertically) {
            val libs = libraries.orEmpty()
            if (libs.size > 1) {
                var open by remember { mutableStateOf(false) }
                Box {
                    ChooserButton(library?.let { libraryName(it) } ?: "") { open = true }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        libs.forEach { lib -> MenuItem(libraryName(lib), onClick = { open = false; if (lib.key != library?.key) { collection = null; library = lib } }) }
                    }
                }
            }
            if (collections.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f, fill = false)) {
                    ChooserButton(collection?.name ?: stringResource(R.string.zotero_all_items)) { open = true }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        MenuItem(stringResource(R.string.zotero_all_items), onClick = { open = false; collection = null })
                        collections.forEach { (c, depth) ->
                            MenuItem(" ".repeat(depth) + c.name, onClick = { open = false; collection = c })
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            ExplorerSearchField(query, { query = it }, expandedWidth = 280.dp)
            Tooltip(stringResource(R.string.zotero_refresh)) {
                IconButton(onClick = { reload++ }) {
                    Icon(XnotesIcons.rotateRight, stringResource(R.string.zotero_refresh), tint = palette.text.toComposeColor(), modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.height(Space.s8))
        val shown = list
        when {
            error != null && shown == null -> Column(Modifier.fillMaxWidth().padding(Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s12)) {
                Text(error.orEmpty(), color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge)
                OutlinedButton(onClick = { reload++ }, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(stringResource(R.string.zotero_retry), fontSize = TextSize.body) }
            }
            shown == null -> Text(stringResource(R.string.zotero_loading), color = palette.textDim.toComposeColor(), fontSize = TextSize.bodyLarge, modifier = Modifier.padding(Space.s16))
            shown.items.isEmpty() -> Text(
                stringResource(if (query.isBlank()) R.string.zotero_empty else R.string.zotero_no_match),
                color = palette.textDim.toComposeColor(), fontSize = TextSize.bodyLarge, modifier = Modifier.padding(Space.s16),
            )
            else -> {
                val state = rememberLazyListState()
                val nearEnd by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= state.layoutInfo.totalItemsCount - 5 } == true } }
                LaunchedEffect(nearEnd, shown) { if (nearEnd) loadMore() }
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    items(shown.items, key = { it.key }) { item ->
                        ZoteroRow(item, opened = item.key in linked) { openItem(item) }
                        HorizontalDivider(color = palette.border.toComposeColor())
                    }
                    if (shown.nextStart != null) item {
                        Text(stringResource(R.string.zotero_loading), color = palette.textDim.toComposeColor(), fontSize = TextSize.body, modifier = Modifier.padding(Space.s16))
                    }
                }
            }
        }
    }
}

@Composable
private fun libraryName(lib: ZoteroLibrary): String = if (lib.group) lib.name else stringResource(R.string.zotero_my_library)

@Composable
private fun ChooserButton(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier.heightIn(min = MinTouchTarget).clip(shape).border(1.dp, palette.border.toComposeColor(), shape)
            .clickable(onClick = onClick).padding(horizontal = Space.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s6),
    ) {
        Text(label, color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
        Icon(XnotesIcons.chevronDown, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ZoteroRow(item: ZoteroItem, opened: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable(onClick = onClick).padding(horizontal = Space.s12, vertical = Space.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s12),
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, color = palette.text.toComposeColor(), fontSize = TextSize.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val line = creatorsLine(item)
            if (line.isNotEmpty()) Text(line, color = palette.textDim.toComposeColor(), fontSize = TextSize.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (opened) Icon(XnotesIcons.bookOpen, stringResource(R.string.zotero_opened_before), tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
    }
}
