package com.xnotes.ui

import com.xnotes.core.model.NativeRestoreHint
import com.xnotes.core.util.DocumentKind
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeColdRestoreCoordinatorTest {
    private class Candidate:NativeColdRestoreCoordinator.Participant {
        var valid=true
        var canClaim=true
        var applied=false
        var closes=0
        var onClose:suspend ()->Unit={}
        override suspend fun verify()=valid
        override fun claim()=canClaim
        override fun apply() {applied=true}
        override suspend fun close() {closes++;onClose()}
    }
    private class Host:NativeColdRestoreCoordinator.Host {
        val first=Candidate();val second=Candidate()
        var allocations=0
        var initialValid=true
        var adoptionValid=true
        var layoutApplied=false
        var sharedAttached=false
        var onPrepare:suspend (Pane)->NativeColdRestoreCoordinator.Participant?={if(it==Pane.PRIMARY) first else second}
        override fun ensurePanes(slots:List<ColdNativeSlot>) {allocations++}
        override fun captureInitialProof():()->Boolean={initialValid}
        override fun captureAdoptionProof():()->Boolean={adoptionValid}
        override suspend fun prepareNote(slot:ColdNativeSlot.Note)=onPrepare(slot.pane)
        override suspend fun prepareCanvas(slot:ColdNativeSlot.Canvas)=onPrepare(slot.pane)
        override fun attachShared(slot:ColdNativeSlot.Shared) {
            check(first.applied);check(!layoutApplied);sharedAttached=true
        }
        override fun applyLayout(layout:ColdNativeLayout) {check(first.applied);layoutApplied=true}
    }
    private fun note(pane:Pane)=ColdNativeSlot.Note(pane,NativeRestoreHint(
        if(pane==Pane.PRIMARY) "11111111-1111-4111-8111-111111111111" else "22222222-2222-4222-8222-222222222222",null,DocumentKind.NOTE))
    private fun pair()=LastPresentation.Native(listOf(note(Pane.PRIMARY),note(Pane.SECONDARY)),ColdNativeLayout(Pane.PRIMARY))

    @Test fun invalidDescriptionsCannotAllocatePanes()=runBlocking {
        val host=Host()
        assertNull(NativeColdRestoreCoordinator(host).prepare(pair().copy(layout=ColdNativeLayout(Pane.PRIMARY,.1f))) {true})
        assertEquals(0,host.allocations)
    }

    @Test fun secondPreparationFailureClosesTheFirstWithoutAdopting()=runBlocking {
        val host=Host().apply {onPrepare={if(it==Pane.PRIMARY) first else null}}
        assertNull(NativeColdRestoreCoordinator(host).prepare(pair()) {true})
        assertEquals(1,host.first.closes);assertFalse(host.first.applied);assertFalse(host.layoutApplied)
    }

    @Test fun cancellationWaitsForSuspendingCleanup()=runBlocking {
        val host=Host()
        val reached=CompletableDeferred<Unit>();var cleaned=false
        host.first.onClose={yield();currentCoroutineContext().ensureActive();cleaned=true}
        host.onPrepare={if(it==Pane.PRIMARY) host.first else {reached.complete(Unit);awaitCancellation()}}
        val job=launch {NativeColdRestoreCoordinator(host).prepare(pair()) {true}}
        reached.await();job.cancelAndJoin()
        assertTrue(cleaned);assertEquals(1,host.first.closes);assertFalse(host.first.applied)
    }

    @Test fun failedClaimCannotApplyAnyPaneOrLayout()=runBlocking {
        val host=Host().apply {second.canClaim=false}
        val prepared=requireNotNull(NativeColdRestoreCoordinator(host).prepare(pair()) {true})
        try {
            assertTrue(prepared.verify());assertFalse(prepared.claimForAdoption())
            assertThrows(IllegalStateException::class.java) {prepared.applyAdoption()}
            assertFalse(host.first.applied);assertFalse(host.second.applied);assertFalse(host.layoutApplied)
        } finally {prepared.close()}
        assertEquals(1,host.first.closes);assertEquals(1,host.second.closes)
    }

    @Test fun changedAdoptionProofRejectsAFormerlyPreparedGroup()=runBlocking {
        val host=Host()
        val prepared=requireNotNull(NativeColdRestoreCoordinator(host).prepare(pair()) {true})
        try {
            assertTrue(prepared.verify());host.adoptionValid=false
            assertFalse(prepared.verify());assertFalse(prepared.claimForAdoption())
            assertFalse(host.first.applied)
        } finally {prepared.close()}
    }

    @Test fun sharedReadersAttachAfterBodyAdoptionAndBeforeLayout()=runBlocking {
        val host=Host()
        val value=LastPresentation.Native(listOf(note(Pane.PRIMARY),ColdNativeSlot.Shared(Pane.SECONDARY,Pane.PRIMARY)),ColdNativeLayout(Pane.SECONDARY))
        val prepared=requireNotNull(NativeColdRestoreCoordinator(host).prepare(value) {true})
        try {
            assertTrue(prepared.verify());assertTrue(prepared.claimForAdoption());prepared.applyAdoption()
            assertTrue(host.sharedAttached);assertTrue(host.layoutApplied);assertFalse(host.second.applied)
            assertThrows(IllegalStateException::class.java) {prepared.applyAdoption()}
        } finally {prepared.close();prepared.close()}
        assertEquals(1,host.first.closes);assertEquals(0,host.second.closes)
    }

    @Test fun preparationFailureRemainsPrimaryWhenCleanupAlsoFails()=runBlocking {
        val host=Host()
        val preparationFailure=IllegalArgumentException("second prepare failed")
        val cleanupFailure=IllegalStateException("first cleanup failed")
        host.onPrepare={if(it==Pane.PRIMARY) host.first else throw preparationFailure}
        host.first.onClose={throw cleanupFailure}
        val failure=try {NativeColdRestoreCoordinator(host).prepare(pair()) {true};null} catch(error:Exception) {error}
        assertSame(preparationFailure,failure)
        assertTrue(requireNotNull(failure).suppressed.contains(cleanupFailure))
        assertEquals(1,host.first.closes);assertFalse(host.first.applied)
    }

    @Test fun cleanupFailureStillReleasesEveryOtherParticipant()=runBlocking {
        val host=Host()
        val firstFailure=IllegalStateException("first close failed")
        val secondFailure=IllegalArgumentException("second close failed")
        host.first.onClose={throw firstFailure};host.second.onClose={throw secondFailure}
        val prepared=requireNotNull(NativeColdRestoreCoordinator(host).prepare(pair()) {true})
        val failure=try {prepared.close();null} catch(error:Exception) {error}
        assertEquals(1,host.first.closes);assertEquals(1,host.second.closes)
        // Coroutine stack recovery may copy an exception; preserve its meaning and original evidence.
        assertEquals(firstFailure.javaClass,requireNotNull(failure).javaClass)
        assertEquals(firstFailure.message,failure.message)
        assertTrue(firstFailure.suppressed.contains(secondFailure))
        assertFalse(host.first.applied);assertFalse(host.second.applied)
    }
}
