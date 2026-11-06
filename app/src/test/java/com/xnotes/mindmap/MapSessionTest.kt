package com.xnotes.mindmap

import android.net.Uri
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MapSessionTest {
    private fun await(condition: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for serialized file IO")
    }
    @Test fun anOlderRecoveryCopyDoesNotAuthorizeClosingNewUnsavedEdits() {
        val context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("session-", ".xmap", context.cacheDir)
        file.writeBytes(MapCodec.encode(MindMapDocument()))
        val session = MapSession()
        session.open(context, Uri.fromFile(file))
        await { !session.loading }
        assertFalse(session.loadFailed)
        session.save()
        await { !session.saving }
        assertFalse(session.saveFailed)
        assertTrue(session.recoverySafe)
        session.operate { it.update(it.root.id) { n -> n.copy(text = "new edit") } }
        assertFalse(session.recoverySafe)
        session.save()
        await { !session.saving }
        assertTrue(session.recoverySafe)
        assertEquals("new edit", MapCodec.decode(file.readBytes()).root.text)
    }
}
