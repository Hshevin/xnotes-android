package com.xnotes.ui

import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.util.DocumentKind
import com.xnotes.core.model.NativeRestoreHint
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Android org.json contracts. Source compilation does not mean Android execution. */
class LastPresentationCodecAndroidTest {
    private fun typedNativeWire():JSONObject=JSONObject().put("format","xnotes-last-presentation")
        .put("version",2).put("state","native")
        .put("slots",JSONArray().put(JSONObject().put("pane","PRIMARY").put("kind","note")
            .put("hint",JSONObject().put("kind","note").put("documentId",noteId).put("path",JSONObject.NULL))
            .put("view",JSONObject().put("zoom",1.25).put("x",17).put("y",27).put("view",JSONObject()).put("fitWidth",true))))
        .put("layout",JSONObject().put("focusedPane","PRIMARY").put("ratio",.5).put("reversed",false))

    @Test fun typedNativeNullPathIsPreservedButMissingPathAndNullViewAreRejected() {
        val wire=typedNativeWire()
        val parsed=parse(wire) as LastPresentation.Native
        val slot=parsed.slots.single() as ColdNativeSlot.Note
        assertNull(slot.hint.path);assertTrue(slot.view!!.fitWidth)
        val encoded=JSONObject(String(LastPresentationCodec.encode(parsed),Charsets.UTF_8))
        val hint=encoded.getJSONArray("slots").getJSONObject(0).getJSONObject("hint")
        assertTrue(hint.has("path"));assertSame(JSONObject.NULL,hint.get("path"))
        val missing=typedNativeWire()
        missing.getJSONArray("slots").getJSONObject(0).getJSONObject("hint").remove("path")
        reject(missing.toString())
        for(key in listOf("view","hint")) {
            val invalid=typedNativeWire();invalid.getJSONArray("slots").getJSONObject(0).put(key,JSONObject.NULL)
            reject(invalid.toString())
        }
        val invalid=typedNativeWire()
        invalid.getJSONArray("slots").getJSONObject(0).getJSONObject("view").put("fitWidth",JSONObject.NULL)
        reject(invalid.toString())
    }

    @Test fun sharedSlotHasOneActualOwnerAndCannotHideAnotherHint() {
        val valid=typedNativeWire()
        valid.getJSONArray("slots").put(JSONObject().put("pane","SECONDARY").put("kind","shared").put("ownerPane","PRIMARY"))
        assertEquals(Pane.PRIMARY,((parse(valid) as LastPresentation.Native).slots[1] as ColdNativeSlot.Shared).ownerPane)
        val hidden=JSONObject(valid.toString())
        hidden.getJSONArray("slots").getJSONObject(1).put("hint",JSONObject.NULL)
        reject(hidden.toString())
        val dangling=JSONObject(valid.toString())
        dangling.getJSONArray("slots").getJSONObject(1).put("ownerPane","SECONDARY")
        reject(dangling.toString())
    }

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
    private val noteId="22222222-2222-4222-8222-222222222222"
    private fun header(state:String)=JSONObject().put("format","xnotes-last-presentation").put("version",1).put("state",state)
    private fun parse(json:JSONObject)=LastPresentationCodec.parse(json.toString().toByteArray(Charsets.UTF_8))
    private fun reject(raw:String,label:String=raw.take(100)) {
        val bytes=raw.toByteArray(Charsets.UTF_8)
        assertNull(label,LastPresentationCodec.parse(bytes))
        assertNull("stream: $label",LastPresentationCodec.read(ByteArrayInputStream(bytes)))
    }
    @Test fun homeIsOnlyAnExplicitCompleteHeaderAndNeverFallsBackToAnotherFormat() {
        assertEquals(LastPresentation.Home,parse(header("home")))
        val encoded=LastPresentationCodec.encode(LastPresentation.Home)
        assertEquals(3,JSONObject(String(encoded,Charsets.UTF_8)).length())
        assertEquals(LastPresentation.Home,LastPresentationCodec.read(ByteArrayInputStream(encoded)))
        assertNull(LastPresentationCodec.parse(byteArrayOf()))
        for(key in listOf("format","version","state")) {
            val missing=header("home");missing.remove(key);reject(missing.toString())
            reject(header("home").put(key,JSONObject.NULL).toString())
        }
        for(value in listOf(0,2,1.5,"1",true)) reject(header("home").put("version",value).toString())
        for(extra in listOf("map","native","layout","camera","nativePosition","primaryPath"))
            reject(header("home").put(extra,JSONObject()).toString())
        reject(header("home").put("format","xnotes-split").toString())
        reject(header("parked").toString())
    }

