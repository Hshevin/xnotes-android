package com.xnotes.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PublicationPurposeTest {
    private fun scenario(block:(File)->Unit) {
        val root=Files.createTempDirectory("publication-purpose-").toFile()
        try {block(root)} finally {root.deleteRecursively()}
    }
    @Test fun durablePurposeIsPassedFromTheSameVerifiedRecordToThePreflight() = scenario {root->
        val current=File(root,"current").apply {writeText("old")};val next=File(root,"next").apply {writeText("new")}
        val directory=File(root,"journal");var writes=0;var expected:String?=null
        val target=object:MigrationPublication.Target {
            override fun read()=current.inputStream()
            override fun write(source:File) {error("must pass the observed current checksum")}
            override fun write(source:File,expectedCurrentHash:String?) {
                writes++;expected=expectedCurrentHash;source.copyTo(current,overwrite=true)
            }
        }
        val old=com.xnotes.format.BundleMigration.hash(current)
        MigrationPublication(directory).stage(current,next,MigrationPublication.Purpose.SAVE_NOTE)
        val resumed=MigrationPublication(directory)
        assertEquals(MigrationPublication.Purpose.SAVE_NOTE,resumed.purpose)
        resumed.publishValidated(target,validate={state->
            assertEquals(MigrationPublication.Purpose.SAVE_NOTE,state.purpose)
            assertEquals("old",state.original.readText());assertEquals("new",state.candidate.readText())
            assertEquals(state.original,state.current)
        })
        assertEquals(1,writes);assertEquals(old,expected);assertFalse(resumed.pending)
    }
    @Test fun purposeRefusalNeverWritesOrClearsRecoveryData() = scenario {root->
        val current=File(root,"current").apply {writeText("old")};val next=File(root,"next").apply {writeText("new")}
        val journal=MigrationPublication(File(root,"journal"));journal.stage(current,next,MigrationPublication.Purpose.SAVE_CANVAS)
        val target=object:MigrationPublication.Target {
            override fun read()=current.inputStream()
            override fun write(source:File) {fail("rejected purpose must not write")}
        }
        assertThrows(ReferenceContractViolation::class.java) {journal.publishValidated(target,validate={
            if(it.purpose!=MigrationPublication.Purpose.SAVE_NOTE) throw ReferenceContractViolation(StorageMsg.REFERENCE_IDENTITY_MISMATCH)
        })}
        assertTrue(journal.pending);assertEquals("old",current.readText())
    }
    @Test fun unknownPurposeAndChangedPurposeRecordDoNotPublish() = scenario {root->
        val current=File(root,"current").apply {writeText("old")};val next=File(root,"next").apply {writeText("new")}
        val dir=File(root,"journal");val journal=MigrationPublication(dir)
        journal.stage(current,next,MigrationPublication.Purpose.SAVE_NOTE)
        val marker=File(dir,"pending");val valid=marker.readText()
        marker.writeText(valid.replace("save-note","future-save"))
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {journal.purpose}
        marker.writeText(valid)
        val target=object:MigrationPublication.Target {
            override fun read()=current.inputStream()
            override fun write(source:File) {fail("modified record must not write")}
        }
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {journal.publishValidated(target,validate={
            marker.writeText(valid.replace("save-note","save-canvas"))
        })}
        assertTrue(journal.pending);assertEquals("old",current.readText())
    }
    @Test fun restoreAndKeepValidateThePurposeBoundToTheirOwnRecordAndRejectChanges() = scenario {root->
        for(restore in listOf(false,true)) {
            val current=File(root,"current-$restore").apply {writeText("original")}
            val next=File(root,"next-$restore").apply {writeText("candidate")}
            val directory=File(root,"journal-$restore");val journal=MigrationPublication(directory)
            journal.stage(current,next,MigrationPublication.Purpose.SAVE_NOTE)
            current.writeText("outside current")
            val target=object:MigrationPublication.Target {
                override fun read()=current.inputStream()
                override fun write(source:File) {fail("modified purpose must not write")}
            }
            val record=File(directory,"pending")
            fun change(purpose:MigrationPublication.Purpose?) {
                assertEquals(MigrationPublication.Purpose.SAVE_NOTE,purpose)
                record.writeText(record.readText().replace("save-note","save-canvas"))
            }
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {
                if(restore) journal.restoreValidated(target,validate={change(it.purpose)})
                else journal.keepCurrentValidated(validate={assertTrue(it.recordValid);change(it.purpose)})
            }
            assertTrue(journal.pending);assertEquals("outside current",current.readText())
        }
    }

}
