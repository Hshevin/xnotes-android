package com.xnotes.storage

import android.content.ContextWrapper
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Document
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.snapshot
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleMigration
import com.xnotes.format.DocumentCodec
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeStagedSaveTest {
    private class Fixture(val root:File) {
        val uri="content://staged-save/document/source"
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val current=File(root,"source");val next=File(root,"next")
        var writes=0
        var afterRead:(()->Unit)?=null
        val backend={_:String->object:MigrationPublication.Target {
            override fun read()=object:java.io.FilterInputStream(current.inputStream()) {
                override fun close() {super.close();afterRead?.invoke()}
            }
            override fun write(source:File) {writes++;source.copyTo(current,overwrite=true)}
        }}
        val storage=NotebookStorage(context,targetOverride=backend)
        val codec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
        val document=Document.blank().apply {enableReferenceAnchors()}
        init {
            current.outputStream().use {codec.write(document.snapshot(),it)}
            document.bookmarks.add(Bookmark(0,"B"))
            next.outputStream().use {codec.write(document.snapshot(),it)}
        }
        val version get()=storage.openSource(uri,DocumentKind.NOTE).use {it.version}
        val journalDir:File get() {
            val key=storage.registry.locationKey(uri)
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") {"%02x".format(it)}
            return File(context.noBackupFilesDir,"migrations/$hash")
        }
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("native-staged-save-").toFile();val f=Fixture(root)
        try {block(f)} finally {f.storage.registry.close();root.deleteRecursively()}
    }
    @Test fun prepareKeepsTheTargetAndReleasesTheStorageLockBeforeCommit()=scenario {f->
        val original=f.current.readBytes();val expected=f.next.readBytes()
        val stage=f.storage.prepareExpected(f.uri,f.next,f.version)
        assertEquals(0,f.writes);assertArrayEquals(original,f.current.readBytes());assertTrue(f.storage.hasRecovery(f.uri))
        assertEquals(BundleMigration.hash(f.next),stage.candidate.sha256)
        val executor=Executors.newSingleThreadExecutor()
        try {assertTrue(executor.submit<Boolean> {f.storage.hasRecovery(f.uri)}.get(5,TimeUnit.SECONDS))}
        finally {executor.shutdownNow()}
        f.next.writeText("caller changed or deleted its temporary candidate")
        val result=f.storage.commitStaged(stage,"source")
        assertArrayEquals(expected,f.current.readBytes());assertEquals(1,f.writes)
        assertEquals(BundleMigration.hash(f.current),result.sha256);assertFalse(f.storage.hasRecovery(f.uri))
        assertThrows(ReferenceContractViolation::class.java) {f.storage.commitStaged(stage,"source")}
        assertEquals(1,f.writes)
    }
    @Test fun aDifferentStorageCannotConsumeTheStage()=scenario {f->
        val stage=f.storage.prepareExpected(f.uri,f.next,f.version)
        val other=NotebookStorage(f.context,targetOverride=f.backend)
        try {assertThrows(ReferenceContractViolation::class.java) {other.commitStaged(stage,"source")}}
        finally {other.registry.close()}
        assertEquals(0,f.writes);f.storage.commitStaged(stage,"source");assertEquals(1,f.writes)
    }
    @Test fun coordinatedStageKeepsTheDefaultGateEvenWhenCalledThroughItsOriginalHandle()=scenario {f->
        val original=f.current.readBytes();val id=java.util.UUID.randomUUID().toString()
        val stage=f.storage.prepareExpected(f.uri,f.next,f.version,handoffId=id)
        assertEquals(id,stage.handoffId)
        val error=assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.commitStaged(stage,"source")}
        assertEquals(StorageMsg.MIGRATION_RECOVERY_PENDING,error.reason)
        assertEquals(0,f.writes);assertArrayEquals(original,f.current.readBytes());assertTrue(f.storage.hasRecovery(f.uri))
    }
    @Test fun replacementAfterStageCannotDowngradeTheActualHandoffProofToANativeOnlySave()=scenario {f->
        val original=f.current.readBytes();var replaced=false
        val stage=f.storage.prepareExpected(f.uri,f.next,f.version,handoffId=java.util.UUID.randomUUID().toString(),checkActive={
            val marker=File(f.journalDir,"pending")
            if(!replaced && marker.isFile) {
                replaced=true
                marker.writeText(marker.readText().split('\n').take(4).joinToString("\n").replaceFirst("4\n","3\n"))
            }
        })
        assertTrue(replaced)
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.commitStaged(stage,"source")}
        assertEquals(0,f.writes);assertArrayEquals(original,f.current.readBytes());assertTrue(f.storage.hasRecovery(f.uri))
    }
    @Test fun cancellationBeforeCommitDoesNotWriteOrClearTheFixedCandidate()=scenario {f->
        val original=f.current.readBytes();val stage=f.storage.prepareExpected(f.uri,f.next,f.version)
        assertThrows(CancellationException::class.java) {f.storage.commitStaged(stage,"source") {throw CancellationException()}}
        assertEquals(0,f.writes);assertArrayEquals(original,f.current.readBytes());assertTrue(f.storage.hasRecovery(f.uri))
        assertTrue(File(f.journalDir,"candidate.zip").isFile)
    }
    @Test fun retainedCandidateChangedAfterTargetReadCannotReuseTheEarlierInspection()=scenario {f->
        val original=f.current.readBytes();val stage=f.storage.prepareExpected(f.uri,f.next,f.version)
        val candidate=File(f.journalDir,"candidate.zip");val stamp=candidate.lastModified()
        val marker=File(f.journalDir,"pending").readBytes()
        f.document.bookmarks.single().label="C"
        val changed=File(f.root,"changed.xnote").also {file->file.outputStream().use {f.codec.write(f.document.snapshot(),it)}}
        assertNotEquals(stage.candidate.sha256,BundleMigration.hash(changed))
        var targetRead=false;var replaced=false
        f.afterRead={targetRead=true}

        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.commitStaged(stage,"source") {
            if(targetRead && !replaced) {
                replaced=true;changed.copyTo(candidate,overwrite=true);candidate.setLastModified(stamp)
            }
        }}

        assertTrue(replaced);assertEquals(0,f.writes);assertArrayEquals(original,f.current.readBytes())
        assertTrue(f.storage.hasRecovery(f.uri));assertArrayEquals(marker,File(f.journalDir,"pending").readBytes())
        assertArrayEquals(changed.readBytes(),candidate.readBytes())
    }
    @Test fun cancellationAfterTargetReadKeepsTheRetainedBytesAndNeverPublishesCachedFacts()=scenario {f->
        val original=f.current.readBytes();val stage=f.storage.prepareExpected(f.uri,f.next,f.version)
        val retained=f.journalDir.listFiles()!!.associate {it.name to it.readBytes().toList()}
        var targetRead=false;var cancelled=false
        f.afterRead={targetRead=true}

        assertThrows(CancellationException::class.java) {f.storage.commitStaged(stage,"source") {
            if(targetRead) {cancelled=true;throw CancellationException("cancel after actual target fingerprint")}
        }}

        assertTrue(cancelled);assertEquals(0,f.writes);assertArrayEquals(original,f.current.readBytes())
        assertTrue(f.storage.hasRecovery(f.uri))
        assertEquals(retained,f.journalDir.listFiles()!!.associate {it.name to it.readBytes().toList()})
    }
    @Test fun changedRecordRetainedBytesOrTargetNeverWritesOrClearsTheStage()=scenario {f->
        val original=f.current.readBytes();val candidate=f.next.readBytes()
        for(change in listOf("record","candidate","original","target")) {
            f.journalDir.deleteRecursively();f.current.writeBytes(original);f.next.writeBytes(candidate)
            val stage=f.storage.prepareExpected(f.uri,f.next,f.version)
            when(change) {
                "record" -> File(f.journalDir,"pending").let {it.writeText(it.readText().replace("save-note","save-canvas"))}
                "candidate" -> File(f.journalDir,"candidate.zip").writeText("corrupt candidate")
                "original" -> File(f.journalDir,"original.zip").writeText("corrupt original")
                "target" -> f.current.writeText("external content")
            }
            val target=f.current.readBytes();val record=File(f.journalDir,"pending").readBytes()
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.commitStaged(stage,"source")}
            assertArrayEquals(target,f.current.readBytes());assertArrayEquals(record,File(f.journalDir,"pending").readBytes())
            assertEquals(0,f.writes);assertTrue(f.storage.hasRecovery(f.uri))
        }
    }
    @Test fun aStageConsumedByRecoveryCannotIssueAnotherSuccess()=scenario {f->
        val stage=f.storage.prepareExpected(f.uri,f.next,f.version)
        f.storage.ensure(f.uri,"source","xnote")
        assertEquals(1,f.writes);assertFalse(f.storage.hasRecovery(f.uri))
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.commitStaged(stage,"source")}
        assertEquals(1,f.writes)
    }
    @Test fun changedLocationRegistrationIsRejectedBeforeWriting()=scenario {f->
        val stage=f.storage.prepareExpected(f.uri,f.next,f.version)
        f.storage.registry.register(java.util.UUID.randomUUID().toString(),"xnote","other",f.uri)
        assertThrows(ReferenceContractViolation::class.java) {f.storage.commitStaged(stage,"source")}
        assertEquals(0,f.writes);assertTrue(f.storage.hasRecovery(f.uri))
    }
    @Test fun theSameRecordInAnotherCanonicalJournalCannotReplaceTheOriginalStage()=scenario {f->
        val stage=f.storage.prepareExpected(f.uri,f.next,f.version)
        val alias="content://staged-save/tree/root/document/source"
        // Force a second pending directory with identical marker and bytes. Lookup must reject the
        // ambiguity rather than select one by filename order or grant the original handle to it.
        val slot=java.security.MessageDigest.getInstance("SHA-256").digest(alias.toByteArray()).joinToString("") {"%02x".format(it)}
        f.journalDir.copyRecursively(File(f.context.noBackupFilesDir,"migrations/$slot"))
        // Both locations map to the same canonical URI; register the alias so lookup sees it too.
        f.storage.registry.register(f.document.identity.documentId,"xnote","source",alias)
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.commitStaged(stage,"source")}
        assertEquals(0,f.writes)
    }
}
