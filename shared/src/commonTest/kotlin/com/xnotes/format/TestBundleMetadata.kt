package com.xnotes.format

import com.xnotes.core.util.DocumentKind
import com.xnotes.core.pal.AssetFile
import okio.use

internal data class TestBundleMetadata(val header: BundleHeader, val format: String,
    val orderedPageIds: List<String>, val embeddedPdfHash: String?) {
    val originalPdfHash get() = header.identity?.pdfResource ?: embeddedPdfHash
    val pageIds get() = orderedPageIds.toSet()
    val kind get() = if (format == "xnote") DocumentKind.NOTE else DocumentKind.CANVAS

    companion object {
        fun read(file: AssetFile): TestBundleMetadata = bundleDirectory(file).use { zip ->
            val header = BundleHeader.read(file)
            val open = { zip.open("manifest.json") }
            val ids = if (header.anchors != null) AnchorManifest.inspectSource(open, true).orderedPageIds else emptyList()
            val pdf = zip.entry(BundlePdf.ENTRY)?.let { it.open().use { input -> BundleMigration.digest(input) { false } } }
            TestBundleMetadata(header, open().use { bundleFormat(it) { false } }, ids, pdf)
        }
    }
}
