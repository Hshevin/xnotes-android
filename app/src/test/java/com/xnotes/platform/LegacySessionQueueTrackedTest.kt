package com.xnotes.platform

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LegacySessionQueueTrackedTest {
    private suspend fun scenario(block:suspend (File)->Unit) {
        val root=Files.createTempDirectory("tracked-legacy-queue-").toFile()
        try {block(root)} finally {root.deleteRecursively()}
    }
    @Test fun aCompletedResultDoesNotSettleItsStillRunningStructuredChild()=runBlocking {withTimeout(5000) {scenario {root->
        val owner=LegacySessionQueue.attach(root)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val accepted=requireNotNull(owner.submitTracked {
            CoroutineScope(currentCoroutineContext()).launch {entered.complete(Unit);release.await()}
            "body complete"
        })
        try {
            assertEquals(LegacySessionQueue.Outcome.Success("body complete"),accepted.result.await())
            entered.await()
            val waiting=async(start=CoroutineStart.UNDISPATCHED) {accepted.awaitSettled()}
            assertFalse("The restore fence must wait for the actual complete operation",waiting.isCompleted)
            release.complete(Unit)
            assertEquals(LegacySessionQueue.Outcome.Success("body complete"),waiting.await())
            accepted.settledFence()
        } finally {release.complete(Unit);owner.awaitAccepted()}
    }}}
    @Test fun aLaterSameOwnerCommandInvalidatesTheVerifiedRestoreBeforeItStarts()=runBlocking {withTimeout(5000) {scenario {root->
        val owner=LegacySessionQueue.attach(root)
        val access=NativeWorkingSession.legacyAccess(root)
        val verified=requireNotNull(owner.submitTracked {(access.observeAbsent() as LegacyAbsentResult.Granted).observation})
        val observation=(verified.awaitSettled() as LegacySessionQueue.Outcome.Success).value
        val fence=verified.settledFence()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val later=requireNotNull(owner.submit {entered.complete(Unit);release.await();"later command"})
        try {
            entered.await()
            assertFalse("Owner identity alone cannot authorize the older restored body",owner.consumeForRestore(fence,observation))
            assertFalse(later.isCompleted)
        } finally {release.complete(Unit);later.await();owner.awaitAccepted()}
    }}}
    @Test fun cancellingTheAwaiterDoesNotCancelTheAcceptedOperationOrItsCompletionFence()=runBlocking {withTimeout(5000) {scenario {root->
        val owner=LegacySessionQueue.attach(root);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val accepted=requireNotNull(owner.submitTracked {entered.complete(Unit);release.await();File(root,"result").writeText("complete");"done"})
        try {
            entered.await()
            val waiter=async(start=CoroutineStart.UNDISPATCHED) {accepted.awaitSettled()}
            waiter.cancelAndJoin()
            assertFalse(accepted.result.isCompleted)
            release.complete(Unit)
            assertEquals(LegacySessionQueue.Outcome.Success("done"),accepted.awaitSettled())
            assertEquals("complete",File(root,"result").readText())
            val observation=(NativeWorkingSession.legacyAccess(root).observeAbsent() as LegacyAbsentResult.Granted).observation
            assertTrue(owner.consumeForRestore(accepted.settledFence(),observation))
        } finally {release.complete(Unit);owner.awaitAccepted()}
    }}}
    @Test fun aNewOwnerCannotUseThePriorOwnersSettledFence()=runBlocking {withTimeout(5000) {scenario {root->
        val old=LegacySessionQueue.attach(root)
        val accepted=requireNotNull(old.submitTracked {"verified"});accepted.awaitSettled()
        val observation=(NativeWorkingSession.legacyAccess(root).observeAbsent() as LegacyAbsentResult.Granted).observation
        val fresh=LegacySessionQueue.attach(File(root,"unused/.."));fresh.awaitAccepted()
        assertFalse(old.consumeForRestore(accepted.settledFence(),observation))
        assertFalse(fresh.consumeForRestore(accepted.settledFence(),observation))
    }}}
    @Test fun repeatedCheckpointChecksPreserveTheOneShotRestoreClaim()=runBlocking {withTimeout(5000) {scenario {root->
        val owner=LegacySessionQueue.attach(root)
        val access=NativeWorkingSession.legacyAccess(root)
        val accepted=requireNotNull(owner.submitTracked {(access.observeAbsent() as LegacyAbsentResult.Granted).observation})
        val observation=(accepted.awaitSettled() as LegacySessionQueue.Outcome.Success).value
        val fence=accepted.settledFence()
        repeat(3) {assertTrue(owner.matchesForCheckpoint(fence,observation))}
        assertTrue(owner.consumeForRestore(fence,observation))
        assertFalse(owner.matchesForCheckpoint(fence,observation))
    }}}
    @Test fun aNewQueueCommandOrNativeReservationInvalidatesTheCheckpointReceipt()=runBlocking {withTimeout(5000) {scenario {root->
        val owner=LegacySessionQueue.attach(root)
        val access=NativeWorkingSession.legacyAccess(root)
        val accepted=requireNotNull(owner.submitTracked {(access.observeAbsent() as LegacyAbsentResult.Granted).observation})
        val observation=(accepted.awaitSettled() as LegacySessionQueue.Outcome.Success).value
        val fence=accepted.settledFence()
        val release=CompletableDeferred<Unit>()
        val later=requireNotNull(owner.submitTracked {release.await();(access.observeAbsent() as LegacyAbsentResult.Granted).observation})
        try {assertFalse(owner.matchesForCheckpoint(fence,observation))}
        finally {release.complete(Unit)}
        val next=(later.awaitSettled() as LegacySessionQueue.Outcome.Success).value
        assertTrue(owner.matchesForCheckpoint(later.settledFence(),next))
        val cache=WorkingSessionCache(root,validateBody={_,_,_->})
        val request=cache.reserve(Any(),WorkingSessionCache.Kind.NOTE,java.util.UUID.randomUUID().toString(),0,org.json.JSONObject())
        try {assertFalse(owner.matchesForCheckpoint(later.settledFence(),next))}
        finally {cache.retire(request)}
        assertFalse("Retiring a newer intent cannot revive the old receipt",owner.matchesForCheckpoint(later.settledFence(),next))
    }}}
}
