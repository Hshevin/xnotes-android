package com.xnotes.storage.lite

import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import okio.Path
import kotlin.time.Clock

internal class UnreadablePdf : Exception()

/** One size in PDF points per source page. A null entry means that page could not be measured. */
internal expect fun pdfPageSizes(pdf: Path): List<Pair<Double, Double>?>?

internal fun importLitePdf(file: AssetFile): Document {
    val sizes = pdfPageSizes(file.assetPath())
    if (sizes.isNullOrEmpty()) throw UnreadablePdf()
    val doc = Document.blank(count = 0)
    doc.pdfFile = file
    doc.created = Clock.System.now().toEpochMilliseconds()
    sizes.forEachIndexed { index, size ->
        val (width, height) = if (size != null && size.first >= 1 && size.second >= 1) {
            size.first / 72.0 * doc.dpi to size.second / 72.0 * doc.dpi
        } else {
            doc.blankPageSize(doc.pages.lastOrNull())
        }
        doc.pages.add(Page(width, height, pdfPage = index))
    }
    return doc
}
