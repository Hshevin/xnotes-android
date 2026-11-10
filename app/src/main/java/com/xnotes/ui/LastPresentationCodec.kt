package com.xnotes.ui

import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.model.Rgba
import com.xnotes.core.util.DocumentKind
import com.xnotes.mindmap.MapNativeRestoreHint
import com.xnotes.mindmap.MapPresentationCamera
import com.xnotes.mindmap.MapSplitLayout
import com.xnotes.mindmap.MindMapView
import com.xnotes.platform.ViewOverridesJson
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import org.json.JSONObject

private typealias MarkerObject=Map<String,Any?>

/** Strict, bounded descriptions of local recovery; no original source is accessed. */
internal object LastPresentationCodec {
    const val MAX_BYTES=64*1024
    private const val MAX_DEPTH=32
    private val viewKeys=setOf("mode","verticalScroll","freeMovement","contrast","invert","brightness","sepia",
        "multiply","screen","keepImages","rotation","scrollbar")

    fun read(input:InputStream):LastPresentation? = try {
        val bytes=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {
            val count=input.read(buffer)
            if(count<0) break
            if(count==0) {
                val next=input.read();if(next<0) break
                require(bytes.size()<MAX_BYTES);bytes.write(next)
            } else {require(count<=MAX_BYTES-bytes.size());bytes.write(buffer,0,count)}
        }
        parse(bytes.toByteArray())
    } catch(_:Exception) {null}

    fun parse(bytes:ByteArray):LastPresentation? = try {
        require(bytes.size<=MAX_BYTES)
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root=objectValue(JsonReader(text).read())
        require(string(root,"format")=="xnotes-last-presentation" && integer(root,"version")==1)
        when(string(root,"state")) {
            "home"->{keys(root,setOf("format","version","state"));LastPresentation.Home}
            "mixed"->LastPresentation.Mixed(readMixed(root))
            else->null
        }
    } catch(_:Exception) {null}

    fun encode(value:LastPresentation):ByteArray {
        val root=JSONObject().put("format","xnotes-last-presentation").put("version",1)
        when(value) {
            LastPresentation.Home->root.put("state","home")
            is LastPresentation.Mixed->{
                val data=value.value;validate(data)
                val map=JSONObject().put("kind","map").put("documentId",data.map.documentId).put("uri",data.map.uri)
                data.map.title?.let {map.put("title",it)}
                val native=JSONObject().put("kind","note").put("documentId",data.nativeHint.documentId)
                    .put("path",data.nativeHint.path ?: JSONObject.NULL)
                val layout=JSONObject().put("mapPane",data.layout.mapPane.name).put("focusedPane",data.layout.focusedPane.name)
                    .put("ratio",data.layout.ratio.toDouble()).put("reversed",data.layout.reversed)
                data.layout.sideBySide?.let {layout.put("sideBySide",it)}
                root.put("state","mixed").put("map",map).put("native",native).put("layout",layout)
                data.mapCamera?.let {camera->root.put("camera",JSONObject().put("zoom",camera.viewport.zoom.toDouble())
                    .put("panX",camera.viewport.panX.toDouble()).put("panY",camera.viewport.panY.toDouble())
                    .put("width",camera.width).put("height",camera.height).put("density",camera.density.toDouble()))}
                data.nativePosition?.let {position->root.put("nativePosition",JSONObject().put("zoom",position.zoom)
                    .put("x",position.x).put("y",position.y).put("view",ViewOverridesJson.write(JSONObject(),position.overrides)))}
            }
        }
        return root.toString().toByteArray(Charsets.UTF_8).also {require(it.size<=MAX_BYTES)}
    }

