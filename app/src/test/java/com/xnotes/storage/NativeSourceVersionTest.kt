package com.xnotes.storage

import android.content.ContextWrapper
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleMigration
import com.xnotes.format.BundlePdf
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeSourceVersionTest {
    private val id="00000000-0000-4000-8000-000000000001"
    private val page="00000000-0000-4000-8000-000000000002"
    private class Fixture(val root:File) {
        val uri="content://source-version/document/a"
        val alias="content://source-version/tree/root/document/a"
        val other="content://source-version/document/b"
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val current=File(root,"current")
        var reads=0;var closes=0;var writes=0;var partialWrite=false
        var beforeWrite:(()->Unit)?=null
        var beforeRead:(()->Unit)?=null
        var validating:(()->Unit)?=null
        val storage=NotebookStorage(context,targetOverride={object:MigrationPublication.Target {
            override fun read():java.io.InputStream {
                beforeRead?.invoke()
                return object:FilterInputStream(current.inputStream()) {
                    init {reads++}
                    override fun close() {closes++;super.close()}
                }
            }
            override fun write(source:File) {
                writes++;beforeWrite?.invoke()
                if(partialWrite) {current.writeText("partial");throw IOException("provider failed")}
                source.copyTo(current,overwrite=true)
            }
        }},sourceValidationHook={validating?.invoke()})
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("native-source-version").toFile();val f=Fixture(root)
        try {block(f)} finally {f.storage.registry.close();root.deleteRecursively()}
    }
    private fun bundle(file:File,kind:DocumentKind=DocumentKind.NOTE,body:String="old",anchored:Boolean=kind==DocumentKind.NOTE,
                       pdf:String?=null,embedded:ByteArray?=null,documentId:String=id):File=file.also {
        ZipOutputStream(file.outputStream()).use {zip->
            BundleIdentity(documentId,pdf,externalPdf=pdf!=null && embedded==null).write(zip)
            if(anchored) AnchorMetadata(documentId).write(zip)
            if(embedded!=null) {zip.putNextEntry(ZipEntry(BundlePdf.ENTRY));zip.write(embedded);zip.closeEntry()}
            val content=if(kind==DocumentKind.NOTE) "\"pages\":[{${if(anchored) "\"reference_id\":\"$page\"," else ""}\"items\":[]}]" else "\"items\":[]"
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"format":"${kind.extension}","version":1,"has_pdf":${pdf!=null || embedded!=null},$content,"user_data":"$body"}""".toByteArray());zip.closeEntry()
        }
    }
    private fun noteCodec()=DocumentCodec(FakeImageCodec(),FakeTextMeasurer())

    @Test fun sourceVersionAndDecodedPageContractComeFromOnePrivateContainer()=scenario {f->
        bundle(f.current);val original=f.current.readBytes();val source=f.storage.openSource(f.uri,DocumentKind.NOTE)
        val version=source.version;val file=source.file
        assertEquals(1,f.reads);assertEquals(1,f.closes);assertEquals(BundleMigration.hash(f.current),version.sha256)
        assertEquals(id,version.documentId);assertEquals(DocumentKind.NOTE,version.kind)
        assertEquals(f.storage.registry.locationKey(f.uri),version.locationKey)
        bundle(f.current,body="external newer")
        val loaded=file.inputStream().use {noteCodec().read(it)};source.verify(loaded)
        assertEquals(page,loaded.pages.single().referenceIdentity.id);assertArrayEquals(original,file.readBytes())
        source.close();source.close();assertFalse(file.exists());assertTrue(f.current.exists())
        assertEquals(id,version.documentId)
        assertThrows(IllegalStateException::class.java) {source.file}
        assertThrows(IllegalStateException::class.java) {source.verify(loaded)}
        assertThrows(IllegalStateException::class.java) {source.retainPdfForAdoption()}
        assertEquals(0,f.writes)
    }
    @Test fun modelUuidMarkerAndPageSetMismatchAreRejectedWithoutMutatingModels()=scenario {f->
        bundle(f.current)
        f.storage.openSource(f.uri,DocumentKind.NOTE).use {source->
            val valid=source.file.inputStream().use {noteCodec().read(it)}
            val other=Document.blank().apply {enableReferenceAnchors()};val before=other.pages.single().referenceIdentity.id
            assertThrows(ReferenceContractViolation::class.java) {source.verify(other)};assertEquals(before,other.pages.single().referenceIdentity.id)
            valid.pages.add(Page(1.0,1.0));assertThrows(ReferenceContractViolation::class.java) {source.verify(valid)}
            assertNull(valid.pages.last().referenceIdentity.id)
            assertThrows(ReferenceContractViolation::class.java) {source.verify(InfiniteDocument())}
        }
        assertEquals(0,f.writes)
    }
    @Test fun canvasAndLegacyNotesHaveNoObjectOrAccidentalPageIdentity()=scenario {f->
        bundle(f.current,kind=DocumentKind.CANVAS)
        f.storage.openSource(f.uri,DocumentKind.CANVAS).use {source->
            val board=source.file.inputStream().use {CanvasCodec(FakeImageCodec(),FakeTextMeasurer()).read(it)}
            source.verify(board);assertEquals(DocumentKind.CANVAS,source.version.kind)
        }
        bundle(f.current,anchored=false)
        f.storage.openSource(f.uri,DocumentKind.NOTE).use {source->
            val doc=source.file.inputStream().use {noteCodec().read(it)};source.verify(doc)
            assertFalse(doc.hasReferenceAnchors);assertNull(doc.pages.single().referenceIdentity.id)
        }
        assertThrows(ReferenceContractViolation::class.java) {f.storage.openSource(f.uri,DocumentKind.CANVAS)}
    }
    @Test fun cancellationClosesSourceAndCleansOnlyItsSnapshot()=scenario {f->
        bundle(f.current);val bytes=f.current.readBytes();var checks=0
        assertThrows(CancellationException::class.java) {f.storage.openSource(f.uri,DocumentKind.NOTE) {if(++checks==3) throw CancellationException()}}
        assertArrayEquals(bytes,f.current.readBytes());assertEquals(f.reads,f.closes)
        assertTrue(f.context.cacheDir.listFiles().orEmpty().none {it.name.startsWith("bundle-source-")})
        assertEquals(0,f.writes);assertFalse(f.storage.hasRecovery(f.uri))
    }
    @Test fun externalSameUuidContentChangeIsRejectedBeforeStageOrTargetWrite()=scenario {f->
        bundle(f.current);val expected=f.storage.openSource(f.uri,DocumentKind.NOTE).use {it.version}
        val next=bundle(File(f.root,"next"),body="my edits")
        bundle(f.current,body="foreign edits");val foreign=f.current.readBytes()
        val error=assertThrows(ReferenceContractViolation::class.java) {f.storage.publishExpected(f.uri,next,expected,"source")}
        assertEquals(StorageMsg.REFERENCE_SOURCE_CHANGED,error.reason);assertEquals(0,f.writes)
        assertFalse(f.storage.hasRecovery(f.uri));assertArrayEquals(foreign,f.current.readBytes())
    }
    @Test fun wrongLocationKindOrIdentityDoesNotGrantOverwriteAuthority()=scenario {f->
        bundle(f.current);val atOther=f.storage.openSource(f.other,DocumentKind.NOTE).use {it.version}
        val next=bundle(File(f.root,"next"),body="new");val before=f.current.readBytes()
        assertThrows(ReferenceContractViolation::class.java) {f.storage.publishExpected(f.uri,next,atOther,"source")}
        bundle(f.current,kind=DocumentKind.CANVAS);val wrongKind=f.storage.openSource(f.uri,DocumentKind.CANVAS).use {it.version}
        f.current.writeBytes(before);assertThrows(ReferenceContractViolation::class.java) {f.storage.publishExpected(f.uri,next,wrongKind,"source")}
        bundle(f.current,documentId="00000000-0000-4000-8000-000000000009")
        val wrongId=f.storage.openSource(f.uri,DocumentKind.NOTE).use {it.version};f.current.writeBytes(before)
        assertThrows(ReferenceContractViolation::class.java) {f.storage.publishExpected(f.uri,next,wrongId,"source")}
        assertEquals(0,f.writes);assertFalse(f.storage.hasRecovery(f.uri));assertArrayEquals(before,f.current.readBytes())
    }
    @Test fun aliasSaveReturnsTheStagedVersionEvenIfOriginalCandidateChangesDuringWrite()=scenario {f->
        bundle(f.current);val expected=f.storage.openSource(f.uri,DocumentKind.NOTE).use {it.version}
        val next=bundle(File(f.root,"next"),body="mine");val staged=next.readBytes();val hash=BundleMigration.hash(next)
        f.beforeWrite={bundle(next,body="different caller candidate")}
        val receipt=f.storage.publishExpected(f.alias,next,expected,"source")
        assertEquals(hash,receipt.sha256);assertEquals(expected.locationKey,receipt.locationKey)
        assertEquals(id,receipt.documentId);assertArrayEquals(staged,f.current.readBytes())
        assertNotEquals(BundleMigration.hash(next),receipt.sha256);assertFalse(f.storage.hasRecovery(f.uri))
    }
    @Test fun failedRegistrationOrPartialPublicationNeverReturnsASuccessReceipt()=scenario {f->
        bundle(f.current);val expected=f.storage.openSource(f.uri,DocumentKind.NOTE).use {it.version}
        val next=bundle(File(f.root,"next"),body="new")
        f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_source_receipt BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT,'DB blocked'); END")
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishExpected(f.uri,next,expected,"source")}
        assertTrue(f.storage.hasRecovery(f.uri));assertArrayEquals(next.readBytes(),f.current.readBytes())
        val writes=f.writes;assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishExpected(f.uri,next,expected,"source")}
        assertEquals(writes,f.writes);assertTrue(f.storage.hasRecovery(f.uri))
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.openSource(f.uri,DocumentKind.NOTE)}
    }
    @Test fun partialSafFailureKeepsTheVerifiedSourceAndCandidateInRecovery()=scenario {f->
        bundle(f.current);val old=f.current.readBytes();val expected=f.storage.openSource(f.uri,DocumentKind.NOTE).use {it.version}
        val next=bundle(File(f.root,"next"),body="new");f.partialWrite=true
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishExpected(f.uri,next,expected,"source")}
        assertTrue(f.storage.hasRecovery(f.uri));f.partialWrite=false;f.storage.restore(f.uri)
        assertArrayEquals(old,f.current.readBytes());assertFalse(f.storage.hasRecovery(f.uri))
    }
    @Test fun privatePdfLeaseCanBeRetainedAcrossSnapshotCloseUntilModelAdoption()=scenario {f->
        val resources=f.storage.pdfResources;val pdf=resources.admit("%PDF-test".byteInputStream(),"source")
        bundle(f.current,pdf=pdf.nameWithoutExtension)
        val source=f.storage.openSource(f.uri,DocumentKind.NOTE)
        f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
        resources.collect();assertTrue(pdf.isFile)
        val adoption=source.retainPdfForAdoption()
        f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0") // resolve refreshes the retention clock.
        source.close();source.close()
        resources.collect();assertTrue(pdf.isFile);adoption.close();resources.collect();assertFalse(pdf.exists())
        assertTrue(f.current.exists())
    }
    @Test fun embeddedOriginalAdmissionIsLeasedAndNeverDeletesTheSourcePdfPayload()=scenario {f->
        val pdf="%PDF-test embedded".toByteArray();bundle(f.current,embedded=pdf);val before=f.current.readBytes()
        val source=f.storage.openSource(f.uri,DocumentKind.NOTE)
        val original=source.admitOriginalPdf(pdf.inputStream(),"original")
        f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
        f.storage.pdfResources.collect();assertTrue(original.isFile)
        val adoption=source.retainPdfForAdoption()
        f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
        source.close();f.storage.pdfResources.collect();assertTrue(original.isFile)
        adoption.close();f.storage.pdfResources.collect();assertFalse(original.exists());assertArrayEquals(before,f.current.readBytes())
    }
    @Test fun aTargetChangeAfterStageRetainsForeignBytesAndTheRecoveryRecord()=scenario {f->
        bundle(f.current);val expected=f.storage.openSource(f.uri,DocumentKind.NOTE).use {it.version}
        val next=bundle(File(f.root,"next"),body="mine");var foreign:ByteArray?=null
        f.beforeRead={if(f.reads>=2 && foreign==null) {bundle(f.current,body="external after stage");foreign=f.current.readBytes()}}
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishExpected(f.uri,next,expected,"source")}
        assertEquals(0,f.writes);assertTrue(f.storage.hasRecovery(f.uri));assertArrayEquals(foreign,f.current.readBytes())
    }
    @Test fun privateSnapshotByteChangesCannotPassModelIdentityVerification()=scenario {f->
        bundle(f.current);val original=f.current.readBytes()
        f.storage.openSource(f.uri,DocumentKind.NOTE).use {source->
            val doc=source.file.inputStream().use {noteCodec().read(it)}
            source.file.appendText("changed snapshot")
            assertThrows(ReferenceContractViolation::class.java) {source.verify(doc)}
            assertArrayEquals(original,f.current.readBytes());assertEquals(page,doc.pages.single().referenceIdentity.id)
        }
    }
    @Test fun anotherPdfCannotBeAdmittedAsThisSnapshotsOriginal()=scenario {f->
        bundle(f.current,embedded="%PDF-original".toByteArray());val original=f.current.readBytes()
        f.storage.openSource(f.uri,DocumentKind.NOTE).use {source->
            val error=assertThrows(ReferenceContractViolation::class.java) {source.admitOriginalPdf("different PDF".byteInputStream(),"other")}
            assertEquals(StorageMsg.PDF_RESOURCE_MISMATCH,error.reason)
            assertArrayEquals(original,f.current.readBytes())
        }
    }
    @Test fun cancellationAfterPdfLeaseAcquisitionReleasesLeaseAndSnapshot()=scenario {f->
        val pdf=f.storage.pdfResources.admit("%PDF-owned".byteInputStream(),"original");bundle(f.current,pdf=pdf.nameWithoutExtension)
        assertThrows(CancellationException::class.java) {
            f.storage.openSource(f.uri,DocumentKind.NOTE) {
                val count=f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {it.moveToFirst();it.getInt(0)}
                if(count>0) throw CancellationException()
            }
        }
        val remaining=f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {it.moveToFirst();it.getInt(0)}
        assertEquals(0,remaining);assertTrue(pdf.isFile)
        assertTrue(f.context.cacheDir.listFiles().orEmpty().none {it.name.startsWith("bundle-source-")})
    }

    @Test fun codecPdfStoreCanAdmitOriginalWithoutClosingLaterManifestIdentityAndImages()=scenario {f->
        val pdf="%PDF-source-first".toByteArray()
        ZipOutputStream(f.current.outputStream()).use {zip->
            zip.putNextEntry(ZipEntry(BundlePdf.ENTRY));zip.write(pdf);zip.closeEntry()
            BundleIdentity(id).write(zip);AnchorMetadata(id).write(zip)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"reference_id":"$page","items":[{"kind":"image","asset":"assets/image-after.png","rect":[0,0,30,40],"src_w":30,"src_h":40}]}]}""".toByteArray());zip.closeEntry()
            zip.putNextEntry(ZipEntry("assets/image-after.png"));zip.write(byteArrayOf(1,2,3));zip.closeEntry()
        }
        val before=f.current.readBytes();val assets=File(f.root,"decoded-assets").apply {mkdir()}
        lateinit var image:File
        f.storage.openSource(f.uri,DocumentKind.NOTE).use {source->
            val doc=source.file.inputStream().use {noteCodec().read(it,pdfDir=assets,imageDir=assets,pdfStore={entry->source.admitOriginalPdf(entry,"original")})}
            source.verify(doc);assertEquals(id,doc.identity.documentId);assertEquals(page,doc.pages.single().referenceIdentity.id)
            image=(doc.pages.single().items.single() as com.xnotes.core.model.ImageItem).image.file
            assertTrue(image.isFile);assertArrayEquals(pdf,doc.pdfFile!!.readBytes())
            f.storage.pdfResources.pin("note:$id",setOf(doc.pdfFile!!.nameWithoutExtension))
        }
        assertTrue(image.isFile);assertArrayEquals(before,f.current.readBytes())
    }

    @Test fun pdfIsAlreadyLeasedWhenLongContainerValidationStarts()=scenario {f->
        val resources=f.storage.pdfResources;val original=resources.admit("%PDF-validate".byteInputStream(),"original")
        bundle(f.current,pdf=original.nameWithoutExtension)
        f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
        var called=false
        f.validating={
            called=true
            f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0") // Lease, not refreshed grace, must protect it.
            resources.collect();assertTrue("Original must survive validation-time GC",original.isFile)
        }
        f.storage.openSource(f.uri,DocumentKind.NOTE).use {assertTrue(called);assertTrue(original.isFile)}
    }

    @Test fun commentedHeaderLocalScanCannotCollectItsExternalPdf()=scenario {f->
        val resources=f.storage.pdfResources;val original=resources.admit("%PDF-commented-header".byteInputStream(),"original")
        bundle(f.current,pdf=original.nameWithoutExtension)
        // A comment deliberately selects the Header fallback that checks the sequential ZIP view.
        val commented=File(f.root,"commented")
        java.util.zip.ZipFile(f.current).use {input->ZipOutputStream(commented.outputStream()).use {output->
            output.setComment("force metadata fallback")
            for(entry in input.entries().asSequence()) {
                output.putNextEntry(ZipEntry(entry.name));input.getInputStream(entry).use {it.copyTo(output)};output.closeEntry()
            }
        }}
        commented.copyTo(f.current,overwrite=true)
        var atEnvelope=false;var collected=false
        f.validating={atEnvelope=true}
        f.storage.openSource(f.uri,DocumentKind.NOTE) {
            if(atEnvelope && !collected) {
                collected=true;f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
                resources.collect();assertTrue("PDF must be protected while the local header view is checked",original.isFile)
            }
        }.use {assertTrue(atEnvelope);assertTrue(collected);assertTrue(original.isFile)}
    }

    @Test fun matchingDocumentAndPagesDoNotAuthorizeAnotherPdfOriginal()=scenario {f->
        val originalPdf="%PDF-correct original".toByteArray();bundle(f.current,embedded=originalPdf)
        val before=f.current.readBytes();val assets=File(f.root,"pdf-model-assets").apply {mkdir()}
        f.storage.openSource(f.uri,DocumentKind.NOTE).use {source->
            val doc=source.file.inputStream().use {noteCodec().read(it,pdfDir=assets)};val original=doc.pdfFile!!
            val wrong=File(assets,"different.pdf").apply {writeText("%PDF-different original")}
            doc.pdfFile=wrong
            assertThrows(ReferenceContractViolation::class.java) {source.verify(doc)}
            assertEquals("%PDF-different original",wrong.readText());assertTrue(original.isFile)
            doc.pdfFile=original
            doc.identity=doc.identity.copy(pdfResource=BundleMigration.hash(wrong),externalPdf=true)
            assertThrows(ReferenceContractViolation::class.java) {source.verify(doc)}
            assertArrayEquals(originalPdf,original.readBytes());assertArrayEquals(before,f.current.readBytes())
        }
    }

    @Test fun anUnverifiedFallbackHintNeverGrantsAuthorityAndReleasesItsTemporaryLease()=scenario {f->
        val resources=f.storage.pdfResources;val original=resources.admit("%PDF-unverified-header".byteInputStream(),"original")
        ZipOutputStream(f.current.outputStream()).use {zip->
            zip.setComment("fallback")
            BundleIdentity(id,original.nameWithoutExtension,true).write(zip);AnchorMetadata(id).write(zip)
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"reference_id":"$page","items":[]}]}""".toByteArray());zip.closeEntry()
        }
        val bytes=f.current.readBytes();bytes[31]='X'.code.toByte();f.current.writeBytes(bytes) // local metadata name differs from the directory.
        var protected=false;f.validating={protected=true}
        val error=assertThrows(ReferenceContractViolation::class.java) {f.storage.openSource(f.uri,DocumentKind.NOTE)}
        assertEquals(StorageMsg.REFERENCE_METADATA_INVALID,error.reason);assertTrue(protected)
        val leases=f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {it.moveToFirst();it.getInt(0)}
        assertEquals(0,leases);assertTrue(original.isFile);assertArrayEquals(bytes,f.current.readBytes())
        assertTrue(f.context.cacheDir.listFiles().orEmpty().none {it.name.startsWith("bundle-source-")})
    }
    @Test fun unusedV1PdfMetadataDoesNotInventAnOriginal()=scenario {f->
        ZipOutputStream(f.current.outputStream()).use {zip->
            BundleIdentity(id,"unused old resource field",false).write(zip);AnchorMetadata(id).write(zip)
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write("""{"format":"xnote","version":1,"pages":[{"reference_id":"$page","items":[]}]}""".toByteArray());zip.closeEntry()
        }
        f.storage.openSource(f.uri,DocumentKind.NOTE).use {source->
            val doc=source.file.inputStream().use {noteCodec().read(it)};source.verify(doc)
            assertNull(doc.pdfFile);assertEquals("unused old resource field",doc.identity.pdfResource)
        }
    }

}
