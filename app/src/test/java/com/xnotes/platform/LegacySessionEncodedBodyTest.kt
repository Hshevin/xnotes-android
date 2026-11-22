package com.xnotes.platform

import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.BundleMigration
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Save simplification: a working-copy body may be published from the exact bytes a file save
 * already encoded, instead of encoding the model a second time. The bytes must be copied (the
 * caller still owns and deletes its save candidate), proven by hash and by bundle identity, and
 * then follow the same bounded legacy publication as an encoded body.
 */
class LegacySessionEncodedBodyTest {
    private fun scenario(block:(File)->Unit) {
        val root=Files.createTempDirectory("legacy-encoded-body-").toFile()
        try {block(root)} finally {root.deleteRecursively()}
    }
    private val noteCodec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
    private val canvasCodec=CanvasCodec(FakeImageCodec(),FakeTextMeasurer())
    private fun noteMeta()=SessionStore.Metadata("content://tree/note","note.xnote",false,1.25,10.0,20.0,false,ViewOverrides())
    private fun canvasMeta()=CanvasSessionStore.Metadata("content://tree/canvas","canvas.xcanvas",false)
    private fun encodedNote(root:File,doc:Document=Document.blank(2)):Pair<Document,File> {
        val fixed=doc.fixedWriteSnapshot()
        val file=File(root,"save-note.tmp").apply {outputStream().use {noteCodec.write(fixed,it,embedPdf=false)}}
        return fixed to file
    }
    private fun encodedCanvas(root:File,doc:InfiniteDocument=InfiniteDocument()):Pair<InfiniteDocument,File> {
        val fixed=doc.snapshotForWrite(fixedItems=true)
        val file=File(root,"save-canvas.tmp").apply {outputStream().use {canvasCodec.write(fixed,it)}}
        return fixed to file
    }
    private fun leftovers(dir:File)=dir.listFiles().orEmpty().filter {it.name.endsWith(".tmp") || it.name.endsWith(".pending")}.map {it.name}
    private fun snapshot(dir:File)=dir.listFiles().orEmpty().filter {it.isFile}.associate {it.name to it.readBytes().toList()}

    @Test fun canvasBodyIsPublishedFromTheSavedBytesAndTheSaveCandidateStaysOwnedByTheCaller()=scenario {root->
        val slot=File(root,"slot").apply {mkdirs()}
        File(slot,"document.xnote").writeBytes(byteArrayOf(1,2,3))
        File(slot,"session.json").writeText("{}")
        val (fixed,encoded)=encodedCanvas(root)
        val saved=encoded.readBytes();val sha=BundleMigration.hash(encoded)
        val access=NativeWorkingSession.legacyAccess(slot)
        val observed=access.observeAbsent() as LegacyAbsentResult.Granted
        PreparedLegacySessionMutation.prepareCanvasFromEncoded(slot,encoded,sha,fixed.snapshotOwnerToken,
            fixed.identity.documentId,canvasMeta()).use {prepared->
            val result=access.publish(observed.observation,prepared)
            assertTrue("A verified saved body must publish: $result",result is LegacyMutationResult.Published)
            val publication=(result as LegacyMutationResult.Published).publication
            assertEquals(File(slot,"canvas.xcanvas").canonicalFile,publication.bodyFile?.canonicalFile)
            assertEquals(sha,publication.bodySha256)
            assertEquals(fixed.identity.documentId,publication.documentId)
            assertSame(fixed.snapshotOwnerToken,publication.snapshotOwnerToken)
        }
        assertArrayEquals("The working copy must hold exactly the saved bytes",saved,File(slot,"canvas.xcanvas").readBytes())
        assertArrayEquals("The caller's save candidate must not be moved or changed",saved,encoded.readBytes())
        assertFalse("A canvas publication still clears the note slot",File(slot,"document.xnote").exists())
        assertFalse(File(slot,"session.json").exists())
        val stored=CanvasSessionStore(slot,canvasCodec,File(root,"images")).load()
        assertNotNull(stored);assertEquals("content://tree/canvas",stored!!.path);assertFalse(stored.dirty)
        assertEquals(emptyList<String>(),leftovers(slot))
    }

