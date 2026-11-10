package com.xnotes.ui

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.rememberCoroutineScope
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class RecordingRecoveryLifetimeTest {
    private class NoViews:AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index:Int,instance:Unit)=Unit
        override fun insertBottomUp(index:Int,instance:Unit)=Unit
        override fun remove(index:Int,count:Int)=Unit
        override fun move(from:Int,to:Int,count:Int)=Unit
        override fun onClear()=Unit
    }
    private class Owner(override val recordingDir:File):RecordOwner {
        override val recordingTitle="Audio"
        override val recordingKey="test"
        override fun freshRecordingId()=1
        override fun placeRecording(audio:AudioData,name:String,rid:Int):AudioItem?=null
        override fun recordingMoved(item:AudioItem,audio:AudioData)=Unit
        override fun recordingMessage(text:String,action:Pair<String,()->Unit>?)=Unit
    }
    private fun scenario(block:(Owner)->Unit) {
        val base=RuntimeEnvironment.getApplication();val root=File(base.cacheDir,"audio-lifetime-${java.util.UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextThemeWrapper(base,android.R.style.Theme_Material_Light_NoActionBar) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=root
        }
        try {
            AudioRecovery.init(context);AudioRecovery.busy=false
            File(root,"recordings/rec-orphan.aac").writeText("original")
            block(Owner(File(root,"assets").apply {mkdirs()}))
        } finally {
            AudioRecovery.dialog?.let(AudioRecovery::detach);AudioRecovery.busy=false;root.deleteRecursively()
        }
    }
    @Test fun disposingDuringAcceptedExportReleasesOnlyItsBusyAndCannotClearANewerDialog()=scenario {owner->
        AudioRecovery.open(owner);val original=AudioRecovery.dialog!!
        val recomposer=Recomposer(Dispatchers.Unconfined);val composition=Composition(NoViews(),recomposer)
        lateinit var scope:CoroutineScope
        composition.setContent {RecordingRecoveryLifetime(original);scope=rememberCoroutineScope()}
        val export=AudioRecovery.beginExport(original,original.candidates.single())!!
        assertTrue(AudioRecovery.startExport(export))
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val copied=AtomicBoolean(false)
        var job:Job?=null
        try {
            val launched=scope.launch(Dispatchers.Default) {AudioRecovery.exportBytes(export) {
                entered.countDown();check(release.await(5,TimeUnit.SECONDS));copied.set(true)
            }}
            job=launched
            launched.invokeOnCompletion {AudioRecovery.finishExport(export)}
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(AudioRecovery.busy)
            composition.dispose();recomposer.cancel()
            assertNull("Disposed composition must release the old global owner/target",AudioRecovery.dialog)
            assertTrue("Accepted audio export continues through disposal",AudioRecovery.busy)
            AudioRecovery.open(owner);val newer=AudioRecovery.dialog!!
            release.countDown();runBlocking {withTimeout(5000) {launched.join()}}
            assertTrue(copied.get());assertFalse(AudioRecovery.busy);assertSame(newer,AudioRecovery.dialog)
            val next=AudioRecovery.beginExport(newer,newer.candidates.single())!!
            AudioRecovery.detach(original);AudioRecovery.finishExport(export)
            assertSame(newer,AudioRecovery.dialog);assertTrue("Old completion cannot release the new export",AudioRecovery.busy)
            AudioRecovery.busy=true // An independent accepted recovery worker is still running.
            AudioRecovery.finishExport(next);assertTrue(AudioRecovery.busy)
            AudioRecovery.busy=false;assertFalse(AudioRecovery.busy)
        } finally {
            release.countDown();composition.dispose();recomposer.cancel()
            runBlocking {withTimeout(5000) {job?.join()}}
            AudioRecovery.finishExport(export)
        }
    }
    @Test fun disposingBeforePickerResultReleasesOnlyThatUnstartedExport()=scenario {owner->
        AudioRecovery.open(owner);val original=AudioRecovery.dialog!!
        val recomposer=Recomposer(Dispatchers.Unconfined);val composition=Composition(NoViews(),recomposer)
        composition.setContent {RecordingRecoveryLifetime(original)}
        val pending=AudioRecovery.beginExport(original,original.candidates.single())!!
        assertTrue(AudioRecovery.busy)
        AudioRecovery.open(owner);val newer=AudioRecovery.dialog!!
        composition.dispose();recomposer.cancel()
        assertSame(newer,AudioRecovery.dialog);assertFalse(AudioRecovery.busy)
        assertFalse(AudioRecovery.startExport(pending))
    }
}
