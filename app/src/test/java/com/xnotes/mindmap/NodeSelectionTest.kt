package com.xnotes.mindmap

import org.junit.Assert.*
import org.junit.Test

class NodeSelectionTest {
    private val ink=listOf(MapInk(listOf(InkPoint(-12.0,201.0,.6,19.0))))
    private val sources=listOf(MapSource(documentId="00000000-0000-0000-0000-000000000001"))
    private val freeSources=listOf(MapSource(documentId="00000000-0000-0000-0000-000000000001"))
    private fun graph()=MindMapDocument(nodes=listOf(
        MapNode(id="r",parent=null,x=-500.0,y=-300.0),
        MapNode(id="a",parent="r",x=-100.0,y=50.0,kind=NodeKind.INK,ink=ink,sources=sources),
        MapNode(id="b",parent="a",x=250.0,y=60.0,collapsed=true),
        MapNode(id="hidden",parent="b",x=600.0,y=70.0),
        MapNode(id="f",parent=null,free=true,kind=NodeKind.INK,ink=ink,sources=freeSources,x=900.0,y=100.0,width=200.0,height=100.0),
        MapNode(id="fc",parent="f",x=1200.0,y=200.0),
        MapNode(id="g",parent=null,free=true,x=1400.0,y=400.0,width=100.0,height=200.0)),
        relations=listOf(MapRelation(id="ab",from="a",to="b"),MapRelation(id="fg",from="f",to="g")))

    @Test fun translatingAncestorAndDescendantMovesTheirUnionOnceIncludingHiddenMembers() {
        val doc=graph();val next=doc.translateNodes(setOf("a","b"),30.0,-40.0)
        for(id in listOf("a","b","hidden")) {
            assertEquals(doc.node(id).x+30,next.node(id).x,0.0)
            assertEquals(doc.node(id).y-40,next.node(id).y,0.0)
            assertEquals(doc.node(id).parent,next.node(id).parent)
        }
        assertSame(doc.node("r"),next.node("r"));assertSame(doc.node("f"),next.node("f"))
        assertSame(ink,next.node("a").ink);assertSame(sources,next.node("a").sources)
        assertSame(doc.relations,next.relations)
    }
    @Test fun rootGroupMovementDoesNotMoveUnrelatedFreeTopicsAndRemainsOneHistoryEntry() {
        val doc=graph();val h=MapHistory(doc)
        h.commit(doc.translateNodes(setOf("r","a","f"),20.0,30.0))
        assertEquals(doc.node("hidden").x+20,h.document.node("hidden").x,0.0)
        assertEquals(doc.node("fc").x+20,h.document.node("fc").x,0.0)
        assertSame(doc.node("g"),h.document.node("g"))
        val moved=h.document;h.undo();assertSame(doc,h.document);assertFalse(h.canUndo)
        h.redo();assertSame(moved,h.document)
    }
    @Test fun rootIsExcludedBeforeDeletionUnionAndOnlyConnectedOrdinaryLinesAreRemoved() {
        val doc=graph();val next=doc.deleteNodes(setOf("r","a","b"))
        assertEquals(listOf("r","f","fc","g"),next.nodes.map {it.id})
        assertEquals(listOf("fg"),next.relations.map {it.id})
        assertSame(doc.root,next.root);assertSame(doc.node("f"),next.node("f"))
        assertSame(doc,doc.deleteNodes(setOf("r")))
        val h=MapHistory(doc);h.commit(next);h.undo();assertSame(doc,h.document)
    }
    @Test fun allSixAlignmentsUseTopicBoundsAndMoveEachWholeFreeBranch() {
        val doc=graph()
        for(alignment in MapAlignment.entries) {
            val next=doc.alignNodes(setOf("f","g"),alignment)
            val f=next.node("f");val g=next.node("g")
            when(alignment) {
                MapAlignment.LEFT->assertEquals(900.0,g.x,0.0)
                MapAlignment.CENTER_X->{assertEquals(1200.0,f.bounds.centerX,0.0);assertEquals(1200.0,g.bounds.centerX,0.0)}
                MapAlignment.RIGHT->assertEquals(1500.0,f.bounds.right,0.0)
                MapAlignment.TOP->assertEquals(100.0,g.y,0.0)
                MapAlignment.CENTER_Y->{assertEquals(350.0,f.bounds.centerY,0.0);assertEquals(350.0,g.bounds.centerY,0.0)}
                MapAlignment.BOTTOM->assertEquals(600.0,f.bounds.bottom,0.0)
            }
            assertEquals(f.x-doc.node("f").x,next.node("fc").x-doc.node("fc").x,0.0)
            assertEquals(f.y-doc.node("f").y,next.node("fc").y-doc.node("fc").y,0.0)
            assertSame(doc.node("a"),next.node("a"));assertSame(doc.relations,next.relations)
            assertSame(ink,f.ink);assertSame(freeSources,f.sources)
            next.validate()
        }
    }
    @Test fun alignmentRejectsTheEntireMixedSelectionAndLeavesFreeTopicsUnchanged() {
        val doc=graph()
        for(ids in listOf(setOf("f"),setOf("r","f"),setOf("f","g","a"),setOf("missing","f"))) {
            assertFalse(doc.canAlignNodes(ids))
            assertThrows(IllegalArgumentException::class.java) {doc.alignNodes(ids,MapAlignment.LEFT)}
        }
        assertTrue(doc.canAlignNodes(setOf("f","g")))
    }
    @Test fun zeroMovementAndAlreadyAlignedTopicsHaveNoContentHistoryEntry() {
        val doc=graph();val h=MapHistory(doc)
        assertSame(doc,doc.translateNodes(setOf("a"),0.0,0.0))
        h.commit(doc.translateNodes(emptySet(),10.0,15.0));assertFalse(h.canUndo)
        val aligned=doc.alignNodes(setOf("f","g"),MapAlignment.LEFT);val history=MapHistory(aligned)
        history.commit(aligned.alignNodes(setOf("f","g"),MapAlignment.LEFT));assertFalse(history.canUndo)
    }
    @Test fun invalidCoordinatesOrUnknownSelectionAreRejectedAsWholeOperations() {
        val doc=graph()
        for(delta in listOf(Double.NaN,Double.POSITIVE_INFINITY,MindMapDocument.MAX_COORD)) {
            assertThrows(IllegalArgumentException::class.java) {doc.translateNodes(setOf("a","f"),delta,0.0)}
        }
        assertThrows(IllegalArgumentException::class.java) {doc.translateNodes(setOf("missing"),1.0,1.0)}
        assertThrows(IllegalArgumentException::class.java) {doc.deleteNodes(setOf("missing"))}
        assertSame(ink,doc.node("a").ink)
    }
}
