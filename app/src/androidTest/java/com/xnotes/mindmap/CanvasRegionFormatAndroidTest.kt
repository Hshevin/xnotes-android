package com.xnotes.mindmap

import com.xnotes.core.geometry.Rect
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Android JSON null/type semantics; compilation alone is not platform acceptance. */
class CanvasRegionFormatAndroidTest {
    private fun graph()=MindMapDocument(nodes=listOf(MapNode(parent=null,sources=listOf(MapSource(
        documentId=UUID.randomUUID().toString(),scope=SourceScope.CANVAS_REGION,region=Rect(-20.0,-30.0,40.0,50.0))))))
    private fun source(j:JSONObject)=j.getJSONArray("nodes").getJSONObject(0).getJSONArray("sources").getJSONObject(0)
    @Test fun canvasRegionUsesExplicitNullPageAndVersionFiveOnAndroid() {
        val doc=graph();val bytes=MapCodec.encode(doc);val json=JSONObject(String(bytes))
        assertEquals(5,json.getInt("version"));assertSame(JSONObject.NULL,source(json).get("pageId"))
        assertEquals(doc,MapCodec.decode(bytes))
        for(version in 1..4) {
            val old=JSONObject(String(bytes)).put("version",version)
            assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(old.toString().toByteArray())}
            source(old).put("scope","FILE").put("region",JSONObject.NULL).put("lastPreview",JSONObject().put("target",JSONObject().put("scope","CANVAS_REGION")))
            assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(old.toString().toByteArray())}
        }
    }
    @Test fun regionNumbersAndScopeFieldsAreNotCoercedFromStringsOrMissingValues() {
        for(change in listOf<(JSONObject)->Unit>(
            {it.put("region",JSONObject.NULL)}, {it.put("pageId",UUID.randomUUID().toString())},
            {it.getJSONArray("region").put(0,"-20")}, {it.getJSONArray("region").put(2,0)}, {it.put("lastKnownPage",1)})) {
            val json=JSONObject(String(MapCodec.encode(graph())));change(source(json))
            assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(json.toString().toByteArray())}
        }
    }
}
