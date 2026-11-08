package com.xnotes.storage

import android.content.ContextWrapper
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.Document
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.snapshot
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleMigration
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeWrittenSaveTest {
    private class Fixture(val root:File) {
        val uri="content://written-save/document/source"
        val alias="content://written-save/tree/root/document/source"
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val current=File(root,"source");val candidate=File(root,"candidate")
        var beforeRead:(()->Unit)?=null;var beforeWrite:(()->Unit)?=null;var afterWrite:(()->Unit)?=null
        var nativeHook:(()->Unit)?=null;var writes=0
        val storage=NotebookStorage(context,targetOverride={object:MigrationPublication.Target {
            override fun read():java.io.InputStream {beforeRead?.invoke();return current.inputStream()}
            override fun write(source:File) {writes++;beforeWrite?.invoke();source.copyTo(current,overwrite=true);afterWrite?.invoke()}
        }},sourceValidationHook={nativeHook?.invoke()})
        val noteCodec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer()).apply {resolvePdf={storage.pdfResources.resolve(it)}}
        val canvasCodec=CanvasCodec(FakeImageCodec(),FakeTextMeasurer())
        val note=Document.blank(2).apply {enableReferenceAnchors()}
        val canvas=InfiniteDocument()
        var kind=DocumentKind.NOTE;var external=false
        val id get()=if(kind==DocumentKind.NOTE) note.identity.documentId else canvas.identity.documentId
        val journalDir:File get() {
            val key=storage.registry.locationKey(uri)
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {"%02x".format(it)}
            return File(context.noBackupFilesDir,"migrations/$hash")
        }
        fun prepare(kind:DocumentKind=DocumentKind.NOTE,pdf:Boolean=false,external:Boolean=false) {
            this.kind=kind;this.external=external
            if(pdf) {
                val file=storage.pdfResources.admit("%PDF-written-original".byteInputStream(),"original")
                note.pdfFile=file
                if(external) note.identity=note.identity.copy(pdfResource=file.nameWithoutExtension)
            }
            encode(current)
            if(kind==DocumentKind.NOTE) note.bookmarks.add(Bookmark(0,"saved B")) else canvas.lastView=Waypoint("B",1.0,2.0,3.0)
            encode(candidate)
        }
        fun encode(file:File) {file.outputStream().use {out->
            if(kind==DocumentKind.NOTE) noteCodec.write(note.snapshot(),out,embedPdf=!external)
            else canvasCodec.write(canvas.snapshotForWrite(),out)
        }}
        fun stage(purpose:MigrationPublication.Purpose?=if(kind==DocumentKind.NOTE) MigrationPublication.Purpose.SAVE_NOTE else MigrationPublication.Purpose.SAVE_CANVAS) {
            MigrationPublication(journalDir).stage(current,candidate,purpose)
        }
        fun writtenFailure() {
            afterWrite={throw java.io.IOException("provider failed after copying every byte")}
            val error=assertThrows(MigrationPublication.RecoveryRequired::class.java) {storage.publishNote(uri,candidate,kind,"source")}
            assertEquals(StorageMsg.MIGRATION_WRITE_INCOMPLETE,error.reason);assertTrue(storage.hasRecovery(uri))
            assertArrayEquals(candidate.readBytes(),current.readBytes());afterWrite=null
        }
        fun retainedBytes()=journalDir.listFiles().orEmpty().associate {it.name to it.readBytes().toList()}
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("native-written-").toFile();val f=Fixture(root)
        try {block(f)} finally {f.storage.registry.close();root.deleteRecursively()}
    }
    @Test fun fullyCopiedNoteAndCanvasAreKeptWithoutAnySecondTargetWrite() {
        for(kind in listOf(DocumentKind.NOTE,DocumentKind.CANVAS)) scenario {f->
            f.prepare(kind);val original=f.current.readBytes();f.writtenFailure()
            val target=f.current.readBytes();assertFalse(original.contentEquals(target));val hash=BundleMigration.hash(f.current)
            val read=f.storage.keepWrittenSave(f.alias,kind)
            assertEquals(hash,read.sha256);assertEquals(f.id,read.documentId);assertEquals(kind,read.kind)
            assertEquals(f.storage.registry.locationKey(f.uri),read.locationKey)
            assertEquals(1,f.writes);assertArrayEquals(target,f.current.readBytes());assertFalse(f.storage.hasRecovery(f.uri))
            assertEquals(f.id,f.storage.registry.idAt(f.uri))
            if(kind==DocumentKind.NOTE) assertEquals(f.note.pages.map {it.referenceIdentity.id},f.current.inputStream().use {f.noteCodec.read(it)}.pages.map {it.referenceIdentity.id})
        }
    }
    @Test fun indexFailureRetainsTheWrittenVersionUntilRegistrationCanBeCompleted()=scenario {f->
        f.prepare();f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_index BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT,'failed index'); END")
        val error=assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.publishNote(f.uri,f.candidate,f.kind,"source")}
        assertEquals(StorageMsg.MIGRATION_INDEX_INCOMPLETE,error.reason)
        val before=f.current.readBytes();val retained=f.retainedBytes()
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.keepWrittenSave(f.uri,f.kind)}
        assertEquals(retained,f.retainedBytes());assertArrayEquals(before,f.current.readBytes());assertEquals(1,f.writes)
        f.storage.registry.writableDatabase.execSQL("DROP TRIGGER fail_index")
        assertEquals(BundleMigration.hash(f.current),f.storage.keepWrittenSave(f.uri,f.kind).sha256)
        assertFalse(f.storage.hasRecovery(f.uri));assertEquals(1,f.writes)
    }
    @Test fun byteIdenticalPendingSaveIsAcceptedWithoutWritingTheFile()=scenario {f->
        f.prepare();f.current.copyTo(f.candidate,overwrite=true);f.stage()
        val before=f.current.readBytes();assertEquals(BundleMigration.hash(f.current),f.storage.keepWrittenSave(f.uri,f.kind).sha256)
        assertEquals(0,f.writes);assertArrayEquals(before,f.current.readBytes());assertFalse(f.storage.hasRecovery(f.uri))
    }
    @Test fun originalPartialAndExternalCurrentVersionsAreNeverCalledTheWrittenCandidate() {
        for(mode in 0..2) scenario {f->
            f.prepare();f.stage()
            when(mode) {1->f.current.writeText("partial");2->{f.note.bookmarks.add(Bookmark(0,"external C"));f.encode(f.current)}}
            val before=f.current.readBytes();val retained=f.retainedBytes()
            assertThrows(Exception::class.java) {f.storage.keepWrittenSave(f.uri,f.kind)}
            assertEquals(0,f.writes);assertArrayEquals(before,f.current.readBytes());assertEquals(retained,f.retainedBytes());assertTrue(f.storage.hasRecovery(f.uri))
        }
    }
    @Test fun creationLegacyWrongKindAndUnknownRecordsCannotBeDemotedToAnOrdinarySave() {
        for(mode in 0..4) scenario {f->
            f.prepare();f.stage(when(mode) {0->MigrationPublication.Purpose.CREATE_NOTE;1->null;else->MigrationPublication.Purpose.SAVE_NOTE})
            f.candidate.copyTo(f.current,overwrite=true)
            if(mode==3) File(f.journalDir,"pending").writeText("4\nfuture")
            if(mode==4) File(f.journalDir,"pending").writeText("3\nbad hashes\nsave-note")
            val before=f.current.readBytes();val retained=f.retainedBytes()
            assertThrows(Exception::class.java) {f.storage.keepWrittenSave(f.uri,if(mode==2) DocumentKind.CANVAS else f.kind)}
            assertEquals(0,f.writes);assertArrayEquals(before,f.current.readBytes());assertEquals(retained,f.retainedBytes())
        }
    }
    @Test fun damagedOriginalOrCandidateCannotAuthorizeDiscardingTheRecoveryRecord() {
        for(name in listOf("original.zip","candidate.zip")) scenario {f->
            f.prepare();f.stage();f.candidate.copyTo(f.current,overwrite=true);File(f.journalDir,name).appendText("damaged")
            val before=f.current.readBytes();val retained=f.retainedBytes()
            assertThrows(Exception::class.java) {f.storage.keepWrittenSave(f.uri,f.kind)}
            assertArrayEquals(before,f.current.readBytes());assertEquals(retained,f.retainedBytes());assertEquals(0,f.writes)
        }
    }
    @Test fun permissionsAndCancellationLeaveEveryRecoveryFileAndTargetUntouched() {
        for(cancel in listOf(false,true)) scenario {f->
            f.prepare();f.stage();f.candidate.copyTo(f.current,overwrite=true);val before=f.current.readBytes();val retained=f.retainedBytes()
            if(cancel) assertThrows(CancellationException::class.java) {f.storage.keepWrittenSave(f.uri,f.kind) {throw CancellationException()}}
            else {
                f.beforeRead={throw SecurityException("permission lost")}
                assertThrows(SecurityException::class.java) {f.storage.keepWrittenSave(f.uri,f.kind)}
            }
            assertEquals(0,f.writes);assertArrayEquals(before,f.current.readBytes());assertEquals(retained,f.retainedBytes())
        }
    }
    @Test fun externalPdfIsLeasedBeforeScanningAndAdoptedByTheRegisteredNote()=scenario {f->
        f.prepare(pdf=true,external=true);f.writtenFailure();val pdf=f.note.pdfFile!!;var protected=false
        f.nativeHook={
            // Only the temporary header lease survives this forced absence of old durable pins.
            f.storage.registry.writableDatabase.execSQL("DELETE FROM resource_refs")
            f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0")
            f.storage.pdfResources.collect();assertTrue(pdf.isFile);protected=true
        }
        val before=f.current.readBytes();f.storage.keepWrittenSave(f.uri,f.kind)
        assertTrue(protected);assertArrayEquals(before,f.current.readBytes());assertEquals(1,f.writes)
        assertTrue(f.storage.pdfResources.references("note:${f.id}").contains(pdf.nameWithoutExtension))
        f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {it.moveToFirst();assertEquals(0,it.getInt(0))}
    }
    @Test fun registrationCannotOverwriteAForeignLocationThatAppearedAfterValidation()=scenario {f->
        f.prepare(pdf=true,external=true);f.writtenFailure();val pdf=f.note.pdfFile!!
        f.storage.pdfResources.pin("note:${f.id}",emptySet())
        val foreign=java.util.UUID.randomUUID().toString();var injected=false
        val before=f.current.readBytes();val retained=f.retainedBytes()
        assertThrows(ReferenceContractViolation::class.java) {f.storage.keepWrittenSave(f.uri,f.kind) {
            if(!injected && f.storage.pdfResources.references("note:${f.id}").contains(pdf.nameWithoutExtension)) {
                injected=true;f.storage.registry.register(foreign,"xnote","foreign",f.uri)
            }
        }}
        assertTrue(injected);assertEquals(foreign,f.storage.registry.idAt(f.uri))
        assertArrayEquals(before,f.current.readBytes());assertEquals(retained,f.retainedBytes());assertEquals(1,f.writes)
    }
    @Test fun targetChangedAfterRegistrationKeepsThePendingJournalWithoutRestoringItsOriginal()=scenario {f->
        f.prepare();f.writtenFailure();val retained=f.retainedBytes()
        f.note.bookmarks.add(Bookmark(0,"external C"));val foreign=File(f.root,"foreign");f.encode(foreign)
        var reads=0;var changed=false
        f.beforeRead={if(++reads>=3 && !changed) {foreign.copyTo(f.current,overwrite=true);changed=true}}
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.keepWrittenSave(f.uri,f.kind)}
        assertTrue(changed);assertArrayEquals(foreign.readBytes(),f.current.readBytes());assertEquals(retained,f.retainedBytes());assertEquals(1,f.writes)
    }
    @Test fun oldExplicitKeepCurrentStillAllowsItsKnownLegacyCandidate()=scenario {f->
        f.prepare();f.stage(null);f.candidate.copyTo(f.current,overwrite=true)
        assertThrows(Exception::class.java) {f.storage.keepWrittenSave(f.uri,f.kind)}
        f.storage.keepCurrent(f.uri);assertFalse(f.storage.hasRecovery(f.uri));assertArrayEquals(f.candidate.readBytes(),f.current.readBytes());assertEquals(0,f.writes)
    }
    @Test fun cancelAfterAcquiringThePdfLeasePreservesTheTargetAndDoesNotLeakTheLease()=scenario {f->
        f.prepare(pdf=true,external=true);f.writtenFailure();val before=f.current.readBytes();val retained=f.retainedBytes();var acquired=false
        f.nativeHook={acquired=true}
        assertThrows(CancellationException::class.java) {f.storage.keepWrittenSave(f.uri,f.kind) {if(acquired) throw CancellationException("after lease")}}
        assertTrue(acquired);assertArrayEquals(before,f.current.readBytes());assertEquals(retained,f.retainedBytes());assertEquals(1,f.writes)
        f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {it.moveToFirst();assertEquals(0,it.getInt(0))}
    }
    @Test fun leaseCleanupFailureAfterConfirmedKeepDoesNotReverseTheSuccess()=scenario {f->
        f.prepare(pdf=true,external=true);f.writtenFailure();val before=f.current.readBytes()
        f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_lease_cleanup BEFORE DELETE ON leases BEGIN SELECT RAISE(ABORT,'lease cleanup'); END")
        val kept=f.storage.keepWrittenSave(f.uri,f.kind)
        assertEquals(BundleMigration.hash(f.current),kept.sha256);assertFalse(f.storage.hasRecovery(f.uri));assertArrayEquals(before,f.current.readBytes());assertEquals(1,f.writes)
        assertTrue(f.storage.pdfResources.references("note:${f.id}").contains(f.note.pdfFile!!.nameWithoutExtension))
    }
    @Test fun knownKindAndPageContractsAreRecheckedInsideObservedRegistration()=scenario {f->
        f.prepare();val header=com.xnotes.format.BundleHeader.read(f.current)
        f.storage.registry.register(f.id,"xcanvas","wrong kind",f.uri)
        assertThrows(ReferenceContractViolation::class.java) {f.storage.registry.registerObserved(header,"xnote","must not register",f.uri)}
        val kind=f.storage.registry.readableDatabase.rawQuery("SELECT kind FROM documents WHERE id=?",arrayOf(f.id)).use {it.moveToFirst();it.getString(0)}
        assertEquals("xcanvas",kind)
        val legacy=Document.blank();val file=File(f.root,"legacy");file.outputStream().use {f.noteCodec.write(legacy.snapshot(),it)}
        val legacyHeader=com.xnotes.format.BundleHeader.read(file)
        val other="content://written-save/document/other"
        f.storage.registry.register(legacy.identity.documentId,"xnote","anchored evidence",other,pageReferences=true)
        assertThrows(ReferenceContractViolation::class.java) {f.storage.registry.registerObserved(legacyHeader,"xnote","lost metadata",other)}
        assertTrue(f.storage.registry.expectationForId(legacy.identity.documentId).requiresPages)
    }
    @Test fun aCorruptManagedPdfWithAnUnchangedStampCannotBeBlessedByWrittenRecovery()=scenario {f->
        f.prepare(pdf=true,external=true);f.writtenFailure();val pdf=f.note.pdfFile!!
        f.storage.pdfResources.holdResolved(setOf(pdf.nameWithoutExtension)).close()
        val stamp=pdf.lastModified();val bytes=pdf.readBytes();bytes[5]=(bytes[5].toInt() xor 1).toByte();pdf.writeBytes(bytes);assertTrue(pdf.setLastModified(stamp))
        val before=f.current.readBytes();val retained=f.retainedBytes()
        assertThrows(PdfResources.Unavailable::class.java) {f.storage.keepWrittenSave(f.uri,f.kind)}
        assertEquals(1,f.writes);assertArrayEquals(before,f.current.readBytes());assertEquals(retained,f.retainedBytes());assertArrayEquals(bytes,pdf.readBytes())
    }
}
