package com.xnotes.format

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Changes only PDF packaging and identity; ink and unknown extension payloads stay byte-identical. */
object BundlePdf {
    const val ENTRY = "assets/source.pdf"

    fun write(source: File, out: OutputStream, identity: BundleIdentity, pdf: File?, external: Boolean) {
        val before = BundleMigration.fingerprints(source)
        require("manifest.json" in before)
        if (external || identity.externalPdf) requireNotNull(pdf)
        pdf?.let { require(BundleMigration.hash(it) == identity.pdfResource) }
        ZipFile(source).use { zip ->
            ZipOutputStream(out).use { dest ->
                dest.setLevel(1)
                zip.entries().asSequence().filter { it.name != BundleIdentity.ENTRY && it.name != ENTRY }.forEach { entry ->
                    dest.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                    zip.getInputStream(entry).use { it.copyTo(dest) }
                    dest.closeEntry()
                }
                if (!external) {
                    if (pdf != null) {
                        // Avoid compressing large scans merely to make a portable copy.
                        val crc = java.util.zip.CRC32()
                        pdf.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; crc.update(b, 0, n) } }
                        dest.putNextEntry(ZipEntry(ENTRY).apply { method = ZipEntry.STORED; size = pdf.length(); compressedSize = size; this.crc = crc.value })
                        pdf.inputStream().use { it.copyTo(dest) }; dest.closeEntry()
                    } else zip.getEntry(ENTRY)?.let { entry ->
                        dest.putNextEntry(ZipEntry(ENTRY)); zip.getInputStream(entry).use { it.copyTo(dest) }; dest.closeEntry()
                    }
                }
                identity.copy(externalPdf = external).write(dest)
            }
        }
    }

    fun prepare(source: File, directory: File, identity: BundleIdentity, pdf: File): BundleMigration.Prepared {
        val hash = BundleMigration.hash(source)
        val before = BundleMigration.fingerprints(source)
        require(before[ENTRY] == identity.pdfResource) { com.xnotes.storage.StorageText.t(com.xnotes.storage.StorageMsg.PDF_RESOURCE_VERIFY_FAILED) }
        val candidate = File.createTempFile("pdf-reference-", ".zip", directory)
        try {
            FileOutputStream(candidate).use { write(source, it, identity, pdf, external = true) }
            java.io.RandomAccessFile(candidate, "rw").use { it.fd.sync() }
            val after = BundleMigration.fingerprints(candidate)
            check(after.filterKeys { it != BundleIdentity.ENTRY } == before.filterKeys { it != BundleIdentity.ENTRY && it != ENTRY })
            check(BundleIdentity.read(candidate) == identity.copy(externalPdf = true))
            check(BundleMigration.hash(source) == hash)
            return BundleMigration.Prepared(candidate, identity.copy(externalPdf = true), hash)
        } catch (e: Throwable) { candidate.delete(); throw e }
    }
}