    @Test fun noteBodyIsPublishedFromTheSavedBytesAndClearsTheCanvasSlot()=scenario {root->
        val slot=File(root,"slot").apply {mkdirs()}
        File(slot,"canvas.xcanvas").writeBytes(byteArrayOf(9))
        val (fixed,encoded)=encodedNote(root)
        val saved=encoded.readBytes();val sha=BundleMigration.hash(encoded)
        val access=NativeWorkingSession.legacyAccess(slot)
        val observed=access.observeAbsent() as LegacyAbsentResult.Granted
        PreparedLegacySessionMutation.prepareNoteFromEncoded(slot,encoded,sha,fixed.snapshotOwnerToken,
            fixed.identity.documentId,noteMeta()).use {prepared->
            val result=access.publish(observed.observation,prepared)
            assertTrue("A verified saved body must publish: $result",result is LegacyMutationResult.Published)
            assertEquals(sha,(result as LegacyMutationResult.Published).publication.bodySha256)
        }
        assertArrayEquals(saved,File(slot,"document.xnote").readBytes())
        assertArrayEquals(saved,encoded.readBytes())
        assertFalse(File(slot,"canvas.xcanvas").exists())
        assertEquals("{}",File(slot,"canvas-session.json").readText())
        val stored=SessionStore(slot,noteCodec,File(root,"pdf"),File(root,"images")).load()
        assertNotNull(stored);assertEquals(2,stored!!.document.pages.size)
        assertEquals(1.25,stored.zoom,1e-9)
        assertEquals(emptyList<String>(),leftovers(slot))
    }

    @Test fun theCopyIsTakenAtPreparationSoALaterDeletionOfTheSaveCandidateCannotLoseTheBody()=scenario {root->
        val slot=File(root,"slot").apply {mkdirs()}
        val (fixed,encoded)=encodedCanvas(root)
        val saved=encoded.readBytes();val sha=BundleMigration.hash(encoded)
        val access=NativeWorkingSession.legacyAccess(slot)
        val observed=access.observeAbsent() as LegacyAbsentResult.Granted
        PreparedLegacySessionMutation.prepareCanvasFromEncoded(slot,encoded,sha,fixed.snapshotOwnerToken,
            fixed.identity.documentId,canvasMeta()).use {prepared->
            assertTrue(encoded.delete())
            assertTrue(access.publish(observed.observation,prepared) is LegacyMutationResult.Published)
        }
        assertArrayEquals(saved,File(slot,"canvas.xcanvas").readBytes())
    }

    @Test fun aHashMismatchFailsWithoutTouchingTheExistingWorkingCopy()=scenario {root->
        val slot=File(root,"slot").apply {mkdirs()}
        val store=CanvasSessionStore(slot,canvasCodec,File(root,"images"))
        assertEquals(LegacySessionWrite.SAVED,store.saveChecked(InfiniteDocument(),CanvasSessionStore.Metadata(null,"old",true)))
        val before=snapshot(slot)
        val (fixed,encoded)=encodedCanvas(root)
        val wrong="0".repeat(64)
        val access=NativeWorkingSession.legacyAccess(slot)
        val observed=access.observeAbsent() as LegacyAbsentResult.Granted
        PreparedLegacySessionMutation.prepareCanvasFromEncoded(slot,encoded,wrong,fixed.snapshotOwnerToken,
            fixed.identity.documentId,canvasMeta()).use {prepared->
            assertTrue(access.publish(observed.observation,prepared) is LegacyMutationResult.Failed)
        }
        assertEquals("A rejected handoff must leave the previous working copy byte-identical",before,snapshot(slot))
        assertEquals(emptyList<String>(),leftovers(slot))
    }

