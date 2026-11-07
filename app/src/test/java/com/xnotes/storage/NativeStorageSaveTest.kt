package com.xnotes.storage

import android.content.ContextWrapper
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeStorageSaveTest {
    private val id="00000000-0000-0000-0000-000000000001"
    private val page="00000000-0000-0000-0000-000000000002"
    private class Fixture(val root:File) {
        val uri="content://native-test/document/a"
        val alias="content://native-test/tree/root/document/a"
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val current=File(root,"current.xnote")
        var writes=0;var failWrite=false
        val storage=NotebookStorage(context,targetOverride={object:MigrationPublication.Target {
            override fun read()=current.inputStream()
            override fun write(source:File) {
                writes++
                if(failWrite) {current.writeText("partial native write");throw IOException("provider lost power")}
                source.copyTo(current,overwrite=true)
            }
        }})
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("native-storage-save-").toFile();val f=Fixture(root)
        try {block(f)} finally {f.storage.registry.close();root.deleteRecursively()}
    }
    private fun bundle(file:File,pages:Boolean=false,format:String="xnote",body:String="old",pdf:String?=null,id:String?=this.id,orphanId:Boolean=false,hasPdf:Boolean=pdf!=null,external:Boolean=pdf!=null,embedded:ByteArray?=null):File = file.also {
        ZipOutputStream(file.outputStream()).use {z->
            id?.let {BundleIdentity(it,pdfResource=pdf,externalPdf=external).write(z)}
            if(pages) AnchorMetadata(requireNotNull(id)).write(z)
            embedded?.let {z.putNextEntry(ZipEntry(com.xnotes.format.BundlePdf.ENTRY));z.write(it);z.closeEntry()}
            z.putNextEntry(ZipEntry("manifest.json"))
            val content=if(format=="xnote") "\"pages\":[{${if(pages || orphanId) "\"reference_id\":\"$page\"," else ""}\"items\":[]}]" else "\"items\":[]"
            z.write("""{"format":"$format","version":1,"has_pdf":$hasPdf,$content,"user_data":"$body"}""".toByteArray());z.closeEntry()
        }
    }
    @Test fun successfulAnchoredSavePublishesRegistersPositiveContractAndClearsRecovery() = scenario {f->
        bundle(f.current,pages=true);val next=bundle(File(f.root,"next.xnote"),pages=true,body="new")
        f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"source.xnote")
        assertArrayEquals(next.readBytes(),f.current.readBytes());assertEquals(1,f.writes)
        assertTrue(f.storage.registry.expectationForId(id).requiresPages);assertFalse(f.storage.hasRecovery(f.uri))
    }
    @Test fun mismatchedIdentityTypeOrPageStateNeverWritesOrStagesARecoveryRecord() = scenario {f->
        bundle(f.current,pages=true);val original=f.current.readBytes()
        f.storage.registry.register(id,"xnote","source",f.uri,pageReferences=true)
        val versions=listOf(bundle(File(f.root,"unmarked")),bundle(File(f.root,"canvas"),format="xcanvas"),
            bundle(File(f.root,"other"),pages=true,id="00000000-0000-0000-0000-000000000003"))
        for(next in versions) {
            assertThrows(ReferenceContractViolation::class.java) {f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"source")}
            assertEquals(0,f.writes);assertArrayEquals(original,f.current.readBytes());assertFalse(f.storage.hasRecovery(f.uri))
        }
    }
    @Test fun registryFailureRetainsRecordAndAliasRetryDoesNotWriteTheCandidateAgain() = scenario {f->
        bundle(f.current,pages=true);val next=bundle(File(f.root,"next"),pages=true,body="new")
        val r=f.storage.registry;r.register(id,"xnote","source",f.uri)
        r.writableDatabase.execSQL("CREATE TRIGGER fail_saved_index BEFORE UPDATE ON documents BEGIN SELECT RAISE(ABORT,'injected registry failure'); END")
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"source")}
        assertEquals(1,f.writes);assertTrue(f.storage.hasRecovery(f.alias));assertArrayEquals(next.readBytes(),f.current.readBytes())
        r.writableDatabase.execSQL("DROP TRIGGER fail_saved_index")
        assertEquals(id,f.storage.ensure(f.alias,"source","xnote").documentId)
        assertEquals(1,f.writes);assertFalse(f.storage.hasRecovery(f.uri));assertTrue(r.expectationForId(id).requiresPages)
    }
    @Test fun partialSafWriteCanRestoreTheVerifiedOriginalAndItsContract() = scenario {f->
        bundle(f.current,pages=true);val original=f.current.readBytes()
        val next=bundle(File(f.root,"next"),pages=true,body="new");f.failWrite=true
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"source")}
        assertTrue(f.storage.hasRecovery(f.uri));f.failWrite=false
        f.storage.restore(f.uri)
        assertArrayEquals(original,f.current.readBytes());assertFalse(f.storage.hasRecovery(f.uri))
        assertTrue(f.storage.registry.expectationForId(id).requiresPages)
    }
    @Test fun keepCurrentRejectsLostPageMetadataWithoutDiscardingTheVerifiedOriginal() = scenario {f->
        bundle(f.current,pages=true);val next=bundle(File(f.root,"next"),pages=true,body="new")
        f.storage.registry.register(id,"xnote","source",f.uri,pageReferences=true);f.failWrite=true
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"source")}
        bundle(f.current,pages=false);val degraded=f.current.readBytes()
        assertThrows(ReferenceContractViolation::class.java) {f.storage.keepCurrent(f.uri)}
        assertTrue(f.storage.hasRecovery(f.uri));assertArrayEquals(degraded,f.current.readBytes())
        f.failWrite=false;f.storage.restore(f.uri);assertFalse(f.storage.hasRecovery(f.uri))
    }
    @Test fun pendingPdfRemainsPinnedAcrossIndexFailureGcAndSuccessfulRetry() = scenario {f->
        val resources=f.storage.pdfResources
        assertSame(f.storage.registry,resources.registry);assertSame(resources,f.storage.pdfResources)
        val pdf=resources.admit("%PDF-1.7\nimmutable original\n%%EOF".byteInputStream(),"source.pdf").nameWithoutExtension
        bundle(f.current,pdf=pdf);val next=bundle(File(f.root,"next"),body="new",pdf=pdf)
        val r=f.storage.registry
        r.writableDatabase.execSQL("CREATE TRIGGER fail_saved_index BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT,'injected registry failure'); END")
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"source")}
        assertTrue(r.owners("migration:").isNotEmpty());assertTrue(f.storage.hasRecovery(f.uri))
        assertEquals(0,r.collect(System.currentTimeMillis()+30L*24*60*60*1000,0,resources.directory))
        assertTrue(resources.resolve(pdf).isFile)
        r.writableDatabase.execSQL("DROP TRIGGER fail_saved_index")
        f.storage.ensure(f.uri,"source","xnote")
        assertEquals(1,f.writes);assertTrue(r.owners("migration:").isEmpty())
        assertTrue(resources.references("note:$id").contains(pdf))
    }
    @Test fun canvasUsesTheSameVerifiedPublicationAndRegistersItsOwnKind() = scenario {f->
        bundle(f.current,format="xcanvas");val next=bundle(File(f.root,"next.xcanvas"),format="xcanvas",body="new")
        f.storage.publishNote(f.uri,next,DocumentKind.CANVAS,"source.xcanvas")
        assertEquals(1,f.writes);assertArrayEquals(next.readBytes(),f.current.readBytes())
        assertEquals(id,f.storage.registry.idAt(f.uri));assertFalse(f.storage.registry.expectationForId(id).requiresPages)
    }
    @Test fun degradedCurrentCannotBeSilentlyRepairedByACompleteCandidate() = scenario {f->
        bundle(f.current,pages=false);val before=f.current.readBytes()
        f.storage.registry.register(id,"xnote","source",f.uri,pageReferences=true)
        val next=bundle(File(f.root,"next"),pages=true)
        assertThrows(ReferenceContractViolation::class.java) {f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"source")}
        assertEquals(0,f.writes);assertFalse(f.storage.hasRecovery(f.uri));assertArrayEquals(before,f.current.readBytes())
    }
    @Test fun unmarkedPageIdsNeedExplicitRecoveryEvenWithoutARegistryContract() = scenario {f->
        bundle(f.current,orphanId=true);val before=f.current.readBytes();val next=bundle(File(f.root,"next"))
        val error=assertThrows(ReferenceContractViolation::class.java) {f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"source")}
        assertEquals(StorageMsg.REFERENCE_METADATA_INVALID,error.reason)
        assertEquals(0,f.writes);assertFalse(f.storage.hasRecovery(f.uri));assertArrayEquals(before,f.current.readBytes())
    }
    @Test fun pinBeforeStageRemainsProtectedEvenThoughThereIsNoPendingRecordYet() = scenario {f->
        val resources=f.storage.pdfResources
        val pdf=resources.admit("immutable before stage".byteInputStream(),"source.pdf").nameWithoutExtension
        resources.protect("migration:interrupted-before-stage",setOf(pdf))
        assertFalse(f.storage.hasRecovery(f.uri))
        assertEquals(0,f.storage.registry.collect(System.currentTimeMillis()+30L*24*60*60*1000,0,resources.directory))
        assertTrue(resources.resolve(pdf).isFile)
        assertTrue(resources.references("migration:interrupted-before-stage").contains(pdf))
    }
    @Test fun pinCleanupFailureDoesNotTurnCommittedSaveIntoFailureOrLosePdfProtection() = scenario {f->
        val resources=f.storage.pdfResources
        val pdf=resources.admit("immutable cleanup failure".byteInputStream(),"source.pdf").nameWithoutExtension
        bundle(f.current,pdf=pdf);val next=bundle(File(f.root,"next"),body="new",pdf=pdf)
        f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_cleanup BEFORE DELETE ON resource_refs WHEN OLD.owner LIKE 'migration:%' BEGIN SELECT RAISE(ABORT,'cleanup failure'); END")
        f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"source")
        assertEquals(1,f.writes);assertFalse(f.storage.hasRecovery(f.uri));assertArrayEquals(next.readBytes(),f.current.readBytes())
        assertTrue(f.storage.registry.owners("migration:").isNotEmpty());assertTrue(resources.references("note:$id").contains(pdf))
        assertEquals(0,f.storage.registry.collect(System.currentTimeMillis()+30L*24*60*60*1000,0,resources.directory))
    }
    @Test fun conflictingLegacyAndCanonicalRecordsAreRetainedWithoutChoosingOne() = scenario {f->
        bundle(f.current);val next=bundle(File(f.root,"next"),body="new");val before=f.current.readBytes()
        for(key in listOf(f.uri,f.storage.registry.locationKey(f.uri))) {
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {"%02x".format(it)}
            MigrationPublication(File(f.root,"private/migrations/$hash")).stage(f.current,next)
        }
        assertTrue(f.storage.hasRecovery(f.alias))
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"source")}
        assertEquals(0,f.writes);assertArrayEquals(before,f.current.readBytes());assertTrue(f.storage.hasRecovery(f.uri))
    }
    @Test fun restoringDoesNotOverwriteARecognizableDifferentDocumentAtTheSameLocation() = scenario {f->
        bundle(f.current);val next=bundle(File(f.root,"next"),body="new")
        f.storage.registry.register(id,"xnote","source",f.uri);f.failWrite=true
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"source")}
        f.failWrite=false;bundle(f.current,id="00000000-0000-0000-0000-000000000003");val other=f.current.readBytes()
        assertThrows(ReferenceContractViolation::class.java) {f.storage.restore(f.uri)}
        assertArrayEquals(other,f.current.readBytes());assertTrue(f.storage.hasRecovery(f.uri));assertEquals(1,f.writes)
    }
    @Test fun refusedForeignRestoreAlsoKeepsThatFilesSharedPdfAvailableForRecovery() = scenario {f->
        bundle(f.current);val next=bundle(File(f.root,"next"),body="new");f.failWrite=true
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"source")}
        f.failWrite=false
        val resources=f.storage.pdfResources
        val pdf=resources.admit("foreign document original PDF".byteInputStream(),"other.pdf").nameWithoutExtension
        bundle(f.current,id="00000000-0000-0000-0000-000000000003",pdf=pdf);val before=f.current.readBytes()
        assertThrows(ReferenceContractViolation::class.java) {f.storage.restore(f.uri)}
        assertTrue(f.storage.hasRecovery(f.uri));assertArrayEquals(before,f.current.readBytes())
        assertEquals(0,f.storage.registry.collect(System.currentTimeMillis()+30L*24*60*60*1000,0,resources.directory))
        assertTrue(resources.resolve(pdf).isFile)
    }
    @Test fun readableLegacyLargeExtensionDoesNotAcquireAnchorTokenLimitsDuringSave() = scenario {f->
        bundle(f.current,body="x".repeat(1024*1024+17));val next=bundle(File(f.root,"next"),body="new")
        f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"source")
        assertArrayEquals(next.readBytes(),f.current.readBytes());assertEquals(1,f.writes)
    }
    @Test fun keepCurrentCannotDiscardRecoveryForAnEmbeddedPdfThatIsMissing() = scenario {f->
        val resource=f.storage.pdfResources.admit("original PDF".byteInputStream(),"source.pdf").nameWithoutExtension
        bundle(f.current,pdf=resource);val next=bundle(File(f.root,"next"),body="new",pdf=resource)
        f.failWrite=true
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"source")}
        f.failWrite=false;bundle(f.current,pdf=resource,hasPdf=true,external=false);val incomplete=f.current.readBytes()
        assertThrows(ReferenceContractViolation::class.java) {f.storage.keepCurrent(f.uri)}
        assertTrue(f.storage.hasRecovery(f.uri));assertArrayEquals(incomplete,f.current.readBytes())
    }
    @Test fun keepCurrentDoesNotReplaceAnImmutableOriginalPdfOrTrustTamperedBackups() = scenario {f->
        val resources=f.storage.pdfResources
        val original=resources.admit("original PDF".byteInputStream(),"source.pdf").nameWithoutExtension
        val other=resources.admit("different PDF".byteInputStream(),"other.pdf").nameWithoutExtension
        bundle(f.current,pdf=original);val next=bundle(File(f.root,"next"),body="new",pdf=original)
        f.failWrite=true
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"source")}
        f.failWrite=false;bundle(f.current,pdf=other);val substituted=f.current.readBytes()
        assertThrows(ReferenceContractViolation::class.java) {f.storage.keepCurrent(f.uri)}
        assertTrue(f.storage.hasRecovery(f.uri));assertArrayEquals(substituted,f.current.readBytes())
        assertEquals(0,f.storage.registry.collect(System.currentTimeMillis()+30L*24*60*60*1000,0,resources.directory))
        assertTrue(resources.resolve(other).isFile)
        val directory=File(f.root,"private/migrations").listFiles()!!.single()
        for(name in listOf("original.zip","candidate.zip")) f.current.copyTo(File(directory,name),overwrite=true)
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.keepCurrent(f.uri)}
        assertTrue(f.storage.hasRecovery(f.uri));assertArrayEquals(substituted,f.current.readBytes())
    }
    @Test fun versionOneRecordCanExplicitlyRestoreAnUnidentifiedLegacyOriginalFromItsObservedCandidate() = scenario {f->
        bundle(f.current,id=null);val original=f.current.readBytes();val next=bundle(File(f.root,"next"),body="new")
        val key=f.storage.registry.locationKey(f.uri)
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {"%02x".format(it)}
        val directory=File(f.root,"private/migrations/$hash")
        MigrationPublication(directory).stage(f.current,next);File(directory,"pending").writeText("1")
        next.copyTo(f.current,overwrite=true)
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.keepCurrent(f.uri)}
        assertTrue(f.storage.hasRecovery(f.uri)) // v1 has no durable SHA proof for a keep-current decision.
        f.storage.restore(f.uri)
        assertArrayEquals(original,f.current.readBytes());assertFalse(f.storage.hasRecovery(f.uri));assertNull(f.storage.identity(f.uri))
    }
    @Test fun keepCurrentCannotBlessAStagedCandidateThatViolatedItsOrdinarySavePurpose() = scenario {f->
        bundle(f.current,pages=true);val next=bundle(File(f.root,"next"),pages=false)
        val key=f.storage.registry.locationKey(f.uri)
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {"%02x".format(it)}
        MigrationPublication(File(f.root,"private/migrations/$hash")).stage(f.current,next,MigrationPublication.Purpose.SAVE_NOTE)
        next.copyTo(f.current,overwrite=true);val current=f.current.readBytes()
        assertThrows(ReferenceContractViolation::class.java) {f.storage.keepCurrent(f.uri)}
        assertTrue(f.storage.hasRecovery(f.uri));assertArrayEquals(current,f.current.readBytes())
    }
    @Test fun missingPrivatePdfCanBeRecoveredFromTheVerifiedEmbeddedOriginal() = scenario {f->
        val resources=f.storage.pdfResources;val pdfBytes="%PDF-1.7 original in portable package".toByteArray()
        val pdfFile=resources.admit(pdfBytes.inputStream(),"source.pdf");val hash=pdfFile.nameWithoutExtension
        resources.pin("note:$id",setOf(hash))
        bundle(f.current,pdf=hash,hasPdf=true,external=false,embedded=pdfBytes);val original=f.current.readBytes()
        val next=bundle(File(f.root,"next"),pdf=hash,hasPdf=true,external=true)
        val key=f.storage.registry.locationKey(f.uri)
        val slot=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {"%02x".format(it)}
        MigrationPublication(File(f.root,"private/migrations/$slot")).stage(f.current,next)
        next.copyTo(f.current,overwrite=true);assertTrue(pdfFile.delete())
        f.storage.restore(f.uri)
        assertArrayEquals(original,f.current.readBytes());assertFalse(f.storage.hasRecovery(f.uri))
        assertEquals(id,f.storage.ensure(f.uri,"source.xnote","xnote").documentId)
        assertArrayEquals(pdfBytes,resources.resolve(hash).readBytes())
    }
    @Test fun availableCurrentPdfIsStillPinnedWhenEmbeddedBackupAllowsOtherRecoveryCases() = scenario {f->
        val resources=f.storage.pdfResources;val bytes="%PDF-1.7 shared current original".toByteArray()
        val pdf=resources.admit(bytes.inputStream(),"source.pdf").nameWithoutExtension
        assertTrue(resources.references("note:$id").isEmpty())
        bundle(f.current,pdf=pdf,hasPdf=true,external=false,embedded=bytes)
        val next=bundle(File(f.root,"next"),pdf=pdf,hasPdf=true,external=true)
        val key=f.storage.registry.locationKey(f.uri)
        val slot=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {"%02x".format(it)}
        MigrationPublication(File(f.root,"private/migrations/$slot")).stage(f.current,next)
        bundle(f.current,id="00000000-0000-0000-0000-000000000003",pdf=pdf);val foreign=f.current.readBytes()
        assertThrows(ReferenceContractViolation::class.java) {f.storage.restore(f.uri)}
        assertTrue(f.storage.hasRecovery(f.uri));assertArrayEquals(foreign,f.current.readBytes())
        assertEquals(0,f.storage.registry.collect(System.currentTimeMillis()+30L*24*60*60*1000,0,resources.directory))
        assertArrayEquals(bytes,resources.resolve(pdf).readBytes())
    }
    @Test fun independentStorageOwnsIndependentRegistryAndResourceIndex() = scenario {f->
        val otherRoot=File(f.root,"other").apply {mkdir()};val other=Fixture(otherRoot)
        try {
            assertNotSame(f.storage.registry,other.storage.registry);assertNotSame(f.storage.pdfResources,other.storage.pdfResources)
            val pdf=f.storage.pdfResources.admit("immutable".byteInputStream(),"source").nameWithoutExtension
            assertNull(other.storage.registry.resource(pdf));assertNotEquals(f.storage.pdfResources.directory,other.storage.pdfResources.directory)
        } finally {other.storage.registry.close()}
    }
}
