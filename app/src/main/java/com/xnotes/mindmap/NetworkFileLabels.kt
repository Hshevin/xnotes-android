package com.xnotes.mindmap

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.xnotes.R

@Composable
internal fun networkFileKindLabel(kind:NetworkFileKind):String=stringResource(when(kind) {
    NetworkFileKind.NOTE->R.string.network_filter_kind_note
    NetworkFileKind.CANVAS->R.string.network_filter_kind_canvas
    NetworkFileKind.MAP->R.string.network_filter_kind_map
    NetworkFileKind.PDF_ORIGINAL->R.string.network_filter_kind_pdf_original
})

/** A document title can be shared by a note and its original; the kind stays visible in choices. */
@Composable
internal fun networkFileTitle(file:LocalNetworkFile):String {
    val title=file.observedNames.firstOrNull().orEmpty().ifBlank {stringResource(R.string.network_file)}
    return file.kind?.let {stringResource(R.string.network_name_with_kind,title,networkFileKindLabel(it))} ?: title
}