    @Test fun aDifferentDocumentOrKindFailsEvenWhenTheHashMatches()=scenario {root->
        val slot=File(root,"slot").apply {mkdirs()}
        val (fixed,encoded)=encodedCanvas(root)
        val sha=BundleMigration.hash(encoded)
        val otherId=InfiniteDocument().identity.documentId
        assertNotEquals(fixed.identity.documentId,otherId)
        val access=NativeWorkingSession.legacyAccess(slot)
        // Another document's identity.
        (access.observeAbsent() as LegacyAbsentResult.Granted).let {observed->
            PreparedLegacySessionMutation.prepareCanvasFromEncoded(slot,encoded,sha,fixed.snapshotOwnerToken,otherId,canvasMeta()).use {prepared->
                assertTrue(access.publish(observed.observation,prepared) is LegacyMutationResult.Failed)
            }
        }
        // A canvas bundle offered as a note body.
        (access.observeAbsent() as LegacyAbsentResult.Granted).let {observed->
            PreparedLegacySessionMutation.prepareNoteFromEncoded(slot,encoded,sha,fixed.snapshotOwnerToken,
                fixed.identity.documentId,noteMeta()).use {prepared->
                assertTrue(access.publish(observed.observation,prepared) is LegacyMutationResult.Failed)
            }
        }
        assertFalse(File(slot,"canvas.xcanvas").exists())
        assertFalse(File(slot,"document.xnote").exists())
        assertEquals(emptyList<String>(),leftovers(slot))
    }

    @Test fun aHandoffBesideANativeHeadIsProtectedRecoveryAndWritesNothing()=scenario {root->
        val slot=File(root,"slot").apply {mkdirs()}
        val (fixed,encoded)=encodedCanvas(root)
        val sha=BundleMigration.hash(encoded)
        val access=NativeWorkingSession.legacyAccess(slot)
        val observed=access.observeAbsent() as LegacyAbsentResult.Granted
        File(slot,"head.json").writeText("preserve unknown native head")
        val before=snapshot(slot)
        PreparedLegacySessionMutation.prepareCanvasFromEncoded(slot,encoded,sha,fixed.snapshotOwnerToken,
            fixed.identity.documentId,canvasMeta()).use {prepared->
            assertEquals(LegacyMutationResult.ProtectedRecovery,access.publish(observed.observation,prepared))
        }
        assertEquals(before,snapshot(slot))
        assertEquals(emptyList<String>(),leftovers(slot))
    }

    @Test fun cleanDiskTrackingAppliesToSavedBodiesLikeEncodedOnes()=scenario {root->
        val slot=File(root,"slot").apply {mkdirs()}
        val (fixed,encoded)=encodedCanvas(root)
        val sha=BundleMigration.hash(encoded)
        val access=NativeWorkingSession.legacyAccess(slot)
        (access.observeAbsent() as LegacyAbsentResult.Granted).let {observed->
            PreparedLegacySessionMutation.prepareCanvasFromEncoded(slot,encoded,sha,fixed.snapshotOwnerToken,
                fixed.identity.documentId,canvasMeta().copy(dirty=true)).use {prepared->
                prepared.trackDiskReopen(false)
                assertTrue(access.publish(observed.observation,prepared) is LegacyMutationResult.Published)
            }
        }
        assertFalse("A dirty handoff must veto direct disk reopen",PreparedLegacySessionMutation.allowsDiskReopen(slot))
        (access.observeAbsent() as LegacyAbsentResult.Granted).let {observed->
            PreparedLegacySessionMutation.prepareCanvasFromEncoded(slot,encoded,sha,fixed.snapshotOwnerToken,
                fixed.identity.documentId,canvasMeta()).use {prepared->
                prepared.trackDiskReopen(true)
                assertTrue(access.publish(observed.observation,prepared) is LegacyMutationResult.Published)
            }
        }
        assertTrue("A clean handoff lifts the veto",PreparedLegacySessionMutation.allowsDiskReopen(slot))
    }
}
