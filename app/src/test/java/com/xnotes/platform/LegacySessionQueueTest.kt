package com.xnotes.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class LegacySessionQueueTest {
    private suspend fun withDirectory(block:suspend (File)->Unit) {
        val directory=Files.createTempDirectory("legacy-session-queue").toFile()
        try {block(directory)} finally {directory.deleteRecursively()}
    }

    @Test fun retirementResumesOutsideTheRootLockAndCanAnnounceAnotherOwner() {runBlocking {withTimeout(5000) {withDirectory {dir->
        val old=LegacySessionQueue.attach(dir)
        val callback=async(kotlinx.coroutines.Dispatchers.Unconfined,start=CoroutineStart.UNDISPATCHED) {
            kotlinx.coroutines.selects.select<Unit> {old.onRetired { }}
            val read=java.util.concurrent.CountDownLatch(1)
            val reader=Thread {old.isCurrent();read.countDown()}.apply {isDaemon=true;start()}
            assertTrue("retirement callback must not hold the registration lock",
                read.await(2,java.util.concurrent.TimeUnit.SECONDS))
            reader.join(1000)
            val nested=LegacySessionQueue.attach(dir)
            requireNotNull(nested.submit {"nested owner"}).await()
        }
        val replaced=LegacySessionQueue.attach(dir)
        assertEquals(LegacySessionQueue.Outcome.Success("nested owner"),callback.await())
        assertFalse(old.isCurrent());assertFalse(replaced.isCurrent())
    }}}}

    @Test fun sameOwnerCommandsFinishInSubmissionOrder() {runBlocking {withTimeout(5000) {withDirectory {dir->
        val owner=LegacySessionQueue.attach(dir)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val file=File(dir,"document.xnote")
        val first=requireNotNull(owner.submit {
            entered.complete(Unit);release.await();file.writeText("first");"saved"
        })
        try {
            entered.await()
            val second=requireNotNull(owner.submit {file.readText()})
            assertFalse(second.isCompleted)
            release.complete(Unit)
            assertEquals(LegacySessionQueue.Outcome.Success("saved"),first.await())
            assertEquals(LegacySessionQueue.Outcome.Success("first"),second.await())
        } finally {release.complete(Unit)}
    }}}}

    @Test fun queuedClearStillRunsBeforeNewOwnerRestores() {runBlocking {withTimeout(5000) {withDirectory {dir->
        val file=File(dir,"document.xnote").apply {writeText("old recovery")}
        val old=LegacySessionQueue.attach(dir)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val save=requireNotNull(old.submit {
            entered.complete(Unit);release.await();file.writeText("last save")
        })
        try {
            entered.await()
            val clear=requireNotNull(old.submit {file.delete()})
            val fresh=LegacySessionQueue.attach(dir)
            assertFalse(old.isCurrent());assertTrue(fresh.isCurrent())
            val load=requireNotNull(fresh.submit {file.exists()})
            assertFalse(load.isCompleted)
            release.complete(Unit)
            assertEquals(LegacySessionQueue.Outcome.Success(Unit),save.await())
            assertEquals(LegacySessionQueue.Outcome.Success(true),clear.await())
            assertEquals(LegacySessionQueue.Outcome.Success(false),load.await())
        } finally {release.complete(Unit)}
    }}}}

    @Test fun canonicalAliasesShareTheSameQueueAndAnnouncedOwner() {runBlocking {withTimeout(5000) {withDirectory {dir->
        val old=LegacySessionQueue.attach(dir)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val file=File(dir,"canvas.xcanvas")
        val accepted=requireNotNull(old.submit {
            entered.complete(Unit);release.await();file.writeText("canvas")
        })
        try {
            entered.await()
            val alias=LegacySessionQueue.attach(File(dir,"unused/.."))
            assertFalse(old.isCurrent());assertTrue(alias.isCurrent())
            val load=requireNotNull(alias.submit {file.readText()})
            assertFalse(load.isCompleted)
            release.complete(Unit)
            accepted.await()
            assertEquals(LegacySessionQueue.Outcome.Success("canvas"),load.await())
        } finally {release.complete(Unit)}
    }}}}

    @Test fun retiredOwnerCannotSubmitButItsAcceptedResultStillCompletes() {runBlocking {withTimeout(5000) {withDirectory {dir->
        val old=LegacySessionQueue.attach(dir)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val accepted=requireNotNull(old.submit {entered.complete(Unit);release.await();"accepted"})
        try {
            entered.await()
            val retirement=async(start=CoroutineStart.UNDISPATCHED) {
                kotlinx.coroutines.selects.select<Unit> {old.onRetired { }}
            }
            assertFalse(retirement.isCompleted)
            val fresh=LegacySessionQueue.attach(dir)
            withTimeout(5000) {retirement.await()}
            val freshRetirement=async(start=CoroutineStart.UNDISPATCHED) {
                kotlinx.coroutines.selects.select<Unit> {fresh.onRetired { }}
            }
            assertFalse(freshRetirement.isCompleted)
            freshRetirement.cancel()
            assertNull(old.submit {fail("retired command must never run")})
            release.complete(Unit)
            assertEquals(LegacySessionQueue.Outcome.Success("accepted"),accepted.await())
            assertEquals(LegacySessionQueue.Outcome.Success("new"),requireNotNull(fresh.submit {"new"}).await())
        } finally {release.complete(Unit)}
    }}}}

    @Test fun failedOrCancelledCommandDoesNotPoisonLaterCommands() {runBlocking {withTimeout(5000) {withDirectory {dir->
        val owner=LegacySessionQueue.attach(dir)
        for(error in listOf(IOException("write failed"),CancellationException("capture cancelled"))) {
            val failed=requireNotNull(owner.submit<Unit> {throw error})
            val next=requireNotNull(owner.submit {"next"})
            val outcome=failed.await()
            assertTrue(outcome is LegacySessionQueue.Outcome.Failure)
            assertSame(error,(outcome as LegacySessionQueue.Outcome.Failure).error)
            assertEquals(LegacySessionQueue.Outcome.Success("next"),next.await())
        }
    }}}}

    @Test fun cancellingResultDoesNotLetFollowingCommandOvertakeAcceptedWrite() {runBlocking {withTimeout(5000) {withDirectory {dir->
        val owner=LegacySessionQueue.attach(dir)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val file=File(dir,"document.xnote")
        val write=requireNotNull(owner.submit {
            entered.complete(Unit);release.await();file.writeText("completed")
        })
        try {
            entered.await();write.cancel()
            val load=requireNotNull(owner.submit {file.readText()})
            assertFalse(load.isCompleted)
            release.complete(Unit)
            assertEquals(LegacySessionQueue.Outcome.Success("completed"),load.await())
        } finally {release.complete(Unit)}
    }}}}

    @Test fun otherDirectoryCanProgressWhileOneQueueIsBlocked() {runBlocking {withTimeout(5000) {withDirectory {dir->
        val first=LegacySessionQueue.attach(File(dir,"session"))
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val blocked=requireNotNull(first.submit {entered.complete(Unit);release.await()})
        try {
            entered.await()
            val other=LegacySessionQueue.attach(File(dir,"session-b"))
            assertEquals(LegacySessionQueue.Outcome.Success("independent"),requireNotNull(other.submit {"independent"}).await())
            assertFalse(blocked.isCompleted)
            assertFalse(File(dir,"session").exists());assertFalse(File(dir,"session-b").exists())
        } finally {release.complete(Unit);blocked.await()}
    }}}}

    @Test fun retiredOwnersAssetBarrierWaitsForItsEncoderButNotTheNewOwnersWork() {runBlocking {withTimeout(5000) {withDirectory {dir->
        val asset=File(dir,"original.pdf").apply {writeText("original bytes")}
        val old=LegacySessionQueue.attach(dir)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val freshEntered=CompletableDeferred<Unit>();val releaseFresh=CompletableDeferred<Unit>()
        val encoder=requireNotNull(old.submit {entered.complete(Unit);release.await();asset.readText()})
        try {
            entered.await()
            val fresh=LegacySessionQueue.attach(dir)
            val next=requireNotNull(fresh.submit {freshEntered.complete(Unit);releaseFresh.await()})
            val reclaim=async(start=CoroutineStart.UNDISPATCHED) {old.awaitAccepted();asset.delete()}
            assertFalse(old.isCurrent());assertFalse(reclaim.isCompleted);assertTrue(asset.exists())
            release.complete(Unit)
            freshEntered.await()
            assertEquals(LegacySessionQueue.Outcome.Success("original bytes"),encoder.await())
            assertTrue(reclaim.await());assertFalse(asset.exists())
            assertFalse(next.isCompleted)
            releaseFresh.complete(Unit);next.await()
            assertNull(old.submit {fail("asset barrier cannot reactivate its owner")})
        } finally {release.complete(Unit);releaseFresh.complete(Unit)}
    }}}}
}
