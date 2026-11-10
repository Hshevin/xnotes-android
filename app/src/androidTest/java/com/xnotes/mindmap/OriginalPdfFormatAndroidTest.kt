package com.xnotes.mindmap

import com.xnotes.core.geometry.Rect
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Android org.json semantics. Source compilation is not a device/platform test result. */
class OriginalPdfFormatAndroidTest {
    private fun graph()=MindMapDocument(nodes=listOf(MapNode(parent=null,sources=listOf(MapSource(
        documentId=UUID.randomUUID().toString(),pageId=UUID.randomUUID().toString(),scope=SourceScope.PDF_ORIGINAL,
        originalPdf=PdfOriginalProjection("a".repeat(64),0))))))
    private fun source(j:JSONObject)=j.getJSONArray("nodes").getJSONObject(0).getJSONArray("sources").getJSONObject(0)
    @Test fun originalPageUsesExplicitNullUserRegionAndOrdinaryRegionWithVersionSix() {
        val graph=graph();val bytes=MapCodec.encode(graph);val json=JSONObject(String(bytes))
        assertEquals(6,json.getInt("version"))
        assertSame(JSONObject.NULL,source(json).get("region"))
        assertSame(JSONObject.NULL,source(json).getJSONObject("originalPdf").get("userRegion"))
        assertEquals(graph,MapCodec.decode(bytes))
        val region=graph.nodes.single().sources.single().copy(originalPdf=PdfOriginalProjection("a".repeat(64),0,userRegion=Rect(10.0,20.0,30.0,40.0)))
        val ranged=graph.copy(nodes=listOf(graph.nodes.single().copy(sources=listOf(region))))
        assertEquals(ranged,MapCodec.decode(MapCodec.encode(ranged)))
    }
    @Test fun legacyVersionsRejectOriginalScopeAndHistoricalProjectionBeforeTheyCanSkipSources() {
        for(version in 1..5) {
            val json=JSONObject(String(MapCodec.encode(graph()))).put("version",version)
            assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(json.toString().toByteArray())}
            val source=source(json)
            val projection=source.remove("originalPdf")
            source.put("scope","FILE").put("pageId",JSONObject.NULL)
            source.put("lastPreview",JSONObject().put("target",JSONObject().put("originalPdf",projection)))
            assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(json.toString().toByteArray())}
        }
    }
    @Test fun projectionRequiresAnActualTypedIndexTurnAndExplicitNullableFrame() {
        for(change in listOf<(JSONObject)->Unit>(
            {it.put("pageIndex","0")},{it.put("viewTurns",0.0)},{it.put("viewTurns",4)},
            {it.remove("userRegion")},{it.put("sha256",JSONObject.NULL)})) {
            val json=JSONObject(String(MapCodec.encode(graph())))
            change(source(json).getJSONObject("originalPdf"))
            assertThrows(Exception::class.java) {MapCodec.decode(json.toString().toByteArray())}
        }
    }
}
