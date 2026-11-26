package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.util.DocumentKind
import okio.buffer
import okio.use

/** Audio associations read from one immutable encoded body, never from a live model. */
class RecordingBodyEvidence private constructor(
    val kind:DocumentKind,val documentId:String,val recordings:Map<Int,String>,
) {
    companion object {
        /** Called before adopting a freshly decoded model. Only extracted recording payloads need reading. */
        fun decoded(note: com.xnotes.core.model.Document?, canvas: com.xnotes.core.infinite.InfiniteDocument?,
                    checkActive: () -> Unit = {}): RecordingBodyEvidence? {
            val items = note?.pages?.asSequence()?.flatMap { it.items.asSequence() }
                ?: requireNotNull(canvas).items.asSequence()
            val hashes = mutableMapOf<Int, String>()
            for (item in items.filterIsInstance<com.xnotes.core.model.AudioItem>()) {
                checkActive()
                if (item.rid <= 0 || item.rid in hashes) return null
                hashes[item.rid] = BundleMigration.hash(item.audio.file) { checkActive(); false }
            }
            val id = (note?.identity ?: requireNotNull(canvas).identity).documentId
            return RecordingBodyEvidence(if (note == null) DocumentKind.CANVAS else DocumentKind.NOTE, id, hashes)
        }

        fun inspect(file:AssetFile,checkActive:()->Unit={}):RecordingBodyEvidence {
            val cancelled={checkActive();false}
            val identity=requireNotNull(readBundleIdentity(file)) {"Recording body has no durable identity"}
            val audio=linkedMapOf<Int,String>()
            var format:String?=null
            bundleDirectory(file).use {zip->
                val manifest=requireNotNull(zip.entry("manifest.json"))
                manifest.open().use {input->
                    val reader=jsonChars(input.cancellable(cancelled))
                    val p=JsonPull(reader,strict=true,tokenLimit=Int.MAX_VALUE)
                    fun items() {
                        p.beginArray()
                        while(p.hasNext()) {
                            checkActive();p.beginObject()
                            val keys=hashSetOf<String>();var kind:String?=null;var rid:Int?=null;var asset:String?=null
                            while(p.hasNext()) {
                                val key=p.nextName();require(keys.add(key)) {"Duplicate recording item field"}
                                when(key) {
                                    "kind"->{require(p.peek()==JsonPull.Token.STRING);kind=p.nextString()}
                                    "rid"->{require(p.peek()==JsonPull.Token.NUMBER);rid=p.nextNumberLiteral().toInt()}
                                    "asset"->{require(p.peek()==JsonPull.Token.STRING);asset=p.nextString()}
                                    else->p.skipValue()
                                }
                            }
                            p.endObject()
                            if(kind=="audio") {
                                val number=requireNotNull(rid).also {require(it>0 && it !in audio) {"Ambiguous recording ID"}}
                                val name=requireNotNull(asset).also {require(it.startsWith("assets/audio-") && !it.contains(".."))}
                                audio[number]=zip.open(name).use { BundleMigration.digest(it, cancelled) }
                            }
                        }
                        p.endArray()
                    }
                    p.beginObject();val keys=hashSetOf<String>()
                    while(p.hasNext()) {
                        checkActive();val key=p.nextName();require(keys.add(key))
                        when(key) {
                            "format"->format=p.nextString()
                            "items"->items()
                            "pages"->{
                                p.beginArray()
                                while(p.hasNext()) {
                                    p.beginObject();val pageKeys=hashSetOf<String>()
                                    while(p.hasNext()) {val k=p.nextName();require(pageKeys.add(k));if(k=="items") items() else p.skipValue()}
                                    p.endObject()
                                }
                                p.endArray()
                            }
                            else->p.skipValue()
                        }
                    }
                    p.endObject();require(p.peek()==JsonPull.Token.END_DOCUMENT)
                }
            }
            val kind = when { format == "xnote" -> DocumentKind.NOTE; CanvasCodec.isCanvasFormat(format) -> DocumentKind.CANVAS; else -> error("Invalid recording body kind") }
            checkActive()
            return RecordingBodyEvidence(kind,identity.documentId,audio.toMap())
        }
    }
}
