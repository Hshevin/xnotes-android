package com.xnotes.ui

import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewingMode
import com.xnotes.mindmap.MapPresentationCamera
import com.xnotes.mindmap.MapSplitLayout
import com.xnotes.mindmap.MindMapView
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
    private fun map(json:JSONObject)=json.getJSONArray("panes").getJSONObject(0)
    private fun note(json:JSONObject)=json.getJSONArray("panes").getJSONObject(1)
    private fun position(json:JSONObject)=note(json).getJSONObject("position")
    private fun layout(json:JSONObject)=json.getJSONObject("layout")
    private fun rejected(json:JSONObject,label:String) {
        val bytes=json.toString().toByteArray(Charsets.UTF_8)
        for(legacy in listOf(false,true)) {
            assertNull("$label (legacy=$legacy)",SplitOpenCodec.parse(bytes,legacy))
            assertNull("stream: $label (legacy=$legacy)",SplitOpenCodec.read(ByteArrayInputStream(bytes),legacy))
        }
    }

    @Test fun validMixedRequestsRoundTripBothPaneOrdersWithPlatformJson() {
        val view=ViewOverrides(mode=ViewingMode.COVER,verticalScroll=false,freeMovement=true,
            contrast=145,invert=25,brightness=120,sepia=35,keepImages=true,rotation=270,scrollbar=true)
        for(mapPane in Pane.entries) for(focus in Pane.entries) for(reversed in listOf(false,true)) {
            val camera=if(focus==Pane.PRIMARY) MapPresentationCamera(MindMapView.Viewport(1.5f,-24f,48f),1200,800,2f) else null
            val sideBySide=if(reversed) false else null
            val request=MixedSplitRequest(SplitSourceRef(mapId,"content://notes/map","Graph"),
                SplitSourceRef(noteId,"content://notes/note"),
                MapSplitLayout(mapPane,focus,.37f,sideBySide,reversed),camera,
                Editor.ReadingPosition(1.25,17.0,27.0,view))
            val bytes=SplitOpenCodec.encodeMixed(request)
            val json=JSONObject(String(bytes,Charsets.UTF_8))
            assertEquals("xnotes-split",json.getString("format"));assertEquals(3,json.getInt("version"))
            val mapIndex=if(mapPane==Pane.PRIMARY) 0 else 1
            val mapEntry=json.getJSONArray("panes").getJSONObject(mapIndex)
            val noteEntry=json.getJSONArray("panes").getJSONObject(1-mapIndex)
            assertEquals("map",mapEntry.getString("kind"));assertEquals(mapId,mapEntry.getString("documentId"))
            assertEquals("note",noteEntry.getString("kind"));assertEquals(noteId,noteEntry.getString("documentId"))
            assertFalse(noteEntry.has("title"))
            if(sideBySide==null) assertFalse(json.getJSONObject("layout").has("sideBySide"))
            assertEquals(request,(SplitOpenCodec.parse(bytes) as? SplitOpenRequest.Mixed)?.request)
            assertEquals(request,(SplitOpenCodec.read(ByteArrayInputStream(bytes)) as? SplitOpenRequest.Mixed)?.request)
        }
        assertNotNull(SplitOpenCodec.parse(valid().toString().toByteArray(Charsets.UTF_8)))
    }

    @Test fun explicitJsonNullCannotBecomeMissingRequiredDataOrAnInheritedOverride() {
        assertSame(JSONObject.NULL,JSONObject("{\"value\":null}").get("value"))
        val changes=listOf<Pair<String,(JSONObject)->Unit>>(
            "format" to {it.put("format",JSONObject.NULL)},
            "version" to {it.put("version",JSONObject.NULL)},
            "panes" to {it.put("panes",JSONObject.NULL)},
            "layout" to {it.put("layout",JSONObject.NULL)},
            "map kind" to {map(it).put("kind",JSONObject.NULL)},
            "map id" to {map(it).put("documentId",JSONObject.NULL)},
            "map uri" to {map(it).put("uri",JSONObject.NULL)},
            "optional map title" to {map(it).put("title",JSONObject.NULL)},
            "optional map camera" to {map(it).put("camera",JSONObject.NULL)},
            "note kind" to {note(it).put("kind",JSONObject.NULL)},
            "note id" to {note(it).put("documentId",JSONObject.NULL)},
            "note uri" to {note(it).put("uri",JSONObject.NULL)},
            "optional note title" to {note(it).put("title",JSONObject.NULL)},
            "position" to {note(it).put("position",JSONObject.NULL)},
            "zoom" to {position(it).put("zoom",JSONObject.NULL)},
            "x" to {position(it).put("x",JSONObject.NULL)},
            "y" to {position(it).put("y",JSONObject.NULL)},
            "view" to {position(it).put("view",JSONObject.NULL)},
            "override boolean" to {position(it).getJSONObject("view").put("verticalScroll",JSONObject.NULL)},
            "ratio" to {layout(it).put("ratio",JSONObject.NULL)},
            "focus" to {layout(it).put("focusedPane",JSONObject.NULL)},
            "reversed" to {layout(it).put("reversed",JSONObject.NULL)},
            "optional direction" to {layout(it).put("sideBySide",JSONObject.NULL)},
        )
        for((label,change) in changes) {val json=valid();change(json);rejected(json,label)}
    }

    @Test fun illegalNumbersAndBooleansAreRejectedWithoutStringCoercionOrRangeRepair() {
        val changes=listOf<Pair<String,(JSONObject)->Unit>>(
            "string version" to {it.put("version","3")},
            "fractional version" to {it.put("version",3.5)},
            "boolean version" to {it.put("version",true)},
            "string ratio" to {layout(it).put("ratio","0.37")},
            "boolean ratio" to {layout(it).put("ratio",false)},
            "string zoom" to {position(it).put("zoom","1.25")},
            "zero zoom" to {position(it).put("zoom",0)},
            "boolean x" to {position(it).put("x",false)},
            "string y" to {position(it).put("y","27")},
            "string reversed" to {layout(it).put("reversed","true")},
            "number reversed" to {layout(it).put("reversed",1)},
            "string direction" to {layout(it).put("sideBySide","false")},
            "number direction" to {layout(it).put("sideBySide",0)},
            "string override boolean" to {position(it).getJSONObject("view").put("verticalScroll","false")},
            "number override boolean" to {position(it).getJSONObject("view").put("keepImages",1)},
            "string override integer" to {position(it).getJSONObject("view").put("contrast","145")},
            "fractional override integer" to {position(it).getJSONObject("view").put("contrast",145.5)},
            "out of range override" to {position(it).getJSONObject("view").put("contrast",201)},
            "invalid rotation" to {position(it).getJSONObject("view").put("rotation",45)},
            "unknown mode" to {position(it).getJSONObject("view").put("mode","future-mode")},
        )
        for((label,change) in changes) {val json=valid();change(json);rejected(json,label)}
        val nonFinite=valid().toString().replace("\"ratio\":0.37","\"ratio\":1e999")
        assertTrue(nonFinite.contains("1e999"))
        assertNull(SplitOpenCodec.parse(nonFinite.toByteArray(Charsets.UTF_8)))
    }

    @Test fun brokenVersionThreeWithCompleteLegacyFieldsNeverFallsBackToNative() {
        for(change in listOf<(JSONObject)->Unit>(
            {note(it).put("position",JSONObject.NULL)},
            {note(it).put("kind","map")},
            {note(it).put("kind","canvas")},
            {it.put("version",4)},
            {it.remove("format")},
        )) {
            val json=valid().put("primaryPath","content://notes/old-a").put("secondPath","content://notes/old-b")
                .put("primaryId",mapId).put("secondId",noteId).put("primaryCanvas",false).put("secondCanvas",false)
            change(json);rejected(json,"typed data with complete legacy fields")
        }
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
