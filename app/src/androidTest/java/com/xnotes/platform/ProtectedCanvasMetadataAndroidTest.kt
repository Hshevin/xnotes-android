package com.xnotes.platform

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Android org.json fixtures. SOURCE compilation alone is not an instrumented execution. */
class ProtectedCanvasMetadataAndroidTest {
    @Test fun androidEncoderProducesTheActualCanvasSidecarShape() {
        val raw=JSONObject().put("dirty",true).put("path","content://canvas/original").put("display_name","Canvas")
        assertEquals(CanvasSessionStore.Metadata("content://canvas/original","Canvas",true),ProtectedCanvasMetadata.parse(raw.toString().toByteArray()))
    }
    @Test fun explicitPlatformNullAndMissingRequiredBooleanAreDifferentInvalidCases() {
        for(key in listOf("dirty","path","display_name"))
            assertThrows(Exception::class.java) {ProtectedCanvasMetadata.parse(JSONObject().put("dirty",false).put(key,JSONObject.NULL).toString().toByteArray())}
        assertThrows(Exception::class.java) {ProtectedCanvasMetadata.parse(JSONObject().toString().toByteArray())}
    }
    @Test fun platformLenientObjectsAndDecodedDuplicateKeysCannotHideInvalidInput() {
        for(raw in listOf("""{/*comment*/"dirty":false}""","""{dirty:false}""","""{'dirty':false}""",
            """{"dirty":false,}""","""{"dirty":NaN}""","""{"dirty":false,"d\u0069rty":true}""",
            """{"dirty":false,"path":"one","p\u0061th":"two"}"""))
            assertThrows(Exception::class.java) {ProtectedCanvasMetadata.parse(raw.toByteArray())}
    }
    @Test fun strictUtf8DepthAndByteBudgetsAreIndependentOfPlatformJson() {
        for(bytes in listOf(byteArrayOf(0xc3.toByte(),0x28),ByteArray(ProtectedCanvasMetadata.MAX_BYTES+1) {' '.code.toByte()},
            """{"dirty":false,"display_name":"\ud800"}""".toByteArray(),
            ("{\"dirty\":"+"[".repeat(2048)+"false"+"]".repeat(2048)+"}").toByteArray()))
            assertThrows(Exception::class.java) {ProtectedCanvasMetadata.parse(bytes)}
    }
}
