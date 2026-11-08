package com.xnotes.storage

import com.xnotes.format.BundleMigration
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PublicationHandoffTest {
    private val id="c04fba34-24e5-418b-840b-3c4ea4495f23"
    private fun scenario(block:(File,File,File)->Unit) {
        val root=Files.createTempDirectory("publication-handoff-").toFile()
        try {block(File(root,"current").apply {writeText("A")},File(root,"next").apply {writeText("B")},File(root,"journal"))}
        finally {root.deleteRecursively()}
    }
    private fun stage(current:File,next:File,dir:File):String {
        MigrationPublication(dir).stage(current,next,MigrationPublication.Purpose.SAVE_NOTE,
            expectedOriginalHash=BundleMigration.hash(current),expectedCandidateHash=BundleMigration.hash(next),handoffId=id)
        val text="4\n${BundleMigration.hash(current)}\n${BundleMigration.hash(next)}\nsave-note\n$id"
        assertEquals(text,File(dir,"pending").readText())
        return text
    }
    private fun target(current:File)=object:MigrationPublication.Target {
        override fun read()=current.inputStream()
        override fun write(source:File) {fail("unacknowledged handoff cannot write")}
    }
    @Test fun restartRecognizesTheSavePurposeButCannotPublishWithoutCacheAcknowledgement()=scenario {current,next,dir->
        val marker=stage(current,next,dir)
        repeat(2) {
            val journal=MigrationPublication(dir)
            assertEquals(MigrationPublication.Purpose.SAVE_NOTE,journal.purpose)
            var called=false
            val error=assertThrows(MigrationPublication.RecoveryRequired::class.java) {
                journal.publishValidated(target(current),validate={called=true},afterVerified={called=true},protectCandidate={called=true})
            }
            assertEquals(StorageMsg.MIGRATION_RECOVERY_PENDING,error.reason);assertFalse(called)
            assertEquals("A",current.readText());assertEquals(marker,File(dir,"pending").readText())
            assertEquals("A",File(dir,"original.zip").readText());assertEquals("B",File(dir,"candidate.zip").readText())
        }
    }
    @Test fun alreadyWrittenCandidateStillNeedsExplicitResolutionAndKeepsItsRecord()=scenario {current,next,dir->
        val marker=stage(current,next,dir);next.copyTo(current,overwrite=true)
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {MigrationPublication(dir).publishValidated(target(current),validate={})}
        assertEquals("B",current.readText());assertEquals(marker,File(dir,"pending").readText())
    }
    @Test fun explicitKeepCanValidateTheWrittenCandidateWithoutGrantingAPublishReceipt()=scenario {current,next,dir->
        stage(current,next,dir);next.copyTo(current,overwrite=true)
        var validated=0
        MigrationPublication(dir).keepCurrentValidated(validate={state->
            validated++;assertEquals(MigrationPublication.Purpose.SAVE_NOTE,state.purpose)
            assertEquals(id,state.handoffId)
            assertTrue(state.recordValid);assertTrue(state.originalVerified);assertTrue(state.candidateVerified)
            assertEquals(BundleMigration.hash(current),state.candidateHash)
        })
        assertEquals(2,validated);assertEquals("B",current.readText());assertFalse(MigrationPublication(dir).pending)
    }
    @Test fun explicitRestoreKeepsItsOriginalContractAndExposesOnlyHandoffData()=scenario {current,next,dir->
        stage(current,next,dir);next.copyTo(current,overwrite=true)
        val journal=MigrationPublication(dir);var writes=0
        journal.restoreValidated(object:MigrationPublication.Target {
            override fun read()=current.inputStream()
            override fun write(source:File) {writes++;source.copyTo(current,overwrite=true)}
        },validate={state->
            assertEquals(id,state.handoffId);assertEquals(MigrationPublication.Purpose.SAVE_NOTE,state.purpose)
            assertTrue(state.candidateStageProof);assertEquals(BundleMigration.hash(next),state.currentHash)
            assertEquals("A",state.original.readText())
        })
        assertEquals(1,writes);assertEquals("A",current.readText());assertFalse(journal.pending)
    }
    @Test fun invalidHandoffParametersDoNotTouchRetainedFiles()=scenario {current,next,dir->
        dir.mkdir();val original=File(dir,"original.zip").apply {writeText("previous retained original")}
        val candidate=File(dir,"candidate.zip").apply {writeText("previous retained candidate")}
        val oldHash=BundleMigration.hash(current);val newHash=BundleMigration.hash(next)
        data class Input(val purpose:MigrationPublication.Purpose?,val id:String?,val old:String?,val next:String?)
        val invalid=listOf(Input(null,id,oldHash,newHash),Input(MigrationPublication.Purpose.CREATE_NOTE,id,oldHash,newHash),
            Input(MigrationPublication.Purpose.CREATE_CANVAS,id,oldHash,newHash),Input(MigrationPublication.Purpose.SAVE_NOTE,"bad",oldHash,newHash),
            Input(MigrationPublication.Purpose.SAVE_NOTE,id,null,newHash),Input(MigrationPublication.Purpose.SAVE_NOTE,id,oldHash,null),
            Input(MigrationPublication.Purpose.SAVE_NOTE,id,"not a digest",newHash))
        for(input in invalid) {
            assertThrows(IllegalArgumentException::class.java) {MigrationPublication(dir).stage(current,next,input.purpose,
                expectedCandidateHash=input.next,expectedOriginalHash=input.old,handoffId=input.id)}
            assertEquals("previous retained original",original.readText());assertEquals("previous retained candidate",candidate.readText())
            assertFalse(MigrationPublication(dir).pending)
        }
    }
    @Test fun changedOriginalOrCandidateCannotPublishAHandoffMarker()=scenario {current,next,dir->
        val oldHash=BundleMigration.hash(current);val newHash=BundleMigration.hash(next)
        current.writeText("changed A")
        assertThrows(ReferenceContractViolation::class.java) {MigrationPublication(dir).stage(current,next,MigrationPublication.Purpose.SAVE_NOTE,
            expectedOriginalHash=oldHash,expectedCandidateHash=newHash,handoffId=id)}
        assertFalse(MigrationPublication(dir).pending)
        current.writeText("A");next.writeText("changed B")
        assertThrows(ReferenceContractViolation::class.java) {MigrationPublication(dir).stage(current,next,MigrationPublication.Purpose.SAVE_NOTE,
            expectedOriginalHash=oldHash,expectedCandidateHash=newHash,handoffId=id)}
        assertFalse(MigrationPublication(dir).pending);assertEquals("A",current.readText())
    }
    @Test fun bothNativeKindsIncludingIdenticalBytesStayBlockedBeforeAnyTargetRead()=scenario {current,_,dir->
        for(purpose in listOf(MigrationPublication.Purpose.SAVE_NOTE,MigrationPublication.Purpose.SAVE_CANVAS)) {
            val journal=MigrationPublication(File(dir,purpose.key));val hash=BundleMigration.hash(current)
            journal.stage(current,current,purpose,expectedOriginalHash=hash,expectedCandidateHash=hash,handoffId=id)
            assertEquals(purpose,journal.purpose)
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {journal.publishValidated(object:MigrationPublication.Target {
                override fun read():java.io.InputStream=error("unacknowledged no-op must not inspect the target")
                override fun write(source:File)=error("unacknowledged no-op must not write")
            },validate={fail("unacknowledged no-op cannot validate")})}
            assertTrue(journal.pending)
        }
    }
    @Test fun malformedHandoffsNeverFallBackToLegacyKeepOrWrite()=scenario {current,next,dir->
        val marker=stage(current,next,dir)
        for(text in listOf(marker.replace(id,"bad"),marker.replace(id,id.uppercase()),marker+"\nextra",
            marker.replace("save-note","create-note"),marker.replace("4\n","5\n"),marker.substringBeforeLast('\n'))) {
            File(dir,"pending").writeText(text)
            val journal=MigrationPublication(dir)
            for(action in listOf<()->Unit>({journal.publishValidated(target(current),validate={})},
                {journal.keepCurrentValidated(validate={})},{journal.restoreValidated(target(current),validate={})})) {
                assertThrows(MigrationPublication.RecoveryRequired::class.java) {action()}
                assertEquals(text,File(dir,"pending").readText());assertEquals("A",current.readText())
            }
        }
    }
}
