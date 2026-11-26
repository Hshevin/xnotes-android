package com.xnotes.core

import com.xnotes.core.history.DeletePage
import com.xnotes.core.history.History
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.*
import com.xnotes.format.BundleIdentity
import org.junit.Assert.*
import org.junit.Test

class BodyRevisionTest {
    @Test fun dirtyTrueCountsEveryOwnerEditEvenBeforeTheBooleanChanges() {
        val doc=Document.blank();val initial=doc.bodyRevision
        doc.dirty=true;val first=doc.bodyRevision;assertTrue(first>initial)
        doc.dirty=true;assertTrue(doc.bodyRevision>first);assertTrue(doc.dirty)
        val edited=doc.bodyRevision;doc.dirty=false;assertEquals(edited,doc.bodyRevision)
        doc.dirty=false;assertEquals(edited,doc.bodyRevision)
    }
    @Test fun aNewEditWhileNativeSaveHasPreclearedDirtyHasANewRevision() {
        val doc=Document.blank();doc.dirty=true;val pending=doc.snapshot()
        doc.dirty=false;assertEquals(pending.bodyRevision,doc.bodyRevision)
        doc.bookmarks.add(Bookmark(0,"new"));doc.dirty=true
        assertTrue(doc.bodyRevision>pending.bodyRevision);assertTrue(pending.bookmarks.isEmpty())
        val beforeFailure=doc.bodyRevision;doc.dirty=true
        assertTrue(doc.bodyRevision>beforeFailure) // A conservative retry revision is safe.
    }
    @Test fun identityAndMarkerOnlyAdvanceWhenTheirPersistedValueChanges() {
        val doc=Document.blank();val start=doc.bodyRevision
        doc.identity=doc.identity.copy();doc.hasReferenceAnchors=false;assertEquals(start,doc.bodyRevision)
        doc.identity=BundleIdentity();val identity=doc.bodyRevision;assertTrue(identity>start)
        doc.enableReferenceAnchors();val marker=doc.bodyRevision;assertTrue(marker>identity)
        doc.enableReferenceAnchors();doc.identity=doc.identity.copy();assertEquals(marker,doc.bodyRevision)
        doc.identity=doc.identity.copy(pdfResource="a".repeat(64));assertTrue(doc.bodyRevision>marker)
    }
    @Test fun snapshotCarriesCapturedRevisionAndOwnerWithoutBeingAffectedByFurtherEdits() {
        val doc=Document.blank();doc.enableReferenceAnchors();repeat(3) {doc.dirty=true}
        val captured=doc.bodyRevision;val snapshot=doc.snapshot();val twice=snapshot.snapshot()
        assertEquals(captured,snapshot.bodyRevision);assertEquals(captured,twice.bodyRevision)
        assertSame(doc.snapshotOwnerToken,snapshot.snapshotOwnerToken);assertSame(doc.snapshotOwnerToken,twice.snapshotOwnerToken)
        doc.dirty=true;assertEquals(captured,snapshot.bodyRevision)
        snapshot.identity=snapshot.identity.copy(documentId=java.util.UUID.randomUUID().toString())
        assertTrue(snapshot.bodyRevision>captured);assertEquals(captured,twice.bodyRevision)
        assertNotEquals(doc.identity,snapshot.identity)
    }
    @Test fun viewAndLocationMetadataDoNotCountAsBodyEdits() {
        val doc=Document.blank();val revision=doc.bodyRevision
        doc.path="content://notes/moved";doc.displayName="Renamed.xnote";doc.dirty=false
        assertEquals(revision,doc.bodyRevision)
        val board=InfiniteDocument();val v=board.bodyRevision
        board.path="content://notes/moved";board.displayName="Renamed.xcanvas"
        board.lastView=Waypoint("",12.0,30.0,2.0);board.dirty=false
        assertEquals(v,board.bodyRevision)
    }
    @Test fun pageCommandsAndUndoUseTheExistingOwnerDirtyContractWithoutAddingHistory() {
        val doc=Document.blank(2);val history=History().apply {onPush={doc.dirty=true}}
        val command=DeletePage(doc,doc.pages[0],0);val initial=doc.bodyRevision
        command.redo();history.push(command);val deleted=doc.bodyRevision;assertTrue(deleted>initial)
        history.undo();doc.dirty=true;val restored=doc.bodyRevision;assertTrue(restored>deleted)
        assertEquals(2,doc.pages.size);assertFalse(history.canUndo);assertTrue(history.canRedo)
        doc.snapshot();assertFalse(history.canUndo);assertTrue(history.canRedo);assertEquals(restored,doc.bodyRevision)
        history.redo();doc.dirty=true;assertTrue(doc.bodyRevision>restored);assertEquals(1,doc.pages.size)
    }
    @Test fun canvasSnapshotsRetainRevisionAndOwnerWhileIndependentDocumentsDoNotShareOwner() {
        val board=InfiniteDocument();board.dirty=true;board.dirty=true
        val snapshot=board.snapshotForWrite();val twice=snapshot.snapshotForWrite()
        assertEquals(board.bodyRevision,snapshot.bodyRevision);assertEquals(snapshot.bodyRevision,twice.bodyRevision)
        assertSame(board.snapshotOwnerToken,snapshot.snapshotOwnerToken);assertSame(board.snapshotOwnerToken,twice.snapshotOwnerToken)
        val captured=snapshot.bodyRevision;board.dirty=false;assertEquals(captured,board.bodyRevision)
        board.dirty=true;assertEquals(captured,snapshot.bodyRevision)
        snapshot.identity=BundleIdentity();assertTrue(snapshot.bodyRevision>captured);assertEquals(captured,twice.bodyRevision)
        assertNotSame(board.snapshotOwnerToken,InfiniteDocument().snapshotOwnerToken)
        assertNotSame(Document.blank().snapshotOwnerToken,Document.blank().snapshotOwnerToken)
    }
    @Test fun canvasDirtyAndIdentityHaveTheSameCountingRulesAsPagedNotes() {
        val board=InfiniteDocument();val initial=board.bodyRevision
        board.dirty=true;val first=board.bodyRevision;board.dirty=true;assertTrue(board.bodyRevision>first)
        assertTrue(first>initial);val current=board.bodyRevision;board.dirty=false;assertEquals(current,board.bodyRevision)
        board.identity=board.identity.copy();assertEquals(current,board.bodyRevision)
        board.identity=BundleIdentity();assertTrue(board.bodyRevision>current)
    }
    @Test fun independentGeometryCopyHasItsOwnOwnerAndTheCounterIsNotInTheNativeFormat() {
        val doc=Document.blank();repeat(10) {doc.dirty=true}
        val copied=doc.deepCopy(FakeTextMeasurer());assertNotSame(doc.snapshotOwnerToken,copied.snapshotOwnerToken)
        val codec=com.xnotes.format.DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
        val bytes=java.io.ByteArrayOutputStream().also {codec.write(doc.snapshot(),it)}.toByteArray()
        val manifest=java.util.zip.ZipInputStream(bytes.inputStream()).use {zip->
            var entry=zip.nextEntry
            while(entry!=null && entry.name!="manifest.json") {zip.closeEntry();entry=zip.nextEntry}
            requireNotNull(entry);String(zip.readBytes(),Charsets.UTF_8)
        }
        assertFalse(manifest.contains("bodyRevision"));assertFalse(manifest.contains("snapshotOwnerToken"))
        val loaded=codec.read(bytes.inputStream());assertNotSame(doc.snapshotOwnerToken,loaded.snapshotOwnerToken)
        assertNotEquals(doc.bodyRevision,loaded.bodyRevision)
    }
}
