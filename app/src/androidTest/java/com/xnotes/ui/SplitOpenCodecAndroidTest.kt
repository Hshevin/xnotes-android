package com.xnotes.ui

import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Android org.json coercion/null contracts; source compilation is not platform execution. */
class SplitOpenCodecAndroidTest {
    private val mapId="11111111-1111-4111-8111-111111111111"
    private val noteId="22222222-2222-4222-8222-222222222222"
    private fun valid()=JSONObject().put("format","xnotes-split").put("version",3)
        .put("panes",JSONArray()
            .put(JSONObject().put("kind","map").put("documentId",mapId).put("uri","content://notes/map"))
            .put(JSONObject().put("kind","note").put("documentId",noteId).put("uri","content://notes/note")
                .put("position",JSONObject().put("zoom",1.25).put("x",17.0).put("y",27.0)
                    .put("view",JSONObject().put("verticalScroll",false)))))
        .put("layout",JSONObject().put("ratio",.37).put("focusedPane",Pane.SECONDARY.name)
            .put("sideBySide",false).put("reversed",true))
    @Test fun versionThreeIsNeverOpenedAsANativeSplit() {
        val json=valid().put("primaryPath","a").put("secondPath","b")
        assertEquals(SplitOpenRequest.LegacyMapSplit,SplitOpenCodec.parse(json.toString().toByteArray()))
        assertNull(SplitOpenCodec.parse(json.put("version",4).toString().toByteArray()))
    }
    @Test fun preflightRejectsAndroidLenientCommentAndUnquotedTokenDepthBypasses() {
        val deep="[".repeat(2048)+"0"+"]".repeat(2048)
        val payloads=listOf(
            "{/*'*/\"version\":3,\"panes\":"+deep+",/*'*/\"format\":\"xnotes-split\"}",
            "{foo'bar:0,\"panes\":"+deep+",baz'qux:0}",
            "{foo\"bar:0,\"panes\":"+deep+",baz\"qux:0}")
        for(payload in payloads) {
            assertThrows(IllegalArgumentException::class.java) {SplitOpenCodec.checkJsonEnvelope(payload)}
            assertNull(SplitOpenCodec.parse(payload.toByteArray(Charsets.UTF_8)))
        }
    }
}
