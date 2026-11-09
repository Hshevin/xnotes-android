package com.xnotes.storage

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundlePdf
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.DocumentCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeDecodedContractTest {
    private fun withNonPdfLegacyDocument(block:(com.xnotes.core.model.Document,BundleReferenceInfo)->Unit) {
        val root=Files.createTempDirectory("decoded-no-pdf-contract").toFile()
        try {
            val bundle=File(root,"legacy.xnote")
            ZipOutputStream(bundle.outputStream()).use {zip->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"format":"xnote","version":1,"pages":[{"items":[]}]}""".toByteArray());zip.closeEntry()
            }
            val info=BundleReferenceInfo.inspectStrict(bundle)
            assertNull(info.header.identity);assertNull(info.originalPdfHash)
            val document=bundle.inputStream().use {DocumentCodec(FakeImageCodec(),FakeTextMeasurer()).read(it)}
            assertNull(document.pdfFile)
            block(document,info)
        } finally {root.deleteRecursively()}
    }

    @Test fun noIdentityNonPdfLegacyDocumentRejectsADanglingPdfResource()=withNonPdfLegacyDocument {document,info->
        document.identity=document.identity.copy(pdfResource="unused PDF resource")
        val error=assertThrows(ReferenceContractViolation::class.java) {NativeDecodedContract.note(document,info,null) {}}
        assertEquals(StorageMsg.PDF_RESOURCE_MISMATCH,error.reason)
    }

    @Test fun noIdentityNonPdfLegacyDocumentWithGeneratedUuidAndNoResourceRemainsReadable()=withNonPdfLegacyDocument {document,info->
        assertNull(document.identity.pdfResource)
        NativeDecodedContract.note(document,info,null) {}
    }

    @Test fun legacyHeaderWithoutIdentityRejectsDifferentPdfResourceEvenWhenOriginalBytesMatch() {
        val root=Files.createTempDirectory("decoded-pdf-contract").toFile()
        try {
            val pdf="%PDF-unchanged legacy original".toByteArray()
            val bundle=File(root,"legacy.xnote")
            ZipOutputStream(bundle.outputStream()).use {zip->
                zip.putNextEntry(ZipEntry(BundlePdf.ENTRY));zip.write(pdf);zip.closeEntry()
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"items":[]}]}""".toByteArray());zip.closeEntry()
            }
            val before=bundle.readBytes()
            val info=BundleReferenceInfo.inspectStrict(bundle)
            assertNull(info.header.identity)
            val document=bundle.inputStream().use {DocumentCodec(FakeImageCodec(),FakeTextMeasurer()).read(it,pdfDir=root)}
            val original=requireNotNull(document.pdfFile)
            assertEquals(info.originalPdfHash,BundleMigration.hash(original))
            document.identity=document.identity.copy(pdfResource="f".repeat(64),externalPdf=true)

            val error=assertThrows(ReferenceContractViolation::class.java) {
                NativeDecodedContract.note(document,info,null) {}
            }
            assertEquals(StorageMsg.PDF_RESOURCE_MISMATCH,error.reason)
            assertArrayEquals(pdf,original.readBytes())
            assertArrayEquals(before,bundle.readBytes())

            document.identity=document.identity.copy(pdfResource=info.originalPdfHash)
            NativeDecodedContract.note(document,info,null) {}
        } finally {root.deleteRecursively()}
    }
}
