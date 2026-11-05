package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Document
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class BundlePdfTest {
    private fun scenario(block: (File, File, File, BundleIdentity) -> Unit) {
        val dir = Files.createTempDirectory("reference-pdf").toFile()
        try {
            val pdf = File(dir, "original.pdf").apply { writeBytes("%PDF-1.7\noriginal\u0000".toByteArray()) }
            val identity = BundleIdentity(pdfResource = BundleMigration.hash(pdf))
            val source = File(dir, "old.xnote")
            ZipOutputStream(source.outputStream()).use { zip ->
                val manifest = """{"format":"xnote","version":1,"has_pdf":true,"pages":[],"future":{"pressure":0.1234567890123456789}}"""
                for ((name, bytes) in listOf("manifest.json" to manifest.toByteArray(), BundlePdf.ENTRY to pdf.readBytes(), "future/raw" to byteArrayOf(1,2,3))) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
                identity.write(zip)
            }
            block(dir, source, pdf, identity)
        } finally { dir.deleteRecursively() }
    }
    @Test fun migrationAndExportPreserveEveryPayloadAndIdentity() = scenario { dir, source, pdf, identity ->
        val before = BundleMigration.fingerprints(source)
        val original = source.readBytes()
        val staged = BundlePdf.prepare(source, dir, identity, pdf)
        assertArrayEquals(original, source.readBytes())
        assertFalse(BundleMigration.fingerprints(staged.file).containsKey(BundlePdf.ENTRY))
        BundleMigration.publish(staged, source)
        assertTrue(BundleIdentity.read(source)!!.externalPdf)
        val exported = File(dir, "export.xnote")
        exported.outputStream().use { BundlePdf.write(source, it, BundleIdentity.read(source)!!, pdf, false) }
        assertEquals(before, BundleMigration.fingerprints(exported))
        assertEquals(identity, BundleIdentity.read(exported))
    }
    @Test fun corruptOriginalCannotReplaceOldNote() = scenario { dir, source, pdf, identity ->
        val before = source.readBytes()
        pdf.appendText("corruption")
        assertThrows(IllegalArgumentException::class.java) { BundlePdf.prepare(source, dir, identity, pdf) }
        assertArrayEquals(before, source.readBytes())
        assertFalse(dir.list()!!.any { it.startsWith("pdf-reference-") })
    }
    @Test fun concurrentEditRejectsMigrationPublication() = scenario { dir, source, pdf, identity ->
        val staged = BundlePdf.prepare(source, dir, identity, pdf)
        source.appendText("external change")
        val changed = source.readBytes()
        assertThrows(IllegalStateException::class.java) { BundleMigration.publish(staged, source) }
        assertArrayEquals(changed, source.readBytes())
    }
    @Test fun localCodecReopensByReferenceAndPortableCodecEmbeds() = scenario { dir, _, pdf, identity ->
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val doc = Document().apply { pdfFile = pdf; this.identity = identity }
        val local = File(dir, "local.xnote")
        local.outputStream().use { codec.write(doc, it, embedPdf = false) }
        assertFalse(BundleMigration.fingerprints(local).containsKey(BundlePdf.ENTRY))
        assertThrows(IllegalArgumentException::class.java) { local.inputStream().use { codec.read(it, dir) } }
        codec.resolvePdf = { id -> assertEquals(identity.pdfResource, id); pdf }
        val reopened = local.inputStream().use { codec.read(it, dir) }
        assertEquals(pdf, reopened.pdfFile)
        val portable = File(dir, "portable.xnote")
        portable.outputStream().use { codec.write(reopened, it) }
        assertEquals(identity.pdfResource, BundleMigration.fingerprints(portable)[BundlePdf.ENTRY])
        assertFalse(BundleIdentity.read(portable)!!.externalPdf)
        val standalone = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val imported = portable.inputStream().use { standalone.read(it, dir) }
        assertArrayEquals(pdf.readBytes(), imported.pdfFile!!.readBytes())
    }
    @Test fun referenceCopyPreservesResourceButChangesDocumentId() = scenario { dir, source, pdf, identity ->
        BundleMigration.publish(BundlePdf.prepare(source, dir, identity, pdf), source)
        val copy = BundleMigration.prepare(source, dir, copy = true)
        assertTrue(copy.identity.externalPdf)
        assertEquals(identity.pdfResource, copy.identity.pdfResource)
        assertNotEquals(identity.documentId, copy.identity.documentId)
    }
    @Test fun portableExportCannotSilentlyOmitMissingReferencedPdf() = scenario { dir, source, pdf, identity ->
        BundleMigration.publish(BundlePdf.prepare(source, dir, identity, pdf), source)
        assertThrows(IllegalArgumentException::class.java) {
            BundlePdf.write(source, java.io.ByteArrayOutputStream(), BundleIdentity.read(source)!!, null, false)
        }
    }
    @Test fun failedExternalMigrationRetainsFullOriginalAndCanRestore() = scenario { dir, source, pdf, identity ->
        val before = source.readBytes()
        val staged = BundlePdf.prepare(source, dir, identity, pdf)
        val journal = com.xnotes.storage.MigrationPublication(File(dir, "recovery"))
        journal.stage(source, staged.file)
        val broken = object : com.xnotes.storage.MigrationPublication.Target {
            override fun read() = source.inputStream()
            override fun write(candidate: File) { source.writeText("partial"); throw java.io.IOException("disk full") }
        }
        assertThrows(com.xnotes.storage.MigrationPublication.RecoveryRequired::class.java) { journal.publish(broken) }
        assertTrue(journal.pending)
        journal.restore(object : com.xnotes.storage.MigrationPublication.Target {
            override fun read() = source.inputStream()
            override fun write(candidate: File) { candidate.copyTo(source, overwrite = true) }
        })
        assertArrayEquals(before, source.readBytes())
        assertEquals(identity.pdfResource, BundleMigration.hash(pdf))
        assertFalse(journal.pending)
    }
    @Test fun traversalReferenceRejectedBeforeResolving() {
        assertThrows(IllegalArgumentException::class.java) { BundleIdentity(pdfResource = "../../outside.pdf", externalPdf = true) }
    }

}
