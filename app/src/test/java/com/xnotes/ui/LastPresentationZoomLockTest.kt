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
