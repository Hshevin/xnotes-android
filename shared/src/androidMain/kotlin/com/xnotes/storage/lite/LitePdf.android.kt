package com.xnotes.storage.lite

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import okio.Path

internal actual fun pdfPageSizes(pdf: Path): List<Pair<Double, Double>?>? = try {
    ParcelFileDescriptor.open(pdf.toFile(), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { document ->
            List(document.pageCount) { index ->
                try {
                    document.openPage(index).use { it.width.toDouble() to it.height.toDouble() }
                } catch (_: Exception) { null }
            }
        }
    }
} catch (_: Exception) { null }
