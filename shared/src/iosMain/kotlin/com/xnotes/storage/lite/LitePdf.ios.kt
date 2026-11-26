package com.xnotes.storage.lite

import com.xnotes.nativelibs.Pdfium
import okio.Path

internal actual fun pdfPageSizes(pdf: Path): List<Pair<Double, Double>?>? =
    Pdfium.openFile(pdf.toString())?.use { document ->
        List(document.pageCount) { index ->
            try { document.pageSize(index) } catch (_: IllegalStateException) { null }
        }
    }
