package com.xnotes.storage

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.openRead
import okio.*
import kotlin.test.assertFailsWith
import com.xnotes.format.*
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundlePdf
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.DocumentCodec
import com.xnotes.core.testkit.*
import kotlin.test.Test

class NativeDecodedContractTest : BundleTestFiles() {
    private fun withNonPdfLegacyDocument(block:(com.xnotes.core.model.Document,BundleReferenceInfo)->Unit) {
        val root=testDirectory("decoded-no-pdf-contract")
        try {
            val bundle=root.child("legacy.xnote")
            bundleWriter(bundle.fileSink()).use {zip->
                zip.putDeflated("manifest.json","""{"format":"xnote","version":1,"pages":[{"items":[]}]}""".encodeToByteArray())
            }
            val info=BundleReferenceInfo.inspectStrict(bundle)
            assertNull(info.header.identity);assertNull(info.originalPdfHash)
            val document=bundle.input().use {DocumentCodec(FakeImageCodec(),FakeTextMeasurer()).read(it)}
            assertNull(document.pdfFile)
            block(document,info)
        } finally {root.deleteTree()}
    }

    @Test fun noIdentityNonPdfLegacyDocumentRejectsADanglingPdfResource()=withNonPdfLegacyDocument {document,info->
        document.identity=document.identity.let { BundleIdentity(it.documentId, externalPdf = it.externalPdf, pdfResource="unused PDF resource") }
        val error=assertFailsWith<ReferenceContractViolation> {NativeDecodedContract.note(document,info,null) {}}
        assertEquals(StorageMsg.PDF_RESOURCE_MISMATCH,error.reason)
    }

    @Test fun noIdentityNonPdfLegacyDocumentWithGeneratedUuidAndNoResourceRemainsReadable()=withNonPdfLegacyDocument {document,info->
        assertNull(document.identity.pdfResource)
        NativeDecodedContract.note(document,info,null) {}
    }

    @Test fun legacyHeaderWithoutIdentityRejectsDifferentPdfResourceEvenWhenOriginalBytesMatch() {
        val root=testDirectory("decoded-pdf-contract")
        try {
            val pdf="%PDF-unchanged legacy original".encodeToByteArray()
            val bundle=root.child("legacy.xnote")
            bundleWriter(bundle.fileSink()).use {zip->
                zip.putDeflated(BundlePdf.ENTRY,pdf)
                zip.putDeflated("manifest.json","""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"items":[]}]}""".encodeToByteArray())
            }
            val before=bundle.fileBytes()
            val info=BundleReferenceInfo.inspectStrict(bundle)
            assertNull(info.header.identity)
            val document=bundle.input().use {DocumentCodec(FakeImageCodec(),FakeTextMeasurer()).read(it,pdfDir=root)}
            val original=requireNotNull(document.pdfFile)
            assertEquals(info.originalPdfHash,BundleMigration.hash(original))
            document.identity=document.identity.let { BundleIdentity(it.documentId, pdfResource="f".repeat(64),externalPdf=true) }

            val error=assertFailsWith<ReferenceContractViolation> {
                NativeDecodedContract.note(document,info,null) {}
            }
            assertEquals(StorageMsg.PDF_RESOURCE_MISMATCH,error.reason)
            assertArrayEquals(pdf,original.fileBytes())
            assertArrayEquals(before,bundle.fileBytes())

            document.identity=document.identity.let { BundleIdentity(it.documentId, externalPdf = it.externalPdf, pdfResource=info.originalPdfHash) }
            NativeDecodedContract.note(document,info,null) {}
        } finally {root.deleteTree()}
    }
}
