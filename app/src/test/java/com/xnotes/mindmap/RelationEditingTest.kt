package com.xnotes.mindmap

import org.junit.Assert.*
import org.junit.Test

class RelationEditingTest {
    private val initial=MapRelation(id="edge",from="a",to="b",kind=RelationKind.REFERENCED_FROM,label="old")
    private fun graph()=MindMapDocument(nodes=listOf(
        MapNode(id="a",parent=null,text="A"),MapNode(id="b",parent="a",text="B"),
        MapNode(id="c",parent=null,free=true,kind=NodeKind.INK,ink=listOf(MapInk(listOf(InkPoint(10.0,20.0))))),
    ),relations=listOf(initial))
    private fun applied(result:RelationEditResult)= (result as RelationEditResult.Applied).document
    @Test fun endpointTypeDirectionAndDescriptionAreOneUndoStepWithAllNodeContentsIntact() {
        val before=graph();val history=MapHistory(before)
        val proposed=initial.copy(from="c",to="a",kind=RelationKind.DEPENDS_ON,label="updated")
        val next=applied(before.changeRelation(initial,proposed));history.commit(next)
        assertEquals(proposed,history.document.relations.single());assertEquals(before.nodes,next.nodes)
        assertEquals(next,MapCodec.decode(MapCodec.encode(next)))
        history.undo();assertEquals(before,history.document);assertFalse(history.canUndo)
        history.redo();assertEquals(next,history.document)
    }
    @Test fun editingIntoADuplicateKeepsBothEdgesAndTheExistingDescription() {
        val other=MapRelation(id="other",from="a",to="c",kind=RelationKind.DEPENDS_ON,label="keep me")
        val before=graph().copy(relations=listOf(initial,other))
        val duplicate=before.changeRelation(initial,initial.copy(from="a",to="c",kind=other.kind,label="must not leak"))
        assertEquals(RelationEditResult.Duplicate(other),duplicate)
        assertEquals(listOf(initial,other),before.relations)
        val creation=before.changeRelation(null,other.copy(id="new"))
        assertEquals(RelationEditResult.Duplicate(other),creation)
    }
    @Test fun undirectedEndpointSwapIsANoopWhileAChangedLabelStillCommits() {
        val edge=initial.copy(kind=RelationKind.RELATED);val before=graph().copy(relations=listOf(edge));val history=MapHistory(before)
        history.commit(applied(before.changeRelation(edge,edge.copy(from="b",to="a"))))
        assertEquals(before,history.document);assertFalse(history.canUndo)
        val changed=applied(before.changeRelation(edge,edge.copy(from="b",to="a",label="new")))
        assertEquals(edge.copy(label="new"),changed.relations.single())
        history.commit(changed);history.undo();assertEquals(before,history.document)
    }
    @Test fun unrelatedLatestChangesAreKeptAndAnEditedOrDeletedInitialEdgeCannotBeOverwritten() {
        val before=graph();val latest=before.update("b") {it.copy(text="later title")}
        val next=applied(latest.changeRelation(initial,initial.copy(to="c")))
        assertEquals("later title",next.node("b").text)
        assertEquals(RelationEditResult.Conflict,next.changeRelation(initial,initial.copy(label="late")))
        assertEquals(RelationEditResult.Conflict,next.changeRelation(initial,null))
        assertEquals(RelationEditResult.Conflict,before.copy(relations=emptyList()).changeRelation(initial,initial.copy(label="late")))
    }
    @Test fun missingEndpointsSelfLoopsChangedIdsAndTooLongLabelsNeverAlterTheDocument() {
        val before=graph()
        for(proposed in listOf(initial.copy(to="absent"),initial.copy(to="a"),initial.copy(id="other"),initial.copy(label="x".repeat(20001)))) {
            assertEquals(RelationEditResult.Invalid,before.changeRelation(initial,proposed))
        }
        val noB=before.delete("b")
        assertEquals(RelationEditResult.Conflict,noB.changeRelation(initial,initial.copy(to="c")))
        assertEquals(listOf(initial),before.relations)
    }
    @Test fun deletingAnOrdinaryRelationPreservesNodesAndHierarchyAndCanBeUndone() {
        val before=graph();val history=MapHistory(before);history.commit(applied(before.changeRelation(initial,null)))
        assertTrue(history.document.relations.isEmpty());assertEquals(before.nodes,history.document.nodes)
        history.undo();assertEquals(before,history.document)
    }
    @Test fun aFullRelationSetAllowsDuplicateLocationAndEditsButRejectsANewUniqueEdge() {
        val nodes=(0 until 100).map {MapNode(id="n$it",parent=null,free=it!=0)}
        val edges=nodes.flatMap {a->nodes.filter {it.id!=a.id}.map {b->MapRelation(id="${a.id}-${b.id}",from=a.id,to=b.id,kind=RelationKind.DEPENDS_ON)}}.take(4000)
        val full=MindMapDocument(nodes=nodes,relations=edges);full.validate()
        assertEquals(RelationEditResult.Duplicate(edges.first()),full.changeRelation(null,edges.first().copy(id="new")))
        val changed=applied(full.changeRelation(edges.first(),edges.first().copy(label="edited at capacity")))
        assertEquals(4000,changed.relations.size);assertEquals("edited at capacity",changed.relations.first().label)
        assertEquals(RelationEditResult.Invalid,full.changeRelation(null,MapRelation(from="n99",to="n98")))
    }
}
