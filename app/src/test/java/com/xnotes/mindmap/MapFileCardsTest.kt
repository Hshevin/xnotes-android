package com.xnotes.mindmap

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class MapFileCardsTest {
    private fun id()=UUID.randomUUID().toString()
    @Test fun aBatchCreatesExplicitFileCardsWithoutChangingTheOriginalConceptOrPositions() {
        val same=id();val ordinary=MapNode(id="r",parent=null,text="Unchanged",x=41.0,y=72.0,
            sources=listOf(MapSource(documentId=same)))
        val doc=MindMapDocument(nodes=listOf(ordinary))
        val seeds=listOf(MapFileCardSeed(same,"First",450.0,80.0),MapFileCardSeed(id(),"Second",950.0,130.0))
        val result=doc.addFileCards(seeds)
        assertSame(ordinary,result.document.node("r"));assertEquals(2,result.nodeIds.size);assertEquals(result.nodeIds,result.addedIds)
        val cards=result.document.nodes.filter {it.fileCardId!=null}
        assertEquals(seeds.map {it.documentId},cards.map {it.fileCardId});assertEquals(seeds.map {it.x to it.y},cards.map {it.x to it.y})
        assertTrue(cards.all {it.kind==NodeKind.REFERENCE && it.free && it.parent==null && it.sources.size==1})
        assertTrue(cards.all {it.sources.single().scope==SourceScope.FILE && it.sources.single().documentId==it.fileCardId && it.sources.single().status==SourceStatus.UNVERIFIED})
        assertEquals(2,cards.map {it.id}.toSet().size);assertEquals(2,cards.map {it.sources.single().id}.toSet().size)
        assertEquals(result.document,MapCodec.decode(MapCodec.encode(result.document)))
    }
    @Test fun duplicateFileSeedsReuseTheLatestCardBodyAndSourceWithoutMovingOrCreatingAnotherIdentity() {
        val uuid=id();val initial=MindMapDocument().addFileCards(listOf(MapFileCardSeed(uuid,"Old",4.0,8.0))).document
        val card=initial.nodes.single {it.fileCardId==uuid};val latest=initial.update(card.id) {it.copy(text="User title",x=940.0,
            sources=it.sources.map {s->s.copy(note="Keep remark",status=SourceStatus.CURRENT)})}
        val next=latest.addFileCards(listOf(MapFileCardSeed(uuid,"New cached name",0.0,0.0),MapFileCardSeed(uuid,"Duplicate",0.0,0.0)))
        assertSame(latest,next.document);assertEquals(setOf(card.id),next.nodeIds);assertTrue(next.addedIds.isEmpty())
        assertSame(latest.node(card.id),next.document.node(card.id))
    }
    @Test fun invalidSeedsAndAnOverBudgetBatchFailAsAWhole() {
        assertThrows(IllegalArgumentException::class.java) {MapFileCardSeed("bad","Bad",0.0,0.0)}
        assertThrows(IllegalArgumentException::class.java) {MapFileCardSeed(id(),"Bad",Double.NaN,0.0)}
        val root=MapNode(id="r",parent=null)
        val full=MindMapDocument(nodes=listOf(root)+(1 until MindMapDocument.MAX_NODES).map {MapNode(id="n$it",parent="r")})
        assertThrows(IllegalArgumentException::class.java) {full.addFileCards(listOf(MapFileCardSeed(id(),"No room",0.0,0.0)))}
        assertEquals(MindMapDocument.MAX_NODES,full.nodes.size);assertTrue(full.nodes.none {it.fileCardId!=null})
        assertThrows(IllegalArgumentException::class.java) {MindMapDocument().addFileCards(List(251) {MapFileCardSeed(id(),"Too many",0.0,0.0)})}
    }
}
