package com.xnotes.platform

import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.model.Rgba
import com.xnotes.format.JsonPull
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Legacy display/view hints. A decoded sidecar never authorizes writing an original file. */
internal object ProtectedNoteMetadata {
    const val MAX_BYTES=64*1024
    fun parse(bytes:ByteArray):SessionStore.Metadata {
        require(bytes.size<=MAX_BYTES)
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val parser=JsonPull(java.io.StringReader(text),strict=true,tokenLimit=MAX_BYTES)
        val values=hashMapOf<String,Any>()
        val booleanKeys=setOf("dirty","zoomLocked","verticalScroll","freeMovement","keepImages","scrollbar")
        val coordinateKeys=setOf("zoom","scrollX","scrollY")
        val integerKeys=setOf("contrast","invert","brightness","sepia","rotation")
        val stringKeys=setOf("path","displayName","mode","multiply","screen")
        parser.beginObject()
        while(parser.hasNext()) {
            val key=parser.nextName();require(!values.containsKey(key)) {"Duplicate session field"}
            values[key]=when(key) {
                in booleanKeys->{require(parser.peek()==JsonPull.Token.BOOLEAN);parser.nextBoolean()}
                in coordinateKeys->{require(parser.peek()==JsonPull.Token.NUMBER);parser.nextDouble().also {require(it.isFinite())}}
                in integerKeys->{require(parser.peek()==JsonPull.Token.NUMBER);parser.nextInt()}
                in stringKeys->{require(parser.peek()==JsonPull.Token.STRING);parser.nextString().also {
                    require(Charsets.UTF_8.newEncoder().canEncode(it))
                }}
                else->error("Unknown session field")
            }
        }
        parser.endObject();require(parser.peek()==JsonPull.Token.END_DOCUMENT)
        fun boolean(key:String)=values[key] as? Boolean ?: error("Missing session boolean")
        fun number(key:String)=values[key] as? Double ?: error("Missing session number")
        fun integer(key:String,range:IntRange)=(values[key] as? Int)?.also {require(it in range)}
        fun color(key:String)=(values[key] as? String)?.let {
            require(Regex("#[a-fA-F0-9]{6}").matches(it));requireNotNull(Rgba.fromHex(it))
        }
        val mode=(values["mode"] as? String)?.let {id->ViewingMode.entries.single {it.id==id}}
        val rotation=(values["rotation"] as? Int)?.also {require(it in setOf(0,90,180,270))}
        val path=(values["path"] as? String)?.also {require(it.isNotBlank())}
        return SessionStore.Metadata(path,values["displayName"] as? String,boolean("dirty"),number("zoom").also {require(it>=0)},
            number("scrollX"),number("scrollY"),boolean("zoomLocked"),ViewOverrides(mode,
                values["verticalScroll"] as? Boolean,values["freeMovement"] as? Boolean,
                integer("contrast",0..200),integer("invert",0..100),integer("brightness",0..200),integer("sepia",0..200),
                color("multiply"),color("screen"),values["keepImages"] as? Boolean,rotation,values["scrollbar"] as? Boolean))
    }
}
