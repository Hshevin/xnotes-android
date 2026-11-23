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
}
