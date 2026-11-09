package com.xnotes.pen

import com.xnotes.format.BundleMigration
import com.xnotes.format.BundlePdf
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.storage.NativeDecodedContract
import com.xnotes.storage.ReferenceContractViolation
import com.xnotes.storage.StorageMsg
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Platform ZIP/JSON/codec coverage; must be executed on Android separately from host tests. */
class NativeDecodedContractAndroidTest {
    @Test fun noIdentityNonPdfLegacyDocumentOnlyAllowsTheGeneratedUuidWithoutPdfResource() {
        val root=File(PenTestRunner.instance.targetContext.cacheDir,"decoded-no-pdf-${UUID.randomUUID()}").apply {mkdirs()}
        try {
            val bundle=File(root,"legacy.xnote")
            ZipOutputStream(bundle.outputStream()).use {zip->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"format":"xnote","version":1,"pages":[{"items":[]}]}""".toByteArray());zip.closeEntry()
            }
            val info=BundleReferenceInfo.inspectStrict(bundle)
            assertNull(info.header.identity);assertNull(info.originalPdfHash)
            val document=bundle.inputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).read(it)}
            assertNull(document.pdfFile);assertNull(document.identity.pdfResource)
            NativeDecodedContract.note(document,info,null) {}
            document.identity=document.identity.copy(pdfResource="unused PDF resource")
            val error=assertThrows(ReferenceContractViolation::class.java) {NativeDecodedContract.note(document,info,null) {}}
            assertEquals(StorageMsg.PDF_RESOURCE_MISMATCH,error.reason)
        } finally {root.deleteRecursively()}
    }

    @Test fun legacyHeaderWithoutIdentityRejectsDifferentPdfResourceEvenWhenOriginalBytesMatch() {
        val root=File(PenTestRunner.instance.targetContext.cacheDir,"decoded-pdf-${UUID.randomUUID()}").apply {mkdirs()}
        try {
            val pdf="%PDF-unchanged legacy original".toByteArray()
            val bundle=File(root,"legacy.xnote")
            ZipOutputStream(bundle.outputStream()).use {zip->
                zip.putNextEntry(ZipEntry(BundlePdf.ENTRY));zip.write(pdf);zip.closeEntry()
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"items":[]}]}""".toByteArray());zip.closeEntry()
            }
            val before=bundle.readBytes();val info=BundleReferenceInfo.inspectStrict(bundle)
            assertNull(info.header.identity)
            val document=bundle.inputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).read(it,pdfDir=root)}
            val original=requireNotNull(document.pdfFile)
            assertEquals(info.originalPdfHash,BundleMigration.hash(original))
            document.identity=document.identity.copy(pdfResource="f".repeat(64),externalPdf=true)
            val error=assertThrows(ReferenceContractViolation::class.java) {NativeDecodedContract.note(document,info,null) {}}
            assertEquals(StorageMsg.PDF_RESOURCE_MISMATCH,error.reason)
            assertArrayEquals(pdf,original.readBytes());assertArrayEquals(before,bundle.readBytes())
            document.identity=document.identity.copy(pdfResource=info.originalPdfHash)
            NativeDecodedContract.note(document,info,null) {}
        } finally {root.deleteRecursively()}
    }
}
