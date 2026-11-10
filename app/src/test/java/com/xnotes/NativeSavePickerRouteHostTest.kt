package com.xnotes

import android.provider.DocumentsContract
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.util.DocumentKind
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.CanvasCodec
import com.xnotes.pen.EditorPageCopyFixture
import com.xnotes.pen.runParkingScenario
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.CanvasSessionStore
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.platform.LegacySessionWrite
import com.xnotes.ui.Editor
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The same capture, filename, guard decision and destination dispatch used by Main's picker. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeSavePickerRouteHostTest {
    private fun field(editor:Editor,name:String)=Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(editor)
    private suspend fun protectedCanvas(f:EditorPageCopyFixture):InfiniteDocument {
        (field(f.editor,"sessionOwner") as LegacySessionQueue.Owner).awaitAccepted()
        val original=InfiniteDocument(path=f.sourceUri)
        val codec=CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer())
        withContext(Dispatchers.IO) {
            f.source.outputStream().use {codec.write(original,it)}
            assertEquals(LegacySessionWrite.SAVED,CanvasSessionStore(field(f.editor,"sessionDir") as File,codec,
                File(f.root,"assets").apply {mkdirs()}).saveChecked(original,CanvasSessionStore.Metadata(f.sourceUri,null,false)))
        }
        return withContext(Dispatchers.Main) {
            f.editor.restoreSession()
            assertTrue(f.editor.canvasOpen);assertTrue(f.editor.requiresIndependentSave)
            assertNotEquals(f.editor.state.document.identity.documentId,f.editor.infinite.document.identity.documentId)
            f.editor.infinite.document
        }
    }

    @Test fun protectedCanvasUsesTheMainPickerCanvasSuffixAndActualDestinationWriter()=runParkingScenario {f->
        val model=protectedCanvas(f);val oldId=model.identity.documentId
        val original=f.source.readBytes();val done=CompletableDeferred<Unit>();val stops=mutableListOf<NativeActionStop>()
        f.targetDisplayName="Recovered.xcanvas"
        val request=withContext(Dispatchers.Main) {
            assertTrue(requiresNativeClosePrompt(f.editor))
            requireNotNull(NativeSavePickerCapture(f.editor).prepare({done.complete(Unit)},stops::add) {true}).also {
                assertEquals(DocumentKind.CANVAS,it.kind);assertTrue(it.suggestedName.endsWith(".xcanvas"))
                assertTrue(it.readyToSave());assertTrue(it.saveTo(f.targetUri,"Recovered.xcanvas"))
            }
        }
        done.await()
        withContext(Dispatchers.Main) {
            assertFalse(request.current());assertTrue(stops.isEmpty())
            assertSame(model,f.editor.infinite.document);assertNotEquals(oldId,model.identity.documentId)
            assertFalse(f.editor.requiresIndependentSave);assertFalse(requiresNativeClosePrompt(f.editor))
            assertEquals(f.targetUri,f.editor.visibleDocumentUri)
        }
        val savedId=model.identity.documentId
        withContext(Dispatchers.Main) {
            model.add(Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(20.0,30.0,.5),Sample(40.0,50.0,.7))))
            model.dirty=true
            assertTrue(saveDisplayedNative(f.editor))
            assertEquals(savedId,model.identity.documentId);assertFalse(model.dirty)
        }
        val persisted=f.target.inputStream().use {CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()).read(it)}
        assertEquals(savedId,persisted.identity.documentId);assertEquals(1,persisted.items.size)
        assertArrayEquals(original,f.source.readBytes());assertEquals(2,f.writes.get())
    }

    @Test fun cancelledOrFailedCanvasPickerKeepsTheActualModelAndIndependentSaveGuard() {
        for(publicationFails in listOf(false,true)) runParkingScenario {f->
            val model=protectedCanvas(f);val original=f.source.readBytes();val oldId=model.identity.documentId
            val stopped=CompletableDeferred<NativeActionStop>()
            val request=withContext(Dispatchers.Main) {
                requireNotNull(NativeSavePickerCapture(f.editor).prepare({fail("A rejected picker cannot report saved")},{stopped.complete(it)}) {true})
            }
            withContext(Dispatchers.Main) {
                if(publicationFails) {
                    f.beforeTargetWrite={throw IOException("Canvas publication rejected")}
                    assertTrue(request.saveTo(f.targetUri,"Failed.xcanvas"))
                } else {
                    request.stop(NativeActionStop.CANCELLED)
                    assertFalse(request.saveTo(f.targetUri,"Cancelled.xcanvas"))
                }
            }
            assertEquals(if(publicationFails) NativeActionStop.FAILED else NativeActionStop.CANCELLED,stopped.await())
            withContext(Dispatchers.Main) {
                assertSame(model,f.editor.infinite.document);assertEquals(oldId,model.identity.documentId)
                assertTrue(f.editor.requiresIndependentSave);assertTrue(requiresNativeClosePrompt(f.editor))
            }
            assertArrayEquals(original,f.source.readBytes());assertEquals(0,f.writes.get())
        }
    }

    @Test fun aSaveQueuedBehindAnOldWriterAndOpenCannotSaveTheReplacementCanvas()=runParkingScenario {f->
        val old=protectedCanvas(f)
        val adopted=CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            val request=requireNotNull(NativeSavePickerCapture(f.editor).prepare({adopted.complete(Unit)},
                {fail("The initial independent save must succeed")}) {true})
            assertTrue(request.saveTo(f.targetUri,"First.xcanvas"))
        }
        adopted.await()
        val other=InfiniteDocument(path=DocumentsContract.buildDocumentUri(f.authority,"retry").toString())
        val codec=CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer())
        f.retryDisplayName="Other.xcanvas"
        withContext(Dispatchers.IO) {f.retry.outputStream().use {codec.write(other,it)}}
        val otherBytes=f.retry.readBytes()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1);val dispatchSave=CompletableDeferred<Unit>()
        try {
            f.beforeTargetWrite={entered.complete(Unit);check(release.await(10,TimeUnit.SECONDS))}
            withContext(Dispatchers.Main) {
                old.add(Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(20.0,30.0,.5),Sample(40.0,50.0,.7))))
                old.dirty=true
            }
            val writer=scope.async {saveDisplayedNative(f.editor)}
            entered.await()
            val opening=scope.async {f.editor.openCanvasAsync(requireNotNull(other.path),"Other.xcanvas")}
            withTimeout(10000) {while(!withContext(Dispatchers.Main) {f.editor.opening}) delay(1)}
            val accepted=withContext(Dispatchers.Main) {captureDisplayedNativeSave(f.editor)}
            val late=scope.async {
                writer.await();opening.await();dispatchSave.await()
                saveDisplayedNative(f.editor,accepted)
            }
            release.countDown()
            assertTrue(writer.await());assertTrue(opening.await())
            withContext(Dispatchers.Main) {
                assertNotSame(old,f.editor.infinite.document)
                f.editor.infinite.document.add(Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),
                    listOf(Sample(80.0,90.0,.5),Sample(110.0,120.0,.7))))
                f.editor.infinite.document.dirty=true
            }
            val writes=f.writes.get()
            dispatchSave.complete(Unit)
            assertFalse(late.await())
            assertEquals(writes,f.writes.get());assertArrayEquals(otherBytes,f.retry.readBytes())
            withContext(Dispatchers.Main) {assertTrue(f.editor.infinite.document.dirty);assertTrue(saveDisplayedNative(f.editor))}
            assertEquals(1,f.retry.inputStream().use {codec.read(it)}.items.size)
        } finally {release.countDown();f.beforeTargetWrite=null;scope.cancel()}
    }
}
