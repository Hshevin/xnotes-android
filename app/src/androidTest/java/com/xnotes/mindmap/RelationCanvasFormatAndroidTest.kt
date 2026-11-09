package com.xnotes.mindmap

import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RelationCanvasFormatAndroidTest {
    @Test fun nullableFileRolesAndEmptyCanvasUseRealAndroidJsonTypes() {
        val canvas=MindMapDocument(nodes=emptyList(),organization=MapOrganization.RELATION_CANVAS)
        assertEquals(canvas,MapCodec.decode(MapCodec.encode(canvas)))
        val withNode=canvas.addFree(NodeKind.TEXT,"Concept",0.0,0.0).first
        val json=JSONObject(String(MapCodec.encode(withNode)))
        assertSame(JSONObject.NULL,json.getJSONArray("nodes").getJSONObject(0).get("fileCardId"))
        assertEquals(withNode,MapCodec.decode(json.toString().toByteArray()))
        val raw=json.toString()
        for(version in listOf("4.0","4e0","true","\"4\"")) {
            val invalid=raw.replace("\"version\":4","\"version\":$version")
            assertNotEquals(raw,invalid)
            assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(invalid.toByteArray())}
        }
    }
    @Test fun explicitFileRoleCannotBeGuessedOrCoercedFromAnOrdinaryReference() {
        val file=UUID.randomUUID().toString()
        val node=MapNode(parent=null,free=true,kind=NodeKind.REFERENCE,fileCardId=file,sources=listOf(MapSource(documentId=file)))
        val doc=MindMapDocument(nodes=listOf(node),organization=MapOrganization.RELATION_CANVAS)
        assertEquals(doc,MapCodec.decode(MapCodec.encode(doc)))
        val json=JSONObject(String(MapCodec.encode(doc)));val data=json.getJSONArray("nodes").getJSONObject(0)
        data.put("fileCardId",JSONObject.NULL)
        assertNull(MapCodec.decode(json.toString().toByteArray()).nodes.single().fileCardId)
        for(value in listOf("null",32,true)) {
            data.put("fileCardId",value)
            assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(json.toString().toByteArray())}
        }
    }
}
