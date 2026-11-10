package com.xnotes.ui

import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.model.Rgba
import com.xnotes.mindmap.MapPresentationCamera
import com.xnotes.mindmap.MapSplitLayout
import com.xnotes.mindmap.MindMapView
import com.xnotes.platform.ViewOverridesJson
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class SplitSourceRef(val documentId:String,val uri:String,val title:String?=null)
internal data class MixedSplitRequest(val map:SplitSourceRef,val native:SplitSourceRef,val layout:MapSplitLayout,
    val mapCamera:MapPresentationCamera?,val nativePosition:Editor.ReadingPosition)
internal sealed interface SplitOpenRequest {
    data class Native(val request:NativeSplitOpenRequest):SplitOpenRequest
    data class Mixed(val request:MixedSplitRequest):SplitOpenRequest
}

/** Arrangement descriptions are not capabilities to read, save or adopt either source. */
internal object SplitOpenCodec {
    const val MAX_BYTES=NativeSplitOpenParser.MAX_BYTES
    private const val MAX_DEPTH=32
    private val viewKeys=setOf("mode","verticalScroll","freeMovement","contrast","invert","brightness","sepia",
        "multiply","screen","keepImages","rotation","scrollbar")

    fun read(input:InputStream,allowLegacyFormat:Boolean=false):SplitOpenRequest? = try {
        val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {
            val count=input.read(buffer)
            if(count<0) break
            if(count==0) {
                val next=input.read();if(next<0) break
                require(output.size()<MAX_BYTES);output.write(next)
            } else {
                require(count<=MAX_BYTES-output.size());output.write(buffer,0,count)
            }
        }
        parse(output.toByteArray(),allowLegacyFormat)
    } catch(_:Exception) {null}

    fun parse(bytes:ByteArray,allowLegacyFormat:Boolean=false):SplitOpenRequest? = try {
        require(bytes.size<=MAX_BYTES)
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        checkJsonEnvelope(text)
        val tokens=JSONTokener(text)
        val root=tokens.nextValue() as? JSONObject ?: error("Expected arrangement object")
        require(tokens.nextClean()=='\u0000')
        val version=if(root.has("version")) integer(root,"version") else 1
        when(version) {
            1,2->NativeSplitOpenParser.parse(bytes,allowLegacyFormat)?.let {SplitOpenRequest.Native(it)}
            3->SplitOpenRequest.Mixed(readMixed(root))
            else->null
        }
    } catch(_:Exception) {null}

