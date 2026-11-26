package com.xnotes.format

import com.xnotes.core.util.DocumentKind
import java.io.File
import java.util.zip.ZipFile

internal data class TestBundleMetadata(val header: BundleHeader, val format: String,
    val orderedPageIds: List<String>, val embeddedPdfHash: String?) {
    val originalPdfHash get() = header.identity?.pdfResource ?: embeddedPdfHash
    val pageIds get() = orderedPageIds.toSet()
    val kind get() = if (format == "xnote") DocumentKind.NOTE else DocumentKind.CANVAS

    companion object {
        fun read(file: File): TestBundleMetadata = ZipFile(file).use { zip ->
            val header = BundleHeader.read(file)
            val open = { zip.getInputStream(requireNotNull(zip.getEntry("manifest.json"))) }
            val ids = if (header.anchors != null) AnchorManifest.inspect(open, true).orderedPageIds else emptyList()
            val pdf = zip.getEntry(BundlePdf.ENTRY)?.let { zip.getInputStream(it).use { input -> BundleMigration.digest(input) { false } } }
            TestBundleMetadata(header, open().use { bundleFormat(it) { false } }, ids, pdf)
        }
    }
}
