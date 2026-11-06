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
    @Test fun nodeScopedInkCommitPreservesConcurrentMetadataAndRejectsStaleContent() {
        val context=RuntimeEnvironment.getApplication()
        val file=File.createTempFile("ink-session-",".xmap",context.cacheDir)
        file.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,kind=NodeKind.INK)))))
        val s=MapSession();s.open(context,Uri.fromFile(file));await {!s.loading}
        val expected=s.document!!.root.ink
        val source=MapSource(documentId="00000000-0000-0000-0000-000000000001")
        s.operate {it.update("r") {n->n.copy(sources=listOf(source))}}
        val stroke=MapInk(listOf(InkPoint(-20.0,300.0,.8,10.0)))
        assertTrue(s.commitInk("r",expected,listOf(stroke)))
        assertEquals(listOf(source),s.document!!.root.sources)
        assertFalse(s.commitInk("r",expected,emptyList()))
        val saved=s.document
        s.undo();assertEquals(expected,s.document!!.root.ink)
        assertEquals(listOf(source),s.document!!.root.sources)
        s.redo();assertEquals(saved,s.document)
        s.save();await {!s.saving}
        assertEquals(saved,MapCodec.decode(file.readBytes()))
    }

    @Test fun stalePropertyDraftCannotOverwriteNewSourcesOrInk() {
        val context=RuntimeEnvironment.getApplication()
        val node=MapNode(id="r",parent=null,kind=NodeKind.INK,x=300.0,y=150.0)
        val file=File.createTempFile("property-session-",".xmap",context.cacheDir).apply {writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(node))))}
        val s=MapSession();s.open(context,Uri.fromFile(file));await {!s.loading}
        assertTrue(s.editNodeProperties("r",node));assertFalse(s.canUndo)
        val source=MapSource(documentId="00000000-0000-0000-0000-000000000001")
        val stroke=MapInk(listOf(InkPoint(10.0,20.0,.8,100.0)))
        s.operate {it.update("r") {n->n.copy(sources=listOf(source),ink=listOf(stroke))}}
        assertTrue(s.editNodeProperties("r",node.copy(text="Renamed",width=500.0)))
        val edited=s.document!!.root
        assertEquals(listOf(source),edited.sources);assertEquals(listOf(stroke),edited.ink)
        assertEquals(node.x,edited.x,0.0);assertEquals(node.y,edited.y,0.0)
        s.undo();assertEquals("",s.document!!.root.text);assertEquals(listOf(source),s.document!!.root.sources)
    }

}
