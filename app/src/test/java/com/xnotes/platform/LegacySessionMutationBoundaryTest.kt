package com.xnotes.platform

import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.io.IOException

class LegacySessionMutationBoundaryTest {
    private fun scenario(block:(File)->Unit) {
        val root=Files.createTempDirectory("legacy-mutation-boundary-").toFile()
        try {block(root)} finally {root.deleteRecursively()}
    }
    private fun note(root:File)=SessionStore(root,DocumentCodec(FakeImageCodec(),FakeTextMeasurer()),File(root,"pdf"),File(root,"images"))
    private fun canvas(root:File)=CanvasSessionStore(root,CanvasCodec(FakeImageCodec(),FakeTextMeasurer()),File(root,"images"))
    private fun metadata()=SessionStore.Metadata(null,"local.xnote",true,1.5,12.0,34.0,false,ViewOverrides())
    private fun bytes(root:File)=root.listFiles().orEmpty().filter {it.isFile}.associate {it.name to it.readBytes().toList()}

    /** Actual old public writer regression, independent of the new staged API scaffold. */
    @Test fun theExistingNoteWriterCannotOverwriteLegacyRecoveryBesideANativeHead()=scenario {root->
        val store=note(root)
        assertEquals(LegacySessionWrite.SAVED,store.saveChecked(Document.blank(2),metadata()))
        File(root,"head.json").writeText("preserve unknown native head")
        val before=bytes(root)
        assertEquals(LegacySessionWrite.FAILED,store.saveChecked(Document.blank(3),metadata()))
        assertEquals(before,bytes(root))
    }
    /** Actual old clear regression: a tombstone is present recovery state, never Absent. */
    @Test fun theExistingCanvasClearCannotEraseRecoveryBesideANativeTombstone()=scenario {root->
        val store=canvas(root)
        assertEquals(LegacySessionWrite.SAVED,store.saveChecked(InfiniteDocument(),CanvasSessionStore.Metadata(null,"local",true)))
        File(root,"head.json").writeText("""{"version":1,"state":"cleared"}""")
        val before=bytes(root)
        assertFalse(store.clearChecked())
        assertEquals(before,bytes(root))
    }
    /** New API scaffold RED; the old-entry regressions above establish the pre-existing defect. */
    @Test fun aPreparedNotePublicationWritesTheActualBodyAndReturnsItsCaptureOwner()=scenario {root->
        val document=Document.blank(2)
        val fixed=document.fixedWriteSnapshot()
        val access=NativeWorkingSession.legacyAccess(root)
        val observed=access.observeAbsent() as LegacyAbsentResult.Granted
        note(root).prepareMutation(fixed,metadata()).use {prepared->
            val result=access.publish(observed.observation,prepared)
            assertTrue("An accepted complete legacy plan must publish",result is LegacyMutationResult.Published)
            val publication=(result as LegacyMutationResult.Published).publication
            assertEquals(File(root,"document.xnote").canonicalFile,publication.bodyFile?.canonicalFile)
            assertSame(fixed.snapshotOwnerToken,publication.snapshotOwnerToken)
            assertEquals(2,note(root).load()!!.document.pages.size)
            assertFalse(File(root,"canvas.xcanvas").exists())
            assertEquals("{}",File(root,"canvas-session.json").readText())
        }
    }
    @Test fun metadataOnlyPublicationCannotAcknowledgeAnAudioBodyAndPreservesItsBytes()=scenario {root->
        val store=note(root)
        assertEquals(LegacySessionWrite.SAVED,store.saveChecked(Document.blank(2),metadata()))
        val old=File(root,"document.xnote").readBytes();val access=NativeWorkingSession.legacyAccess(root)
        val observed=access.observeAbsent() as LegacyAbsentResult.Granted
        store.prepareMutation(null,metadata()).use {prepared->
            val result=access.publish(observed.observation,prepared) as LegacyMutationResult.Published
            assertNull(result.publication.bodyFile);assertNull(result.publication.snapshotOwnerToken)
            assertNull(result.publication.bodySha256);assertNull(result.publication.documentId)
        }
        assertArrayEquals(old,File(root,"document.xnote").readBytes())
    }
    @Test fun failureAtEachNotePlanStepPreservesTheActualCompletedPrefixAndUnvisitedPeer() {
        for(failAt in 1..4) scenario {root->
            val codec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
            assertEquals(LegacySessionWrite.SAVED,note(root).saveChecked(Document.blank(1),metadata()))
            assertEquals(LegacySessionWrite.SAVED,canvas(root).saveChecked(InfiniteDocument(),CanvasSessionStore.Metadata(null,"old canvas",true)))
            val oldNote=File(root,"document.xnote").readBytes();val oldCanvas=File(root,"canvas.xcanvas").readBytes()
            val oldCanvasMeta=File(root,"canvas-session.json").readBytes();val visited=ArrayList<String>()
            val ops=object:PreparedLegacySessionMutation.FileOps {
                private fun step(target:File) {visited+=target.name;if(visited.size==failAt) throw IOException("injected publication failure")}
                override fun replace(source:File,target:File) {step(target);PreparedLegacySessionMutation.SystemFileOps.replace(source,target)}
                override fun delete(target:File) {step(target);PreparedLegacySessionMutation.SystemFileOps.delete(target)}
            }
            val access=NativeWorkingSession.legacyAccess(root);val observation=(access.observeAbsent() as LegacyAbsentResult.Granted).observation
            PreparedLegacySessionMutation.prepareNote(root,codec,Document.blank(3).fixedWriteSnapshot(),metadata(),fileOps=ops).use {prepared->
                val result=access.publish(observation,prepared) as LegacyMutationResult.Failed
                assertEquals(listOf(LegacyMutationStep.NOTE_BODY,LegacyMutationStep.NOTE_METADATA,LegacyMutationStep.CANVAS_BODY,LegacyMutationStep.CANVAS_METADATA).take(failAt-1),result.completed)
            }
            assertEquals(listOf("document.xnote","session.json","canvas.xcanvas","canvas-session.json").take(failAt),visited)
            if(failAt==1) assertArrayEquals(oldNote,File(root,"document.xnote").readBytes())
            else assertEquals(3,note(root).load()!!.document.pages.size)
            if(failAt<=3) assertArrayEquals(oldCanvas,File(root,"canvas.xcanvas").readBytes()) else assertFalse(File(root,"canvas.xcanvas").exists())
            assertArrayEquals(oldCanvasMeta,File(root,"canvas-session.json").readBytes())
            assertFalse(root.listFiles().orEmpty().any {it.name.endsWith(".tmp") || it.name.endsWith(".pending")})
        }
    }
    @Test fun aMoveThatPublishesThenThrowsCannotBeRolledBackByClose()=scenario {root->
        val codec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer());val access=NativeWorkingSession.legacyAccess(root)
        val observation=(access.observeAbsent() as LegacyAbsentResult.Granted).observation
        val ops=object:PreparedLegacySessionMutation.FileOps {
            override fun replace(source:File,target:File) {PreparedLegacySessionMutation.SystemFileOps.replace(source,target);throw IOException("post-move failure")}
            override fun delete(target:File)=PreparedLegacySessionMutation.SystemFileOps.delete(target)
        }
        PreparedLegacySessionMutation.prepareNote(root,codec,Document.blank(4),metadata(),fileOps=ops).use {prepared->
            val result=access.publish(observation,prepared) as LegacyMutationResult.Failed
            assertEquals(listOf(LegacyMutationStep.NOTE_BODY),result.completed)
        }
        assertEquals(4,File(root,"document.xnote").inputStream().use {codec.read(it)}.pages.size)
    }
    @Test fun aLateNativeHeadRejectsAllThreePlansWithoutTouchingLegacyFiles() {
        for(kind in 0..2) scenario {root->
            note(root).saveChecked(Document.blank(1),metadata())
            canvas(root).saveChecked(InfiniteDocument(),CanvasSessionStore.Metadata(null,"old",true))
            val access=NativeWorkingSession.legacyAccess(root);val observation=(access.observeAbsent() as LegacyAbsentResult.Granted).observation
            val before=bytes(root)
            val prepared=when(kind) {
                0->note(root).prepareMutation(Document.blank(2),metadata())
                1->canvas(root).prepareMutation(InfiniteDocument(),CanvasSessionStore.Metadata(null,"new",true))
                else->PreparedLegacySessionMutation.prepareClosed(root)
            }
            prepared.use {File(root,"head.json").writeText("late unknown head");assertSame(LegacyMutationResult.ProtectedRecovery,access.publish(observation,it))}
            val after=bytes(root).filterKeys {it!="head.json"}
            assertEquals(before,after);assertEquals("late unknown head",File(root,"head.json").readText())
        }
    }
    @Test fun closePreservesForeignFilesReusingEveryPublishedCandidatePathname()=scenario {root->
        val codec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer());val access=NativeWorkingSession.legacyAccess(root)
        val observation=(access.observeAbsent() as LegacyAbsentResult.Granted).observation
        val foreign=ArrayList<File>()
        val ops=object:PreparedLegacySessionMutation.FileOps {
            override fun replace(source:File,target:File) {
                PreparedLegacySessionMutation.SystemFileOps.replace(source,target)
                source.writeText("a different request now owns this pathname");foreign+=source
            }
            override fun delete(target:File)=PreparedLegacySessionMutation.SystemFileOps.delete(target)
        }
        PreparedLegacySessionMutation.prepareNote(root,codec,Document.blank(2),metadata(),fileOps=ops).use {prepared->
            assertTrue(access.publish(observation,prepared) is LegacyMutationResult.Published)
        }
        assertEquals(3,foreign.size)
        for(file in foreign) assertEquals("a different request now owns this pathname",file.readText())
        assertEquals(2,File(root,"document.xnote").inputStream().use {codec.read(it)}.pages.size)
    }
}
