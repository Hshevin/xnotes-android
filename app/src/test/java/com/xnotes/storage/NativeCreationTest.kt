package com.xnotes.storage

import android.content.ContextWrapper
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Document
import com.xnotes.core.model.PageReferenceCopy
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.snapshot
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleMigration
import com.xnotes.format.DocumentCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeCreationTest {
    private class Fixture(val root:File) {
        val uri="content://native-creation/document/a"
        val alias="content://native-creation/tree/root/document/a"
        val other="content://native-creation/document/b"
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val current=File(root,"current").apply {writeBytes(byteArrayOf())}
        var writes=0;var reads=0;var partial=false
        var beforeWrite:(()->Unit)?=null
        var beforeRead:(()->Unit)?=null
        var validating:(()->Unit)?=null
        val storage=NotebookStorage(context,targetOverride={backend()},sourceValidationHook={validating?.invoke()})
        fun backend()=object:MigrationPublication.Target {
            override fun read():java.io.InputStream {reads++;beforeRead?.invoke();return current.inputStream()}
            override fun write(source:File) {
                writes++;beforeWrite?.invoke()
                if(partial) {current.writeText("partial");throw IOException("provider failed")}
                source.copyTo(current,overwrite=true)
            }
        }
        fun journal():MigrationPublication {
            val key=storage.registry.locationKey(uri)
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {"%02x".format(it)}
            return MigrationPublication(File(context.noBackupFilesDir,"migrations/$hash"))
        }
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("native-creation-").toFile();val f=Fixture(root)
        try {block(f)} finally {f.storage.registry.close();root.deleteRecursively()}
    }
    private fun bundle(file:File,id:String,kind:DocumentKind=DocumentKind.NOTE,anchored:Boolean=kind==DocumentKind.NOTE,
                       page:String=UUID.randomUUID().toString(),pdf:String?=null,body:String="mine"):File=file.also {
        ZipOutputStream(file.outputStream()).use {zip->
            BundleIdentity(id,pdf,externalPdf=pdf!=null).write(zip)
            if(anchored) AnchorMetadata(id).write(zip)
            zip.putNextEntry(ZipEntry("manifest.json"))
            val content=if(kind==DocumentKind.NOTE) "\"pages\":[{${if(anchored) "\"reference_id\":\"$page\"," else ""}\"items\":[]}]" else "\"items\":[]"
            zip.write("""{"format":"${kind.extension}","version":1,"has_pdf":${pdf!=null},$content,"user_data":"$body"}""".toByteArray());zip.closeEntry()
        }
    }
    private fun blockRegistration(f:Fixture) {
        f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_creation BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT,'DB failed'); END")
    }
    private fun unblockRegistration(f:Fixture) {f.storage.registry.writableDatabase.execSQL("DROP TRIGGER fail_creation")}

    @Test fun explicitCreationPublishesNewNoteOrCanvasAndIssuesARealReceipt() {
        for(kind in listOf(DocumentKind.NOTE,DocumentKind.CANVAS)) scenario {f->
            val intent=f.storage.beginCreation(kind)
            val permit=f.storage.prepareNewTarget(intent,f.uri)
            val next=bundle(File(f.root,"next"),intent.documentId,kind)
            assertEquals(0,f.writes);assertFalse(f.storage.hasRecovery(f.uri))
            val receipt=f.storage.publishCreated(f.alias,next,permit,"new${kind.suffix}")
            assertEquals(intent.documentId,receipt.documentId);assertEquals(kind,receipt.kind)
            assertEquals(BundleMigration.hash(f.current),receipt.sha256);assertEquals(f.storage.registry.locationKey(f.uri),receipt.locationKey)
            assertArrayEquals(next.readBytes(),f.current.readBytes());assertEquals(1,f.writes)
            assertEquals(intent.documentId,f.storage.registry.idAt(f.uri));assertFalse(f.storage.hasRecovery(f.uri))
            assertEquals(kind==DocumentKind.NOTE,f.storage.registry.expectationForId(intent.documentId).requiresPages)
            f.storage.openSource(f.uri,kind).use {assertEquals(receipt.sha256,it.version.sha256)}
        }
    }
    @Test fun intentsAndPermitsAreOneUseAndCannotBeUsedByAnotherStorageIssuer()=scenario {f->
        val foreign=NotebookStorage(f.context,targetOverride={f.backend()})
        try {
            val intent=f.storage.beginCreation(DocumentKind.NOTE)
            assertThrows(ReferenceContractViolation::class.java) {foreign.prepareNewTarget(intent,f.uri)}
            val permit=f.storage.prepareNewTarget(intent,f.uri)
            assertThrows(ReferenceContractViolation::class.java) {f.storage.prepareNewTarget(intent,f.uri)}
            val next=bundle(File(f.root,"next"),intent.documentId)
            assertThrows(ReferenceContractViolation::class.java) {foreign.publishCreated(f.uri,next,permit,"new")}
            f.storage.publishCreated(f.uri,next,permit,"new")
            assertThrows(ReferenceContractViolation::class.java) {f.storage.publishCreated(f.uri,next,permit,"new")}
            assertEquals(1,f.writes)
        } finally {foreign.registry.close()}
    }
    @Test fun unsupportedKindsNeverAcquireACreationContext()=scenario {f->
        for(kind in listOf(DocumentKind.MIND_MAP,DocumentKind.SPLIT))
            assertThrows(ReferenceContractViolation::class.java) {f.storage.beginCreation(kind)}
        assertEquals(0,f.reads);assertEquals(0,f.writes)
    }
    @Test fun nonemptyKnownOrUnreadableTargetsAreNeverTreatedAsNewFiles()=scenario {f->
        f.current.writeText("foreign existing file")
        val bad=f.storage.beginCreation(DocumentKind.NOTE)
        assertThrows(ReferenceContractViolation::class.java) {f.storage.prepareNewTarget(bad,f.uri)}
        assertEquals("foreign existing file",f.current.readText());f.current.writeText("")
        assertThrows(ReferenceContractViolation::class.java) {f.storage.prepareNewTarget(bad,f.uri)} // consumed on first valid issuer attempt
        val known=UUID.randomUUID().toString();f.storage.registry.register(known,"xnote","missing",f.uri)
        f.storage.registry.markUnavailable(f.uri)
        assertThrows(ReferenceContractViolation::class.java) {f.storage.prepareNewTarget(f.storage.beginCreation(DocumentKind.NOTE),f.alias)}
        f.current.delete()
        assertThrows(IOException::class.java) {f.storage.prepareNewTarget(f.storage.beginCreation(DocumentKind.NOTE),f.other)}
        assertEquals(0,f.writes);assertFalse(f.storage.hasRecovery(f.uri))
    }
    @Test fun aNewlyKnownUuidOrLocationCannotBeClaimedByAnOldPermit()=scenario {f->
        val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
        val next=bundle(File(f.root,"next"),intent.documentId)
        f.storage.registry.register(intent.documentId,"xnote","unavailable other",f.other,pageReferences=true)
        f.storage.registry.markUnavailable(f.other)
        assertThrows(ReferenceContractViolation::class.java) {f.storage.publishCreated(f.uri,next,permit,"new")}
        assertEquals(0,f.writes);assertEquals(0L,f.current.length());assertFalse(f.storage.hasRecovery(f.uri))
    }
    @Test fun targetMutationAfterPreparationDoesNotWriteOrStage()=scenario {f->
        val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
        val next=bundle(File(f.root,"next"),intent.documentId);f.current.writeText("foreign arrived")
        assertThrows(ReferenceContractViolation::class.java) {f.storage.publishCreated(f.alias,next,permit,"new")}
        assertEquals("foreign arrived",f.current.readText());assertEquals(0,f.writes);assertFalse(f.storage.hasRecovery(f.uri))
    }
    @Test fun wrongLocationUuidKindOrBrokenCandidateNeverWrites() {
        for(bad in listOf("location","uuid","kind","broken","marker")) scenario {f->
            val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
            val next=bundle(File(f.root,"next"),if(bad=="uuid") UUID.randomUUID().toString() else intent.documentId,
                if(bad=="kind") DocumentKind.CANVAS else DocumentKind.NOTE)
            if(bad=="broken") next.writeText("broken")
            if(bad=="marker") bundle(next,intent.documentId,kind=DocumentKind.CANVAS,anchored=true)
            assertThrows(ReferenceContractViolation::class.java) {f.storage.publishCreated(if(bad=="location") f.other else f.uri,next,permit,"new")}
            assertEquals(0,f.writes);assertEquals(0L,f.current.length());assertFalse(f.storage.hasRecovery(f.uri))
        }
    }
    @Test fun indexFailureCanOnlyExplicitlyCompleteTheSameRetainedCreationWithoutRewriting()=scenario {f->
        val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
        val next=bundle(File(f.root,"next"),intent.documentId);val staged=next.readBytes();val hash=BundleMigration.hash(next)
        blockRegistration(f)
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishCreated(f.uri,next,permit,"new")}
        assertEquals(MigrationPublication.Purpose.CREATE_NOTE,f.journal().purpose)
        assertTrue(f.storage.hasRecovery(f.alias));assertArrayEquals(staged,f.current.readBytes());assertEquals(1,f.writes)
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.ensure(f.uri,"new","xnote")}
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.restore(f.uri)}
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.keepCurrent(f.uri)}
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishNote(f.uri,next,DocumentKind.NOTE,"new")}
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.prepareNewTarget(f.storage.beginCreation(DocumentKind.NOTE),f.alias)}
        unblockRegistration(f);next.writeText("caller replaced temporary candidate")
        val resumed=NotebookStorage(f.context,targetOverride={f.backend()})
        try {
            val receipt=resumed.completeCreation(f.alias,"new")
            assertEquals(hash,receipt.sha256);assertEquals(intent.documentId,receipt.documentId)
            assertEquals(1,f.writes);assertFalse(resumed.hasRecovery(f.uri));assertArrayEquals(staged,f.current.readBytes())
        } finally {resumed.registry.close()}
    }
    @Test fun partialOrForeignTargetRemainsUntouchedByEveryOrdinaryRecoveryEntry() {
        for(foreign in listOf(false,true)) scenario {f->
            val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
            val next=bundle(File(f.root,"next"),intent.documentId);f.partial=true
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishCreated(f.uri,next,permit,"new")}
            f.partial=false;if(foreign) bundle(f.current,UUID.randomUUID().toString())
            val before=f.current.readBytes();val writes=f.writes
            for(action in listOf<()->Unit>({f.storage.completeCreation(f.uri,"new")},{f.storage.restore(f.uri)},{f.storage.keepCurrent(f.uri)},
                {f.storage.ensure(f.uri,"new","xnote")},{f.journal().publish(f.backend())},{f.journal().restore(f.backend())},{f.journal().keepCurrent()})) {
                assertThrows(MigrationPublication.RecoveryRequired::class.java) {action()}
                assertArrayEquals(before,f.current.readBytes());assertEquals(writes,f.writes);assertTrue(f.storage.hasRecovery(f.uri))
            }
        }
    }
    @Test fun creationCannotClaimARegistryLocationThatChangedDuringWrite()=scenario {f->
        val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
        val next=bundle(File(f.root,"next"),intent.documentId);val otherId=UUID.randomUUID().toString()
        f.beforeWrite={f.storage.registry.register(otherId,"xnote","other",f.uri)}
        assertThrows(ReferenceContractViolation::class.java) {f.storage.publishCreated(f.uri,next,permit,"new")}
        assertEquals(otherId,f.storage.registry.idAt(f.uri));assertTrue(f.storage.hasRecovery(f.uri))
        assertArrayEquals(next.readBytes(),f.current.readBytes());assertEquals(1,f.writes)
        assertThrows(ReferenceContractViolation::class.java) {f.storage.completeCreation(f.uri,"new")}
        assertEquals(otherId,f.storage.registry.idAt(f.uri));assertEquals(1,f.writes)
    }
    @Test fun pendingPdfStaysProtectedAcrossFailureAndBecomesADurableNoteReference()=scenario {f->
        val resources=f.storage.pdfResources;val pdf=resources.admit("%PDF-original".byteInputStream(),"original")
        val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
        val next=bundle(File(f.root,"next"),intent.documentId,pdf=pdf.nameWithoutExtension);blockRegistration(f)
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishCreated(f.uri,next,permit,"new")}
        f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
        resources.collect();assertTrue(pdf.exists());assertTrue(f.storage.registry.owners("migration:").isNotEmpty())
        unblockRegistration(f);f.storage.completeCreation(f.uri,"new")
        assertEquals(setOf(pdf.nameWithoutExtension),resources.references("note:${intent.documentId}"))
        assertTrue(f.storage.registry.owners("migration:").isEmpty());resources.collect();assertTrue(pdf.exists())
    }
    @Test fun pageCopyAndCreationKeepTheSourceModelUnchangedUntilExplicitAdoption()=scenario {f->
        val document=Document.blank().apply {enableReferenceAnchors()}
        val originalId=document.identity.documentId;val originalPage=document.pages.single().referenceIdentity.id
        val intent=f.storage.beginCreation(DocumentKind.NOTE)
        val copy=PageReferenceCopy.prepare(document,intent.documentId);val snapshot=copy.project(document.snapshot())
        val next=File(f.root,"next");next.outputStream().use {DocumentCodec(FakeImageCodec(),FakeTextMeasurer()).write(snapshot,it)}
        val permit=f.storage.prepareNewTarget(intent,f.uri);f.storage.publishCreated(f.uri,next,permit,"copy")
        assertEquals(originalId,document.identity.documentId);assertEquals(originalPage,document.pages.single().referenceIdentity.id)
        val reopened=f.current.inputStream().use {DocumentCodec(FakeImageCodec(),FakeTextMeasurer()).read(it)}
        assertEquals(intent.documentId,reopened.identity.documentId);assertNotEquals(originalPage,reopened.pages.single().referenceIdentity.id)
        copy.adopt(document);assertEquals(reopened.pages.single().referenceIdentity.id,document.pages.single().referenceIdentity.id)
    }
    @Test fun cancelledPreparationConsumesItsRequestWithoutWritingOrStaging()=scenario {f->
        val intent=f.storage.beginCreation(DocumentKind.NOTE)
        assertThrows(CancellationException::class.java) {f.storage.prepareNewTarget(intent,f.uri) {throw CancellationException()}}
        assertThrows(ReferenceContractViolation::class.java) {f.storage.prepareNewTarget(intent,f.uri)}
        assertEquals(0,f.writes);assertEquals(0L,f.current.length());assertFalse(f.storage.hasRecovery(f.uri))
    }
    @Test fun committedRegistrationWithFailedFinalReadbackRetriesWithoutWritingAgain()=scenario {f->
        val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
        val next=bundle(File(f.root,"next"),intent.documentId)
        f.beforeRead={if(f.storage.registry.idAt(f.uri)==intent.documentId) throw IOException("readback unavailable after DB commit")}
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishCreated(f.uri,next,permit,"new")}
        assertEquals(intent.documentId,f.storage.registry.idAt(f.uri));assertTrue(f.storage.hasRecovery(f.uri));assertEquals(1,f.writes)
        f.beforeRead=null
        val receipt=f.storage.completeCreation(f.alias,"new")
        assertEquals(intent.documentId,receipt.documentId);assertEquals(1,f.writes);assertFalse(f.storage.hasRecovery(f.uri))
        assertTrue(f.storage.registry.expectationForId(intent.documentId).requiresPages)
    }
    @Test fun retryRejectsRegisteredKindContractAndOtherLocationConflicts() {
        for(conflict in listOf("kind","contract","other-location","orphan-contract")) scenario {f->
            val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
            val next=bundle(File(f.root,"next"),intent.documentId,anchored=conflict!="contract");blockRegistration(f)
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishCreated(f.uri,next,permit,"new")}
            unblockRegistration(f)
            val registry=f.storage.registry
            if(conflict=="orphan-contract") registry.writableDatabase.execSQL("INSERT INTO page_reference_contracts(document_id) VALUES(?)",arrayOf(intent.documentId))
            else registry.register(intent.documentId,if(conflict=="kind") "xcanvas" else "xnote","known",if(conflict=="other-location") f.other else f.uri,pageReferences=true)
            val before=f.current.readBytes()
            assertThrows(ReferenceContractViolation::class.java) {f.storage.completeCreation(f.uri,"new")}
            assertArrayEquals(before,f.current.readBytes());assertEquals(1,f.writes);assertTrue(f.storage.hasRecovery(f.uri))
        }
    }
    @Test fun aForeignChangeAfterStagingRetainsBothCreationVersionsAndDoesNotWrite()=scenario {f->
        val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
        val next=bundle(File(f.root,"next"),intent.documentId);var changed=false
        f.beforeRead={if(f.storage.hasRecovery(f.uri) && !changed) {changed=true;f.current.writeText("foreign after staging")}}
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishCreated(f.uri,next,permit,"new")}
        assertTrue(changed);assertEquals(0,f.writes);assertEquals("foreign after staging",f.current.readText());assertTrue(f.storage.hasRecovery(f.uri))
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.completeCreation(f.uri,"new")}
        assertEquals(0,f.writes)
    }
    @Test fun aCreateRecordWithANonemptyOriginalCannotBeUsedAsCreationAuthority()=scenario {f->
        f.current.writeText("existing original");val next=bundle(File(f.root,"next"),UUID.randomUUID().toString())
        f.journal().stage(f.current,next,MigrationPublication.Purpose.CREATE_NOTE)
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.completeCreation(f.uri,"new")}
        assertEquals("existing original",f.current.readText());assertEquals(0,f.writes);assertTrue(f.storage.hasRecovery(f.uri))
    }
    @Test fun headerFallbackLeasesPdfDuringValidationAndHandsItToThePendingPin()=scenario {f->
        val resources=f.storage.pdfResources;val pdf=resources.admit("%PDF-fallback".byteInputStream(),"original")
        val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
        val next=bundle(File(f.root,"next"),intent.documentId,pdf=pdf.nameWithoutExtension)
        // A ZIP comment forces the bounded central-metadata hint path before the long local scan.
        val bytes=next.readBytes();val comment="fallback".toByteArray()
        bytes[bytes.size-2]=comment.size.toByte();bytes[bytes.size-1]=0;next.writeBytes(bytes+comment)
        var validations=0
        f.validating={
            validations++;f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
            resources.collect();assertTrue("original must be leased before a full scan",pdf.isFile)
        }
        blockRegistration(f)
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishCreated(f.uri,next,permit,"new")}
        assertTrue(validations>=2);assertTrue(pdf.isFile);assertTrue(f.storage.hasRecovery(f.uri))
        unblockRegistration(f);f.storage.completeCreation(f.uri,"new")
        assertEquals(1,f.writes);assertTrue(pdf.isFile);assertEquals(setOf(pdf.nameWithoutExtension),resources.references("note:${intent.documentId}"))
    }
    @Test fun failedValidationReleasesOnlyItsLeaseAndDoesNotStageOrDeleteThePdf()=scenario {f->
        val resources=f.storage.pdfResources;val pdf=resources.admit("%PDF-cancel".byteInputStream(),"original")
        val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
        val next=bundle(File(f.root,"next"),intent.documentId,pdf=pdf.nameWithoutExtension)
        f.validating={
            f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0");resources.collect();assertTrue(pdf.isFile)
            throw CancellationException("cancel during validation")
        }
        assertThrows(CancellationException::class.java) {f.storage.publishCreated(f.uri,next,permit,"new")}
        assertTrue(pdf.isFile);assertFalse(f.storage.hasRecovery(f.uri));assertEquals(0,f.writes)
        resources.collect();assertFalse("validation lease must have been released",pdf.exists())
        assertTrue(next.exists());assertEquals(0L,f.current.length())
    }

    @Test fun restartedCreationReacquiresPdfProtectionBeforePublicationFingerprinting()=scenario {f->
        val resources=f.storage.pdfResources;val pdf=resources.admit("%PDF-restart".byteInputStream(),"original")
        val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
        val next=bundle(File(f.root,"next"),intent.documentId,pdf=pdf.nameWithoutExtension);blockRegistration(f)
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishCreated(f.uri,next,permit,"new")}
        unblockRegistration(f)
        // Rebuild protection even if a restored registry lost old pins; do not depend on another model's lease.
        for(owner in f.storage.registry.owners("migration:")+"note:${intent.documentId}") resources.pin(owner,emptySet())
        f.beforeRead={f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0");resources.collect();assertTrue(pdf.isFile)}
        val restarted=NotebookStorage(f.context,targetOverride={f.backend()})
        try {
            val receipt=restarted.completeCreation(f.uri,"new")
            assertEquals(intent.documentId,receipt.documentId);assertEquals(1,f.writes);assertTrue(pdf.exists())
        } finally {restarted.registry.close()}
    }
    @Test fun cancellationKeepsItsIdentityWhenLeaseCleanupAlsoFails()=scenario {f->
        val resources=f.storage.pdfResources;val pdf=resources.admit("%PDF-cleanup".byteInputStream(),"original")
        val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
        val next=bundle(File(f.root,"next"),intent.documentId,pdf=pdf.nameWithoutExtension)
        val cancelled=CancellationException("cancelled validation")
        f.validating={
            f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_lease_release BEFORE DELETE ON leases BEGIN SELECT RAISE(ABORT,'release failed'); END")
            throw cancelled
        }
        val thrown=assertThrows(CancellationException::class.java) {f.storage.publishCreated(f.uri,next,permit,"new")}
        assertSame(cancelled,thrown);assertTrue(thrown.suppressed.isNotEmpty());assertEquals(0,f.writes)
        assertFalse(f.storage.hasRecovery(f.uri));assertEquals(0L,f.current.length())
    }

    @Test fun anInspectedCreationCannotLeaveARetryableJournalForADifferentUuid()=scenario {f->
        val intent=f.storage.beginCreation(DocumentKind.NOTE);f.storage.prepareNewTarget(intent,f.uri)
        val candidate=bundle(File(f.root,"next"),intent.documentId)
        val checked=NativeSourceVersion.inspect(candidate,f.storage.registry.locationKey(f.uri),DocumentKind.NOTE)
        val foreignId=UUID.randomUUID().toString();bundle(candidate,foreignId)
        assertThrows(ReferenceContractViolation::class.java) {
            f.journal().stage(f.current,candidate,MigrationPublication.Purpose.CREATE_NOTE,expectedCandidateHash=checked.version.sha256)
        }
        assertFalse(f.storage.hasRecovery(f.uri));assertEquals(0L,f.current.length())
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.completeCreation(f.uri,"new")}
        assertEquals(0,f.writes);assertNull(f.storage.registry.idAt(f.uri))
    }
    @Test fun aCallerChangingItsCandidateAfterStagingDoesNotChangeThePublishedReceipt()=scenario {f->
        val intent=f.storage.beginCreation(DocumentKind.NOTE);val permit=f.storage.prepareNewTarget(intent,f.uri)
        val next=bundle(File(f.root,"next"),intent.documentId);val expected=next.readBytes();val hash=BundleMigration.hash(next)
        f.beforeWrite={bundle(next,UUID.randomUUID().toString(),body="unrelated candidate")}
        val receipt=f.storage.publishCreated(f.uri,next,permit,"new")
        assertEquals(intent.documentId,receipt.documentId);assertEquals(hash,receipt.sha256)
        assertArrayEquals(expected,f.current.readBytes());assertNotEquals(hash,BundleMigration.hash(next));assertEquals(1,f.writes)
    }

}
