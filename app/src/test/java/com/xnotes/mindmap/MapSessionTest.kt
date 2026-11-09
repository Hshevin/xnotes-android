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
    @Test fun relationSelectionIsRuntimeOnlyAndSurvivesItsOwnEditUndoAndRedo() {
        val context=RuntimeEnvironment.getApplication()
        val edge=MapRelation(id="edge",from="a",to="b",label="before")
        val doc=MindMapDocument(nodes=listOf(MapNode(id="a",parent=null),MapNode(id="b",parent="a")),relations=listOf(edge))
        val file=File.createTempFile("selected-relation-",".xmap",context.cacheDir).apply {writeBytes(MapCodec.encode(doc))}
        val session=MapSession();session.open(context,Uri.fromFile(file));await {!session.loading}
        assertTrue(session.selectRelation("edge"))
        assertNull(session.selected);assertEquals("edge",session.selectedRelation)
        assertFalse(session.canUndo);assertEquals(doc,session.document)
        assertTrue(session.changeRelation(edge,edge.copy(label="after")) is RelationEditResult.Applied)
        assertEquals("edge",session.selectedRelation);assertNull(session.selected)
        session.undo();assertEquals("edge",session.selectedRelation);assertEquals(doc,session.document)
        session.redo();assertEquals("edge",session.selectedRelation);assertEquals("after",session.document!!.relations.single().label)
        session.selected="a";assertNull(session.selectedRelation)
        assertFalse(session.selectRelation("missing"));assertEquals("a",session.selected)
    }
    @Test fun relationSelectionIsClearedWhenHiddenDeletedOrTheFileVersionIsAdopted() {
        val context=RuntimeEnvironment.getApplication()
        val edge=MapRelation(id="edge",from="a",to="b")
        val doc=MindMapDocument(nodes=listOf(MapNode(id="a",parent=null),MapNode(id="b",parent="a")),relations=listOf(edge))
        val file=File.createTempFile("retired-relation-",".xmap",context.cacheDir).apply {writeBytes(MapCodec.encode(doc))}
        val session=MapSession();session.open(context,Uri.fromFile(file));await {!session.loading}
        assertTrue(session.selectRelation("edge"))
        session.operate {it.update("a") {node->node.copy(collapsed=true)}}
        assertNull(session.selectedRelation)
        session.undo();assertTrue(session.selectRelation("edge"))
        val nodes=session.document!!.nodes
        assertTrue(session.changeRelation(session.document!!.relations.single(),null) is RelationEditResult.Applied)
        assertNull(session.selectedRelation);assertEquals(nodes,session.document!!.nodes)
        session.undo();assertTrue(session.selectRelation("edge"))
        session.useFileVersion();await {!session.replacingDocument}
        assertNull(session.selectedRelation)
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
    @Test fun relationConfirmationUsesTheLatestGraphAndIsOnePersistedUndoStep() {
        val context=RuntimeEnvironment.getApplication()
        val initial=MapRelation(id="e",from="a",to="b")
        val original=MindMapDocument(nodes=listOf(MapNode(id="a",parent=null),MapNode(id="b",parent="a"),MapNode(id="c",parent="a")),relations=listOf(initial))
        val file=File.createTempFile("relation-session-",".xmap",context.cacheDir).apply {writeBytes(MapCodec.encode(original))}
        val s=MapSession();s.open(context,Uri.fromFile(file));await {!s.loading}
        val source=MapSource(documentId="00000000-0000-0000-0000-000000000001")
        s.operate {it.update("b") {n->n.copy(text="later",sources=listOf(source))}}
        val before=s.document
        val proposed=initial.copy(from="c",to="a",kind=RelationKind.BELONGS_TO,label="one operation")
        assertTrue(s.changeRelation(initial,proposed) is RelationEditResult.Applied)
        assertEquals(before!!.nodes,s.document!!.nodes);assertEquals(proposed,s.document!!.relations.single())
        s.undo();assertEquals(before,s.document);s.redo();val saved=s.document
        assertEquals(RelationEditResult.Conflict,s.changeRelation(initial,initial.copy(label="stale")))
        assertEquals(saved,s.document)
        s.save();await {!s.saving};assertEquals(saved,MapCodec.decode(file.readBytes()))
    }
    @Test fun duplicateOrUndirectedSwapDoesNotAddHistoryAndPenContactRejectsConfirmation() {
        val context=RuntimeEnvironment.getApplication()
        val initial=MapRelation(id="e",from="a",to="b",label="existing")
        val original=MindMapDocument(nodes=listOf(MapNode(id="a",parent=null),MapNode(id="b",parent="a")),relations=listOf(initial))
        val file=File.createTempFile("relation-duplicate-",".xmap",context.cacheDir).apply {writeBytes(MapCodec.encode(original))}
        val s=MapSession();s.open(context,Uri.fromFile(file));await {!s.loading}
        assertEquals(RelationEditResult.Duplicate(initial),s.changeRelation(null,initial.copy(id="new",from="b",to="a",label="discard")))
        assertTrue(s.changeRelation(initial,initial.copy(from="b",to="a")) is RelationEditResult.Applied)
        assertFalse(s.canUndo);assertEquals(original,s.document)
        s.inkContact=true
        assertEquals(RelationEditResult.Conflict,s.changeRelation(initial,initial.copy(label="while writing")))
        assertEquals(RelationEditResult.Conflict,s.changeRelation(initial,null))
        s.inkContact=false;assertFalse(s.canUndo);assertEquals(original,s.document)
    }

}
