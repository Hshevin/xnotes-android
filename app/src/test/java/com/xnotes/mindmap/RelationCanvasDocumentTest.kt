package com.xnotes.mindmap

import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class RelationCanvasDocumentTest {
    private fun id()=UUID.randomUUID().toString()
    private fun canvas(nodes:List<MapNode> = emptyList())=MindMapDocument(nodes=nodes,organization=MapOrganization.RELATION_CANVAS)
    private fun card(file:String=id())=MapNode(parent=null,free=true,kind=NodeKind.REFERENCE,text="My title",
        fileCardId=file,sources=listOf(MapSource(documentId=file,fileName="Original.xnote")))

    @Test fun aRelationCanvasCanBeTrulyEmptyAndTheLastNodeCanBeDeletedAndUndone() {
        val empty=canvas();empty.validate();assertNull(empty.centralRoot);assertNull(empty.initialSelection)
        val history=MapHistory(empty);val added=empty.addFree(NodeKind.TEXT,"Topic",41.0,87.0)
        history.commit(added.first);history.commit(history.document.deleteNodes(setOf(added.second)))
        assertTrue(history.document.nodes.isEmpty());assertEquals(empty,MapCodec.decode(MapCodec.encode(empty)))
        history.undo();assertEquals(added.first,history.document);history.redo();assertTrue(history.document.nodes.isEmpty())
        val bounds=MapPainter().drawingBounds(empty);assertTrue(bounds.w>0 && bounds.h>0)
    }
    @Test fun emptyPermissionDoesNotRemoveTheProtectedMindMapRootOrAllowACycle() {
        assertThrows(IllegalArgumentException::class.java) {MindMapDocument(nodes=emptyList()).validate()}
        val map=MindMapDocument();assertEquals(map,map.deleteNodes(setOf(map.root.id)))
        assertThrows(IllegalArgumentException::class.java) {canvas(listOf(MapNode(id="a",parent="b"),MapNode(id="b",parent="a"))).validate()}
        assertThrows(IllegalArgumentException::class.java) {canvas(listOf(MapNode(parent=null))).validate()}
    }
    @Test fun automaticBranchLayoutPreservesEveryFreeRootAndOrdinaryRelationsDoNotCreateParents() {
        val first=card().copy(id="a",x=513.0,y=-217.0)
        val second=card().copy(id="b",x=-83.0,y=129.0)
        val child=MapNode(id="c",parent="a",text="Child")
        val initial=canvas(listOf(first,second,child)).connect("a","b",RelationKind.DEPENDS_ON).first
        val laid=MapLayout.arrange(initial)
        assertEquals(first.bounds,laid.node("a").bounds);assertEquals(second.bounds,laid.node("b").bounds)
        assertTrue(laid.node("c").x>first.bounds.right);assertNull(laid.node("b").parent)
        assertEquals(laid,laid.reparent("a","c"));assertNull(laid.centralRoot)
    }
    @Test fun onlyDocumentsUsingTheNewSemanticsRequireVersionFourAndLegacyDocumentsStillRead() {
        val map=MindMapDocument(nodes=listOf(MapNode(parent=null,text="Legacy")))
        val old=JSONObject(String(MapCodec.encode(map)));assertEquals(3,old.getInt("version"))
        for(version in 1..3) assertEquals(map,MapCodec.decode(old.put("version",version).toString().toByteArray()))
        assertEquals(4,JSONObject(String(MapCodec.encode(canvas()))).getInt("version"))
        val withCard=map.copy(nodes=map.nodes+card());assertEquals(4,JSONObject(String(MapCodec.encode(withCard))).getInt("version"))
        assertEquals(withCard,MapCodec.decode(MapCodec.encode(withCard)))
        assertTrue(MapCodec.semanticPreviewVersion(map).startsWith("xmap-render-v1:"))
        assertTrue(MapCodec.semanticPreviewVersion(withCard).startsWith("xmap-render-v2:"))
    }
    @Test fun versionFourStrictlyChecksRoleAndNullableIdentityInsteadOfCoercingValues() {
        val json=JSONObject(String(MapCodec.encode(canvas(listOf(card())))))
        val raw=json.toString()
        assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(raw.replace("\"version\":4","\"version\":4.0").toByteArray())}
        json.getJSONArray("nodes").getJSONObject(0).put("fileCardId",JSONObject.NULL)
        assertNull(MapCodec.decode(json.toString().toByteArray()).nodes.single().fileCardId)
        json.getJSONArray("nodes").getJSONObject(0).put("fileCardId","null")
        assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(json.toString().toByteArray())}
        json.getJSONArray("nodes").getJSONObject(0).put("fileCardId",JSONObject.NULL)
        assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(json.put("organization","unknown").toString().toByteArray())}
    }
    @Test fun fileCardIdentityIsExplicitUniqueAndIndependentOfTheDisplayTitle() {
        val a=card();val changed=a.copy(text="Renamed display")
        canvas(listOf(changed)).validate();assertEquals(a.sources,changed.sources)
        assertThrows(IllegalArgumentException::class.java) {canvas(listOf(a,a.copy(id=id(),sources=a.sources.map {it.copy(id=id())}))).validate()}
        assertThrows(IllegalArgumentException::class.java) {canvas(listOf(a.copy(kind=NodeKind.TEXT))).validate()}
        assertThrows(IllegalArgumentException::class.java) {canvas(listOf(a.copy(sources=emptyList()))).validate()}
        assertThrows(IllegalArgumentException::class.java) {canvas(listOf(a.copy(fileCardId=id()))).validate()}
        assertNull(a.copy(fileCardId=null).fileCardId) // Ordinary single-source nodes are not file cards.
    }
}
