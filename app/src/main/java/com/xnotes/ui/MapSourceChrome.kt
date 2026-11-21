package com.xnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.PopupProperties
import com.xnotes.R
import com.xnotes.core.model.Page
import com.xnotes.map.SourceRef
import com.xnotes.map.source.AndroidSourceCatalogs
import com.xnotes.map.source.PageIdEnableResult
import com.xnotes.map.source.PageLink
import com.xnotes.map.source.PageLinkOffer
import com.xnotes.map.source.SourceLinks
import com.xnotes.map.source.SourceResolver
import com.xnotes.map.source.SourceStatus
import com.xnotes.platform.SystemClipboard
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.MinTouchTarget
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The selected node's sources: each with where it stands now (file and page as they are, or why
 * it cannot be opened), opened by a tap, removed by its trash button; and "Paste link" for a
 * link copied from a page menu.
 */
@Composable
internal fun MapSourcesPanel(editor: InfiniteEditor, onDismiss: () -> Unit) {
    val map = editor.map
    editor.mapVersion
    val node = map.selected ?: return onDismiss()
    val palette = LocalPalette.current
    val context = LocalContext.current
    val sources = map.selectedSources
    val resolver = remember { SourceResolver(AndroidSourceCatalogs(context)) }
    var statuses by remember { mutableStateOf<Map<String, SourceStatus>>(emptyMap()) }
    LaunchedEffect(sources) {
        statuses = runCatching { withContext(Dispatchers.IO) { resolver.resolveAll(sources) } }.getOrDefault(emptyMap())
    }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, properties = PopupProperties(focusable = false)) {
        Column(Modifier.width(SOURCES_WIDTH).padding(horizontal = Space.s16, vertical = Space.s8)) {
            PopupTitle(stringResource(R.string.map2_sources))
            if (sources.isEmpty()) {
                Text(stringResource(R.string.map2_no_sources), color = palette.textDim.toComposeColor(), fontSize = TextSize.caption)
            }
            for (ref in sources) {
                val status = statuses[ref.id]
                Row(
                    Modifier.heightIn(min = MinTouchTarget).clickable(enabled = status is SourceStatus.Ready) {
                        onDismiss()
                        editor.onFollowMapSource?.invoke(node, ref)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (ref.kind == com.xnotes.map.SourceKind.CANVAS) XnotesIcons.mindMap else XnotesIcons.link, null,
                        tint = palette.textDim.toComposeColor(), modifier = Modifier.size(Space.s16),
                    )
                    Column(Modifier.weight(1f).padding(horizontal = Space.s8)) {
                        Text(sourceTitle(ref, status), fontSize = TextSize.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        sourceProblem(status)?.let {
                            Text(stringResource(it), color = palette.textDim.toComposeColor(), fontSize = TextSize.caption)
                        }
                    }
                    IconButton(onClick = { map.removeSource(ref.id) }) {
                        Icon(XnotesIcons.trash, stringResource(R.string.map2_remove_source), modifier = Modifier.size(Space.s20))
                    }
                }
            }
            HorizontalDivider(color = palette.border.toComposeColor())
            TextButton(onClick = {
                val ref = SystemClipboard.text(context)?.let { PageLink.parse(it) { UUID.randomUUID().toString() } }
                when {
                    ref == null -> editor.mapMessage(context.getString(R.string.map2_no_link))
                    map.addSources(listOf(ref)) -> editor.mapMessage(context.getString(R.string.map2_link_added))
                    else -> editor.mapMessage(context.getString(R.string.map2_link_already))
                }
            }, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(stringResource(R.string.map2_paste_link), fontSize = TextSize.body)
            }
        }
    }
}

/** A source's first line: the file and page as they are now when known, else the label kept when it was linked. */
@Composable
private fun sourceTitle(ref: SourceRef, status: SourceStatus?): String = when (status) {
    is SourceStatus.Ready -> status.pageNumber?.let { stringResource(R.string.map2_source_page, status.name, it) } ?: status.name
    is SourceStatus.PageDeleted -> status.name
    else -> ref.label.ifBlank { "?" }
}

/** Why a source cannot be opened; null when it can, or while it is being checked. */
internal fun sourceProblem(status: SourceStatus?): Int? = when (status) {
    null -> R.string.map2_source_checking
    is SourceStatus.Ready -> null
    is SourceStatus.PageDeleted -> R.string.map2_source_deleted
    SourceStatus.Missing -> R.string.map2_source_missing
    SourceStatus.Unavailable -> R.string.map2_source_unavailable
    SourceStatus.WrongKind -> R.string.map2_source_wrong_kind
}

/**
 * "Copy page link" for page [index] of the open note: copies at once when the page can be linked;
 * for a note without page ids, asks first and enables them (the note reloads), then copies.
 */
@Composable
internal fun CopyPageLinkFlow(editor: Editor, index: Int, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    val offeredDocument = remember(editor, index) { editor.state.document }
    val offeredPage = remember(offeredDocument, index) { offeredDocument.pages.getOrNull(index) }
    val offeredIdentity = remember(offeredDocument, index) { offeredDocument.identity }
    val offeredPath = remember(offeredDocument, index) { offeredDocument.path }
    fun page(): Page? = editor.state.document.pages.getOrNull(index)
    fun offer(): PageLinkOffer? = page()?.let { SourceLinks.forPage(editor.state.document, it) { UUID.randomUUID().toString() } }
    fun say(id: Int) { editor.message = context.getString(id) }
    /** Act on an offer; true when the flow is finished. */
    fun settle(o: PageLinkOffer?): Boolean {
        when (o) {
            is PageLinkOffer.Ready -> say(
                if (SystemClipboard.putText(context, o.ref.label, PageLink.encode(o.ref))) R.string.map2_link_copied else R.string.map2_open_failed,
            )
            PageLinkOffer.NeedsPageIds -> return false
            PageLinkOffer.Unsaved -> say(R.string.map2_link_unsaved)
            PageLinkOffer.PageNotSaved -> say(R.string.map2_link_page_not_saved)
            PageLinkOffer.NotInDocument, null -> say(R.string.map2_link_not_in_doc)
        }
        return true
    }

    LaunchedEffect(Unit) { if (settle(offer())) onDone() else asking = true }

    if (asking) {
        AlertDialog(
            onDismissRequest = { if (!working) { asking = false; onDone() } },
            title = { Text(stringResource(R.string.map2_enable_ids_title)) },
            text = { Text(stringResource(R.string.map2_enable_ids_body), fontSize = TextSize.body) },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    working = true
                    scope.launch {
                        val result = try {
                            if (editor.state.document === offeredDocument && offeredDocument.identity == offeredIdentity &&
                                offeredDocument.path == offeredPath && page() === offeredPage)
                                editor.enablePageIds() else PageIdEnableResult.CHANGED
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { PageIdEnableResult.FAILED }
                        working = false
                        asking = false
                        when (result) {
                            PageIdEnableResult.ENABLED -> settle(offer())
                            PageIdEnableResult.CHANGED -> say(R.string.map2_enable_changed)
                            PageIdEnableResult.FAILED -> say(R.string.map2_enable_failed)
                            PageIdEnableResult.RETAINED -> say(R.string.map2_enable_retained)
                        }
                        onDone()
                    }
                }) { Text(stringResource(R.string.map2_enable)) }
            },
            dismissButton = {
                TextButton(enabled = !working, onClick = { asking = false; onDone() }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private val SOURCES_WIDTH = Space.s48 * 7
