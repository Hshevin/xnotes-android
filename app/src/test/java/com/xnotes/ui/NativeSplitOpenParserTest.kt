package com.xnotes.ui

import java.io.ByteArrayInputStream
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSplitOpenParserTest {
    private fun saved()=JSONObject().put("format","xnotes-split").put("version",2)
        .put("primaryPath","content://notes/a").put("secondPath","content://notes/b")
    private fun parse(json:JSONObject,legacy:Boolean=false)=NativeSplitOpenParser.parse(json.toString().toByteArray(),legacy)

    @Test fun versionTwoPreservesInputPrefixPositionsAndPhysicalFirstRatio() {
        val id=UUID.randomUUID().toString()
        val request=requireNotNull(parse(saved().put("primaryId",id).put("ratio",.31).put("sideBySide",false)
            .put("reversed",true).put("focused","SECONDARY").put("primaryZoom",1.3).put("primaryX",15)
            .put("primaryY",25).put("zoom",1.7).put("x",35).put("y",45)
            .put("secondView",JSONObject().put("verticalScroll",false))))
        assertEquals(id,request.first.expectedId);assertEquals(.31f,request.layout.ratio,0f)
        assertEquals(false,request.layout.sideBySide);assertTrue(request.layout.reversed);assertEquals(Pane.SECONDARY,request.layout.focused)
        assertEquals(15.0,request.layout.firstPosition!!.x,0.0);assertEquals(45.0,request.layout.secondPosition!!.y,0.0)
        assertEquals(false,request.layout.secondPosition!!.overrides.verticalScroll)
    }
    @Test fun onlyTheExplicitRecentCompatibilityModeAcceptsVersionOneWithoutFormat() {
        val json=saved().put("version",1).apply {remove("format")}
        assertNull(parse(json));assertNotNull(parse(json,true))
        assertNull(parse(json.put("version",2),true));assertNull(parse(saved().put("version",3),true))
    }
    @Test fun canvasTypedModesMalformedIdentityAndNumericStringsAreRejected() {
        assertNull(parse(saved().put("primaryCanvas",true)))
        assertNull(parse(saved().put("secondKind","xmap")))
        assertNull(parse(saved().put("primaryId","not-a-uuid")))
        assertNull(parse(saved().put("ratio","0.5")))
        assertNull(parse(saved().put("focused","side")))
    }
    @Test fun oversizedAndMalformedUtf8InputAreRejectedByTheSharedReader() {
        assertNull(NativeSplitOpenParser.read(ByteArrayInputStream(ByteArray(NativeSplitOpenParser.MAX_BYTES+1))))
        assertNull(NativeSplitOpenParser.parse(byteArrayOf(0xc3.toByte(),0x28)))
        assertNull(NativeSplitOpenParser.parse("{\"version\":2,\"format\":\"xnotes-split\",\"ratio\":1e999,\"primaryPath\":\"a\",\"secondPath\":\"b\"}".toByteArray()))
    }
    @Test fun parsingFreezesARequestAndDoesNotFollowLaterJsonMutation() {
        val json=saved();val request=requireNotNull(parse(json))
        json.put("primaryPath","content://notes/changed").put("ratio",.75)
        assertEquals("content://notes/a",request.first.uri);assertEquals(.5f,request.layout.ratio,0f)
    }
}
