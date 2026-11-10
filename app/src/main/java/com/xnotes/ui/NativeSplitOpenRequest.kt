package com.xnotes.ui

import com.xnotes.canvas.ViewOverrides
import com.xnotes.platform.ViewOverridesJson
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import org.json.JSONObject

internal data class NativeSplitOpenRequest(val first:NativeSplitSource,val second:NativeSplitSource,val layout:NativeSplitOpenLayout)

/** The saved arrangement is read once into a bounded, immutable request; document IO comes later. */
internal object NativeSplitOpenParser {
    const val MAX_BYTES=1024*1024
    fun read(input:InputStream,allowLegacyFormat:Boolean=false):NativeSplitOpenRequest? = try {
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {
            val count=input.read(buffer);if(count<0) break
            require(output.size()+count<=MAX_BYTES);output.write(buffer,0,count)
        }
        parse(output.toByteArray(),allowLegacyFormat)
    } catch(_:Exception) {null}

    fun parse(bytes:ByteArray,allowLegacyFormat:Boolean=false):NativeSplitOpenRequest? = try {
        require(bytes.size<=MAX_BYTES)
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val json=JSONObject(text)
        val rawVersion=if(json.has("version")) json.get("version") else 1
        require(rawVersion is Number && rawVersion.toDouble().isFinite() && rawVersion.toDouble()==rawVersion.toInt().toDouble())
        val version=rawVersion.toInt();require(version in 1..2)
        if(json.has("format")) require(json.get("format")=="xnotes-split") else require(allowLegacyFormat && version==1)
        require(listOf("panes","documents","primaryKind","secondKind","primaryType","secondType").none {json.has(it)})
        fun bool(key:String,fallback:Boolean):Boolean {
            if(!json.has(key)) return fallback
            return (json.get(key) as? Boolean) ?: error("Invalid boolean")
        }
        require(!bool("primaryCanvas",false) && !bool("secondCanvas",false))
        fun number(key:String,fallback:Double):Double {
            if(!json.has(key)) return fallback
            val value=json.get(key) as? Number ?: error("Invalid number")
            return value.toDouble().also {require(it.isFinite())}
        }
        fun source(prefix:String,pathKey:String):NativeSplitSource {
            val uri=(json.get(pathKey) as? String)?.takeIf {it.isNotBlank()} ?: error("Missing source")
            val id=if(json.has(prefix+"Id")) ((json.get(prefix+"Id") as? String) ?: error("Invalid identity")).takeIf {it.isNotBlank()} else null
            id?.let {require(UUID.fromString(it).toString()==it)}
            return NativeSplitSource(uri,expectedId=id)
        }
        fun position(prefix:String,z:String,x:String,y:String):Editor.ReadingPosition {
            val overrides=if(json.has(prefix+"View")) {
                val raw=json.getJSONObject(prefix+"View")
                raw.keys().forEach {key->(raw.get(key) as? Number)?.let {require(it.toDouble().isFinite())}}
                ViewOverridesJson.read(raw)
            } else ViewOverrides()
            return Editor.ReadingPosition(number(z,1.0).coerceIn(.05,20.0),number(x,0.0),number(y,0.0),overrides)
        }
        val focused=if(json.has("focused")) json.get("focused") as? String ?: error("Invalid focus") else Pane.PRIMARY.name
        require(focused in Pane.entries.map {it.name})
        NativeSplitOpenRequest(source("primary","primaryPath"),source("second","secondPath"),NativeSplitOpenLayout(
            number("ratio",.5).toFloat().coerceIn(.18f,.82f),
            if(json.has("sideBySide")) bool("sideBySide",false) else null,
            bool("reversed",false),Pane.valueOf(focused),
            position("primary","primaryZoom","primaryX","primaryY"),position("second","zoom","x","y")))
    } catch(_:Exception) {null}
}
