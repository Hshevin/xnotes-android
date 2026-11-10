package com.xnotes.ui

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.Document
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.DocumentCodec
import com.xnotes.format.RecordingBodyEvidence
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecordingRecoveryStoreTest {
    private fun scenario(block:(File,RecordingRecoveryStore)->Unit) {
        val root=Files.createTempDirectory("recording-recovery-").toFile()
        try {block(root,RecordingRecoveryStore(File(root,"recordings").apply {mkdirs()}))}
        finally {root.deleteRecursively()}
    }
    private fun body(root:File,id:String,rid:Int,audio:File):RecordingBodyEvidence {
        val doc=Document.blank().apply {identity=identity.copy(documentId=id)}
        doc.pages.first().items.add(AudioItem(AudioData(audio,"aac",1000,ByteArray(0)),Rect(0.0,0.0,100.0,50.0),"Audio",rid,FakeTextMeasurer()))
        val file=File(root,"body.xnote")
        file.outputStream().use {DocumentCodec(FakeImageCodec(),FakeTextMeasurer()).write(doc,it)}
        return RecordingBodyEvidence.inspect(file)
    }
    @Test fun orphanAfterFailedInitialMetadataRemainsUnboundAndNeverAutoDeleted()=scenario {_,store->
        val raw=File(store.directory,"rec-orphan.aac").apply {writeText("original audio bytes");setLastModified(1L)}
        val bytes=raw.readBytes()
        val failed=RecordingRecoveryStore(store.directory) {_,_->false}
        assertNull(failed.create(raw,"note|A","Recording",1,DocumentKind.NOTE,java.util.UUID.randomUUID().toString()))
        val orphan=RecordingRecoveryStore(store.directory).list().single()
        assertEquals(RecordingRecoveryStore.Problem.Unbound,orphan.problem);assertNull(orphan.record)
        assertArrayEquals(bytes,raw.readBytes());assertTrue(store.list(raw).isEmpty())
    }
    @Test fun legacyAndCorruptSidecarsRemainWithoutInventedHistoricalIdentity()=scenario {_,store->
        File(store.directory,"rec-old.aac").writeText("audio")
        val legacy=File(store.directory,"rec-old.json").apply {writeText("""{"key":"note|A","file":"rec-old.aac","rid":7,"name":"Old"}""")}
        val corrupt=File(store.directory,"bad.json").apply {writeText("{broken")}
        val list=store.list();val old=list.single {it.metadata==legacy}
        assertEquals(RecordingRecoveryStore.Problem.Legacy,old.problem);assertNull(old.record!!.documentId)
        assertTrue(list.any {it.metadata==corrupt && it.problem==RecordingRecoveryStore.Problem.Invalid})
        assertEquals("{broken",corrupt.readText());assertTrue(legacy.exists())
    }
    @Test fun applyingNeedsActualBodyAndExactRidRegisteredShaBeforeCleanup()=scenario {root,store->
        val id=java.util.UUID.randomUUID().toString();val token=Any()
        val raw=File(store.directory,"rec-test.aac").apply {writeText("original audio")}
        val record=store.create(raw,"note|A","Recording",9,DocumentKind.NOTE,id)!!
        val candidate=store.list().single {it.record?.id==record.id}
        val sha=com.xnotes.format.BundleMigration.hash(raw)
        assertNotNull(store.applying(candidate,DocumentKind.NOTE,id,9,sha,token))
        assertEquals(RecordingRecoveryStore.Problem.Applying,RecordingRecoveryStore(store.directory).list().single().problem)
        val other=File(root,"different.aac").apply {writeText("different")}
        assertEquals(0,store.committed(body(root,id,9,other),token));assertTrue(raw.exists())
        assertEquals(0,store.committed(body(root,id,9,raw),Any()));assertTrue(raw.exists())
        assertEquals(1,store.committed(body(root,id,9,raw),token));assertFalse(raw.exists());assertTrue(store.list().isEmpty())
    }
    @Test fun aNewProcessCanFinalizeActualPersistedApplyingBodyWithoutReinsertion()=scenario {root,store->
        val id=java.util.UUID.randomUUID().toString();val raw=File(store.directory,"rec-cold.aac").apply {writeText("audio")}
        store.create(raw,"note|A","Recording",3,DocumentKind.NOTE,id)
        val candidate=store.list().single();store.applying(candidate,DocumentKind.NOTE,id,3,com.xnotes.format.BundleMigration.hash(raw),Any())
        val evidence=body(root,id,3,raw);val cold=RecordingRecoveryStore(store.directory)
        assertEquals(1,cold.committed(evidence));assertFalse(raw.exists());assertTrue(cold.list().isEmpty())
    }
    @Test fun aNewUuidCopyNeedsTheFrozenSameOwnerMappingAndKeepsRawUntilAdopted()=scenario {root,store->
        val oldId=java.util.UUID.randomUUID().toString();val newId=java.util.UUID.randomUUID().toString();val owner=Any()
        val raw=File(store.directory,"rec-copy.aac").apply {writeText("copy audio")}
        store.create(raw,"note|A","Copy",5,DocumentKind.NOTE,oldId)
        store.applying(store.list().single(),DocumentKind.NOTE,oldId,5,com.xnotes.format.BundleMigration.hash(raw),owner)
        val evidence=body(root,newId,5,raw);val frozen=store.matches(owner,evidence)
        assertEquals(1,frozen.size);assertTrue(raw.exists())
        assertEquals(0,store.committed(evidence,owner));assertTrue(raw.exists())
        assertEquals(0,store.createdCommitted(Any(),oldId,newId,evidence,frozen));assertTrue(raw.exists())
        assertEquals(0,store.createdCommitted(owner,oldId,oldId,evidence,frozen));assertTrue(raw.exists())
        assertEquals(1,store.createdCommitted(owner,oldId,newId,evidence,frozen));assertFalse(raw.exists())
        assertEquals(0,store.createdCommitted(owner,oldId,newId,evidence,frozen))
    }
    @Test fun malformedPathAndFutureJournalDoNotClaimOrDeleteAnOrphan()=scenario {_,store->
        val raw=File(store.directory,"rec-orphan.aac").apply {writeText("raw")}
        File(store.directory,"escape.json").writeText("""{"key":"A","file":"../rec-orphan.aac","rid":1,"name":"Escape"}""")
        File(store.directory,"future.json").writeText("""{"version":99,"file":"rec-orphan.aac"}""")
        val list=store.list();assertEquals(2,list.count {it.problem==RecordingRecoveryStore.Problem.Invalid})
        assertEquals(1,list.count {it.problem==RecordingRecoveryStore.Problem.Unbound && it.raw==raw});assertEquals("raw",raw.readText())
    }
    @Test fun committedCleanupFailureCannotBecomeAnotherInsertionAttempt()=scenario {root,store->
        val id=java.util.UUID.randomUUID().toString();val owner=Any()
        val raw=File(store.directory,"rec-cleanup.aac").apply {writeText("audio")}
        store.create(raw,"note|A","Recording",7,DocumentKind.NOTE,id)
        store.applying(store.list().single(),DocumentKind.NOTE,id,7,com.xnotes.format.BundleMigration.hash(raw),owner)
        val evidence=body(root,id,7,raw)
        assertTrue(raw.delete());assertTrue(raw.mkdir());File(raw,"keep").writeText("prevent deletion")
        assertEquals(1,store.committed(evidence,owner))
        val side=store.directory.listFiles()!!.single {it.name.endsWith(".json")}
        assertTrue(side.readText().contains("Committed"));assertTrue(RecordingRecoveryStore(store.directory).list().isEmpty())
        assertTrue(raw.exists());assertTrue(side.exists())
    }
    @Test fun onlyAPreRegisteredRemuxPayloadCanAcknowledgeTheOriginalRecording()=scenario {root,store->
        val id=java.util.UUID.randomUUID().toString();val owner=Any()
        val raw=File(store.directory,"rec-remux.aac").apply {writeText("ADTS audio")}
        store.create(raw,"note|A","Recording",2,DocumentKind.NOTE,id)
        val record=store.applying(store.list().single(),DocumentKind.NOTE,id,2,com.xnotes.format.BundleMigration.hash(raw),owner)!!
        val m4a=File(root,"remux.m4a").apply {writeText("M4A audio")}
        val evidence=body(root,id,2,m4a)
        assertEquals(0,store.committed(evidence,owner));assertTrue(raw.exists())
        assertTrue(store.register(record.id,com.xnotes.format.BundleMigration.hash(m4a)))
        assertEquals(1,store.committed(evidence,owner));assertFalse(raw.exists())
    }
}
