package com.xnotes.format

import com.xnotes.core.pal.*
import com.xnotes.kio.*
import okio.*
import kotlin.test.assertFailsWith

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Document
import com.xnotes.core.testkit.*
import kotlin.test.Test

class SharedBundlePdfTest : BundleTestFiles() {
    private fun scenario(block: (AssetFile, AssetFile, AssetFile, BundleIdentity) -> Unit) {
        val dir = testDirectory("reference-pdf")
        try {
            val pdf = dir.child("original.pdf").apply { setBytes("%PDF-1.7\noriginal\u0000".encodeToByteArray()) }
            val identity = BundleIdentity(pdfResource = BundleMigration.hash(pdf))
            val source = dir.child("old.xnote")
            bundleWriter(source.fileSink()).use { zip ->
                val manifest = """{"format":"xnote","version":1,"has_pdf":true,"pages":[],"future":{"pressure":0.1234567890123456789}}"""
                for ((name, bytes) in listOf("manifest.json" to manifest.encodeToByteArray(), BundlePdf.ENTRY to pdf.fileBytes(), "future/raw" to byteArrayOf(1,2,3))) {
                    zip.putDeflated(name,bytes)
                }
                zip.addIdentity(identity)
            }
            block(dir, source, pdf, identity)
        } finally { dir.deleteTree() }
    }
    @Test fun migrationAndExportPreserveEveryPayloadAndIdentity() = scenario { dir, source, pdf, identity ->
        val before = BundleMigration.fingerprints(source)
        val original = source.fileBytes()
        val staged = BundlePdf.prepare(source, dir, identity, pdf)
        assertArrayEquals(original, source.fileBytes())
        assertFalse(BundleMigration.fingerprints(staged.file).containsKey(BundlePdf.ENTRY))
        BundleMigration.publish(staged, source)
        assertTrue(readBundleIdentity(source)!!.externalPdf)
        val exported = dir.child("export.xnote")
        exported.fileSink().use { BundlePdf.write(source, it, readBundleIdentity(source)!!, pdf, false) }
        assertEquals(before, BundleMigration.fingerprints(exported))
        assertEquals(identity, readBundleIdentity(exported))
    }
    @Test fun corruptOriginalCannotReplaceOldNote() = scenario { dir, source, pdf, identity ->
        val before = source.fileBytes()
        pdf.appendUtf8("corruption")
        assertFailsWith<IllegalArgumentException> { BundlePdf.prepare(source, dir, identity, pdf) }
        assertArrayEquals(before, source.fileBytes())
        assertFalse(dir.fileNames().any { it.startsWith("pdf-reference-") })
    }
    @Test fun concurrentEditRejectsMigrationPublication() = scenario { dir, source, pdf, identity ->
        val staged = BundlePdf.prepare(source, dir, identity, pdf)
        source.appendUtf8("external change")
        val changed = source.fileBytes()
        assertFailsWith<IllegalStateException> { BundleMigration.publish(staged, source) }
        assertArrayEquals(changed, source.fileBytes())
    }
    @Test fun localCodecReopensByReferenceAndPortableCodecEmbeds() = scenario { dir, _, pdf, identity ->
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val doc = Document().apply { pdfFile = pdf; this.identity = identity }
        val local = dir.child("local.xnote")
        local.fileSink().use { codec.write(doc, it, embedPdf = false) }
        assertFalse(BundleMigration.fingerprints(local).containsKey(BundlePdf.ENTRY))
        assertFailsWith<IllegalArgumentException> { local.input().use { codec.read(it, dir) } }
        codec.resolvePdf = { id -> assertEquals(identity.pdfResource, id); pdf }
        val reopened = local.input().use { codec.read(it, dir) }
        assertEquals(pdf, reopened.pdfFile)
        val portable = dir.child("portable.xnote")
        portable.fileSink().use { codec.write(reopened, it) }
        assertEquals(identity.pdfResource, BundleMigration.fingerprints(portable)[BundlePdf.ENTRY])
        assertFalse(readBundleIdentity(portable)!!.externalPdf)
        val standalone = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val imported = portable.input().use { standalone.read(it, dir) }
        assertArrayEquals(pdf.fileBytes(), imported.pdfFile!!.fileBytes())
    }
    @Test fun missingReferencedPdfStillYieldsInkWithoutPdfDirectory() = scenario { dir, _, pdf, identity ->
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val doc = Document().apply { pdfFile = pdf; this.identity = identity }
        doc.pages.add(com.xnotes.core.model.Page.blank(com.xnotes.core.model.PageSize.A4, com.xnotes.core.model.Orientation.PORTRAIT, doc.dpi).apply { pdfPage = 0 })
        val local = dir.child("local.xnote")
        local.fileSink().use { codec.write(doc, it, embedPdf = false) }
        codec.resolvePdf = { error("original is gone") }
        assertFailsWith<IllegalStateException> { local.input().use { codec.read(it, dir) } }
        val inkOnly = local.input().use { codec.read(it, null) }
        assertNull(inkOnly.pdfFile)
        assertEquals(doc.pages.size, inkOnly.pages.size)
        assertEquals(0, inkOnly.pages[0].pdfPage)
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
        assertFailsWith<IllegalArgumentException> {
            BundlePdf.write(source, Buffer(), readBundleIdentity(source)!!, null, false)
        }
    }
    @Test fun traversalReferenceRejectedBeforeResolving() {
        assertFailsWith<IllegalArgumentException> { BundleIdentity(pdfResource = "../../outside.pdf", externalPdf = true) }
    }

}
