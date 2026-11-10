package com.xnotes.platform

import android.content.ContextWrapper
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.mindmap.MapNativeRestoreHint
import com.xnotes.storage.NotebookStorage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class ProtectedCanvasSessionSourceTest {
    private class Fixture(val root:File) {
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val storage=NotebookStorage(context,targetOverride={error("Canvas cache must never open an original URI")})
        val directory=File(root,"session").apply {mkdir()}
        val assets=File(root,"assets").apply {mkdir()};val temporary=File(root,"raw").apply {mkdir()}
        val codec=CanvasCodec(FakeImageCodec(),FakeTextMeasurer())
        val owner=LegacySessionQueue.attach(directory)
        val native=NativeWorkingSession(directory,DocumentCodec(FakeImageCodec(),FakeTextMeasurer()),codec,storage.pdfResources)
        val source=ProtectedCanvasSessionSource(directory,owner,native,codec,assets,temporary)
        fun seed():InfiniteDocument=InfiniteDocument(lastView=Waypoint("",20.0,30.0,2.5)).also {doc->
            assertEquals(LegacySessionWrite.SAVED,CanvasSessionStore(directory,codec,assets).saveChecked(doc.snapshotForWrite(fixedItems=true),
                CanvasSessionStore.Metadata("content://canvas/unverified","Original.xcanvas",false)))
        }
        fun hint(doc:InfiniteDocument)=MapNativeRestoreHint(doc.identity.documentId,"content://canvas/unverified",DocumentKind.CANVAS)
        fun bytes()=directory.listFiles().orEmpty().filter {it.isFile}.associate {it.name to it.readBytes().toList()}
    }
    private fun scenario(block:suspend (Fixture)->Unit)=runBlocking {withTimeout(10000) {
        val root=Files.createTempDirectory("protected-canvas-source-").toFile();val f=Fixture(root)
        try {block(f)} finally {f.owner.awaitAccepted();f.storage.registry.close();root.deleteRecursively()}
    }}
    @Test fun aTypedCanvasHintRestoresTheActualCacheWithoutOpeningItsRememberedOriginal()=scenario {f->
        val original=f.seed();val before=f.bytes()
        val load=f.source.prepare(f.hint(original))
        assertTrue(load is ProtectedCanvasSessionLoad.Ready)
        (load as ProtectedCanvasSessionLoad.Ready).prepared.use {prepared->
            assertEquals(original.identity,prepared.body.document.identity)
            assertEquals(original.lastView,prepared.body.document.lastView)
            assertEquals("content://canvas/unverified",prepared.body.document.path)
            val verified=requireNotNull(prepared.verify())
            assertTrue(verified.consume());assertFalse(verified.consume())
        }
        assertEquals(before,f.bytes());assertTrue(f.assets.listFiles()!!.isEmpty());assertTrue(f.temporary.listFiles()!!.isEmpty())
    }
    @Test fun aWrongHintAndACoexistingNoteBodyNeverSelectOrAdoptAnotherCache()=scenario {f->
        val original=f.seed();val before=f.bytes()
        val wrong=f.hint(original).copy(documentId=java.util.UUID.randomUUID().toString())
        assertSame(ProtectedCanvasSessionLoad.Mismatched,f.source.prepare(wrong) {
            assertTrue("Wrong actual UUID must be rejected before an asset directory is decoded",f.assets.listFiles()!!.isEmpty())
        })
        assertEquals(before,f.bytes());assertTrue(f.assets.listFiles()!!.isEmpty())
        File(f.directory,"document.xnote").writeText("partial NOTE publication: preserve this material")
        val both=f.bytes()
        assertSame(ProtectedCanvasSessionLoad.Mismatched,f.source.prepare(f.hint(original)))
        assertEquals(both,f.bytes())
    }
    @Test fun nativeClearedWinsOverACompleteLegacyCanvasWithoutDeletingIt()=scenario {f->
        val original=f.seed()
        assertTrue(f.native.publishClear(f.native.reserveClear()).published)
        val before=f.bytes()
        assertSame(ProtectedCanvasSessionLoad.Cleared,f.source.prepare(f.hint(original)))
        assertEquals(before,f.bytes())
    }
    @Test fun aLaterSameOwnerCommandAndChangedSidecarInvalidateTheFinalCanvasTicket()=scenario {f->
        val original=f.seed();val load=f.source.prepare(f.hint(original))
        assertTrue(load is ProtectedCanvasSessionLoad.Ready)
        (load as ProtectedCanvasSessionLoad.Ready).prepared.use {prepared->
            val verified=requireNotNull(prepared.verify())
            requireNotNull(f.owner.submit {"a later accepted command"}).await()
            assertFalse(verified.consume())
            File(f.directory,"canvas-session.json").writeText("""{"dirty":true}""")
            assertNull(prepared.verify())
        }
    }
    @Test fun anUnsavedCanvasHintHasAnActualUuidAndNeverNeedsAnOriginalPath()=scenario {f->
        val document=InfiniteDocument()
        assertEquals(LegacySessionWrite.SAVED,CanvasSessionStore(f.directory,f.codec,f.assets).saveChecked(document.snapshotForWrite(fixedItems=true),
            CanvasSessionStore.Metadata(null,"Unsaved",true)))
        val load=f.source.prepare(MapNativeRestoreHint(document.identity.documentId,null,DocumentKind.CANVAS))
        assertTrue(load is ProtectedCanvasSessionLoad.Ready)
        (load as ProtectedCanvasSessionLoad.Ready).prepared.use {prepared->
            assertNull(prepared.body.document.path);assertTrue(prepared.body.document.dirty)
            assertEquals(document.identity.documentId,prepared.body.persistedDocumentId)
        }
    }
    @Test fun anActiveNativeBodyRemainsProtectedInsteadOfFallingBackToLegacyCanvas()=scenario {f->
        val original=f.seed()
        assertTrue(f.native.publish(f.native.capture(InfiniteDocument(),null)).published)
        val before=f.bytes()
        assertSame(ProtectedCanvasSessionLoad.ProtectedRecovery,f.source.prepare(f.hint(original)))
        assertEquals(before,f.bytes());assertTrue(f.assets.listFiles()!!.isEmpty())
    }
    @Test fun cancelledPreparationWaitsForItsAcceptedSnapshotAndClosesAllLateOwnedOutputs()=scenario {f->
        val original=f.seed();val before=f.bytes()
        val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
        val preparing=CoroutineScope(currentCoroutineContext()).async(Dispatchers.Default) {
            f.source.prepare(f.hint(original)) {
                if(f.temporary.listFiles().orEmpty().isNotEmpty()) {
                    entered.complete(Unit);check(release.await(5,TimeUnit.SECONDS))
                }
            }
        }
        try {
            entered.await();preparing.cancel()
            assertFalse("The accepted FIFO still owns a blocked output",preparing.isCompleted)
            release.countDown()
            assertThrows(CancellationException::class.java) {runBlocking {preparing.await()}}
            f.owner.awaitAccepted()
            assertTrue(f.temporary.listFiles()!!.isEmpty());assertTrue(f.assets.listFiles()!!.isEmpty())
            assertEquals(before,f.bytes())
        } finally {release.countDown();preparing.cancelAndJoin()}
    }
}