    private fun readMixed(root:MarkerObject):ColdMixedPresentation {
        keys(root,setOf("format","version","state","map","native","layout","camera","nativePosition"))
        val map=obj(root,"map");keys(map,setOf("kind","documentId","uri","title"));require(string(map,"kind")=="map")
        val native=obj(root,"native");keys(native,setOf("kind","documentId","path"));require(string(native,"kind")=="note")
        val path=required(native,"path").let {if(it==null) null else it as? String ?: error("Expected nullable path")}
        val layout=obj(root,"layout");keys(layout,setOf("mapPane","focusedPane","ratio","sideBySide","reversed"))
        val ratio=bounded(layout,"ratio","0.18","0.82").toFloat()
        val camera=optional(root,"camera") {key->
            val camera=obj(root,key);keys(camera,setOf("zoom","panX","panY","width","height","density"))
            MapPresentationCamera(MindMapView.Viewport(float(bounded(camera,"zoom","0.05","4")),
                float(number(camera,"panX")),float(number(camera,"panY"))),integer(camera,"width"),integer(camera,"height"),
                float(number(camera,"density")))
        }
        val position=optional(root,"nativePosition") {key->
            val position=obj(root,key);keys(position,setOf("zoom","x","y","view"))
            Editor.ReadingPosition(bounded(position,"zoom","0.05","20"),number(position,"x"),number(position,"y"),readView(obj(position,"view")))
        }
        return ColdMixedPresentation(SplitSourceRef(string(map,"documentId"),string(map,"uri"),optional(map,"title") {string(map,it)}),
            MapNativeRestoreHint(string(native,"documentId"),path,DocumentKind.NOTE),
            MapSplitLayout(Pane.valueOf(string(layout,"mapPane")),Pane.valueOf(string(layout,"focusedPane")),ratio,
                optional(layout,"sideBySide") {boolean(layout,it)},boolean(layout,"reversed")),camera,position).also(::validate)
    }

    private fun readView(json:MarkerObject):ViewOverrides {
        keys(json,viewKeys)
        fun bool(key:String)=optional(json,key) {boolean(json,it)}
        fun int(key:String)=optional(json,key) {integer(json,it)}
        fun color(key:String)=optional(json,key) {
            val value=string(json,it);require(Regex("#[0-9a-fA-F]{6}").matches(value));requireNotNull(Rgba.fromHex(value))
        }
        return ViewOverrides(optional(json,"mode") {key->ViewingMode.entries.single {it.id==string(json,key)}},
            bool("verticalScroll"),bool("freeMovement"),int("contrast"),int("invert"),int("brightness"),int("sepia"),
            color("multiply"),color("screen"),bool("keepImages"),int("rotation"),bool("scrollbar"))
    }

    private fun validate(data:ColdMixedPresentation) {
        require(UUID.fromString(data.map.documentId).toString()==data.map.documentId)
        require(UUID.fromString(data.nativeHint.documentId).toString()==data.nativeHint.documentId)
        require(data.map.documentId!=data.nativeHint.documentId && data.nativeHint.kind==DocumentKind.NOTE)
        text(data.map.uri);require(data.map.uri.isNotBlank());data.map.title?.let(::text)
        data.nativeHint.path?.let {text(it);require(it.isNotBlank())}
        require(!data.layout.nativeHome && data.layout.ratio.isFinite() && data.layout.ratio in 0.18f..0.82f)
        data.mapCamera?.let {
            require(it.viewport.zoom.isFinite() && it.viewport.zoom in .05f..4f && it.viewport.panX.isFinite() && it.viewport.panY.isFinite())
            require(it.width>0 && it.height>0 && it.density.isFinite() && it.density>0)
        }
        data.nativePosition?.let {position->
            require(position.zoom.isFinite() && position.zoom in .05..20.0 && position.x.isFinite() && position.y.isFinite())
            val view=position.overrides
            view.contrast?.let {require(it in 0..200)};view.invert?.let {require(it in 0..100)}
            view.brightness?.let {require(it in 0..200)};view.sepia?.let {require(it in 0..200)}
            view.rotation?.let {require(it in listOf(0,90,180,270))}
            for(color in listOfNotNull(view.multiply,view.screen))
                require(color.r in 0..255 && color.g in 0..255 && color.b in 0..255 && color.a==255)
        }
    }

