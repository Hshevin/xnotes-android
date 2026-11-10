package com.xnotes.pen

import android.os.Looper
import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.Document
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleMigration
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.SessionStore
import com.xnotes.ui.AudioRecording
import com.xnotes.ui.AudioRecovery
import com.xnotes.ui.Editor
import com.xnotes.ui.RecordingRecoveryStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** These use real saved bundle bytes and actual Editor adoption, not freshly invented owner proof. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class EditorRecordingRecoveryHostTest {
    private fun field(editor:Editor,name:String)=Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(editor)
    private suspend fun stage(f:EditorPageCopyFixture,session:Boolean=false,differentSha:Boolean=false,includeAudio:Boolean=true):File {
        val slot=withContext(Dispatchers.Main) {
            AudioRecording.init(f.context)
            field(f.editor,"sessionDir") as File
        }
        return withContext(Dispatchers.IO) {
            val raw=File(AudioRecording.dir(f.context),"rec-applying.aac").apply {writeText("original audio payload")}
            val document=Document.blank().apply {path=f.sourceUri}
            val store=requireNotNull(AudioRecovery.store)
            store.create(raw,"note|${f.sourceUri}","Recovered",6,DocumentKind.NOTE,document.identity.documentId)
            assertNotNull(store.applying(store.list().single(),DocumentKind.NOTE,document.identity.documentId,6,BundleMigration.hash(raw),Any()))
            val embedded=if(differentSha) File(f.root,"other.aac").apply {writeText("other audio payload")} else raw
            if(includeAudio) document.pages.first().items.add(AudioItem(AudioData(embedded,"aac",1000,ByteArray(0)),Rect(0.0,0.0,100.0,50.0),"Recovered",6,AndroidTextMeasurer()))
            f.source.outputStream().use {f.codec.write(document,it)}
            if(session) {
                val store=SessionStore(slot,f.codec,File(f.root,"pdf").apply {mkdirs()},File(f.root,"assets").apply {mkdirs()})
                assertEquals(com.xnotes.platform.LegacySessionWrite.SAVED,store.saveChecked(document,
                    SessionStore.Metadata(f.sourceUri,"source.xnote",false,1.0,0.0,0.0,false,ViewOverrides())))
            }
            raw
        }
    }
    @Test fun normalOpenFinalizesTheActualSavedApplyingAudioWithoutAddingAnotherPill()=runParkingScenario {f->
        val raw=stage(f)
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote")
            assertEquals(1,f.editor.state.document.pages.flatMap {it.items}.filterIsInstance<AudioItem>().size)
        }
        withTimeout(5000) {while(raw.exists()) delay(1)}
        assertTrue(AudioRecovery.store!!.list().isEmpty())
    }
    @Test fun aNewEditorRestoresAnActualSavedApplyingBodyAndFinalizesWithoutReplayingIt()=runParkingScenario {f->
        val raw=stage(f,session=true)
        withContext(Dispatchers.Main) {
            val oldScope=field(f.editor,"autosaveScope") as CoroutineScope
            oldScope.cancel();oldScope.coroutineContext[Job]?.join()
            f.editor=Editor(f.context)
            f.editor.restoreSession()
            assertTrue(f.editor.noteOpen)
            assertEquals(1,f.editor.state.document.pages.flatMap {it.items}.filterIsInstance<AudioItem>().size)
        }
        withTimeout(5000) {while(raw.exists()) delay(1)}
        assertTrue(AudioRecovery.store!!.list().isEmpty())
    }
    @Test fun anEqualRidWithDifferentActualSavedAudioKeepsTheOriginalAndDoesNotReplay()=runParkingScenario {f->
        val raw=stage(f,differentSha=true)
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.sourceUri,"source.xnote")
            f.editor.view.layout(0,0,1000,1000)
            val target=requireNotNull(f.editor.recordingRecoveryTarget())
            AudioRecording.recover(target)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(raw.exists())
            assertEquals(1,f.editor.state.document.pages.flatMap {it.items}.filterIsInstance<AudioItem>().size)
            assertEquals(RecordingRecoveryStore.Problem.Applying,AudioRecovery.store!!.list().single().problem)
        }
    }
    @Test fun protectedRestoreExplicitAudioAndActualIndependentSaveAsOnlyFinalizeAfterNewUuidAdoption()=runParkingScenario {f->
        val raw=stage(f,session=true,includeAudio=false);val rawSha=BundleMigration.hash(raw);val original=f.source.readBytes()
        val priorExecutor=AudioRecording.recoveryExecutor;val priorDuration=AudioRecording.readDuration
        val priorRemux=AudioRecording.remux;val priorPeaks=AudioRecording.readPeaks
        try {
            val restored=withContext(Dispatchers.Main) {
                val oldScope=field(f.editor,"autosaveScope") as CoroutineScope
                oldScope.cancel();oldScope.coroutineContext[Job]?.join();f.editor=Editor(f.context)
                f.editor.restoreSession();assertTrue(f.editor.requiresIndependentSave)
                assertTrue(f.editor.state.document.pages.flatMap {it.items}.filterIsInstance<AudioItem>().isEmpty())
                assertTrue(raw.exists())
                AudioRecording.recoveryExecutor=java.util.concurrent.Executor {it.run()}
                AudioRecording.readDuration={1000};AudioRecording.remux={_,_->false};AudioRecording.readPeaks={_,_,done->done(ByteArray(0))}
                val candidate=AudioRecovery.candidates().single()
                AudioRecording.recoverExplicit(requireNotNull(f.editor.recordingRecoveryTarget()),candidate)
                f.editor.state.document
            }
            withTimeout(5000) {while(withContext(Dispatchers.Main) {restored.pages.flatMap {it.items}.filterIsInstance<AudioItem>().size}!=1) delay(1)}
            val oldId=restored.identity.documentId
            val failed=CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                f.denyTargetRead=true
                f.editor.saveAsToThen(f.targetUri,"audio-copy.xnote") {failed.complete(it)}
            }
            assertFalse(failed.await());assertTrue(raw.exists());assertEquals(oldId,restored.identity.documentId)
            assertEquals(RecordingRecoveryStore.Problem.Applying,AudioRecovery.store!!.list().single().problem)
            val saved=CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                f.denyTargetRead=false
                f.editor.saveAsToThen(f.targetUri,"audio-copy.xnote") {saved.complete(it)}
            }
            assertTrue(saved.await())
            withTimeout(5000) {while(raw.exists()) delay(1)}
            val encoded=withContext(Dispatchers.IO) {f.target.inputStream().use {f.codec.read(it,
                File(f.root,"verify-pdf").apply {mkdirs()},File(f.root,"verify-audio").apply {mkdirs()})}}
            withContext(Dispatchers.Main) {
                assertSame(restored,f.editor.state.document);assertNotEquals(oldId,restored.identity.documentId)
                assertEquals(f.targetUri,restored.path);assertFalse(f.editor.requiresIndependentSave)
                assertEquals(restored.identity.documentId,encoded.identity.documentId)
            }
            val audio=encoded.pages.flatMap {it.items}.filterIsInstance<AudioItem>().single()
            assertEquals(6,audio.rid);assertEquals(rawSha,BundleMigration.hash(audio.audio.file))
            assertTrue(AudioRecovery.store!!.list().isEmpty());assertArrayEquals(original,f.source.readBytes())
        } finally {
            AudioRecording.recoveryExecutor=priorExecutor;AudioRecording.readDuration=priorDuration
            AudioRecording.remux=priorRemux;AudioRecording.readPeaks=priorPeaks
        }
    }
}
