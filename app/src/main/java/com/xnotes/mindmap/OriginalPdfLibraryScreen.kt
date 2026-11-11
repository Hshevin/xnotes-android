package com.xnotes.mindmap

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.xnotes.R
import com.xnotes.storage.OriginalPdfCatalog
import com.xnotes.ui.ExplorerSearchField
import com.xnotes.ui.Tooltip
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.*

/** Restore picker for the library pane, kept above Home so a pane rebuild never drops its result. */
private val LocalOriginalPdfChooser=staticCompositionLocalOf<(String)->Unit> {{}}

/**
 * The library is a Home pane beside the sidebar, like AI and Trash, so the native host stays composed
 * underneath; its picker lives here, outside Home, to survive the read-only reader replacing Home.
 */
@Composable
internal fun OriginalPdfLibraryHost(state:OriginalPdfLibraryState,nativeContent:@Composable ()->Unit) {
    var pickerRequest by rememberSaveable {mutableStateOf<String?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        val id=pickerRequest;pickerRequest=null
        if(id!=null) state.receiveRestoreFile(id,uri)
    }
    val chooseFile:(String)->Unit={id->
        if(pickerRequest==null && state.restoreRequest?.id==id && state.needsRestoreFile) {
            pickerRequest=id
            try {picker.launch(arrayOf("application/pdf"))}
            catch(_:Exception) {pickerRequest=null;state.reportUnavailable()}
        }
    }
    CompositionLocalProvider(LocalOriginalPdfChooser provides chooseFile) {nativeContent()}
}

/** Home's original-library pane; the host closes the library when Home moves to another view. */
@Composable
internal fun OriginalPdfLibraryPane(state:OriginalPdfLibraryState,
    onOpen:(OriginalPdfCatalog.Observation,()->Boolean)->Boolean,sidebarOpen:Boolean,onShowSidebar:()->Unit) {
    val chooseFile=LocalOriginalPdfChooser.current
    val palette=LocalPalette.current
    var query by rememberSaveable {mutableStateOf("")}
    var deleting by remember {mutableStateOf<OriginalPdfCatalog.Observation?>(null)}
    var restoring by remember {mutableStateOf<OriginalPdfCatalog.Observation?>(null)}
    CompositionLocalProvider(LocalInkBusy provides false) {
        Column(Modifier.fillMaxSize().imePadding()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val searchRoom=maxWidth-MinTouchTarget*(if(sidebarOpen) 2 else 3)
                Row(Modifier.fillMaxWidth().heightIn(min=OriginalPdfLibraryHeaderHeight),verticalAlignment=Alignment.CenterVertically) {
                    if(!sidebarOpen) Tooltip(stringResource(R.string.show_sidebar)) {
                        IconButton(onClick=onShowSidebar) {
                            Icon(XnotesIcons.menu,stringResource(R.string.show_sidebar),tint=palette.text.toComposeColor(),modifier=Modifier.size(Space.s24))
                        }
                    }
                    Text(stringResource(R.string.original_pdf_library_title),color=palette.text.toComposeColor(),fontWeight=FontWeight.Bold,
                        fontSize=TextSize.headline,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                    ExplorerSearchField(query,{query=it},expandedWidth=searchRoom.coerceIn(Space.s40,OriginalPdfLibrarySearchWidth))
                    MapButton(R.string.original_pdf_refresh,enabled=!state.busy && state.restoreRequest==null) {state.refresh()}
                }
            }
            HorizontalDivider(color=palette.border.toComposeColor())
            val needle=query.trim()
            val shown=if(needle.isEmpty()) state.records else state.records.filter {it.record.title.contains(needle,ignoreCase=true)}
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(vertical=Space.s16),verticalArrangement=Arrangement.spacedBy(Space.s12)) {
                item {Text(stringResource(R.string.original_pdf_library_notice),fontSize=TextSize.body)}
                item {Text(stringResource(R.string.original_pdf_backup_notice),fontSize=TextSize.caption,color=palette.textDim.toComposeColor())}
                if(state.busy) item {Text(stringResource(R.string.graph_ref_preparing),fontSize=TextSize.body)}
                state.failure?.let {failure->item {Text(stringResource(if(failure==OriginalPdfLibraryIssue.CHANGED)
                    R.string.original_pdf_changed else R.string.original_pdf_unavailable),fontSize=TextSize.body)}}
                state.restoreRequest?.takeIf {state.needsRestoreFile}?.let {request->item {
                    Text(stringResource(R.string.original_pdf_missing),fontSize=TextSize.body)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        MapButton(R.string.original_pdf_choose_file,enabled=!state.busy) {chooseFile(request.id)}
                        MapButton(R.string.cancel,enabled=!state.busy) {state.cancelRestore(request.id)}
                    }
                }}
                if(!state.busy && state.records.isEmpty() && state.failure==null) item {
                    Text(stringResource(R.string.original_pdf_empty),fontSize=TextSize.body)
                } else if(state.records.isNotEmpty() && shown.isEmpty()) item {
                    Text(stringResource(R.string.no_matches),fontSize=TextSize.body,color=palette.textDim.toComposeColor())
                }
                items(shown,key={it.record.id}) {observation->
                    val record=observation.record
                    Column(verticalArrangement=Arrangement.spacedBy(Space.s4)) {
                        Text(record.title,fontSize=TextSize.title)
                        Text(stringResource(R.string.original_pdf_pages,record.pageCount),fontSize=TextSize.caption,color=palette.textDim.toComposeColor())
                        if(record.deleted) Text(stringResource(R.string.original_pdf_deleted),fontSize=TextSize.caption,color=palette.textDim.toComposeColor())
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            if(!record.deleted) {
                                MapButton(R.string.original_pdf_open,enabled=!state.busy && state.restoreRequest==null) {
                                    val current=state.captureOpen(observation)
                                    if(current==null || !onOpen(observation,current)) state.reportChanged()
                                }
                                MapButton(R.string.original_pdf_delete,enabled=!state.busy && state.restoreRequest==null) {deleting=observation}
                            }
                            MapButton(if(record.deleted) R.string.original_pdf_restore else R.string.original_pdf_relink,
                                enabled=!state.busy && state.restoreRequest==null) {restoring=observation}
                        }
                        HorizontalDivider(color=palette.border.toComposeColor())
                    }
                }
                if(state.limited) item {Text(stringResource(R.string.original_pdf_limited),fontSize=TextSize.caption)}
            }
        }
    }
    deleting?.let {expected->AlertDialog(onDismissRequest={deleting=null},
        title={Text(stringResource(R.string.original_pdf_delete_title),fontSize=TextSize.heading)},
        text={Text(stringResource(R.string.original_pdf_delete_notice),fontSize=TextSize.body)},
        confirmButton={MapButton(R.string.original_pdf_delete) {
            deleting=null;if(!state.delete(expected)) state.reportChanged()
        }},dismissButton={MapButton(R.string.cancel) {deleting=null}})}
    restoring?.let {expected->AlertDialog(onDismissRequest={restoring=null},
        title={Text(stringResource(if(expected.record.deleted) R.string.original_pdf_restore else R.string.original_pdf_relink),fontSize=TextSize.heading)},
        text={Text(stringResource(R.string.original_pdf_restore_notice),fontSize=TextSize.body)},
        confirmButton={MapButton(if(expected.record.deleted) R.string.original_pdf_restore else R.string.original_pdf_relink) {
            restoring=null;if(state.requestRestore(expected)==null) state.reportChanged()
        }},dismissButton={MapButton(R.string.cancel) {restoring=null}})}
}
