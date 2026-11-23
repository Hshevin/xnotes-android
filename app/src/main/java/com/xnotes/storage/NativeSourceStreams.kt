package com.xnotes.storage

import com.xnotes.format.BundleHeader
import java.io.File
import java.io.InputStream
import okio.Source
import okio.buffer
import okio.source

internal fun DocumentRegistryIdentityEpoch.Companion.forDatabase(path: String) = forCanonicalDatabase(File(path).canonicalPath)

internal fun PdfResources.forSourceSnapshot(): OriginalPdfAssets = object : OriginalPdfAssets {
    override fun admitHeld(input: Source, name: String, expectedHash: String): OriginalPdfAssets.Held {
        val held = this@forSourceSnapshot.admitHeld(input.buffer().inputStream(),name,expectedHash)
        return OriginalPdfAssets.Held(held.file,held.lease)
    }
    override fun holdResolved(ids: Set<String>) = this@forSourceSnapshot.holdResolved(ids)
}

internal fun NativeSourceSnapshot.admitOriginalPdf(input: InputStream, name: String) = admitOriginalPdf(input.source(),name)
internal fun NativeReferenceReader.read(directory: File, tryHeader: () -> BundleHeader?, open: () -> InputStream,
    checkActive: () -> Unit = {}, check: (BundleHeader) -> Unit) = readSource(directory,tryHeader,{open().source()},checkActive,check)
