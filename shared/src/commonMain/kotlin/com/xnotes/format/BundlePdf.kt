package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.KFiles
import okio.*

/** Byte-preserving PDF packaging; an independent copy also receives fresh document/page IDs. */
expect abstract class PdfStreamAdapters()

object BundlePdf : PdfStreamAdapters() {
    const val ENTRY = "assets/source.pdf"

    fun write(source:AssetFile,out:Sink,identity:BundleIdentity,pdf:AssetFile?,external:Boolean,cancelled:()->Boolean={false}) {
        if (identity.externalPdf || external) requireNotNull(pdf)
        val stamp = BundleMigration.stamp(source)
        rewriteBundle(source, out, BundleIdentity(identity.documentId,identity.pdfResource,external), cancelled,
            portablePdf = pdf?.takeUnless { external }, removePdf = external)
        check(!cancelled() && BundleMigration.stamp(source) == stamp)
    }

    fun prepare(source: AssetFile, directory: AssetFile, identity: BundleIdentity, pdf: AssetFile,
                cancelled: () -> Boolean = { false }): BundleMigration.Prepared {
        val stamp = BundleMigration.stamp(source)
        require(BundleHeader.read(source, cancelled).identity?.documentId == identity.documentId)
        require(bundleDirectory(source).use { it.entry(ENTRY)?.size == KFiles.system.metadata(pdf.assetPath()).size })
        val candidate = temporaryAsset("pdf-reference-", ".zip", directory)
        val target = BundleIdentity(identity.documentId,identity.pdfResource,true)
        try {
            candidate.durable { write(source, it, target, pdf, true, cancelled) }
            check(BundleMigration.stamp(source) == stamp)
            return BundleMigration.Prepared(candidate, target, stamp)
        } catch (error: Throwable) { candidate.remove(); throw error }
    }
}
