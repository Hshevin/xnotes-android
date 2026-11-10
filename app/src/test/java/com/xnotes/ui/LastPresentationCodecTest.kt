package com.xnotes.ui

import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.model.Rgba
import com.xnotes.core.util.DocumentKind
import com.xnotes.mindmap.MapNativeRestoreHint
import com.xnotes.mindmap.MapPresentationCamera
import com.xnotes.mindmap.MapSplitLayout
import com.xnotes.mindmap.MindMapView
import java.io.ByteArrayInputStream
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal fun coldMarkerSample(path:String?=null)=LastPresentation.Mixed(ColdMixedPresentation(
    SplitSourceRef(UUID.randomUUID().toString(),"content://originals/map","导图"),
    MapNativeRestoreHint(UUID.randomUUID().toString(),path,DocumentKind.NOTE),
    MapSplitLayout(Pane.PRIMARY,Pane.SECONDARY,.31f,false,true),
    MapPresentationCamera(MindMapView.Viewport(1.5f,-24f,48f),1200,800,2f),
    Editor.ReadingPosition(1.25,17.0,27.0,ViewOverrides(mode=ViewingMode.COVER,verticalScroll=false,
        freeMovement=true,contrast=124,invert=32,brightness=87,sepia=13,multiply=Rgba(12,34,56),
        screen=Rgba(98,76,54),keepImages=true,rotation=270,scrollbar=true))))

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
        val paired=coldMarkerJson().toString().replace(""""title":"Map"""", """"title":"\uD834\uDD1E"""")
        assertEquals("\uD834\uDD1E",(LastPresentationCodec.parse(paired.toByteArray()) as LastPresentation.Mixed).value.map.title)
    }
    private fun parse(json:JSONObject)=LastPresentationCodec.parse(json.toString().toByteArray(Charsets.UTF_8))
    private fun native(json:JSONObject)=json.getJSONObject("native")
    private fun layout(json:JSONObject)=json.getJSONObject("layout")
    private fun camera(json:JSONObject)=json.getJSONObject("camera")
    private fun position(json:JSONObject)=json.getJSONObject("nativePosition")
    private fun rejects(change:(JSONObject)->Unit) {val json=coldMarkerJson();change(json);assertNull(json.toString(),parse(json))}
    private fun rejectsRaw(raw:String) {assertNull(raw,LastPresentationCodec.parse(raw.toByteArray(Charsets.UTF_8)))}
    private fun invalidEncode(value:LastPresentation) {
        try {LastPresentationCodec.encode(value);fail("Invalid DTO must not be encoded")} catch(_:IllegalArgumentException) {}
    }

    @Test fun homeIsAnExplicitTombstoneAndCannotBeConfusedWithMissingData() {
        assertEquals(LastPresentation.Home,parse(coldMarkerJson("home")))
        assertEquals(LastPresentation.Home,LastPresentationCodec.parse(LastPresentationCodec.encode(LastPresentation.Home)))
        assertNull(LastPresentationCodec.parse(byteArrayOf()))
        assertNull(parse(JSONObject()))
        val extra=coldMarkerJson("home").put("map",coldMarkerJson().getJSONObject("map"));assertNull(parse(extra))
    }

    @Test fun independentFixtureAllowsAnUnsavedNativeHintWithoutInventingASourceUri() {
        val value=(parse(coldMarkerJson()) as LastPresentation.Mixed).value
        assertNull(value.nativeHint.path);assertEquals(DocumentKind.NOTE,value.nativeHint.kind)
        assertEquals("content://originals/map",value.map.uri)
        assertEquals(Pane.PRIMARY,value.layout.mapPane);assertEquals(Pane.SECONDARY,value.layout.focusedPane)
        assertEquals(.31f,value.layout.ratio,0f);assertEquals(27.0,value.nativePosition!!.y,0.0)
        assertEquals(-24f,value.mapCamera!!.viewport.panX,0f)
    }

    @Test fun everySlotFocusDirectionAndReverseCombinationRoundTripsWithAllOverrides() {
        for(mapPane in Pane.entries) for(focus in Pane.entries) for(side in listOf(null,false,true)) for(reverse in listOf(false,true)) {
            val sample=coldMarkerSample("content://originals/note")
            val value=sample.copy(value=sample.value.copy(layout=sample.value.layout.copy(mapPane=mapPane,focusedPane=focus,sideBySide=side,reversed=reverse)))
            assertEquals(value,LastPresentationCodec.parse(LastPresentationCodec.encode(value)))
        }
    }

    @Test fun onlyTheRequiredNativePathMayBeJsonNullAndOptionalViewsRemainAbsent() {
        val json=coldMarkerJson();json.remove("camera");json.remove("nativePosition");layout(json).remove("sideBySide")
        val value=parse(json) as LastPresentation.Mixed
        assertNull(value.value.nativeHint.path);assertNull(value.value.mapCamera);assertNull(value.value.nativePosition)
        assertNull(value.value.layout.sideBySide)
        assertEquals(value,LastPresentationCodec.parse(LastPresentationCodec.encode(value)))
        rejects {native(it).remove("path")}
        rejects {native(it).put("path","")};rejects {native(it).put("path","  ")}
        rejects {it.put("camera",JSONObject.NULL)};rejects {it.put("nativePosition",JSONObject.NULL)}
        rejects {it.getJSONObject("map").put("title",JSONObject.NULL)}
        rejects {layout(it).put("sideBySide",JSONObject.NULL)}
        rejects {position(it).put("view",JSONObject.NULL)}
    }

    @Test fun missingMalformedAndUnknownHeaderFieldsNeverFallBackToRecentOrSplitFormats() {
        rejects {it.remove("format")};rejects {it.remove("version")};rejects {it.remove("state")}
        for(value in listOf(0,-1,2,99,1.5,"1",JSONObject.NULL)) rejects {it.put("version",value)}
        rejects {it.put("format","xnotes-split")};rejects {it.put("state","parked")}
        rejects {it.put("primaryPath","content://old/a").put("secondPath","content://old/b")}
    }

    @Test fun rolesIdentitiesAndHomeFlagsAreStrictDescriptions() {
        rejects {native(it).put("kind","canvas")};rejects {it.getJSONObject("map").put("kind","note")}
        rejects {native(it).put("documentId",it.getJSONObject("map").getString("documentId"))}
        rejects {native(it).put("documentId","1-1-1-1-1")};rejects {native(it).put("documentId",JSONObject.NULL)}
        rejects {it.getJSONObject("map").put("uri","  ")}
        rejects {layout(it).put("nativeHome",true)};rejects {layout(it).put("nativeHome",false)}
        rejects {it.put("runtimeOwner",JSONObject())};rejects {native(it).put("uri","content://fake-unsaved")}
    }

    @Test fun booleansAndEnumsCannotBeCoercedFromOtherJsonTypes() {
        rejects {layout(it).put("reversed",1)};rejects {layout(it).put("sideBySide","false")}
        rejects {layout(it).put("mapPane","map")};rejects {layout(it).put("focusedPane","NOTE")}
        rejects {position(it).getJSONObject("view").put("verticalScroll","false")}
        rejects {position(it).getJSONObject("view").put("mode","unknown")}
    }

    @Test fun finiteGeometryAndExactIntegerRangesAreValidatedWithoutRepair() {
        rejects {layout(it).put("ratio",0)};rejects {layout(it).put("ratio",1)};rejects {layout(it).put("ratio",".31")}
        rejects {camera(it).put("zoom",.049)};rejects {camera(it).put("zoom",4.01)}
        rejects {camera(it).put("width",0)};rejects {camera(it).put("height",800.5)}
        rejects {camera(it).put("density",0)};rejects {camera(it).put("panX",1e100)}
        rejects {position(it).put("zoom",20.01)};rejects {position(it).put("zoom",.049)}
        rejectsRaw(coldMarkerJson().toString().replace("\"ratio\":0.31","\"ratio\":1e999"))
    }

    @Test fun everyOverrideRetainsItsMeaningAndRejectsInvalidValues() {
        rejects {position(it).getJSONObject("view").put("contrast",201)}
        rejects {position(it).getJSONObject("view").put("invert",-1)}
        rejects {position(it).getJSONObject("view").put("brightness",100.5)}
        rejects {position(it).getJSONObject("view").put("rotation",45)}
        rejects {position(it).getJSONObject("view").put("multiply","#80112233")}
        rejects {position(it).getJSONObject("view").put("screen",JSONObject.NULL)}
        rejects {position(it).getJSONObject("view").put("futureView",true)}
    }

    @Test fun decodedDuplicateKeysAreRejectedAtEveryObjectLevel() {
        val base=coldMarkerJson().toString()
        val cases=listOf(
            base.replaceFirst("{","{\"state\":\"home\","),
            base.replace("\"mapPane\":\"PRIMARY\"","\"mapPane\":\"PRIMARY\",\"map\\u0050ane\":\"SECONDARY\""),
            base.replace("\"width\":1200","\"width\":1200,\"wi\\u0064th\":1200"),
            base.replace("\"path\":null","\"path\":null,\"pa\\u0074h\":null"),
            base.replace("\"map\":{","\"map\":{\"ki\\u006ed\":\"map\","),
            base.replace("\"x\":17","\"x\":17,\"\\u0078\":17"),
            base.replace("\"view\":{}","\"view\":{\"mode\":\"single\",\"m\\u006fde\":\"cover\"}"),
        )
        for(raw in cases) {assertNotEquals(base,raw);rejectsRaw(raw)}
    }

    @Test fun androidLenientGrammarCannotBypassStrictParsingOrDepthLimits() {
        for(raw in listOf("/*\"*/"+coldMarkerJson(),coldMarkerJson().toString().replace("\"state\"","state"),
            coldMarkerJson("home").toString().replace("\"home\"","'home'"),"{'format':'xnotes-last-presentation','version':1,'state':'home'}",
            coldMarkerJson().toString()+" trailing",coldMarkerJson().toString()+"\u0000trailing",
            coldMarkerJson().toString().replace("\"ratio\":0.31","\"ratio\":+0.31"),
            "{\"format\":\"xnotes-last-presentation\",\"version\":1,\"state\":\"home\",}")) rejectsRaw(raw)
        val deeplyNested="[".repeat(2048)+"0"+"]".repeat(2048)
        rejectsRaw("{\"format\":\"xnotes-last-presentation\",\"version\":1,\"state\":\"mixed\",\"map\":$deeplyNested}")
    }

    @Test fun structuralCharactersInsideEscapedStringsAreDataAndRequestsAreFrozen() {
        val json=coldMarkerJson();val title="quoted \\\" "+"[{".repeat(100)+"'"
        json.getJSONObject("map").put("title",title)
        val bytes=json.toString().toByteArray();val value=LastPresentationCodec.parse(bytes) as LastPresentation.Mixed
        assertEquals(title,value.value.map.title)
        json.getJSONObject("map").put("uri","changed");bytes.fill(0)
        assertEquals("content://originals/map",value.value.map.uri)
    }

    @Test fun strictUtf8AndBothInputAndOutputBudgetsAreEnforced() {
        assertNull(LastPresentationCodec.parse(byteArrayOf(0xc3.toByte(),0x28)))
        assertNull(LastPresentationCodec.read(ByteArrayInputStream(ByteArray(LastPresentationCodec.MAX_BYTES+1))))
        val sample=coldMarkerSample();invalidEncode(sample.copy(value=sample.value.copy(map=sample.value.map.copy(title="界".repeat(LastPresentationCodec.MAX_BYTES)))))
        val bytes=coldMarkerJson().toString().toByteArray();assertNotNull(LastPresentationCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test fun encodingRejectsIllegalInMemoryDescriptionsRatherThanNormalizingThem() {
        val value=coldMarkerSample();val data=value.value
        invalidEncode(value.copy(value=data.copy(nativeHint=data.nativeHint.copy(kind=DocumentKind.CANVAS))))
        invalidEncode(value.copy(value=data.copy(layout=data.layout.copy(nativeHome=true))))
        invalidEncode(value.copy(value=data.copy(layout=data.layout.copy(ratio=Float.NaN))))
        invalidEncode(value.copy(value=data.copy(nativeHint=data.nativeHint.copy(documentId=data.map.documentId))))
        invalidEncode(value.copy(value=data.copy(mapCamera=data.mapCamera!!.copy(density=Float.POSITIVE_INFINITY))))
        invalidEncode(value.copy(value=data.copy(nativePosition=data.nativePosition!!.copy(x=Double.NaN))))
        invalidEncode(value.copy(value=data.copy(nativePosition=data.nativePosition!!.copy(overrides=ViewOverrides(multiply=Rgba(1,2,3,128))))))
    }
}
