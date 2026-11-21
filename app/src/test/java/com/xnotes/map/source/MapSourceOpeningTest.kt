package com.xnotes.map.source

import com.xnotes.storage.NativeReferenceIssue
import org.junit.Assert.*
import org.junit.Test

class MapSourceOpeningTest {
    @Test fun aBookmarkIsOfferedOnTheSourceButHiddenOnItsOwnCanvas() {
        assertFalse(offersMapReturn(null, null))
        assertFalse(offersMapReturn("map", "map"))
        assertTrue(offersMapReturn("map", null))
        assertTrue(offersMapReturn("map", "linked canvas"))
    }

    @Test fun aCatalogProblemOnlyExplainsACompatibleOpenFailure() {
        val deleted = SourceStatus.PageDeleted("note", "Notebook")
        assertEquals(deleted, sourceFailureStatus(NativeReferenceIssue.DELETED, deleted))
        assertNull(sourceFailureStatus(NativeReferenceIssue.DELETED, SourceStatus.Missing))
        assertEquals(SourceStatus.WrongKind, sourceFailureStatus(NativeReferenceIssue.UNSUPPORTED, SourceStatus.WrongKind))
        assertEquals(SourceStatus.Missing, sourceFailureStatus(NativeReferenceIssue.UNAVAILABLE, SourceStatus.Missing))
        assertNull(sourceFailureStatus(NativeReferenceIssue.UNAVAILABLE, SourceStatus.Ready("file", "name", 2)))
        for (issue in listOf(NativeReferenceIssue.BUSY, NativeReferenceIssue.CHANGED, NativeReferenceIssue.DUPLICATE))
            assertNull(sourceFailureStatus(issue, deleted))
    }
}
