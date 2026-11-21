package com.xnotes.ui

import com.xnotes.core.util.DocumentKind
import com.xnotes.core.model.NativeRestoreHint
import java.io.ByteArrayInputStream
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal fun coldMarkerSample(path:String?=null)=LastPresentation.Native(listOf(ColdNativeSlot.Note(Pane.PRIMARY,
    NativeRestoreHint(UUID.randomUUID().toString(),path,DocumentKind.NOTE))),ColdNativeLayout(Pane.PRIMARY))

/** Independent wire fixture; storage tests can seed bytes even before the encoder exists. */
internal fun coldMarkerJson(state:String="mixed"):JSONObject {
    val root=JSONObject().put("format","xnotes-last-presentation").put("version",1).put("state",state)
    if(state=="home") return root
    return root.put("map",JSONObject().put("kind","map").put("documentId","11111111-1111-4111-8111-111111111111")
        .put("uri","content://originals/map").put("title","Map"))
        .put("native",JSONObject().put("kind","note").put("documentId","22222222-2222-4222-8222-222222222222")
            .put("path",JSONObject.NULL))
        .put("layout",JSONObject().put("mapPane","PRIMARY").put("focusedPane","SECONDARY").put("ratio",.31)
            .put("sideBySide",false).put("reversed",true))
        .put("camera",JSONObject().put("zoom",1.5).put("panX",-24).put("panY",48).put("width",1200).put("height",800).put("density",2))
        .put("nativePosition",JSONObject().put("zoom",1.25).put("x",17).put("y",27).put("view",JSONObject()))
}

class LastPresentationCodecTest {
    @Test fun nonAsciiUnicodeEscapeDigitsAreRejected() {
        val home="""{"format":"xnotes-last-presentation","version":1,"state":"home"}"""
        for(raw in listOf(
            home.replace("home", """\u００６８ome"""),
            home.replace("home", """\u0０6８ome"""),
            home.replace("state", """sta\u００７４e"""),
        )) {
            assertNotEquals(home,raw)
            assertNull(raw,LastPresentationCodec.parse(raw.toByteArray(Charsets.UTF_8)))
        }
        assertEquals(LastPresentation.Home,LastPresentationCodec.parse(home.replace("home", """\u0068ome""").toByteArray()))
    }
    private fun parse(json:JSONObject)=LastPresentationCodec.parse(json.toString().toByteArray(Charsets.UTF_8))
    @Test fun homeIsAnExplicitTombstoneAndCannotBeConfusedWithMissingData() {
        assertEquals(LastPresentation.Home,parse(coldMarkerJson("home")))
        assertEquals(LastPresentation.Home,LastPresentationCodec.parse(LastPresentationCodec.encode(LastPresentation.Home)))
        assertNull(LastPresentationCodec.parse(byteArrayOf()))
        assertNull(parse(JSONObject()))
        val extra=coldMarkerJson("home").put("map",coldMarkerJson().getJSONObject("map"));assertNull(parse(extra))
    }

    @Test fun oldPresentationsFallBackToHomeWithoutFollowingSources() {
        for(state in listOf("map","mixed","network")) assertEquals(LastPresentation.Home,parse(coldMarkerJson().put("state",state)))
        assertNull(parse(coldMarkerJson().put("version",100)))
    }

    @Test fun nativePathsRoundTripEscapesButDuplicateKeysAndInvalidWireBytesAreRejected() {
        val value=coldMarkerSample("content://notes/\"\\[]笔记")
        val bytes=LastPresentationCodec.encode(value)
        assertEquals(value,LastPresentationCodec.read(ByteArrayInputStream(bytes)))
        val wire=String(bytes,Charsets.UTF_8)
        for (invalid in listOf(wire.replaceFirst("{","{\"sta\\u0074e\":\"home\","),wire+" trailing",
            wire.replace("\"hint\":{","\"hint\":{\"pa\\u0074h\":null,"))) {
            assertNotEquals(wire,invalid);assertNull(LastPresentationCodec.parse(invalid.toByteArray()))
        }
        assertNull(LastPresentationCodec.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(),0x28))))
        assertNull(LastPresentationCodec.read(ByteArrayInputStream(bytes+ByteArray(LastPresentationCodec.MAX_BYTES))))
    }
}
