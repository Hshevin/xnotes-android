package com.xnotes.platform

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class WorkingSessionCacheLegacyBoundaryTest {
    private fun scenario(block:(File,LegacyAccess)->Unit) {
        val root=Files.createTempDirectory("legacy-native-boundary-").toFile()
        try {block(root,NativeWorkingSession.legacyAccess(root))} finally {root.deleteRecursively()}
    }
    private fun observed(access:LegacyAccess):LegacyAbsentObservation {
        val result=access.observeAbsent()
        assertTrue("Expected actual absence, got $result",result is LegacyAbsentResult.Granted)
        return (result as LegacyAbsentResult.Granted).observation
    }
    @Test fun anExistingUnknownHeadCannotGrantLegacyWriteOrDraftPermission()=scenario {root,access->
        val head=File(root,"head.json").apply {writeText("future or damaged native head")}
        val old=head.readBytes()
        assertSame(LegacyAbsentResult.ProtectedRecovery,access.observeAbsent())
        assertArrayEquals(old,head.readBytes())
    }
    @Test fun aHeadAppearingAfterObservationRejectsTheFinalConsume()=scenario {root,access->
        val observation=observed(access)
        val head=File(root,"head.json").apply {writeText("late damaged head")}
        val old=head.readBytes()
        assertFalse("A late unknown head must not be hidden by the cached Absent result",access.consumeForNewDraft(observation))
        assertArrayEquals(old,head.readBytes())
    }
    @Test fun anAcceptedNativeReservationRetiresTheOldAbsentObservation()=scenario {root,access->
        val observation=observed(access)
        val cache=WorkingSessionCache(root,validateBody={_,_,_->})
        val request=cache.reserve(Any(),WorkingSessionCache.Kind.NOTE,UUID.randomUUID().toString(),0,JSONObject())
        try {
            assertFalse(access.consumeForNewDraft(observation))
            assertTrue("Consuming an old legacy ticket must not retire a newer native writer",cache.isCurrent(request))
        } finally {cache.retire(request)}
    }
    @Test fun anAbsentObservationCanBeConsumedOnlyOnceAndNeverByAnotherSlot()=scenario {root,access->
        val old=observed(access)
        assertTrue(access.consumeForNewDraft(old));assertFalse(access.consumeForNewDraft(old))
        val otherRoot=Files.createTempDirectory("other-legacy-slot-").toFile()
        try {assertFalse(NativeWorkingSession.legacyAccess(otherRoot).consumeForNewDraft(observed(access)))}
        finally {otherRoot.deleteRecursively()}
    }
    @Test fun canonicalAliasesShareNativeReservationsAndRetiringAnOldTicketKeepsTheNewOne()=scenario {root,access->
        val alias=NativeWorkingSession.legacyAccess(File(root,"unused/.."))
        val cache=WorkingSessionCache(root,validateBody={_,_,_->})
        val old=cache.reserve(Any(),WorkingSessionCache.Kind.NOTE,UUID.randomUUID().toString(),0,JSONObject())
        val next=cache.reserve(Any(),WorkingSessionCache.Kind.NOTE,UUID.randomUUID().toString(),0,JSONObject())
        cache.retire(old)
        assertTrue(cache.isCurrent(next));assertSame(LegacyAbsentResult.ProtectedRecovery,alias.observeAbsent())
        cache.retire(next)
        assertTrue(alias.observeAbsent() is LegacyAbsentResult.Granted)
        assertTrue(access.observeAbsent() is LegacyAbsentResult.Granted)
    }
    @Test fun aDirectoryOrDanglingLinkAtTheNativeHeadNeverCountsAsAbsent()=scenario {root,access->
        val head=File(root,"head.json")
        assertTrue(head.mkdir());assertSame(LegacyAbsentResult.ProtectedRecovery,access.observeAbsent());assertTrue(head.delete())
        Files.createSymbolicLink(head.toPath(),File(root,"missing-head").toPath())
        assertSame(LegacyAbsentResult.ProtectedRecovery,access.observeAbsent());assertTrue(Files.isSymbolicLink(head.toPath()))
    }
}
