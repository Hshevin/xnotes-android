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
    private fun originalTarget(uuid:String=id())=MapSourceTarget(uuid,SourceScope.PDF_ORIGINAL,id(),
        originalPdf=PdfOriginalProjection("a".repeat(64),0))
    @Test fun mixedOriginalAndNativeCardsPreserveExactTargetsAndUseV6WithoutBecomingConceptSources() {
        val original=originalTarget();val native=id()
        val result=MindMapDocument().addFileCards(listOf(MapFileCardSeed(original.documentId,"Original",20.0,30.0,original),
            MapFileCardSeed(native,"Native",300.0,30.0)))
        assertEquals(original,result.document.nodes.single {it.fileCardId==original.documentId}.sources.single().target)
        assertEquals(MapSourceTarget(native,SourceScope.FILE),result.document.nodes.single {it.fileCardId==native}.sources.single().target)
        val encoded=MapCodec.encode(result.document)
        assertTrue(String(encoded).contains("\"version\":6"));assertEquals(result.document,MapCodec.decode(encoded))
        val card=result.document.nodes.single {it.fileCardId==original.documentId}
        val reused=result.document.addFileCards(listOf(MapFileCardSeed(original.documentId,"Changed cached name",0.0,0.0,original)))
        assertSame(result.document,reused.document);assertSame(card,reused.document.node(card.id))
    }
    @Test fun oneUuidWithDifferentExactTargetsIsAConflictAcrossTheInputBatchAndExistingCards() {
        val target=originalTarget();val native=MapFileCardSeed(target.documentId,"Wrong FILE",0.0,0.0)
        val original=MapFileCardSeed(target.documentId,"Original",0.0,0.0,target)
        assertThrows(IllegalArgumentException::class.java) {MindMapDocument().addFileCards(listOf(native,original))}
        val existing=MindMapDocument().addFileCards(listOf(native)).document
        assertThrows(IllegalArgumentException::class.java) {existing.addFileCards(listOf(original))}
        assertEquals(SourceScope.FILE,existing.nodes.single {it.fileCardId!=null}.sources.single().scope)
    }
    @Test fun originalCardsAcceptOnlyTheCanonicalWholeOriginalFirstPageAnchor() {
        val target=originalTarget()
        val wrong=listOf(target.copy(originalPdf=target.originalPdf!!.copy(pageIndex=1)),
            target.copy(originalPdf=target.originalPdf!!.copy(viewTurns=1)),
            target.copy(originalPdf=target.originalPdf!!.copy(userRegion=com.xnotes.core.geometry.Rect(0.0,0.0,20.0,30.0))),
            MapSourceTarget(target.documentId,SourceScope.PAGE,target.pageId))
        for(value in wrong) assertThrows(IllegalArgumentException::class.java) {MapFileCardSeed(value.documentId,"No",0.0,0.0,value)}
        val card=MapNode(parent=null,free=true,kind=NodeKind.REFERENCE,fileCardId=target.documentId,
            sources=listOf(MapSource(documentId=target.documentId,scope=SourceScope.PDF_ORIGINAL,pageId=target.pageId,
                originalPdf=target.originalPdf!!.copy(pageIndex=1))))
        assertThrows(IllegalArgumentException::class.java) {MindMapDocument(nodes=listOf(card)).validate()}
    }

}
