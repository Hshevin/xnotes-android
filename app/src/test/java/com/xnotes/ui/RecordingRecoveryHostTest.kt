package com.xnotes.ui

import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.Document
import com.xnotes.core.util.DocumentKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class RecordingRecoveryHostTest {
    private class Queue:Executor {
        val jobs=java.util.ArrayDeque<Runnable>()
        override fun execute(work:Runnable) {jobs.add(work)}
        fun one() {jobs.removeFirst().run()}
        fun drain() {repeat(20) {if(jobs.isEmpty()) return;one();shadowOf(Looper.getMainLooper()).idle()}}
    }
    private class Owner(val directory:File):RecordOwner {
        var model=Document.blank();var epoch=0;var added=0;var moved=0
        var messageAction:Pair<String,()->Unit>?=null
        override val recordingDir=File(directory,"assets").apply {mkdirs()}
        override val recordingKey="note|original"
        override val recordingTitle="Original"
        override fun freshRecordingId()=(model.pages.flatMap {it.items}.filterIsInstance<AudioItem>().maxOfOrNull {it.rid} ?: 0)+1
        override fun placeRecording(audio:AudioData,name:String,rid:Int):AudioItem=AudioItem(audio,Rect(0.0,0.0,100.0,50.0),name,rid,FakeTextMeasurer()).also {model.pages.first().items.add(it);added++}
        override fun recordingMoved(item:AudioItem,audio:AudioData) {item.audio=audio;moved++}
        override fun recordingMessage(text:String,action:Pair<String,()->Unit>?) {messageAction=action}
        override fun recordingRecoveryTarget():RecordingRecoveryTarget {
            val old=model;val at=epoch;val id=old.identity.documentId
            return RecordingRecoveryTarget(this,old,old.snapshotOwnerToken,DocumentKind.NOTE,id,setOf(recordingKey),recordingDir,recordingTitle,
                {model===old && epoch==at && old.identity.documentId==id},
                {rid->old.pages.flatMap {it.items}.filterIsInstance<AudioItem>().filter {it.rid==rid}},
                {item->old.pages.any {p->p.items.any {it===item}}})
        }
        fun replace() {model=Document.blank();epoch++}
    }
    private fun scenario(block:(Owner,Queue,File)->Unit) {
        val base=RuntimeEnvironment.getApplication();val root=File(base.cacheDir,"audio-host-${java.util.UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextThemeWrapper(base,android.R.style.Theme_Material_Light_NoActionBar) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=root
        }
        val priorExecutor=AudioRecording.recoveryExecutor;val priorDuration=AudioRecording.readDuration
        val priorRemux=AudioRecording.remux;val priorPeaks=AudioRecording.readPeaks
        try {
            AudioRecording.init(context);val owner=Owner(root);val queue=Queue()
            AudioRecording.recoveryExecutor=queue;AudioRecording.readDuration={1000};AudioRecording.remux={_,_->false};AudioRecording.readPeaks={_,_,done->done(ByteArray(3))}
            val raw=File(AudioRecording.dir(context),"rec-test.aac").apply {writeText("original AAC")}
            AudioRecovery.store!!.create(raw,owner.recordingKey,"Recovered",4,DocumentKind.NOTE,owner.model.identity.documentId)
            block(owner,queue,raw)
        } finally {
            AudioRecording.abandon()
            AudioRecording::class.java.getDeclaredField("discarded").apply {isAccessible=true}.set(null,null)
            val forget=AudioRecording::class.java.getDeclaredField("forget").apply {isAccessible=true}.get(null) as Runnable
            android.os.Handler(Looper.getMainLooper()).removeCallbacks(forget)
            AudioRecording.recoveryExecutor=priorExecutor;AudioRecording.readDuration=priorDuration
            AudioRecording.remux=priorRemux;AudioRecording.readPeaks=priorPeaks
            AudioRecovery.busy=false;AudioRecovery.close();root.deleteRecursively()
        }
    }
    @Test fun replacingTheModelBeforeDurationCompletesKeepsRawWithoutTouchingNewModel()=scenario {owner,queue,raw->
        AudioRecording.recover(owner.recordingRecoveryTarget());owner.replace();queue.drain()
        assertEquals(0,owner.added);assertEquals(0,owner.moved);assertTrue(owner.model.pages.first().items.isEmpty());assertTrue(raw.exists())
    }
    @Test fun replacingBetweenApplyingAndPlacementKeepsApplyingForAnExplicitChoice()=scenario {owner,queue,raw->
        AudioRecording.recover(owner.recordingRecoveryTarget());queue.one();shadowOf(Looper.getMainLooper()).idle()
        queue.one();owner.replace();shadowOf(Looper.getMainLooper()).idle();queue.drain()
        assertEquals(0,owner.added);assertTrue(raw.exists());assertEquals(RecordingRecoveryStore.Problem.Applying,AudioRecovery.store!!.list().single().problem)
    }
    @Test fun latePeaksAndRemuxCannotModifyTheReplacementAndUndoDoesNotAutoReplay()=scenario {owner,queue,raw->
        var peaks:((ByteArray?)->Unit)?=null
        AudioRecording.readPeaks={_,_,done->peaks=done}
        AudioRecording.remux={_,out->out.writeText("M4A");true}
        AudioRecording.recover(owner.recordingRecoveryTarget());queue.one();shadowOf(Looper.getMainLooper()).idle();queue.one();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,owner.added);owner.model.pages.first().items.clear()
        AudioRecording.recover(owner.recordingRecoveryTarget());assertEquals(1,owner.added)
        owner.replace();peaks!!(ByteArray(4));shadowOf(Looper.getMainLooper()).idle();queue.drain()
        assertEquals(0,owner.moved);assertTrue(owner.model.pages.first().items.isEmpty());assertTrue(raw.exists())
    }
    @Test fun discardUndoWindowReservesRawFromMenuAndExplicitRecoveryThenUndoStillWorks()=scenario {owner,queue,raw->
        val record=AudioRecovery.store!!.list().single().record!!
        val s=AudioRecording.Session(owner,record.rid,record.name,raw).apply {
            recoveryId=record.id;accumulated=1000;paused=true
        }
        val state=AudioRecording::class.java.getDeclaredField("session\$delegate").apply {isAccessible=true}.get(null)
            as androidx.compose.runtime.MutableState<AudioRecording.Session?>
        state.value=s
        AudioRecording.discard()
        assertTrue(raw.exists());assertTrue(AudioRecording.recoveryFileProtected(raw))
        val orphan=AudioRecovery.store!!.list().single()
        AudioRecovery.open(owner);assertTrue(AudioRecovery.dialog!!.candidates.isEmpty())
        AudioRecording.recoverExplicit(owner.recordingRecoveryTarget(),orphan,separate=true)
        assertTrue(queue.jobs.isEmpty());assertEquals(0,owner.added)
        owner.messageAction!!.second.invoke()
        assertSame(s,AudioRecording.session);assertTrue(s.paused)
        AudioRecovery.open(owner);assertTrue(AudioRecovery.dialog!!.candidates.isEmpty())
        AudioRecording.stop();queue.drain()
        assertEquals(1,owner.added);assertTrue(raw.exists())
    }
    @Test fun twoRecordingsStartedInTheSameMillisecondNeverOverwriteAnOlderRawFile()=scenario {owner,_,_->
        val context=object:ContextThemeWrapper(RuntimeEnvironment.getApplication(),android.R.style.Theme_Material_Light_NoActionBar) {
            override fun getFilesDir()=owner.directory
        }
        val first=AudioRecording.newRecordingFile(context,1234L).apply {writeText("keep original")}
        val second=AudioRecording.newRecordingFile(context,1234L).apply {writeText("second")}
        assertNotEquals(first.canonicalPath,second.canonicalPath)
        assertEquals("keep original",first.readText());assertEquals("second",second.readText())
    }
    @Test fun anOldDiscardCleanupCannotDeleteRawClaimedByANewApplyingRecordId()=scenario {owner,_,raw->
        val candidate=AudioRecovery.store!!.list().single();val original=candidate.record!!
        val old=AudioRecording.Session(owner,original.rid,original.name,raw).apply {recoveryId=original.id}
        assertTrue(AudioRecovery.store!!.discardMetadata(candidate))
        val orphan=AudioRecovery.store!!.list().single()
        val claimed=AudioRecovery.store!!.applying(orphan,DocumentKind.NOTE,owner.model.identity.documentId,8,
            com.xnotes.format.BundleMigration.hash(raw),owner.model.snapshotOwnerToken)!!
        assertNotEquals(original.id,claimed.id)
        val drop=AudioRecording::class.java.getDeclaredMethod("dropFile",AudioRecording.Session::class.java).apply {isAccessible=true}
        drop.invoke(AudioRecording,old)
        assertTrue(raw.exists());assertEquals(claimed.id,AudioRecovery.store!!.list().single().record!!.id)
        assertEquals(RecordingRecoveryStore.Problem.Applying,AudioRecovery.store!!.list().single().problem)
    }
    @Test fun anOldDiscardUndoCannotRestartRecordingOnAReplacementModel()=scenario {owner,_,raw->
        val record=AudioRecovery.store!!.list().single().record!!
        val s=AudioRecording.Session(owner,record.rid,record.name,raw).apply {
            recoveryId=record.id;accumulated=1000;paused=true
        }
        val state=AudioRecording::class.java.getDeclaredField("session\$delegate").apply {isAccessible=true}.get(null)
            as androidx.compose.runtime.MutableState<AudioRecording.Session?>
        state.value=s;AudioRecording.discard();val undo=owner.messageAction!!.second
        owner.replace();undo()
        assertNull(AudioRecording.session);assertNull(AudioRecording.markFor(owner));assertTrue(raw.exists())
        assertEquals(0,owner.added);assertTrue(owner.model.pages.first().items.isEmpty())
        assertEquals(RecordingRecoveryStore.Problem.Unbound,AudioRecovery.candidates().single().problem)
    }
    @Test fun anActiveOldRecordingCannotStampInkInAReplacementModelUsingTheSameHost()=scenario {owner,_,raw->
        val s=AudioRecording.Session(owner,4,"Recording",raw)
        val state=AudioRecording::class.java.getDeclaredField("session\$delegate").apply {isAccessible=true}.get(null)
            as androidx.compose.runtime.MutableState<AudioRecording.Session?>
        state.value=s;assertNotNull(AudioRecording.markFor(owner))
        owner.replace();assertNull(AudioRecording.markFor(owner))
    }
}
