package com.xnotes.platform

import android.content.ContextWrapper
import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Document
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.storage.NotebookStorage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeLegacyBoundaryTest {
    private fun scenario(block:(NativeWorkingSession,LegacyAccess,((WorkingSessionCache.Event)->Unit)->Unit)->Unit) {
        val root=Files.createTempDirectory("native-legacy-retirement-").toFile()
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val storage=NotebookStorage(context)
        var hook:(WorkingSessionCache.Event)->Unit={}
        val directory=File(root,"working")
        val store=NativeWorkingSession(directory,DocumentCodec(FakeImageCodec(),FakeTextMeasurer()),
            CanvasCodec(FakeImageCodec(),FakeTextMeasurer()),storage.pdfResources,onEvent={event,_->hook(event)})
        try {block(store,NativeWorkingSession.legacyAccess(directory)) {hook=it}}
        finally {storage.registry.close();root.deleteRecursively()}
    }
    private fun capture(store:NativeWorkingSession)=store.capture(Document.blank(),null,
        NativeWorkingSession.NoteView(1.0,0.0,0.0,false,ViewOverrides()))

    @Test fun aFailedNativePublicationRetiresOnlyItsTicketAndAnActualSuccessStaysCurrent()=scenario {store,access,hook->
        val failed=capture(store)
        hook {if(it==WorkingSessionCache.Event.BEFORE_HEAD_COMMIT) throw IOException("fail before actual head publication")}
        assertThrows(IOException::class.java) {store.publish(failed)}
        assertFalse(store.isCurrent(failed));assertTrue(access.observeAbsent() is LegacyAbsentResult.Granted)
        hook {}
        val success=capture(store)
        assertTrue(store.publish(success).published)
        store.retire(success)
        assertTrue("Closing a completed request cannot revoke the actual successful head proof",store.isCurrent(success))
        assertSame(LegacyAbsentResult.ProtectedRecovery,access.observeAbsent())
    }
    @Test fun clearPreflightFailureAndExplicitlyAbandonedCaptureLeaveNoPermanentReservation()=scenario {store,access,_->
        val clear=store.reserveClear()
        assertThrows(IOException::class.java) {store.publishClear(clear) {throw IOException("cancelled clear")}}
        assertTrue(access.observeAbsent() is LegacyAbsentResult.Granted)
        val abandoned=capture(store);store.retire(abandoned);store.retire(abandoned)
        assertFalse(store.isCurrent(abandoned));assertTrue(access.observeAbsent() is LegacyAbsentResult.Granted)
        val older=capture(store);val newer=capture(store);store.retire(older)
        assertTrue(store.isCurrent(newer));store.retire(newer)
        assertTrue(access.observeAbsent() is LegacyAbsentResult.Granted)
    }
}
