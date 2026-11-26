package com.xnotes.format

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipFile

/** Byte-preserving PDF packaging; an independent copy also receives fresh document/page IDs. */
object BundlePdf {
    const val ENTRY = "assets/source.pdf"

    fun write(source:File,out:OutputStream,identity:BundleIdentity,pdf:File?,external:Boolean,cancelled:()->Boolean={false}) {
        if (identity.externalPdf || external) requireNotNull(pdf)
        val stamp = BundleMigration.stamp(source)
        rewriteBundle(source, out, identity.copy(externalPdf = external), cancelled,
            portablePdf = pdf?.takeUnless { external }, removePdf = external)
        check(!cancelled() && BundleMigration.stamp(source) == stamp)
    }

    fun prepare(source: File, directory: File, identity: BundleIdentity, pdf: File,
                cancelled: () -> Boolean = { false }): BundleMigration.Prepared {
        val stamp = BundleMigration.stamp(source)
        require(BundleHeader.read(source, cancelled).identity?.documentId == identity.documentId)
        require(ZipFile(source).use { it.getEntry(ENTRY)?.size == pdf.length() })
        val candidate = File.createTempFile("pdf-reference-", ".zip", directory)
        val target = identity.copy(externalPdf = true)
        try {
            candidate.outputStream().use { write(source, it, target, pdf, true, cancelled) }
            check(BundleMigration.stamp(source) == stamp)
            return BundleMigration.Prepared(candidate, target, stamp)
        } catch (error: Throwable) { candidate.delete(); throw error }
    }
}
