package com.xnotes.mindmap

import androidx.lifecycle.viewModelScope
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

/** Actual MapSession saveTail and private journal; only provider failure is controlled. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapRecoveryCheckpointTest {
    private class Target(var bytes:ByteArray):MapTarget {
        var writes=0
        var failWrite=false
        var beforeWrite:()->Unit={}
        override fun read()=bytes.clone()
        override fun write(bytes:ByteArray) {
            writes++;beforeWrite()
            if(failWrite) throw IOException("Provider refused write")
            this.bytes=bytes.clone()
        }
    }
    private fun scenario(block:suspend(MapBacklinkLibraryTest.Fixture,MapSession,Target)->Unit)=
        MapBacklinkLibraryTest().scenario {f->
            val doc=MindMapDocument();val file=f.graph("checkpoint.xmap",doc);val target=Target(file.readBytes())
            val session=withContext(Dispatchers.Main) {MapSession(MapSourceAccess::inspect) {target}.also {it.open(f.context,f.uri(file))}}
            try {
                withTimeout(10000) {while(withContext(Dispatchers.Main) {session.loading}) delay(1)}
                block(f,session,target)
            } finally {
                target.beforeWrite={}
                withContext(NonCancellable+Dispatchers.Main) {session.cancelPendingAutosave();session.viewModelScope.cancel()}
            }
        }
    private suspend fun edit(session:MapSession)=withContext(Dispatchers.Main) {
        assertTrue(session.operate {it.update(it.root.id) {node->node.copy(text="current body")}})
        session.cancelPendingAutosave()
    }

    @Test fun cleanBodyRequiresAnActualDurableReadAndNeverWritesTheOriginal()=scenario {f,session,target->
        val raw=target.bytes.clone()
        val checkpoint=withContext(Dispatchers.Main) {requireNotNull(session.awaitRecoveryCheckpoint())}
        withContext(Dispatchers.Main) {assertTrue(checkpoint.current());assertEquals(session.document!!.id,checkpoint.documentId)}
        assertEquals(0,target.writes);assertArrayEquals(raw,target.bytes);assertTrue(f.journalSnapshot().isEmpty())
        target.bytes=MapCodec.encode(session.document!!.update(session.document!!.root.id) {it.copy(text="external")})
        withContext(Dispatchers.Main) {assertNull(session.awaitRecoveryCheckpoint());assertFalse(checkpoint.current())}
        assertEquals(0,target.writes)
    }

    @Test fun providerWriteFailureStillReturnsTheActualLatestLocalJournalTicketWithoutRetryingIt()=scenario {f,session,target->
        edit(session);target.failWrite=true
        val ticket=withContext(Dispatchers.Main) {requireNotNull(session.awaitRecoveryCheckpoint())}
        val journals=f.journalSnapshot()
        assertTrue(journals.isNotEmpty());assertEquals(1,target.writes)
        withContext(Dispatchers.Main) {
            assertTrue(session.dirty);assertTrue(session.saveFailed);assertTrue(ticket.current())
            val next=requireNotNull(session.awaitRecoveryCheckpoint())
            assertFalse(ticket.current());assertTrue(next.current())
        }
        assertEquals("A camera checkpoint cannot retry the failed original",1,target.writes)
        assertEquals(journals,f.journalSnapshot())
    }

    @Test fun failedLocalJournalNeverReturnsATicketOrTouchesTheOriginal()=scenario {f,session,target->
        edit(session)
        val recovery=File(f.context.noBackupFilesDir,"mind-map-recovery").apply {writeText("blocked")}
        val raw=target.bytes.clone()
        withContext(Dispatchers.Main) {
            assertNull(session.awaitRecoveryCheckpoint())
            assertNull(session.awaitRecoveryCheckpoint())
        }
        assertEquals(0,target.writes);assertArrayEquals(raw,target.bytes);assertEquals("blocked",recovery.readText())
    }

    @Test fun checkpointWaitsForTheActualAcceptedSaveAndDoesNotIssueAnotherOriginalWrite()=scenario {_,session,target->
        edit(session)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        target.beforeWrite={entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
        val accepted=withContext(Dispatchers.Main) {session.saveAttempt()}
        val scope=CoroutineScope(currentCoroutineContext())
        val result=scope.async(Dispatchers.Main) {session.awaitRecoveryCheckpoint()}
        try {
            assertTrue(withContext(Dispatchers.IO) {entered.await(10,TimeUnit.SECONDS)})
            assertFalse("Accepted is not durable success until its actual write settles",result.isCompleted)
        } finally {release.countDown();target.beforeWrite={}}
        accepted.await()
        val ticket=requireNotNull(result.await())
        withContext(Dispatchers.Main) {assertTrue(ticket.current())}
        assertEquals(1,target.writes)
    }

    @Test fun aNewBodyOrAcceptedSaveImmediatelyRetiresTheOldActualTicket()=scenario {_,session,_->
        val ticket=withContext(Dispatchers.Main) {requireNotNull(session.awaitRecoveryCheckpoint())}
        edit(session)
        withContext(Dispatchers.Main) {assertFalse(ticket.current())}
        val current=withContext(Dispatchers.Main) {requireNotNull(session.awaitRecoveryCheckpoint())}
        val accepted=withContext(Dispatchers.Main) {
            session.saveAttempt().also {assertFalse(current.current())}
        }
        accepted.await()
        withContext(Dispatchers.Main) {assertFalse(current.current())}
    }
}
