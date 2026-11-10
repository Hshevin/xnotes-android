package com.xnotes.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** v3 stores per-reader locks; v1/v2 remain readable without inventing a lock value. */
class LastPresentationZoomLockTest {
    private val view="""{"zoom":1,"x":0,"y":0,"view":{},"fitWidth":false}"""
    private fun wire(version:Int,lock:String?=null):String {
        val reading=if(lock==null) view else view.dropLast(1)+",\"zoomLocked\":$lock}"
        return """{"format":"xnotes-last-presentation","version":$version,"state":"native","slots":[{"kind":"note","pane":"PRIMARY","hint":{"kind":"note","documentId":"22222222-2222-4222-8222-222222222222","path":null},"view":$view},{"kind":"shared","pane":"SECONDARY","ownerPane":"PRIMARY","view":$reading}],"layout":{"focusedPane":"SECONDARY","ratio":0.5,"reversed":false}}"""
    }

    @Test fun version3PreservesBothExplicitLockValues() {
        for(lock in listOf("true","false")) {
            val parsed=requireNotNull(LastPresentationCodec.parse(wire(3,lock).toByteArray()))
            val encoded=JSONObject(String(LastPresentationCodec.encode(parsed)))
            assertEquals(3,encoded.getInt("version"))
            assertEquals(lock.toBoolean(),encoded.getJSONArray("slots").getJSONObject(1).getJSONObject("view").getBoolean("zoomLocked"))
        }
    }

    @Test fun mixedViewsRetainTheSameLockContract() {
        val native=JSONObject(wire(3,"true"))
        val first=native.getJSONArray("slots").getJSONObject(0)
        val second=native.getJSONArray("slots").getJSONObject(1)
        val mixed=JSONObject().put("format","xnotes-last-presentation").put("version",3).put("state","mixed")
            .put("map",JSONObject().put("kind","map").put("documentId","11111111-1111-4111-8111-111111111111").put("uri","content://map/one"))
            .put("native",first.getJSONObject("hint"))
            .put("layout",JSONObject().put("mapPane","PRIMARY").put("focusedPane","SECONDARY").put("ratio",0.5).put("reversed",false))
            .put("nativeView",second.getJSONObject("view"))
        val parsed=requireNotNull(LastPresentationCodec.parse(mixed.toString().toByteArray()))
        val encoded=JSONObject(String(LastPresentationCodec.encode(parsed)))
        assertEquals(3,encoded.getInt("version"))
        assertTrue(encoded.getJSONObject("nativeView").getBoolean("zoomLocked"))
        mixed.put("version",2)
        assertNull(LastPresentationCodec.parse(mixed.toString().toByteArray()))
    }

    @Test fun oldVersion2WithoutALockRemainsReadableAndDoesNotInventAnOverride() {
        val parsed=requireNotNull(LastPresentationCodec.parse(wire(2).toByteArray()))
        val encoded=JSONObject(String(LastPresentationCodec.encode(parsed)))
        assertFalse(encoded.getJSONArray("slots").getJSONObject(1).getJSONObject("view").has("zoomLocked"))
    }

    @Test fun oldVersionNumbersCannotSmuggleTheNewField() {
        assertNull(LastPresentationCodec.parse(wire(2,"true").toByteArray()))
    }

    @Test fun malformedLockValuesAndFutureVersionsRemainRejected() {
        for(lock in listOf("null","1","\"true\"","{}"))
            assertNull(LastPresentationCodec.parse(wire(3,lock).toByteArray()))
        assertNull(LastPresentationCodec.parse(wire(99,"true").toByteArray()))
    }
}
