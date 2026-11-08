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

    @Test fun legacyWrappersCannotConsumeTypedSaveOrCreationRecords() = scenario {root->
        for(purpose in MigrationPublication.Purpose.entries) {
            val current=File(root,"current-${purpose.key}").apply {writeText("original")}
            val next=File(root,"next-${purpose.key}").apply {writeText("candidate")}
            val journal=MigrationPublication(File(root,"journal-${purpose.key}"));journal.stage(current,next,purpose)
            val target=object:MigrationPublication.Target {
                override fun read()=current.inputStream()
                override fun write(source:File) {fail("legacy entry cannot consume typed record")}
            }
            for(action in listOf<()->Unit>({journal.publish(target)},{journal.restore(target)},{journal.keepCurrent()})) {
                assertThrows(MigrationPublication.RecoveryRequired::class.java) {action()}
                assertTrue(journal.pending);assertEquals("original",current.readText())
            }
        }
    }
    @Test fun malformedOrUnknownTypedRecordsNeverBecomeLegacyKeepRequests() = scenario {root->
        val current=File(root,"current").apply {writeText("original")};val next=File(root,"next").apply {writeText("candidate")}
        for((index,text) in listOf("3\nfuture", "3\n"+"a".repeat(64)+"\n"+"b".repeat(64)+"\nunknown-create",
            "9".repeat(257)+"\nfuture", "000000000000003\ninvalid", "0".repeat(300)+"3\nfuture").withIndex()) {
            val directory=File(root,"journal-$index");val journal=MigrationPublication(directory);journal.stage(current,next)
            File(directory,"pending").writeText(text)
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {journal.keepCurrent()}
            assertTrue(journal.pending);assertEquals(text,File(directory,"pending").readText())
            assertEquals("original",File(directory,"original.zip").readText());assertEquals("candidate",File(directory,"candidate.zip").readText())
        }
    }

    @Test fun malformedLegacyClassificationIsBoundToTheMarkerBytesUsedForValidation() = scenario {root->
        val current=File(root,"current").apply {writeText("original")};val next=File(root,"next").apply {writeText("candidate")}
        val directory=File(root,"journal");val journal=MigrationPublication(directory);journal.stage(current,next)
        val marker=File(directory,"pending");val typed="3\nfuture"
        marker.writeText("historical broken record")
        var replaced=false
        val racing=MigrationPublication(directory) {file->
            if(!replaced) {replaced=true;marker.writeText(typed)}
            com.xnotes.format.BundleMigration.hash(file)
        }
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {racing.keepCurrent()}
        assertTrue(replaced);assertEquals(typed,marker.readText());assertTrue(racing.pending)
    }

    @Test fun stagingMustBindTheRetainedCandidateToThePreviouslyInspectedHash() = scenario {root->
        val current=File(root,"current").apply {writeText("")};val next=File(root,"next").apply {writeText("inspected candidate A")}
        val inspected=com.xnotes.format.BundleMigration.hash(next)
        next.writeText("replacement B before stage")
        val directory=File(root,"journal");val journal=MigrationPublication(directory)
        assertThrows(ReferenceContractViolation::class.java) {journal.stage(current,next,MigrationPublication.Purpose.CREATE_NOTE,expectedCandidateHash=inspected)}
        assertFalse(journal.pending);assertEquals("",current.readText())
    }
    @Test fun strictAndTypedKeepMarkerReadsStopAtTheEnvelopeBudget() = scenario {root->
        val current=File(root,"current").apply {writeText("original")};val next=File(root,"next").apply {writeText("candidate")}
        for((index,body) in listOf("3\n"+"x".repeat(100000), "9".repeat(100000)).withIndex()) {
            val directory=File(root,"journal-$index");MigrationPublication(directory).stage(current,next)
            File(directory,"pending").writeText(body)
            var bytes=0
            val journal=MigrationPublication(directory,{com.xnotes.format.BundleMigration.hash(it)},markerOpen={file->
                object:java.io.FilterInputStream(file.inputStream()) {
                    override fun read():Int=super.read().also {if(it>=0) bytes++}
                    override fun read(b:ByteArray,off:Int,len:Int):Int=`in`.read(b,off,len).also {if(it>0) bytes+=it}
                }
            })
            val target=object:MigrationPublication.Target {
                override fun read()=current.inputStream()
                override fun write(source:File) {fail("oversized record must never write")}
            }
            for(action in listOf<()->Unit>({journal.purpose},{journal.publish(target)},{journal.restore(target)},{journal.keepCurrent()})) {
                bytes=0;assertThrows(MigrationPublication.RecoveryRequired::class.java) {action()}
                assertTrue("strict or typed record read more than 257 bytes: $bytes",bytes<=257);assertTrue(journal.pending)
            }
        }
    }

}
