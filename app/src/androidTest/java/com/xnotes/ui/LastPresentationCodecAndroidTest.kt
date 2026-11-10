package com.xnotes.ui

import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.util.DocumentKind
import com.xnotes.mindmap.MapNativeRestoreHint
import com.xnotes.mindmap.MapPresentationCamera
import com.xnotes.mindmap.MapSplitLayout
import com.xnotes.mindmap.MindMapView
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
        val paired=wire().toString().replace(""""title":"Map"""", """"title":"\uD834\uDD1E"""")
        assertEquals("\uD834\uDD1E",(LastPresentationCodec.parse(paired.toByteArray()) as LastPresentation.Mixed).value.map.title)
    }
    private val mapId="11111111-1111-4111-8111-111111111111"
    private val noteId="22222222-2222-4222-8222-222222222222"
    private fun header(state:String)=JSONObject().put("format","xnotes-last-presentation").put("version",1).put("state",state)
    // Independent wire fixture: neither the JVM fixture nor the encoder builds malformed inputs.
    private fun wire()=header("mixed")
        .put("map",JSONObject().put("kind","map").put("documentId",mapId).put("uri","content://originals/map").put("title","Map"))
        .put("native",JSONObject().put("kind","note").put("documentId",noteId).put("path",JSONObject.NULL))
        .put("layout",JSONObject().put("mapPane","PRIMARY").put("focusedPane","SECONDARY").put("ratio",.31)
            .put("sideBySide",false).put("reversed",true))
        .put("camera",JSONObject().put("zoom",1.5).put("panX",-24).put("panY",48)
            .put("width",1200).put("height",800).put("density",2))
        .put("nativePosition",JSONObject().put("zoom",1.25).put("x",17).put("y",27).put("view",JSONObject()))
    private fun parse(json:JSONObject)=LastPresentationCodec.parse(json.toString().toByteArray(Charsets.UTF_8))
    private fun mixed(json:JSONObject):LastPresentation.Mixed {
        val value=parse(json);assertTrue("The independent wire fixture must be a mixed marker",value is LastPresentation.Mixed)
        return value as LastPresentation.Mixed
    }
    private fun reject(raw:String,label:String=raw.take(100)) {
        val bytes=raw.toByteArray(Charsets.UTF_8)
        assertNull(label,LastPresentationCodec.parse(bytes))
        assertNull("stream: $label",LastPresentationCodec.read(ByteArrayInputStream(bytes)))
    }
    private fun reject(change:(JSONObject)->Unit) {val value=wire();change(value);reject(value.toString())}
    private fun sample(path:String?):LastPresentation.Mixed=LastPresentation.Mixed(ColdMixedPresentation(
        SplitSourceRef(mapId,"content://originals/map","Map"),MapNativeRestoreHint(noteId,path,DocumentKind.NOTE),
        MapSplitLayout(Pane.PRIMARY,Pane.SECONDARY,.31f,false,true),
        MapPresentationCamera(MindMapView.Viewport(1.5f,-24f,48f),1200,800,2f),
        Editor.ReadingPosition(1.25,17.0,27.0,ViewOverrides(mode=ViewingMode.COVER,verticalScroll=false,
            freeMovement=true,contrast=124,invert=32,brightness=87,sepia=13,keepImages=true,rotation=270,scrollbar=true))))

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

    @Test fun nativePathJsonNullIsRequiredAndIsDifferentFromAMissingOrCoercedPath() {
        val json=wire();val native=json.getJSONObject("native")
        assertTrue(native.has("path"));assertSame(JSONObject.NULL,native.get("path"))
        val restored=mixed(json);assertNull(restored.value.nativeHint.path)
        val encoded=JSONObject(String(LastPresentationCodec.encode(restored),Charsets.UTF_8))
        assertTrue(encoded.getJSONObject("native").has("path"));assertSame(JSONObject.NULL,encoded.getJSONObject("native").get("path"))
        reject {it.getJSONObject("native").remove("path")}
        for(value in listOf("","  ",false,7,JSONObject())) reject {it.getJSONObject("native").put("path",value)}
        for(path in listOf(null,"content://originals/note")) for(mapPane in Pane.entries) for(focus in Pane.entries) {
            val base=sample(path)
            val value=base.copy(value=base.value.copy(layout=base.value.layout.copy(mapPane=mapPane,focusedPane=focus)))
            assertEquals(value,LastPresentationCodec.parse(LastPresentationCodec.encode(value)))
        }
    }

    @Test fun optionalObjectsMayBeAbsentButExplicitNullNeverMeansMissingOrDefault() {
        val without=wire();without.remove("camera");without.remove("nativePosition")
        without.getJSONObject("layout").remove("sideBySide");without.getJSONObject("map").remove("title")
        val value=mixed(without).value
        assertNull(value.mapCamera);assertNull(value.nativePosition);assertNull(value.layout.sideBySide);assertNull(value.map.title)
        val changes=listOf<(JSONObject)->Unit>(
            {it.put("map",JSONObject.NULL)}, {it.put("native",JSONObject.NULL)}, {it.put("layout",JSONObject.NULL)},
            {it.put("camera",JSONObject.NULL)}, {it.put("nativePosition",JSONObject.NULL)},
            {it.getJSONObject("map").put("title",JSONObject.NULL)},
            {it.getJSONObject("layout").put("sideBySide",JSONObject.NULL)},
            {it.getJSONObject("layout").put("ratio",JSONObject.NULL)},
            {it.getJSONObject("camera").put("density",JSONObject.NULL)},
            {it.getJSONObject("nativePosition").put("view",JSONObject.NULL)},
            {it.getJSONObject("nativePosition").getJSONObject("view").put("verticalScroll",JSONObject.NULL)},
        )
        changes.forEach {reject(it)}
    }

    @Test fun androidLenientCommentsQuotesTokensAndTrailingCommasAreRejected() {
        val base=wire().toString()
        val malformed=listOf(
            "/*'*/$base", "// comment\n$base", "# comment\n$base",
            base.replace("\"state\":\"mixed\"","state:\"mixed\""),
            base.replace("\"state\":\"mixed\"","\"state\":mixed"),
            "{'format':'xnotes-last-presentation','version':1,'state':'home'}",
            base.replace("\"state\":\"mixed\"","\"state\":'mixed'"),
            header("home").toString().dropLast(1)+",}",
            "$base trailing",base+"\u0000trailing",
            base.replace("\"ratio\":0.31","\"ratio\":NaN"),
            base.replace("\"ratio\":0.31","\"ratio\":Infinity"),
            base.replace("\"ratio\":0.31","\"ratio\":1e999"),
            base.replace("\"ratio\":0.31","\"ratio\":+0.31"),
        )
        for(raw in malformed) {assertNotEquals(base,raw);reject(raw)}
    }

    @Test fun deepArraysCannotHideBehindCommentsOrQuotesInUnquotedKeys() {
        val deep="[".repeat(2048)+"0"+"]".repeat(2048)
        val payloads=listOf(
            "{\"format\":\"xnotes-last-presentation\",\"version\":1,\"state\":\"mixed\",\"map\":$deep}",
            "{/*'*/\"version\":1,\"map\":$deep,/*'*/\"format\":\"xnotes-last-presentation\"}",
            "{foo'bar:0,\"map\":$deep,baz'qux:0}",
            "{foo\"bar:0,\"map\":$deep,baz\"qux:0}",
        )
        for((index,raw) in payloads.withIndex()) reject(raw,"deep lenient payload $index")
    }

    @Test fun unicodeEscapedDuplicateKeysAreRejectedAtEveryObjectLevel() {
        val base=wire().toString()
        val duplicates=listOf(
            base.replaceFirst("{","{\"sta\\u0074e\":\"mixed\","),
            base.replace("\"map\":{","\"map\":{\"ur\\u0069\":\"content://originals/map\","),
            base.replace("\"native\":{","\"native\":{\"pa\\u0074h\":null,"),
            base.replace("\"layout\":{","\"layout\":{\"map\\u0050ane\":\"PRIMARY\","),
            base.replace("\"camera\":{","\"camera\":{\"wi\\u0064th\":1200,"),
            base.replace("\"nativePosition\":{","\"nativePosition\":{\"zo\\u006fm\":1.25,"),
            base.replace("\"view\":{}","\"view\":{\"mode\":\"single\",\"m\\u006fde\":\"cover\"}"),
        )
        for((index,raw) in duplicates.withIndex()) {assertNotEquals(base,raw);reject(raw,"duplicate object level $index")}
    }

    @Test fun geometryRolesAndOverridesAreTypedAndInvalidDtosCannotBeEncoded() {
        val changes=listOf<(JSONObject)->Unit>(
            {it.getJSONObject("native").put("kind","canvas")},
            {it.getJSONObject("native").put("documentId",mapId)},
            {it.getJSONObject("native").put("uri","content://invented/note")},
            {it.getJSONObject("layout").put("nativeHome",false)},
            {it.getJSONObject("layout").put("ratio",0)}, {it.getJSONObject("layout").put("ratio",1)},
            {it.getJSONObject("layout").put("ratio","0.31")}, {it.getJSONObject("layout").put("reversed",1)},
            {it.getJSONObject("layout").put("sideBySide","false")},
            {it.getJSONObject("camera").put("width",1200.5)}, {it.getJSONObject("camera").put("height",0)},
            {it.getJSONObject("camera").put("zoom",4.01)}, {it.getJSONObject("camera").put("density",0)},
            {it.getJSONObject("nativePosition").put("zoom",20.01)},
            {it.getJSONObject("nativePosition").put("x",true)},
            {it.getJSONObject("nativePosition").getJSONObject("view").put("verticalScroll","false")},
            {it.getJSONObject("nativePosition").getJSONObject("view").put("brightness",100.5)},
            {it.getJSONObject("nativePosition").getJSONObject("view").put("rotation",45)},
            {it.getJSONObject("nativePosition").getJSONObject("view").put("multiply","#80112233")},
        )
        changes.forEach {reject(it)}
        val value=sample(null);val data=value.value
        for(invalid in listOf(
            value.copy(value=data.copy(layout=data.layout.copy(nativeHome=true))),
            value.copy(value=data.copy(layout=data.layout.copy(ratio=Float.NaN))),
            value.copy(value=data.copy(nativeHint=data.nativeHint.copy(kind=DocumentKind.CANVAS))),
            value.copy(value=data.copy(mapCamera=data.mapCamera!!.copy(density=Float.POSITIVE_INFINITY))),
            value.copy(value=data.copy(nativePosition=data.nativePosition!!.copy(x=Double.NaN))),
        )) assertThrows(IllegalArgumentException::class.java) {LastPresentationCodec.encode(invalid)}
    }

    @Test fun utf8BudgetsAndEscapedStructuralStringsAreCheckedWithoutChangingFrozenData() {
        val json=wire();val title="quoted \\\" "+"[{".repeat(100)+"'"
        json.getJSONObject("map").put("title",title)
        val value=mixed(json);assertEquals(title,value.value.map.title)
        json.getJSONObject("map").put("uri","changed");assertEquals("content://originals/map",value.value.map.uri)
        val raw=wire().toString().replace("\"title\":\"Map\"","\"title\":\"INVALID_UTF8\"")
        val parts=raw.split("INVALID_UTF8");assertEquals(2,parts.size)
        val malformed=parts[0].toByteArray(Charsets.UTF_8)+byteArrayOf(0xc3.toByte(),0x28)+parts[1].toByteArray(Charsets.UTF_8)
        assertNull(LastPresentationCodec.parse(malformed))
        val bytes=wire().toString().toByteArray(Charsets.UTF_8)
        val exact=bytes+ByteArray(LastPresentationCodec.MAX_BYTES-bytes.size) {32}
        assertNotNull(LastPresentationCodec.read(ByteArrayInputStream(exact)))
        val tooLarge=exact+byteArrayOf(32)
        assertNull(LastPresentationCodec.parse(tooLarge));assertNull(LastPresentationCodec.read(ByteArrayInputStream(tooLarge)))
        val sample=sample(null)
        assertThrows(IllegalArgumentException::class.java) {
            LastPresentationCodec.encode(sample.copy(value=sample.value.copy(map=sample.value.map.copy(title="界".repeat(LastPresentationCodec.MAX_BYTES)))))
        }
    }
}
