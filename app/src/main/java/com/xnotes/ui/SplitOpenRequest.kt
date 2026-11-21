package com.xnotes.ui

import com.xnotes.platform.ViewOverridesJson
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import org.json.JSONObject
import org.json.JSONTokener

internal sealed interface SplitOpenRequest {
    data class Native(val request:NativeSplitOpenRequest):SplitOpenRequest
    data object LegacyMapSplit:SplitOpenRequest
}

/** Arrangement descriptions are not capabilities to read, save or adopt either source. */
internal object SplitOpenCodec {
    const val MAX_BYTES=NativeSplitOpenParser.MAX_BYTES
    private const val MAX_DEPTH=32

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
            3->if(root.opt("format")=="xnotes-split") SplitOpenRequest.LegacyMapSplit else null
            else->null
        }
    } catch(_:Exception) {null}

    fun encodeNative(request:NativeSplitOpenRequest):ByteArray {
        val layout=request.layout
        require(layout.ratio.isFinite() && layout.ratio in 0.18f..0.82f)
        for(source in listOf(request.first,request.second)) {
            require(source.uri.isNotBlank());require(source.uri.length<=MAX_BYTES && Charsets.UTF_8.newEncoder().canEncode(source.uri))
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

    private fun number(json:JSONObject,key:String):Double {
        val value=json.get(key) as? Number ?: error("Expected number: $key")
        return value.toDouble().also {require(it.isFinite())}
    }
    private fun integer(json:JSONObject,key:String):Int {
        val value=number(json,key)
        require(value>=Int.MIN_VALUE.toDouble() && value<=Int.MAX_VALUE.toDouble() && value==value.toInt().toDouble())
        return value.toInt()
    }
}
