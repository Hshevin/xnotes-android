package com.xnotes.mindmap

import androidx.activity.compose.BackHandler
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
import com.xnotes.R
import com.xnotes.storage.OriginalPdfCatalog
import com.xnotes.ui.theme.*

/** The library replaces the native host while open; it never overlays an on-top ink Surface. */
@Composable
internal fun OriginalPdfLibraryHost(state:OriginalPdfLibraryState,
    onOpen:(OriginalPdfCatalog.Observation,()->Boolean)->Boolean,nativeContent:@Composable ()->Unit) {
    var pickerRequest by rememberSaveable {mutableStateOf<String?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        val id=pickerRequest;pickerRequest=null
        if(id!=null) state.receiveRestoreFile(id,uri)
    }
    if(!state.visible) {nativeContent();return}
    BackHandler {state.close()}
    val dark=LocalPalette.current.isDark
    XnotesTheme(monochromePalette(dark)) {
        CompositionLocalProvider(LocalInkBusy provides false) {
            OriginalPdfLibraryScreen(state,onOpen,chooseFile={id->
                if(pickerRequest==null && state.restoreRequest?.id==id && state.needsRestoreFile) {
                    pickerRequest=id
                    try {picker.launch(arrayOf("application/pdf"))}
                    catch(_:Exception) {pickerRequest=null;state.reportUnavailable()}
                }
            })
        }
    }
}

@Composable
private fun OriginalPdfLibraryScreen(state:OriginalPdfLibraryState,
    onOpen:(OriginalPdfCatalog.Observation,()->Boolean)->Boolean,chooseFile:(String)->Unit) {
    var deleting by remember {mutableStateOf<OriginalPdfCatalog.Observation?>(null)}
    var restoring by remember {mutableStateOf<OriginalPdfCatalog.Observation?>(null)}
    Surface(Modifier.fillMaxSize(),color=LocalPalette.current.panel.toComposeColor()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=Space.s8),
                verticalAlignment=Alignment.CenterVertically) {
                MapButton(R.string.map_close) {state.close()}
                Text(stringResource(R.string.original_pdf_library_title),fontSize=TextSize.title,modifier=Modifier.padding(Space.s12))
                MapButton(R.string.original_pdf_refresh,enabled=!state.busy && state.restoreRequest==null) {state.refresh()}
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize().padding(Space.s16),verticalArrangement=Arrangement.spacedBy(Space.s12)) {
                item {Text(stringResource(R.string.original_pdf_library_notice),fontSize=TextSize.body)}
                item {Text(stringResource(R.string.original_pdf_backup_notice),fontSize=TextSize.caption)}
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
                }
                items(state.records,key={it.record.id}) {observation->
                    val record=observation.record
                    Column(verticalArrangement=Arrangement.spacedBy(Space.s4)) {
                        Text(record.title,fontSize=TextSize.title)
                        Text(stringResource(R.string.original_pdf_pages,record.pageCount),fontSize=TextSize.caption)
                        if(record.deleted) Text(stringResource(R.string.original_pdf_deleted),fontSize=TextSize.caption)
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
                        HorizontalDivider()
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