    fun encodeMixed(request:MixedSplitRequest):ByteArray {
        validate(request)
        fun source(value:SplitSourceRef,kind:String)=JSONObject().put("kind",kind)
            .put("documentId",value.documentId).put("uri",value.uri).also {json->value.title?.let {json.put("title",it)}}
        val map=source(request.map,"map")
        request.mapCamera?.let {camera->map.put("camera",JSONObject()
            .put("zoom",camera.viewport.zoom.toDouble()).put("panX",camera.viewport.panX.toDouble())
            .put("panY",camera.viewport.panY.toDouble()).put("width",camera.width).put("height",camera.height)
            .put("density",camera.density.toDouble()))}
        val position=request.nativePosition
        val note=source(request.native,"note").put("position",JSONObject().put("zoom",position.zoom)
            .put("x",position.x).put("y",position.y).put("view",ViewOverridesJson.write(JSONObject(),position.overrides)))
        val panes=JSONArray()
        if(request.layout.mapPane==Pane.PRIMARY) {panes.put(map);panes.put(note)} else {panes.put(note);panes.put(map)}
        val layout=JSONObject().put("ratio",request.layout.ratio.toDouble()).put("focusedPane",request.layout.focusedPane.name)
            .put("reversed",request.layout.reversed)
        request.layout.sideBySide?.let {layout.put("sideBySide",it)}
        val bytes=JSONObject().put("format","xnotes-split").put("version",3).put("panes",panes)
            .put("layout",layout).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX_BYTES)
        return bytes
    }

    /** Keep the native recent arrangement in its existing v2 wire format. */
    fun encodeNative(request:NativeSplitOpenRequest):ByteArray {
        val layout=request.layout
        require(layout.ratio.isFinite() && layout.ratio in 0.18f..0.82f)
        for(source in listOf(request.first,request.second)) {
            require(source.uri.isNotBlank());validText(source.uri)
            source.expectedId?.let {require(UUID.fromString(it).toString()==it)}
        }
        val json=JSONObject().put("format","xnotes-split").put("version",2)
            .put("primaryOpen",true).put("secondOpen",true).put("primaryCanvas",false).put("secondCanvas",false)
            .put("primaryPath",request.first.uri).put("secondPath",request.second.uri)
            .put("ratio",layout.ratio.toDouble()).put("focused",layout.focused.name).put("reversed",layout.reversed)
        request.first.expectedId?.let {json.put("primaryId",it)}
        request.second.expectedId?.let {json.put("secondId",it)}
        layout.sideBySide?.let {json.put("sideBySide",it)}
        fun position(value:Editor.ReadingPosition?,prefix:String,zoom:String,x:String,y:String) {
            if(value==null) return
            require(value.zoom.isFinite() && value.zoom in .05..20.0 && value.x.isFinite() && value.y.isFinite())
            val view=value.overrides
            view.contrast?.let {require(it in 0..200)};view.invert?.let {require(it in 0..100)}
            view.brightness?.let {require(it in 0..200)};view.sepia?.let {require(it in 0..200)}
            view.rotation?.let {require(it in listOf(0,90,180,270))}
            for(color in listOfNotNull(view.multiply,view.screen))
                require(color.r in 0..255 && color.g in 0..255 && color.b in 0..255 && color.a==255)
            json.put(zoom,value.zoom).put(x,value.x).put(y,value.y).put(prefix+"View",ViewOverridesJson.write(JSONObject(),view))
        }
        position(layout.firstPosition,"primary","primaryZoom","primaryX","primaryY")
        position(layout.secondPosition,"second","zoom","x","y")
        val bytes=json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX_BYTES)
        return bytes
    }

    /** Reject lenient token/comment syntax before either platform's recursive JSON parser runs. */
    internal fun checkJsonEnvelope(text:String) {
        require(text.length<=MAX_BYTES)
        var depth=0;var quoted=false;var escaped=false;var unicodeLeft=0;var literalStart=-1
        fun finishLiteral(end:Int) {
            if(literalStart<0) return
            val value=text.substring(literalStart,end)
            require(value in listOf("true","false","null") || jsonNumber.matches(value))
            literalStart=-1
        }
        for((index,char) in text.withIndex()) {
            if(char.code<32) require(!quoted && char in "\t\r\n")
            if(quoted) {
                when {
                    unicodeLeft>0->{require(char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F');unicodeLeft--}
                    escaped->{require(char in "\"\\/bfnrtu");unicodeLeft=if(char=='u') 4 else 0;escaped=false}
                    char=='\\'->escaped=true
                    char=='"'->quoted=false
                }
            } else when(char) {
                '"'->{require(literalStart<0);quoted=true}
                '{','['->{finishLiteral(index);depth++;require(depth<=MAX_DEPTH)}
                '}',']'->{finishLiteral(index);depth--;require(depth>=0)}
                ',',':',' ','\t','\r','\n'->finishLiteral(index)
                else->{
                    // No comments, single quotes, unquoted keys or embedded quote delimiters.
                    require(char in "0123456789eE+-.truefalsn")
                    if(literalStart<0) literalStart=index
                    require(index-literalStart<128)
                }
            }
        }
        finishLiteral(text.length)
        require(depth==0 && !quoted && !escaped && unicodeLeft==0)
    }
    private val jsonNumber=Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    private fun readMixed(root:JSONObject):MixedSplitRequest {
        keys(root,setOf("format","version","panes","layout"))
        require(string(root,"format")=="xnotes-split")
        val panes=root.get("panes") as? JSONArray ?: error("Expected panes")
        require(panes.length()==2)
        val first=panes.get(0) as? JSONObject ?: error("Expected pane")
        val second=panes.get(1) as? JSONObject ?: error("Expected pane")
        val kinds=listOf(string(first,"kind"),string(second,"kind"))
        require(kinds.toSet()==setOf("map","note"))
        val mapPane=if(kinds[0]=="map") Pane.PRIMARY else Pane.SECONDARY
        val map=if(mapPane==Pane.PRIMARY) first else second
        val note=if(mapPane==Pane.PRIMARY) second else first
        keys(map,setOf("kind","documentId","uri","title","camera"))
        keys(note,setOf("kind","documentId","uri","title","position"))
        fun source(json:JSONObject)=SplitSourceRef(string(json,"documentId"),string(json,"uri"),optional(json,"title") {string(json,it)})
        val layout=objectValue(root,"layout")
        keys(layout,setOf("ratio","focusedPane","sideBySide","reversed"))
        val ratio=number(layout,"ratio").also {require(it in 0.18..0.82)}.toFloat()
        val focus=Pane.valueOf(string(layout,"focusedPane"))
        val camera=optional(map,"camera") {
            val value=objectValue(map,it)
            keys(value,setOf("zoom","panX","panY","width","height","density"))
            val zoom=number(value,"zoom").also {require(it in .05..4.0)}
            MapPresentationCamera(MindMapView.Viewport(float(zoom),float(number(value,"panX")),float(number(value,"panY"))),
                integer(value,"width"),integer(value,"height"),float(number(value,"density")))
        }
        val position=objectValue(note,"position")
        keys(position,setOf("zoom","x","y","view"))
        val request=MixedSplitRequest(source(map),source(note),MapSplitLayout(mapPane,focus,ratio,
            optional(layout,"sideBySide") {boolean(layout,it)},boolean(layout,"reversed")),camera,
            Editor.ReadingPosition(number(position,"zoom"),number(position,"x"),number(position,"y"),readView(objectValue(position,"view"))))
        validate(request)
        return request
    }

    private fun readView(json:JSONObject):ViewOverrides {
        keys(json,viewKeys)
        fun bool(key:String)=optional(json,key) {boolean(json,it)}
        fun int(key:String)=optional(json,key) {integer(json,it)}
        fun color(key:String)=optional(json,key) {
            val value=string(json,it);require(Regex("#[0-9a-fA-F]{6}").matches(value))
            requireNotNull(Rgba.fromHex(value))
        }
        return ViewOverrides(mode=optional(json,"mode") {key->
            ViewingMode.entries.single {it.id==string(json,key)}
        },verticalScroll=bool("verticalScroll"),freeMovement=bool("freeMovement"),contrast=int("contrast"),invert=int("invert"),
            brightness=int("brightness"),sepia=int("sepia"),multiply=color("multiply"),screen=color("screen"),
            keepImages=bool("keepImages"),rotation=int("rotation"),scrollbar=bool("scrollbar"))
    }

    private fun validate(request:MixedSplitRequest) {
        for(source in listOf(request.map,request.native)) {
            require(UUID.fromString(source.documentId).toString()==source.documentId)
            require(source.uri.isNotBlank());validText(source.uri);source.title?.let(::validText)
        }
        require(request.map.documentId!=request.native.documentId && request.map.uri!=request.native.uri)
        require(!request.layout.nativeHome && request.layout.ratio.isFinite() && request.layout.ratio in 0.18f..0.82f)
        request.mapCamera?.let {
            require(it.viewport.zoom.isFinite() && it.viewport.zoom in .05f..4f && it.viewport.panX.isFinite() && it.viewport.panY.isFinite())
            require(it.width>0 && it.height>0 && it.density.isFinite() && it.density>0)
        }
        val position=request.nativePosition
        require(position.zoom.isFinite() && position.zoom in .05..20.0 && position.x.isFinite() && position.y.isFinite())
        val view=position.overrides
        view.contrast?.let {require(it in 0..200)};view.invert?.let {require(it in 0..100)}
        view.brightness?.let {require(it in 0..200)};view.sepia?.let {require(it in 0..200)}
        view.rotation?.let {require(it in listOf(0,90,180,270))}
        for(color in listOfNotNull(view.multiply,view.screen)) require(color.r in 0..255 && color.g in 0..255 && color.b in 0..255 && color.a==255)
    }

    private fun validText(value:String) {require(value.length<=MAX_BYTES && Charsets.UTF_8.newEncoder().canEncode(value))}
    private fun keys(json:JSONObject,allowed:Set<String>) {json.keys().forEach {require(it in allowed)}}
    private fun string(json:JSONObject,key:String)=json.get(key) as? String ?: error("Expected string: $key")
    private fun boolean(json:JSONObject,key:String)=json.get(key) as? Boolean ?: error("Expected boolean: $key")
    private fun objectValue(json:JSONObject,key:String)=json.get(key) as? JSONObject ?: error("Expected object: $key")
    private fun number(json:JSONObject,key:String):Double {
        val value=json.get(key) as? Number ?: error("Expected number: $key")
        return value.toDouble().also {require(it.isFinite())}
    }
    private fun integer(json:JSONObject,key:String):Int {
        val value=number(json,key)
        require(value>=Int.MIN_VALUE.toDouble() && value<=Int.MAX_VALUE.toDouble() && value==value.toInt().toDouble())
        return value.toInt()
    }
    private fun float(value:Double)=value.toFloat().also {require(it.isFinite())}
    private inline fun <T> optional(json:JSONObject,key:String,read:(String)->T):T?=if(json.has(key)) read(key) else null
}
