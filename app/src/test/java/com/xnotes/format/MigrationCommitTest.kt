package com.xnotes.format

import com.xnotes.storage.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MigrationCommitTest {
    private class Fixture(val root:File) {
        val original=File(root,"old.bin").apply {writeText("original")}
        val next=File(root,"new.bin").apply {writeText("candidate")}
        val current=File(root,"current.bin").apply {writeText("original")}
        val recovery=File(root,"recovery")
        val journal=MigrationPublication(recovery).apply {stage(original,next)}
        var writes=0
        val target=object:MigrationPublication.Target {
            override fun read()=current.inputStream()
            override fun confirmedMissing()=!current.exists()
            override fun write(source:File) {writes++;source.copyTo(current,overwrite=true)}
        }
        fun backups() {assertTrue(journal.pending);assertTrue(File(recovery,"original.zip").isFile);assertTrue(File(recovery,"candidate.zip").isFile)}
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("migration-commit-").toFile()
        try {block(Fixture(root))} finally {root.deleteRecursively()}
    }
    @Test fun registrationFailureRetainsBothVersionsAndRetryDoesNotWriteTheFileAgain() = scenario {f->
        val failure=IllegalStateException("database unavailable");val seen=mutableListOf<String>()
        val validate:(File,File,File)->Unit={_,_,current->seen+=current.readText()}
        val error=assertThrows(MigrationPublication.RecoveryRequired::class.java) {
            f.journal.publish(f.target,validate=validate,afterVerified={throw failure})
        }
        assertSame(failure,error.cause);assertEquals(StorageMsg.MIGRATION_INDEX_INCOMPLETE,error.reason);f.backups();assertEquals("candidate",f.current.readText());assertEquals(1,f.writes)
        f.journal.publish(f.target,validate=validate,afterVerified={assertEquals("candidate",it.readText());assertTrue(f.journal.pending)})
        assertFalse(f.journal.pending);assertEquals(1,f.writes);assertEquals(listOf("original","candidate"),seen)
    }
    @Test fun typedPreflightFailurePreventsWritesAndKeepsRecoveryEvidence() = scenario {f->
        val failure=ReferenceContractViolation(StorageMsg.REFERENCE_PAGE_METADATA_MISSING)
        assertSame(failure,assertThrows(ReferenceContractViolation::class.java) {f.journal.publish(f.target,validate={_,_,_->throw failure})})
        assertEquals(0,f.writes);assertEquals("original",f.current.readText());f.backups()
    }
    @Test fun sourceAndRetainedFileChangesDuringPreflightAreRejectedBeforeWriting() = scenario {f->
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.journal.publish(f.target,validate={_,_,_->f.current.writeText("external change")})}
        assertEquals("external change",f.current.readText());assertEquals(0,f.writes);f.backups()
        f.current.writeText("original")
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.journal.publish(f.target,validate={_,candidate,_->candidate.writeText("tampered")})}
        assertEquals("original",f.current.readText());assertEquals(0,f.writes);f.backups()
    }
    @Test fun targetOrBackupChangesDuringSuccessfulRegistrationStillPreventCleanup() = scenario {f->
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.journal.publish(f.target,afterVerified={f.current.writeText("external after registration")})}
        assertEquals("external after registration",f.current.readText());f.backups()
    }
    @Test fun restoreAndKeepCurrentRegistrationFailuresPreserveRecoveryUntilSuccessfulRetry() = scenario {f->
        f.current.writeText("partial")
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.journal.restore(f.target,afterVerified={throw IOException("database failed")})}
        assertEquals("original",f.current.readText());f.backups();assertEquals(1,f.writes)
        f.journal.restore(f.target,afterVerified={assertEquals("original",it.readText())})
        assertFalse(f.journal.pending);assertEquals(1,f.writes)
        f.journal.stage(f.original,f.next)
        var checks=0
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.journal.keepCurrent(validate={_,_->checks++},afterVerified={throw IOException("database failed")})}
        f.backups()
        f.journal.keepCurrent(validate={_,_->checks++},afterVerified={assertTrue(f.journal.pending)})
        assertEquals(3,checks);assertFalse(f.journal.pending)
    }
    @Test fun keepCurrentRechecksAfterRegistrationAndDoesNotLoseTheOnlyBackup() = scenario {f->
        var checked=0
        assertThrows(ReferenceContractViolation::class.java) {f.journal.keepCurrent(validate={_,_->
            checked++;if(checked==2) throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH)
        })}
        assertEquals(2,checked);f.backups()
    }
    @Test fun stageDigestsSurviveRecreationAndDetectAChangedCandidateBeforeAnyWrite() = scenario {f->
        val marker=File(f.recovery,"pending").readText()
        assertEquals(listOf("2",BundleMigration.hash(f.original),BundleMigration.hash(f.next)),marker.split('\n'))
        File(f.recovery,"candidate.zip").writeText("different but well-formed future candidate")
        val resumed=MigrationPublication(f.recovery)
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {resumed.publish(f.target)}
        assertEquals("original",f.current.readText());assertEquals(0,f.writes);f.backups()
        resumed.restore(f.target)
        assertEquals("original",f.current.readText());assertFalse(resumed.pending)
    }
    @Test fun legacyRecordRemainsReadableButUnknownOrOversizedRecordsAreRetained() = scenario {f->
        val marker=File(f.recovery,"pending")
        marker.writeText("1");f.journal.publish(f.target);assertFalse(f.journal.pending)
        for(text in listOf("3\nfuture","x".repeat(257))) {
            f.current.writeText("original");f.journal.stage(f.original,f.next);marker.writeText(text)
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.journal.publish(f.target)}
            assertEquals("original",f.current.readText());f.backups()
            if(text.startsWith("3\n")) {
                assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.journal.keepCurrent()};f.backups()
                marker.writeText("1") // Test cleanup only: production never downgrades a typed record.
            }
            f.journal.keepCurrent()
        }
    }
    @Test fun typedFailuresAndCancellationPassThroughReadWriteReadbackAndRegistration() = scenario {f->
        val failures=listOf<Exception>(ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH),CancellationException("stopped"))
        for(failure in failures) for(where in listOf("read","stream","write","readback","registration")) {
            f.current.writeText("original");var writes=0
            val target=object:MigrationPublication.Target {
                override fun read():java.io.InputStream {
                    if(where=="read" || where=="readback" && writes>0) throw failure
                    if(where=="stream") return object:java.io.InputStream() {override fun read():Int=throw failure}
                    return f.current.inputStream()
                }
                override fun write(source:File) {if(where=="write") throw failure;writes++;source.copyTo(f.current,overwrite=true)}
            }
            assertSame(failure,assertThrows(failure.javaClass) {
                f.journal.publish(target,afterVerified={if(where=="registration") throw failure})
            });f.backups()
        }
    }
    @Test fun successfulRegistrationCannotAlterAnyRetainedRecoveryFileAndThenClearIt() {
        for(name in listOf("original.zip","candidate.zip","pending")) scenario {f->
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.journal.publish(f.target,afterVerified={
                File(f.recovery,name).appendText("modified by callback")
            })}
            assertEquals("candidate",f.current.readText());f.backups()
        }
    }
    @Test fun restoreAndKeepCurrentPreserveTypedFailureAndCancellationFromTheirCallbacks() = scenario {f->
        for(error in listOf<Exception>(ReferenceContractViolation(StorageMsg.REFERENCE_PAGE_METADATA_MISSING),CancellationException("cancel"))) {
            assertSame(error,assertThrows(error.javaClass) {f.journal.restore(f.target,validate={throw error})});f.backups()
            assertSame(error,assertThrows(error.javaClass) {f.journal.restore(f.target,afterVerified={throw error})});f.backups()
            assertSame(error,assertThrows(error.javaClass) {f.journal.keepCurrent(validate={_,_->throw error})});f.backups()
            assertSame(error,assertThrows(error.javaClass) {f.journal.keepCurrent(afterVerified={throw error})});f.backups()
        }
    }
    @Test fun originalDigestMismatchAndDamagedRecordCannotBeSilentlyRestored() = scenario {f->
        val original=File(f.recovery,"original.zip");original.writeText("not the staged original")
        val resumed=MigrationPublication(f.recovery)
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {resumed.restore(f.target)}
        assertEquals(0,f.writes);assertEquals("original",f.current.readText());f.backups()
        original.writeText("original");File(f.recovery,"pending").writeText("2\ninvalid-hash\ninvalid-hash")
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {resumed.publish(f.target)}
        assertEquals(0,f.writes);f.backups()
    }

    @Test fun missingTargetAndDamagedCandidateStillAllowExplicitRestoreOfVerifiedOriginal() = scenario {f->
        File(f.recovery,"candidate.zip").delete();File(f.recovery,"candidate.zip").mkdir()
        f.current.delete()
        f.journal.restore(f.target)
        assertEquals("original",f.current.readText());assertEquals(1,f.writes);assertFalse(f.journal.pending)
    }
    @Test fun unreadableTargetIsNotMistakenForConfirmedMissingAndIsNotOverwritten() = scenario {f->
        val denied=object:MigrationPublication.Target {
            override fun read():java.io.InputStream=throw SecurityException("read access denied")
            override fun write(source:File)=throw AssertionError("must not overwrite an unreadable existing target")
        }
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.journal.restore(denied)}
        assertEquals("original",f.current.readText());f.backups()
    }

    @Test fun parsedRecordCannotBeReplacedWhileRetainedFilesAreBeingHashed() = scenario {f->
        var changed=false
        val resumed=MigrationPublication(f.recovery,hashFile={file->
            if(file.name=="original.zip" && !changed) {changed=true;File(f.recovery,"pending").writeText("3\nfuture record")}
            BundleMigration.hash(file)
        })
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {resumed.publish(f.target)}
        assertTrue(changed);assertEquals(0,f.writes);assertEquals("original",f.current.readText());f.backups()
    }
    @Test fun unreadableCandidateCannotPreventRestoringTheVerifiedOriginal() = scenario {f->
        f.current.writeText("partial")
        val resumed=MigrationPublication(f.recovery,hashFile={file->
            if(file.name=="candidate.zip") throw IOException("candidate is unreadable") else BundleMigration.hash(file)
        })
        resumed.restore(f.target)
        assertEquals("original",f.current.readText());assertFalse(resumed.pending)
    }

    @Test fun transientCandidateReadFailureDoesNotCountAsContentChangeWhenItBecomesReadable() = scenario {f->
        f.current.writeText("partial");var attempts=0
        val resumed=MigrationPublication(f.recovery,hashFile={file->
            if(file.name=="candidate.zip" && attempts++==0) throw IOException("transient read failure")
            BundleMigration.hash(file)
        })
        resumed.restore(f.target)
        assertEquals("original",f.current.readText());assertFalse(resumed.pending)
    }

}
