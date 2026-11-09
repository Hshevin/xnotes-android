package com.xnotes.mindmap

import org.junit.Assert.*
import org.junit.Test

class GraphModelTest {
    private fun doc() = MindMapDocument(nodes = listOf(MapNode(id="r",parent=null,text="Body"), MapNode(id="c",parent="r")))
    @Test fun freeNodesSurviveLayoutAndOrdinaryEdgesNeverChangeParents() {
        val d = doc().addFree(NodeKind.TEXT,"Free",900.0,600.0).first
        val free = d.nodes.last()
        val linked = d.connect("c",free.id,RelationKind.DEPENDS_ON).first
        assertEquals("r",linked.node("c").parent)
        assertNull(linked.node(free.id).parent)
        assertEquals(free,MapLayout.arrange(linked).node(free.id))
        assertEquals(3,linked.visible().size)
        assertEquals(linked,MapCodec.decode(MapCodec.encode(linked)))
    }
    @Test fun reverseUndirectedDuplicatesLocateExistingEdgeButDirectedEdgesRemainDistinct() {
        val (d,id) = doc().connect("r","c",RelationKind.RELATED)
        val (same,found) = d.connect("c","r",RelationKind.RELATED)
        assertEquals(id,found); assertEquals(d,same)
        assertEquals(3,same.connect("r","c",RelationKind.DEPENDS_ON).first.connect("c","r",RelationKind.DEPENDS_ON).first.relations.size)
    }
    @Test fun multiSourceEditsAndUndoPreserveBodyAndIndependentRanges() {
        val a=MapSource(documentId="00000000-0000-0000-0000-000000000001",fileName="Original",note="first")
        val b=a.copy(id="second",scope=SourceScope.PAGE,pageId="00000000-0000-0000-0000-000000000002",note="second")
        val d=doc().update("r") { it.copy(sources=listOf(a,b),primarySource=b.id) }
        val h=MapHistory(d)
        h.commit(d.update("r") { it.copy(sources=it.sources.reversed()) })
        assertEquals("Body",h.document.root.text)
        assertEquals(b.id,h.document.root.primarySource)
        assertEquals(h.document,MapCodec.decode(MapCodec.encode(h.document)))
        h.undo(); assertEquals(d,h.document)
    }
    @Test fun deletingBranchRemovesEdgesAndUndoRestoresAll() {
        val d=doc().connect("r","c",RelationKind.BELONGS_TO).first
        val h=MapHistory(d);h.commit(d.delete("c"));assertTrue(h.document.relations.isEmpty());h.undo();assertEquals(d,h.document)
    }
    @Test fun detachIsExplicitAndRejoiningCannotCreateCycle() {
        val d=doc().detach("c")
        assertTrue(d.node("c").free)
        assertEquals(d, d.reparent("c","c"))
        assertFalse(d.reparent("c","r").node("c").free)
    }
    @Test fun malformedSourceAndDanglingRelationsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { doc().update("r") { it.copy(sources=listOf(MapSource(documentId="name-only"))) }.validate() }
        assertThrows(IllegalArgumentException::class.java) { doc().copy(relations=listOf(MapRelation(from="r",to="missing"))).validate() }
        assertThrows(IllegalArgumentException::class.java) { doc().update("r") { it.copy(primarySource="missing") }.validate() }
    }
    @Test fun legacyVersionOneLoadsWithoutWritingOrLosingInk() {
        val original=doc().update("c") { it.copy(kind=NodeKind.INK,ink=listOf(MapInk(listOf(InkPoint(20.0,30.0,.8,12.0))))) }
        val legacy=org.json.JSONObject(String(MapCodec.encode(original))).put("version",1)
        legacy.remove("direction");legacy.remove("relations")
        val nodes=legacy.getJSONArray("nodes")
        for(i in 0 until nodes.length()) {val n=nodes.getJSONObject(i); n.remove("free");n.remove("sources");n.remove("primarySource")}
        val bytes=legacy.toString().toByteArray()
        val before=bytes.copyOf()
        assertEquals(original,MapCodec.decode(bytes));assertArrayEquals(before,bytes)
        assertEquals(3,org.json.JSONObject(String(MapCodec.encode(MapCodec.decode(bytes)))).getInt("version"))
    }
    @Test fun allDirectionsAreNonOverlappingAndDoNotMoveFreeBranches() {
        val original=doc().addFree(NodeKind.TEXT,"free",500.0,1000.0).first
        for(direction in MapDirection.entries) {
            val result=MapLayout.arrange(original.copy(direction=direction))
            assertEquals(original.nodes.last(),result.nodes.last())
            val r=result.root;val c=result.node("c")
            if(direction==MapDirection.DOWN) assertTrue(c.y>=r.y+r.height) else assertTrue(c.x>=r.x+r.width)
        }
    }

}
