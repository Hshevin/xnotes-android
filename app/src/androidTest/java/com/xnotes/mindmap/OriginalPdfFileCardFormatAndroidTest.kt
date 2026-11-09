package com.xnotes.mindmap

import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Android JSON semantics for the original card's permanent initial page. Compiled, not device accepted. */
class OriginalPdfFileCardFormatAndroidTest {
    private fun target()=MapSourceTarget(UUID.randomUUID().toString(),SourceScope.PDF_ORIGINAL,
        pageId=UUID.randomUUID().toString(),originalPdf=PdfOriginalProjection("a".repeat(64),0))
    @Test fun mixedNativeAndOriginalFileCardsKeepExactTargetsAndUseVersionSix() {
        val original=target();val note=UUID.randomUUID().toString()
        val doc=MindMapDocument(nodes=emptyList(),organization=MapOrganization.RELATION_CANVAS).addFileCards(listOf(
            MapFileCardSeed(note,"Annotated note",0.0,0.0),MapFileCardSeed(original.documentId,"Original",300.0,0.0,original))).document
        val bytes=MapCodec.encode(doc);val json=JSONObject(String(bytes))
        assertEquals(6,json.getInt("version"));assertEquals(doc,MapCodec.decode(bytes))
        val source=json.getJSONArray("nodes").getJSONObject(1).getJSONArray("sources").getJSONObject(0)
        assertSame(JSONObject.NULL,source.get("region"))
        assertSame(JSONObject.NULL,source.getJSONObject("originalPdf").get("userRegion"))
        assertEquals(original,doc.nodes.single {it.fileCardId==original.documentId}.sources.single().target)
        assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(json.put("version",5).toString().toByteArray())}
    }
    @Test fun anOriginalFileCardCannotSilentlyBecomeAnArbitraryPageOrRegion() {
        val initial=target()
        for(target in listOf(initial.copy(originalPdf=initial.originalPdf!!.copy(pageIndex=1)),
            initial.copy(originalPdf=initial.originalPdf!!.copy(viewTurns=1)),
            initial.copy(originalPdf=initial.originalPdf!!.copy(userRegion=com.xnotes.core.geometry.Rect(0.0,0.0,10.0,10.0))))) {
            assertThrows(IllegalArgumentException::class.java) {
                MindMapDocument(nodes=emptyList(),organization=MapOrganization.RELATION_CANVAS)
                    .addFileCards(listOf(MapFileCardSeed(target.documentId,"Original",0.0,0.0,target)))
            }
        }
    }
}