    @Test fun androidLenientCommentsQuotesTokensAndTrailingCommasAreRejected() {
        val base=typedNativeWire().toString()
        val malformed=listOf(
            "/*'*/$base", "// comment\n$base", "# comment\n$base",
            base.replace("\"state\":\"native\"","state:\"mixed\""),
            base.replace("\"state\":\"native\"","\"state\":mixed"),
            "{'format':'xnotes-last-presentation','version':1,'state':'home'}",
            base.replace("\"state\":\"native\"","\"state\":'mixed'"),
            header("home").toString().dropLast(1)+",}",
            "$base trailing",base+"\u0000trailing",
            base.replace("\"ratio\":0.5","\"ratio\":NaN"),
            base.replace("\"ratio\":0.5","\"ratio\":Infinity"),
            base.replace("\"ratio\":0.5","\"ratio\":1e999"),
            base.replace("\"ratio\":0.5","\"ratio\":+0.5"),
        )
        for(raw in malformed) {assertNotEquals(base,raw);reject(raw)}
    }

    @Test fun deepArraysCannotHideBehindCommentsOrQuotesInUnquotedKeys() {
        val deep="[".repeat(2048)+"0"+"]".repeat(2048)
        val payloads=listOf(
            "{\"format\":\"xnotes-last-presentation\",\"version\":1,\"state\":\"native\",\"map\":$deep}",
            "{/*'*/\"version\":1,\"map\":$deep,/*'*/\"format\":\"xnotes-last-presentation\"}",
            "{foo'bar:0,\"map\":$deep,baz'qux:0}",
            "{foo\"bar:0,\"map\":$deep,baz\"qux:0}",
        )
        for((index,raw) in payloads.withIndex()) reject(raw,"deep lenient payload $index")
    }

    @Test fun unicodeEscapedDuplicateKeysAreRejectedAtEveryObjectLevel() {
        val base=typedNativeWire().toString()
        val duplicates=listOf(
            base.replaceFirst("{","{\"sta\\u0074e\":\"mixed\","),
            base.replace("\"hint\":{","\"hint\":{\"pa\\u0074h\":null,"),
            base.replace("\"layout\":{","\"layout\":{\"focused\\u0050ane\":\"PRIMARY\","),
            base.replace("\"view\":{}","\"view\":{\"mode\":\"single\",\"m\\u006fde\":\"cover\"}"),
        )
        for((index,raw) in duplicates.withIndex()) {assertNotEquals(base,raw);reject(raw,"duplicate object level $index")}
    }

    @Test fun version4CleanReopenPreservesBooleanAndRejectsNullOrOlderSmuggling() {
        val path="content://editor-page-copy/document/root%2Fsource"
        val valid=JSONObject().put("format","xnotes-last-presentation").put("version",4).put("state","native")
            .put("slots",JSONArray().put(JSONObject().put("pane","PRIMARY").put("kind","note")
                .put("hint",JSONObject().put("kind","note").put("documentId",noteId).put("path",path))
                .put("reopenFromFile",true)))
            .put("layout",JSONObject().put("focusedPane","PRIMARY").put("ratio",.5).put("reversed",false))
        val parsed=parse(valid) as LastPresentation.Native
        val slot=parsed.slots.single() as ColdNativeSlot.Note
        assertTrue(slot.reopenFromFile)
        val encoded=JSONObject(String(LastPresentationCodec.encode(parsed),Charsets.UTF_8))
        assertEquals(4,encoded.getInt("version"))
        assertTrue(encoded.getJSONArray("slots").getJSONObject(0).getBoolean("reopenFromFile"))

        val nullFlag=JSONObject(valid.toString())
        nullFlag.getJSONArray("slots").getJSONObject(0).put("reopenFromFile",JSONObject.NULL)
        reject(nullFlag.toString())

        val nullPath=JSONObject(valid.toString())
        nullPath.getJSONArray("slots").getJSONObject(0).getJSONObject("hint").put("path",JSONObject.NULL)
        reject(nullPath.toString())

        val smuggled=JSONObject(valid.toString()).put("version",3)
        reject(smuggled.toString())
    }

}
