package com.xnotes.platform

import com.xnotes.format.JsonPull
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Legacy Canvas display hints only; no original-source write authority. */
internal object ProtectedCanvasMetadata {
    const val MAX_BYTES=64*1024
    fun parse(bytes:ByteArray):CanvasSessionStore.Metadata {
        require(bytes.size<=MAX_BYTES)
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val parser=JsonPull(java.io.StringReader(text),strict=true,tokenLimit=MAX_BYTES)
        val values=hashMapOf<String,Any>()
        parser.beginObject()
        while(parser.hasNext()) {
            val key=parser.nextName();require(!values.containsKey(key)) {"Duplicate Canvas session field"}
            values[key]=when(key) {
                "dirty"->{require(parser.peek()==JsonPull.Token.BOOLEAN);parser.nextBoolean()}
                "path","display_name"->{
                    require(parser.peek()==JsonPull.Token.STRING)
                    parser.nextString().also {require(Charsets.UTF_8.newEncoder().canEncode(it))}
                }
                else->error("Unknown Canvas session field")
            }
        }
        parser.endObject();require(parser.peek()==JsonPull.Token.END_DOCUMENT)
        return CanvasSessionStore.Metadata(values["path"] as? String,values["display_name"] as? String,
            values["dirty"] as? Boolean ?: error("Missing Canvas session dirty flag"))
    }
}