    private fun text(value:String) {require(value.length<=MAX_BYTES && Charsets.UTF_8.newEncoder().canEncode(value))}
    private fun keys(json:MarkerObject,allowed:Set<String>) {require(json.keys.all {it in allowed})}
    private fun required(json:MarkerObject,key:String):Any? {require(json.containsKey(key));return json[key]}
    private fun string(json:MarkerObject,key:String)=required(json,key) as? String ?: error("Expected string: $key")
    private fun boolean(json:MarkerObject,key:String)=required(json,key) as? Boolean ?: error("Expected boolean: $key")
    @Suppress("UNCHECKED_CAST")
    private fun objectValue(value:Any?):MarkerObject {
        require(value is Map<*,*> && value.keys.all {it is String});return value as MarkerObject
    }
    private fun obj(json:MarkerObject,key:String)=objectValue(required(json,key))
    private fun numeric(json:MarkerObject,key:String)=required(json,key) as? JsonNumber ?: error("Expected number: $key")
    private fun number(json:MarkerObject,key:String)=numeric(json,key).value.toDouble().also {require(it.isFinite())}
    private fun bounded(json:MarkerObject,key:String,minimum:String,maximum:String):Double {
        val value=numeric(json,key).value;require(value>=BigDecimal(minimum) && value<=BigDecimal(maximum))
        return value.toDouble().also {require(it.isFinite())}
    }
    private fun integer(json:MarkerObject,key:String)=numeric(json,key).value.intValueExact()
    private fun float(value:Double)=value.toFloat().also {require(it.isFinite())}
    private inline fun <T> optional(json:MarkerObject,key:String,read:(String)->T):T?=if(json.containsKey(key)) read(key) else null
    private data class JsonNumber(val value:BigDecimal)

    /** Grammar and decoded-key uniqueness do not depend on platform org.json behavior. */
    private class JsonReader(private val source:String) {
        private var at=0
        fun read():Any? {val result=value(0);space();require(at==source.length);return result}
        private fun value(depth:Int):Any? {
            space();require(at<source.length)
            return when(source[at]) {
                '{'->objectValue(depth+1)
                '['->arrayValue(depth+1)
                '"'->stringValue()
                't'->{literal("true");true}
                'f'->{literal("false");false}
                'n'->{literal("null");null}
                '-',in '0'..'9'->numberValue()
                else->error("Invalid JSON value")
            }
        }
        private fun objectValue(depth:Int):MarkerObject {
            require(depth<=MAX_DEPTH);at++;space()
            val fields=linkedMapOf<String,Any?>()
            if(take('}')) return fields
            while(true) {
                space();require(at<source.length && source[at]=='"')
                val key=stringValue();require(!fields.containsKey(key));space();expect(':')
                fields[key]=value(depth);space()
                if(take('}')) return fields
                expect(',')
            }
        }
        private fun arrayValue(depth:Int):List<Any?> {
            require(depth<=MAX_DEPTH);at++;space()
            val values=ArrayList<Any?>()
            if(take(']')) return values
            while(true) {values.add(value(depth));space();if(take(']')) return values;expect(',')}
        }
        private fun stringValue():String {
            expect('"');val out=StringBuilder()
            while(at<source.length) {
                val char=source[at++]
                when {
                    char=='"'->return out.toString().also(::text)
                    char=='\\'->{
                        require(at<source.length)
                        when(val escaped=source[at++]) {
                            '"','\\','/'->out.append(escaped)
                            'b'->out.append('\b')
                            'f'->out.append('\u000c')
                            'n'->out.append('\n')
                            'r'->out.append('\r')
                            't'->out.append('\t')
                            'u'->{
                                require(at+4<=source.length)
                                var code=0
                                repeat(4) {
                                    val hex=source[at++]
                                    require(hex in '0'..'9' || hex in 'a'..'f' || hex in 'A'..'F')
                                    code=(code shl 4)+hex.digitToInt(16)
                                }
                                out.append(code.toChar())
                            }
                            else->error("Invalid string escape")
                        }
                    }
                    else->{require(char.code>=32);out.append(char)}
                }
            }
            error("Unclosed JSON string")
        }
        private fun numberValue():JsonNumber {
            val start=at;take('-');require(at<source.length)
            if(!take('0')) {require(source[at] in '1'..'9');while(at<source.length && source[at] in '0'..'9') at++}
            if(take('.')) digits()
            if(at<source.length && source[at] in "eE") {at++;if(at<source.length && source[at] in "+-") at++;digits()}
            require(at-start<=128)
            return JsonNumber(BigDecimal(source.substring(start,at)))
        }
        private fun digits() {val start=at;while(at<source.length && source[at] in '0'..'9') at++;require(at>start)}
        private fun literal(value:String) {require(source.regionMatches(at,value,0,value.length));at+=value.length}
        private fun space() {while(at<source.length && source[at] in " \t\r\n") at++}
        private fun take(char:Char):Boolean=if(at<source.length && source[at]==char) {at++;true} else false
        private fun expect(char:Char) {require(take(char))}
    }
}
