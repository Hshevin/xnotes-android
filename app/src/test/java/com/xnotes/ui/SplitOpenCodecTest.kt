package com.xnotes.ui

import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.model.Rgba
import com.xnotes.mindmap.MapPresentationCamera
import com.xnotes.mindmap.MapSplitLayout
import com.xnotes.mindmap.MindMapView
import java.io.ByteArrayInputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal fun mixedSplitSample(mapPane:Pane=Pane.PRIMARY,focus:Pane=Pane.SECONDARY,reversed:Boolean=true)=MixedSplitRequest(
    SplitSourceRef(UUID.randomUUID().toString(),"content://notes/map","导图"),
    SplitSourceRef(UUID.randomUUID().toString(),"content://notes/note","笔记"),
    MapSplitLayout(mapPane,focus,.31f,false,reversed),
    MapPresentationCamera(MindMapView.Viewport(1.75f,-120f,240f),920,640,2f),
    Editor.ReadingPosition(1.25,-30.0,500.0,ViewOverrides(mode=ViewingMode.COVER,verticalScroll=false,
        freeMovement=true,contrast=124,invert=32,brightness=87,sepia=13,multiply=Rgba(12,34,56),
        screen=Rgba(98,76,54),keepImages=true,rotation=270,scrollbar=true)))

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
    private fun mixed(json:JSONObject)=((parse(json) as SplitOpenRequest.Mixed).request)
    private fun map(json:JSONObject)=json.getJSONArray("panes").getJSONObject(0)
    private fun note(json:JSONObject)=json.getJSONArray("panes").getJSONObject(1)
    private fun rejects(change:(JSONObject)->Unit) {val json=mixedSplitJson();change(json);assertNull(json.toString(),parse(json))}
    private fun invalidEncode(request:MixedSplitRequest) {
        try {SplitOpenCodec.encodeMixed(request);fail("Invalid request must not be encoded")} catch(_:IllegalArgumentException) {}
    }

    @Test fun independentWireFixturePreservesBothSourcesAndTheirRoles() {
        val json=mixedSplitJson();val value=mixed(json)
        assertEquals(map(json).getString("documentId"),value.map.documentId)
        assertEquals(note(json).getString("documentId"),value.native.documentId)
        assertEquals("Map",value.map.title);assertNull(value.native.title)
        assertEquals(Pane.PRIMARY,value.layout.mapPane);assertEquals(Pane.SECONDARY,value.layout.focusedPane)
        assertEquals(.31f,value.layout.ratio,0f);assertEquals(false,value.layout.sideBySide);assertTrue(value.layout.reversed)
        assertEquals(-25f,value.mapCamera!!.viewport.panX,0f);assertEquals(800,value.mapCamera!!.width)
        assertEquals(42.0,value.nativePosition.y,0.0)
    }
    @Test fun everyLogicalSlotFocusAndReverseCombinationRoundTripsWithoutMovingPhysicalFirstRatio() {
        for(mapPane in Pane.entries) for(focus in Pane.entries) for(reversed in listOf(false,true)) {
            val value=mixedSplitSample(mapPane,focus,reversed)
            val restored=(SplitOpenCodec.parse(SplitOpenCodec.encodeMixed(value)) as SplitOpenRequest.Mixed).request
            assertEquals(value,restored)
        }
    }
    @Test fun cameraAndAutomaticOrientationMayBeAbsentButRequiredReadingPositionMayNot() {
        val json=mixedSplitJson();map(json).remove("camera");json.getJSONObject("layout").remove("sideBySide")
        val value=mixed(json);assertNull(value.mapCamera);assertNull(value.layout.sideBySide)
        assertEquals(value,(SplitOpenCodec.parse(SplitOpenCodec.encodeMixed(value)) as SplitOpenRequest.Mixed).request)
        rejects {note(it).remove("position")};rejects {note(it).getJSONObject("position").remove("view")}
        rejects {note(it).getJSONObject("position").remove("zoom")}
    }
    @Test fun nativeVersionsKeepTheOriginalParserAndRecentOnlyLegacyCompatibility() {
        val native=JSONObject().put("format","xnotes-split").put("version",2)
            .put("primaryPath","content://notes/a").put("secondPath","content://notes/b").put("ratio",.27)
        assertEquals(SplitOpenRequest.Native(requireNotNull(NativeSplitOpenParser.parse(native.toString().toByteArray()))),parse(native))
        native.put("version",1).remove("format")
        assertNull(parse(native));assertTrue(SplitOpenCodec.parse(native.toString().toByteArray(),true) is SplitOpenRequest.Native)
        native.put("version",2);assertNull(SplitOpenCodec.parse(native.toString().toByteArray(),true))
    }
    @Test fun corruptTypedOrFutureVersionsNeverFallBackToCompleteLegacyFields() {
        val json=mixedSplitJson().put("primaryPath","content://legacy/a").put("secondPath","content://legacy/b")
        map(json).put("kind","canvas");assertNull(parse(json))
        for(version in listOf(0,-1,4,100,3.5,"3",JSONObject.NULL)) rejects {it.put("version",version)}
        rejects {it.remove("format")};rejects {it.put("format","other")}
    }
    @Test fun panesMustHaveExactlyOneMapAndOneNoteWithDistinctValidIdentities() {
        rejects {note(it).put("kind","map")};rejects {map(it).put("kind","note")}
        rejects {map(it).put("kind","original_pdf")};rejects {it.getJSONArray("panes").put(JSONObject(map(it).toString()))}
        rejects {it.put("panes",JSONArray().put(map(it)))}
        rejects {note(it).put("documentId",map(it).getString("documentId"))}
        rejects {note(it).put("uri",map(it).getString("uri"))}
        rejects {map(it).put("documentId","1-1-1-1-1")};rejects {map(it).put("documentId","not-an-id")}
        rejects {note(it).put("uri","  ")};rejects {map(it).put("documentId",JSONObject.NULL)}
    }
    @Test fun malformedBooleansEnumsAndJsonNullCannotBecomeDefaults() {
        rejects {it.getJSONObject("layout").put("sideBySide","false")}
        rejects {it.getJSONObject("layout").put("reversed",1)}
        rejects {it.getJSONObject("layout").put("focusedPane","map")}
        rejects {it.getJSONObject("layout").put("ratio",JSONObject.NULL)}
        rejects {it.getJSONObject("layout").put("sideBySide",JSONObject.NULL)}
        rejects {map(it).put("camera",JSONObject.NULL)};rejects {map(it).put("title",JSONObject.NULL)}
        rejects {note(it).getJSONObject("position").put("view",JSONObject.NULL)}
    }
    @Test fun finiteNumbersAndGeometriesAreRequiredInsteadOfSilentCoercion() {
        rejects {it.getJSONObject("layout").put("ratio","0.31")}
        rejects {it.getJSONObject("layout").put("ratio",0)};rejects {it.getJSONObject("layout").put("ratio",1)}
        rejects {map(it).getJSONObject("camera").put("width",0)}
        rejects {map(it).getJSONObject("camera").put("height",600.5)}
        rejects {map(it).getJSONObject("camera").put("density",0)}
        rejects {map(it).getJSONObject("camera").put("zoom",-1)}
        rejects {map(it).getJSONObject("camera").put("zoom",4.01)}
        rejects {note(it).getJSONObject("position").put("zoom",0)}
        rejects {note(it).getJSONObject("position").put("zoom",20.01)}
        val huge=mixedSplitJson().toString().replace("\"zoom\":1.5","\"zoom\":1e999")
        assertNull(SplitOpenCodec.parse(huge.toByteArray()))
        val floatOverflow=mixedSplitJson();map(floatOverflow).getJSONObject("camera").put("panX",1e100)
        assertNull(parse(floatOverflow))
    }
    @Test fun viewOverridesRetainAllFieldsAndRejectInvalidTypedValues() {
        rejects {note(it).getJSONObject("position").getJSONObject("view").put("verticalScroll","false")}
        rejects {note(it).getJSONObject("position").getJSONObject("view").put("mode","unknown")}
        rejects {note(it).getJSONObject("position").getJSONObject("view").put("contrast",201)}
        rejects {note(it).getJSONObject("position").getJSONObject("view").put("invert",-1)}
        rejects {note(it).getJSONObject("position").getJSONObject("view").put("brightness",100.5)}
        rejects {note(it).getJSONObject("position").getJSONObject("view").put("rotation",17)}
        rejects {note(it).getJSONObject("position").getJSONObject("view").put("multiply","not-a-color")}
        rejects {note(it).getJSONObject("position").getJSONObject("view").put("screen",JSONObject.NULL)}
    }
    @Test fun frozenRequestsDoNotExposeJsonOrDependOnInputBytesAfterParsing() {
        val json=mixedSplitJson();val bytes=json.toString().toByteArray()
        val value=(SplitOpenCodec.parse(bytes) as SplitOpenRequest.Mixed).request
        map(json).put("uri","content://changed");json.getJSONObject("layout").put("ratio",.6);bytes.fill(0)
        assertEquals("content://notes/a",value.map.uri);assertEquals(.31f,value.layout.ratio,0f)
        assertEquals(-25f,value.mapCamera!!.viewport.panX,0f)
    }
    @Test fun encoderRejectsInvalidInMemoryRequestsAndOversizedOutput() {
        val value=mixedSplitSample()
        invalidEncode(value.copy(map=value.map.copy(documentId="broken")))
        invalidEncode(value.copy(native=value.native.copy(documentId=value.map.documentId)))
        invalidEncode(value.copy(native=value.native.copy(uri=value.map.uri)))
        invalidEncode(value.copy(layout=value.layout.copy(nativeHome=true)))
        invalidEncode(value.copy(layout=value.layout.copy(ratio=Float.NaN)))
        invalidEncode(value.copy(mapCamera=value.mapCamera!!.copy(density=Float.POSITIVE_INFINITY)))
        invalidEncode(value.copy(nativePosition=value.nativePosition.copy(x=Double.NaN)))
        invalidEncode(value.copy(nativePosition=value.nativePosition.copy(overrides=ViewOverrides(rotation=45))))
        invalidEncode(value.copy(nativePosition=value.nativePosition.copy(overrides=ViewOverrides(multiply=Rgba(1,2,3,128)))))
        invalidEncode(value.copy(map=value.map.copy(title="界".repeat(SplitOpenCodec.MAX_BYTES))))
    }
    @Test fun nativeHomeAndUnknownTypedFieldsCannotBeSilentlyRestoredAsFullLayouts() {
        rejects {it.getJSONObject("layout").put("nativeHome",true)}
        rejects {it.put("runtimeOwner",JSONObject())}
        rejects {map(it).put("position",JSONObject())}
    }
    @Test fun streamBudgetMalformedUtf8AndTrailingTypedGarbageAreRejected() {
        assertNull(SplitOpenCodec.read(ByteArrayInputStream(ByteArray(SplitOpenCodec.MAX_BYTES+1))))
        assertNull(SplitOpenCodec.parse(byteArrayOf(0xc3.toByte(),0x28)))
        assertNull(SplitOpenCodec.parse((mixedSplitJson().toString()+" trailing").toByteArray()))
        assertNull(SplitOpenCodec.parse((mixedSplitJson().toString()+"\u0000trailing").toByteArray()))
        assertTrue(SplitOpenCodec.read(ByteArrayInputStream(mixedSplitJson().toString().toByteArray())) is SplitOpenRequest.Mixed)
    }
    @Test fun excessiveJsonDepthIsRejectedBeforeRecursiveParsingButBracketsInsideStringsAreData() {
        val nested="[".repeat(2048)+"0"+"]".repeat(2048)
        val malicious="{\"format\":\"xnotes-split\",\"version\":3,\"panes\":$nested}"
        assertNull(SplitOpenCodec.parse(malicious.toByteArray()))
        val json=mixedSplitJson();val title="quoted \\\" "+"[{".repeat(100)+"'"
        map(json).put("title",title)
        assertEquals(title,mixed(json).map.title)
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
