package com.xnotes.storage

import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleMigration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeCreationPendingTest {
    private fun scenario(block:(CreationPendingFixture)->Unit) {
        val f=CreationPendingFixture(Files.createTempDirectory("creation-pending-").toFile())
        try {block(f)} finally {f.close()}
    }
    @Test fun postWriteFailureCanCompleteOnlyItsOwnRetainedNoteOrCanvasWithoutRewriting() {
        for(kind in listOf(DocumentKind.NOTE,DocumentKind.CANVAS)) scenario {f->
            val (intent,candidate)=f.failed(kind);val bytes=candidate.readBytes();val hash=BundleMigration.hash(candidate)
            val pending=f.storage.retainedCreation(intent,f.alias,"created")
            assertNotNull(pending);candidate.writeText("caller temporary was replaced")
            val receipt=f.storage.completeCreation(pending!!)
            assertEquals(intent.documentId,receipt.documentId);assertEquals(kind,receipt.kind);assertEquals(hash,receipt.sha256)
            assertArrayEquals(bytes,f.current.readBytes());assertEquals(1,f.writes);assertFalse(f.marker.exists())
            assertEquals(intent.documentId,f.storage.registry.idAt(f.uri))
            assertNull(f.storage.retainedCreation(intent,f.uri,"created"))
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.completeCreation(pending)}
        }
    }
    @Test fun anUnstagedIntentCannotObserveItselfIntoAuthorityAndForeignIssuersCannotUseTokens()=scenario {f->
        val untouched=f.storage.beginCreation(DocumentKind.NOTE)
        assertNull(f.storage.retainedCreation(untouched,f.uri,"none"))
        val (intent,_)=f.failed();val before=f.retainedBytes();val current=f.current.readBytes();val writes=f.writes
        assertNull(f.storage.retainedCreation(untouched,f.uri,"foreign"))
        val pending=f.storage.retainedCreation(intent,f.alias,"created")!!
        assertThrows(ReferenceContractViolation::class.java) {f.storage.retainedCreation(intent,f.other,"wrong location")}
        val other=NotebookStorage(f.context,targetOverride={f.backend()})
        try {
            assertThrows(ReferenceContractViolation::class.java) {other.retainedCreation(intent,f.uri,"foreign issuer")}
            assertThrows(ReferenceContractViolation::class.java) {other.completeCreation(pending)}
        } finally {other.registry.close()}
        f.assertRetained(before);assertArrayEquals(current,f.current.readBytes());assertEquals(writes,f.writes)
    }
    @Test fun recognitionRejectsAReplacementRecordEvenWhenItReusesThisIntentUuid()=scenario {f->
        val (intent,_)=f.failed();val oldDigest=BundleMigration.hash(f.marker)
        f.replace(intent.documentId);val changed=f.retainedBytes();val current=f.current.readBytes();val reads=f.reads
        assertNotEquals(oldDigest,BundleMigration.hash(f.marker))
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.retainedCreation(intent,f.alias,"created")}
        f.assertRetained(changed);assertArrayEquals(current,f.current.readBytes());assertEquals(reads,f.reads);assertEquals(1,f.writes)
    }
    @Test fun aPreviouslyRecognizedTokenCannotCompleteAnotherOrChangedJournal() {
        for(sameId in listOf(false,true)) scenario {f->
            val (intent,_)=f.failed();val pending=f.storage.retainedCreation(intent,f.uri,"created")!!
            f.replace(if(sameId) intent.documentId else UUID.randomUUID().toString())
            val changed=f.retainedBytes();val current=f.current.readBytes();val reads=f.reads;val writes=f.writes
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.completeCreation(pending)}
            f.assertRetained(changed);assertArrayEquals(current,f.current.readBytes());assertEquals(reads,f.reads);assertEquals(writes,f.writes)
        }
    }
    @Test fun damagedOrUnsupportedRetainedDataNeverProducesATokenOrGetsCleared() {
        for(damage in listOf("original","candidate","purpose","future","marker")) scenario {f->
            val (intent,_)=f.failed()
            when(damage) {
                "original"->File(f.directory,"original.zip").writeText("not empty")
                "candidate"->File(f.directory,"candidate.zip").appendText("tampered bytes")
                "purpose"->f.marker.writeText(f.marker.readText().replace("create-note","save-note"))
                "future"->f.marker.writeText(f.marker.readText().replaceFirst("3\n","9\n"))
                else->f.marker.writeText("malformed")
            }
            val damaged=f.retainedBytes();val current=f.current.readBytes();val writes=f.writes
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.retainedCreation(intent,f.uri,"created")}
            f.assertRetained(damaged);assertArrayEquals(current,f.current.readBytes());assertEquals(writes,f.writes)
        }
    }
    @Test fun typedRetryNeverOverwritesAPartialOrForeignTarget() {
        for(foreign in listOf(false,true)) scenario {f->
            val (intent,_)=f.failed();val pending=f.storage.retainedCreation(intent,f.uri,"created")!!
            if(foreign) f.bundle(UUID.randomUUID().toString()).copyTo(f.current,overwrite=true) else f.current.writeText("partial")
            val before=f.retainedBytes();val current=f.current.readBytes();val writes=f.writes
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.completeCreation(pending)}
            f.assertRetained(before);assertArrayEquals(current,f.current.readBytes());assertEquals(writes,f.writes)
        }
    }
    @Test fun pdfGcAndCancellationKeepTheAttemptAndReleaseOnlyTemporaryInspectionLeases()=scenario {f->
        val resources=f.storage.pdfResources
        val pdf=resources.admit(ByteArray(2*1024*1024) {it.toByte()}.inputStream(),"original")
        val (intent,_)=f.failed(pdf=pdf.nameWithoutExtension);val before=f.retainedBytes();val current=f.current.readBytes()
        var leased=false
        f.validating={
            assertTrue(f.leaseCount()>0);leased=true
            f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0");resources.collect();assertTrue(pdf.isFile)
        }
        val pending=f.storage.retainedCreation(intent,f.alias,"created")!!
        assertTrue(leased);assertEquals(0,f.leaseCount())
        val verified=PdfResources::class.java.getDeclaredField("verified").apply {isAccessible=true}
        (verified.get(resources) as MutableMap<*,*>).clear()
        var pdfChecks=0
        assertThrows(CancellationException::class.java) {f.storage.retainedCreation(intent,f.uri,"created") {
            if(Thread.currentThread().stackTrace.any {it.className==PdfResources::class.java.name && it.methodName.startsWith("resolveChecked")} && ++pdfChecks==3)
                throw CancellationException("stop first external PDF hash")
        }}
        assertEquals(3,pdfChecks)
        assertEquals(0,f.leaseCount());f.assertRetained(before);assertArrayEquals(current,f.current.readBytes())
        var retainedChecks=0;val reads=f.reads
        assertThrows(CancellationException::class.java) {f.storage.completeCreation(pending) {
            if(Thread.currentThread().stackTrace.any {it.className==MigrationPublication::class.java.name && it.methodName=="retained"} && ++retainedChecks==3)
                throw CancellationException("stop actual retained fingerprint")
        }}
        assertEquals(3,retainedChecks);assertEquals(reads,f.reads);assertEquals(0,f.leaseCount());f.assertRetained(before)
        f.beforeRead={throw CancellationException("stop retry target read")}
        assertThrows(CancellationException::class.java) {f.storage.completeCreation(pending)}
        f.beforeRead=null;assertEquals(0,f.leaseCount());f.assertRetained(before);assertEquals(1,f.writes);assertTrue(pdf.isFile)
        f.storage.completeCreation(pending);assertEquals(1,f.writes);assertFalse(f.marker.exists());assertTrue(pdf.isFile)
        assertEquals(setOf(pdf.nameWithoutExtension),resources.references("note:${intent.documentId}"));assertEquals(0,f.leaseCount())
    }
}
