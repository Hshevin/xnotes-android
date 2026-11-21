package com.xnotes.ui

import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.model.Rgba
import com.xnotes.core.util.DocumentKind
import com.xnotes.core.model.NativeRestoreHint
import com.xnotes.platform.ViewOverridesJson
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
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
        require(string(root,"format")=="xnotes-last-presentation")
        val version=integer(root,"version");require(version in 1..3)
        when(string(root,"state")) {
            "home"->{require(version==1);keys(root,setOf("format","version","state"));LastPresentation.Home}
            "mixed","map","network"->LastPresentation.Home
            "native"->{require(version>=2);readNative(root,version).also(LastPresentationContract::validateNative)}
            else->null
        }
    } catch(_:Exception) {null}

    fun encode(value:LastPresentation):ByteArray {
        // v1/v2 keep their strict contracts; only a description carrying a stored lock needs v3.
        val views=when(value) {
            is LastPresentation.Native->value.slots.mapNotNull {(it as? ColdNativeSlot.Note)?.view ?: (it as? ColdNativeSlot.Shared)?.view}
            else->emptyList()
        }
        val version=when {
            views.any {it.zoomLocked!=null}->3
            value===LastPresentation.Home->1
            else->2
        }
        val root=JSONObject().put("format","xnotes-last-presentation").put("version",version)
        when(value) {
            LastPresentation.Home->root.put("state","home")
            is LastPresentation.Native->{
                LastPresentationContract.validateNative(value)
                val slots=org.json.JSONArray()
                for(slot in value.slots) {
                    val item=JSONObject().put("pane",slot.pane.name)
                    when(slot) {
                        is ColdNativeSlot.Home->item.put("kind","home")
                        is ColdNativeSlot.Note->{item.put("kind","note").put("hint",writeHint(slot.hint));slot.view?.let {item.put("view",writeNativeView(it))}}
                        is ColdNativeSlot.Canvas->{item.put("kind","canvas").put("hint",writeHint(slot.hint));slot.view?.let {
                            item.put("view",JSONObject().put("cx",it.centerX).put("cy",it.centerY).put("zoom",it.zoom))}}
                        is ColdNativeSlot.Shared->{item.put("kind","shared").put("ownerPane",slot.ownerPane.name);slot.view?.let {item.put("view",writeNativeView(it))}}
                    }
                    slots.put(item)
                }
                val layout=JSONObject().put("focusedPane",value.layout.focusedPane.name).put("ratio",value.layout.ratio.toDouble())
                    .put("reversed",value.layout.reversed)
                value.layout.sideBySide?.let {layout.put("sideBySide",it)}
                root.put("state","native").put("slots",slots).put("layout",layout)
            }
        }
        return root.toString().toByteArray(Charsets.UTF_8).also {require(it.size<=MAX_BYTES)}
    }

    private fun readHint(value:MarkerObject,kind:DocumentKind):NativeRestoreHint {
        keys(value,setOf("kind","documentId","path"));require(string(value,"kind")==kind.name.lowercase())
        val path=required(value,"path").let {if(it==null) null else it as? String ?: error("Expected nullable path")}
        return NativeRestoreHint(string(value,"documentId"),path,kind).also {LastPresentationContract.validateHint(it,kind)}
    }
    private fun writeHint(value:NativeRestoreHint)=JSONObject().put("kind",value.kind.name.lowercase())
        .put("documentId",value.documentId).put("path",value.path ?: JSONObject.NULL)
    private fun readPosition(value:MarkerObject,extra:Set<String> = emptySet()):Editor.ReadingPosition {
        keys(value,setOf("zoom","x","y","view")+extra)
        return Editor.ReadingPosition(bounded(value,"zoom","0.05","20"),number(value,"x"),number(value,"y"),readView(obj(value,"view")))
    }
    private fun writePosition(value:Editor.ReadingPosition)=JSONObject().put("zoom",value.zoom).put("x",value.x).put("y",value.y)
        .put("view",ViewOverridesJson.write(JSONObject(),value.overrides))
    private fun readNativeView(value:MarkerObject,version:Int)=ColdNativeView(
        readPosition(value,setOf("fitWidth")+if(version>=3) setOf("zoomLocked") else emptySet()),boolean(value,"fitWidth"),
        optional(value,"zoomLocked") {boolean(value,it)})
    private fun writeNativeView(value:ColdNativeView)=writePosition(value.position).put("fitWidth",value.fitWidth)
        .also {json->value.zoomLocked?.let {json.put("zoomLocked",it)}}
    private fun readNative(root:MarkerObject,version:Int):LastPresentation.Native {
        keys(root,setOf("format","version","state","slots","layout"))
        val array=required(root,"slots") as? List<*> ?: error("Expected native slots")
        require(array.size in 1..2)
        val slots=array.map {item->
            val json=objectValue(item);val pane=Pane.valueOf(string(json,"pane"))
            when(string(json,"kind")) {
                "home"->{keys(json,setOf("kind","pane"));ColdNativeSlot.Home(pane)}
                "note"->{keys(json,setOf("kind","pane","hint","view"));ColdNativeSlot.Note(pane,readHint(obj(json,"hint"),DocumentKind.NOTE),optional(json,"view") {readNativeView(obj(json,it),version)})}
                "canvas"->{
                    keys(json,setOf("kind","pane","hint","view"))
                    ColdNativeSlot.Canvas(pane,readHint(obj(json,"hint"),DocumentKind.CANVAS),optional(json,"view") {
                        val view=obj(json,it);keys(view,setOf("cx","cy","zoom"));ColdCanvasView(number(view,"cx"),number(view,"cy"),number(view,"zoom"))})
                }
                "shared"->{keys(json,setOf("kind","pane","ownerPane","view"));ColdNativeSlot.Shared(pane,Pane.valueOf(string(json,"ownerPane")),optional(json,"view") {readNativeView(obj(json,it),version)})}
                else->error("Unknown native slot")
            }
        }
        val layout=obj(root,"layout");keys(layout,setOf("focusedPane","ratio","sideBySide","reversed"))
        return LastPresentation.Native(slots,ColdNativeLayout(Pane.valueOf(string(layout,"focusedPane")),
            bounded(layout,"ratio","0.18","0.82").toFloat(),optional(layout,"sideBySide") {boolean(layout,it)},boolean(layout,"reversed")))
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
                    char=='"'->return out.toString().also(LastPresentationContract::text)
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
