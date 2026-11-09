package com.xnotes.mindmap

import org.junit.Assert.*
import org.junit.Test

class ConnectedNodeTest {
    private fun graph()=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Source")))
    @Test fun everyBodyKindCreatesOneFreeNodeAndUndirectedEdgeAndUndoesTogether() {
        for(kind in NodeKind.entries) {
            val before=graph();val source=MapSource(documentId="00000000-0000-0000-0000-000000000001",fileName="Original")
            val node=MapNode(id="new",parent=null,free=true,kind=kind,text="Title",x=-123.0,y=456.0,
                ink=if(kind==NodeKind.INK) listOf(MapInk(listOf(InkPoint(-10.0,230.0,.4,10.0)))) else emptyList(),
                sources=if(kind==NodeKind.REFERENCE) listOf(source) else emptyList())
            val result=before.connectNewNode("root",node) as ConnectedNodeResult.Applied
            val history=MapHistory(before);history.commit(result.document)
            assertEquals(node,history.document.node("new"));assertEquals("root",history.document.relations.single().from)
            assertEquals("new",history.document.relations.single().to);assertFalse(history.document.relations.single().directed)
            assertEquals(result.document,MapCodec.decode(MapCodec.encode(result.document)))
            history.undo();assertEquals(before,history.document);assertFalse(history.canUndo)
            history.redo();assertEquals(result.document,history.document)
        }
    }
    @Test fun missingSourceDuplicateIdsInvalidNodeAndBothCapacityLimitsLeaveTheGraphUnchanged() {
        val doc=graph();val node=MapNode(id="new",parent=null,free=true)
        val before=MapCodec.encode(doc)
        assertEquals(ConnectedNodeResult.Conflict,doc.connectNewNode("missing",node))
        assertEquals(ConnectedNodeResult.Invalid,doc.connectNewNode("root",node.copy(id="root")))
        assertEquals(ConnectedNodeResult.Invalid,doc.connectNewNode("root",node.copy(parent="root")))
        assertEquals(ConnectedNodeResult.Invalid,doc.connectNewNode("root",node.copy(x=Double.NaN)))
        val full=doc.copy(nodes=doc.nodes+List(MindMapDocument.MAX_NODES-1) {MapNode(id="n$it",parent="root")})
        assertEquals(ConnectedNodeResult.Invalid,full.connectNewNode("root",node))
        val a=List(100) {MapNode(id="n$it",parent="root")}
        val edges=(0 until 100).flatMap {from->(from+1 until 100).map {to->MapRelation(from="n$from",to="n$to")}}.take(4000)
        val many=doc.copy(nodes=doc.nodes+a,relations=edges).also {it.validate()}
        assertEquals(ConnectedNodeResult.Invalid,many.connectNewNode("root",node))
        val sources=doc.copy(nodes=doc.nodes+List(40) {i->MapNode(id="s$i",parent="root",sources=List(125) {j->
            MapSource(id="source-$i-$j",documentId="00000000-0000-0000-0000-000000000001")
        })}).also {it.validate()}
        assertEquals(ConnectedNodeResult.Invalid,sources.connectNewNode("root",node.copy(sources=listOf(
            MapSource(id="extra",documentId="00000000-0000-0000-0000-000000000001")))))
        assertEquals(ConnectedNodeResult.Invalid,sources.connectNewNode("root",node.copy(sources=sources.nodes.last().sources.take(1))))
        assertArrayEquals(before,MapCodec.encode(doc))
    }
}
