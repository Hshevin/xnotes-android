package com.xnotes.mindmap

import com.xnotes.ui.SplitSourceRef
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual broker leases, provider bytes and recovery journals; no substitute Map actor. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapColdAccessTest {
    private fun scenario(block:suspend(MapBacklinkLibraryTest.Fixture)->Unit)=MapBacklinkLibraryTest().scenario(block=block)
    private suspend fun prepare(f:MapBacklinkLibraryTest.Fixture,file:File,doc:MindMapDocument)=withContext(Dispatchers.Main) {
        MapColdAccess(f.context).prepare(SplitSourceRef(doc.id,f.uri(file).toString(),file.name))
    }
    private fun recovered(f:MapBacklinkLibraryTest.Fixture,file:File,doc:MindMapDocument):MindMapDocument {
        val target=object:MapTarget {
            override fun read()=file.readBytes()
            override fun write(bytes:ByteArray):Unit=throw IOException("Controlled provider failure")
        }
        val store=MapFiles.store(f.context,f.uri(file),target)
        store.open()
        val next=doc.update(doc.root.id) {it.copy(text="retained")}
        assertThrows(IOException::class.java) {store.save(next)}
        return next
    }

    @Test fun ordinaryColdOpenTransfersTheCanonicalLeaseOnlyAfterFinalRead()=scenario {f->
        val doc=MindMapDocument();val file=f.graph("cold.xmap",doc);val raw=file.readBytes()
        val held=requireNotNull(prepare(f,file,doc))
        withContext(Dispatchers.Main) {
            assertTrue(held.current());assertEquals(doc,held.lease.session.document)
            assertSame(held.lease.session,MapDocuments.observations(f.context).single().session)
            assertTrue(held.finish {lease->f.leases+=lease;true})
            held.closeUnadopted().join()
            assertTrue(held.lease.alive);assertFalse(held.current())
        }
        assertArrayEquals(raw,file.readBytes());assertEquals(0,f.writes.get());assertTrue(f.journalSnapshot().isEmpty())
    }

    @Test fun itsOwnPendingJournalCanRestoreWhenTheRawIsMissingWithoutRecreatingIt()=scenario {f->
        val doc=MindMapDocument();val file=f.graph("missing.xmap",doc)
        val edit=withContext(Dispatchers.IO) {recovered(f,file,doc).also {assertTrue(file.delete())}}
        val before=f.journalSnapshot()
        val held=requireNotNull(prepare(f,file,doc))
        withContext(Dispatchers.Main) {
            assertEquals(edit,held.lease.session.document);assertTrue(held.lease.session.recovered)
            assertTrue(held.finish {lease->f.leases+=lease;true})
        }
        assertFalse(file.exists());assertEquals(before,f.journalSnapshot());assertEquals(0,f.writes.get())
    }

    @Test fun anUnadoptedColdLeaseCannotReleaseAnotherOwnerOrRebaseItsLoadedRaw()=scenario {f->
        val doc=MindMapDocument();val file=f.graph("shared.xmap",doc);val owner=f.acquire(f.uri(file))
        val held=requireNotNull(prepare(f,file,doc))
        withContext(Dispatchers.Main) {assertSame(owner.session,held.lease.session)}
        val changed=MapCodec.encode(doc.update(doc.root.id) {it.copy(text="external")})
        file.writeBytes(changed)
        var adopted=false
        withContext(Dispatchers.Main) {
            assertFalse(held.finish {adopted=true;false})
            held.closeUnadopted().join();assertFalse(adopted);assertTrue(owner.alive)
            assertSame(owner.session,MapDocuments.observations(f.context).single().session)
        }
        assertNull(prepare(f,file,doc))
        assertArrayEquals(changed,file.readBytes());assertEquals(0,f.writes.get())
    }

    @Test fun aChangedJournalCannotBeAdoptedAsTheOldActorsOriginalRecovery()=scenario {f->
        val doc=MindMapDocument();val file=f.graph("journal.xmap",doc)
        withContext(Dispatchers.IO) {recovered(f,file,doc)}
        val owner=f.acquire(f.uri(file))
        val held=requireNotNull(prepare(f,file,doc))
        val journal=File(f.context.noBackupFilesDir,"mind-map-recovery").listFiles()!!.single {it.extension=="json"}
        journal.writeText(journal.readText().replace("retained","mutated!"))
        val changed=f.journalSnapshot();var adopted=false
        withContext(Dispatchers.Main) {
            assertFalse(held.finish {adopted=true;false});assertFalse(adopted);assertTrue(owner.alive)
        }
        assertEquals(changed,f.journalSnapshot());assertEquals(0,f.writes.get())
    }

    @Test fun cancellationDuringTheFinalProviderReadOnlyReleasesThisPreparationsLease()=scenario {f->
        val doc=MindMapDocument();val file=f.graph("cancel.xmap",doc);val owner=f.acquire(f.uri(file))
        val held=requireNotNull(prepare(f,file,doc));val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val raw=file.readBytes();val journals=f.journalSnapshot()
        f.beforeOpenRead={entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
        var adopted=false
        val job=CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Main) {held.finish {adopted=true;false}}
        try {
            assertTrue(withContext(Dispatchers.IO) {entered.await(10,TimeUnit.SECONDS)})
            job.cancel()
        } finally {release.countDown();job.join();f.beforeOpenRead=null}
        withContext(Dispatchers.Main) {held.closeUnadopted().join();assertTrue(owner.alive);assertFalse(held.lease.alive)}
        assertFalse(adopted);assertArrayEquals(raw,file.readBytes());assertEquals(journals,f.journalSnapshot());assertEquals(0,f.writes.get())
    }
}
