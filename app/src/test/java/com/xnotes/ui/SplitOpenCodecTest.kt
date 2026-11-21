package com.xnotes.ui

import java.io.ByteArrayInputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test


/** Build the wire format independently of the production encoder. */
internal fun mixedSplitJson():JSONObject {
    val map=JSONObject().put("kind","map").put("documentId",UUID.randomUUID().toString()).put("uri","content://notes/a")
        .put("title","Map").put("camera",JSONObject().put("zoom",1.5).put("panX",-25).put("panY",70)
            .put("width",800).put("height",600).put("density",2))
    val native=JSONObject().put("kind","note").put("documentId",UUID.randomUUID().toString()).put("uri","content://notes/b")
        .put("position",JSONObject().put("zoom",1.25).put("x",12).put("y",42).put("view",JSONObject()))
    return JSONObject().put("format","xnotes-split").put("version",3).put("panes",JSONArray().put(map).put(native))
        .put("layout",JSONObject().put("ratio",.31).put("focusedPane","SECONDARY").put("sideBySide",false).put("reversed",true))
}

class SplitOpenCodecTest {
    private fun parse(json:JSONObject)=SplitOpenCodec.parse(json.toString().toByteArray())
    private fun map(json:JSONObject)=json.getJSONArray("panes").getJSONObject(0)

    @Test fun mixedSplitIsRecognizedButNeverOpenedAsNative() {
        val json=mixedSplitJson().put("primaryPath","a").put("secondPath","b")
        assertEquals(SplitOpenRequest.LegacyMapSplit,parse(json));assertNull(parse(json.put("version",4)))
    }
    @Test fun nativeVersionsKeepTheOriginalParserAndRecentOnlyLegacyCompatibility() {
        val native=JSONObject().put("format","xnotes-split").put("version",2)
            .put("primaryPath","content://notes/a").put("secondPath","content://notes/b").put("ratio",.27)
        assertEquals(SplitOpenRequest.Native(requireNotNull(NativeSplitOpenParser.parse(native.toString().toByteArray()))),parse(native))
        native.put("version",1).remove("format")
        assertNull(parse(native));assertTrue(SplitOpenCodec.parse(native.toString().toByteArray(),true) is SplitOpenRequest.Native)
        native.put("version",2);assertNull(SplitOpenCodec.parse(native.toString().toByteArray(),true))
    }
    @Test fun streamBudgetMalformedUtf8AndTrailingTypedGarbageAreRejected() {
        assertNull(SplitOpenCodec.read(ByteArrayInputStream(ByteArray(SplitOpenCodec.MAX_BYTES+1))))
        assertNull(SplitOpenCodec.parse(byteArrayOf(0xc3.toByte(),0x28)))
        assertNull(SplitOpenCodec.parse((mixedSplitJson().toString()+" trailing").toByteArray()))
        assertNull(SplitOpenCodec.parse((mixedSplitJson().toString()+"\u0000trailing").toByteArray()))
        assertTrue(SplitOpenCodec.read(ByteArrayInputStream(mixedSplitJson().toString().toByteArray())) ===SplitOpenRequest.LegacyMapSplit)
    }
    @Test fun excessiveJsonDepthIsRejectedBeforeRecursiveParsingButBracketsInsideStringsAreData() {
        val nested="[".repeat(2048)+"0"+"]".repeat(2048)
        val malicious="{\"format\":\"xnotes-split\",\"version\":3,\"panes\":$nested}"
        assertNull(SplitOpenCodec.parse(malicious.toByteArray()))
        val json=mixedSplitJson();val title="quoted \\\" "+"[{".repeat(100)+"'"
        map(json).put("title",title)
        assertEquals(SplitOpenRequest.LegacyMapSplit,parse(json))
    }
    @Test fun preflightRejectsAndroidLenientCommentAndUnquotedTokenDepthBypasses() {
        val deep="[".repeat(2048)+"0"+"]".repeat(2048)
        val payloads=listOf(
            "{/*'*/\"version\":3,\"panes\":"+deep+",/*'*/\"format\":\"xnotes-split\"}",
            "{foo'bar:0,\"panes\":"+deep+",baz'qux:0}",
            "{foo\"bar:0,\"panes\":"+deep+",baz\"qux:0}")
        for(payload in payloads) {
            try {SplitOpenCodec.checkJsonEnvelope(payload);fail("Preflight itself must reject the Android lenient payload")}
            catch(_:IllegalArgumentException) {}
        }
    }
}
