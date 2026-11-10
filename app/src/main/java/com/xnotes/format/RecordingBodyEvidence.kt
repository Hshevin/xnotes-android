package com.xnotes.format

import com.xnotes.core.util.DocumentKind
import java.io.File
import java.io.InputStreamReader
import java.util.Collections
import java.util.zip.ZipFile

/** Audio associations read from one immutable encoded body, never from a live model. */
internal class RecordingBodyEvidence private constructor(
    val kind:DocumentKind,val documentId:String,val bodySha256:String,val recordings:Map<Int,String>,
) {
    companion object {
        fun inspect(file:File,checkActive:()->Unit={}):RecordingBodyEvidence {
            val cancelled={checkActive();false}
            val before=BundleMigration.hash(file,cancelled)
            val identity=requireNotNull(BundleIdentity.read(file)) {"Recording body has no durable identity"}
            val payloads=BundleEntries.digests(file,cancelled)
            val audio=linkedMapOf<Int,String>()
            var format:String?=null
            ZipFile(file).use {zip->
                val manifest=requireNotNull(zip.getEntry("manifest.json"))
                zip.getInputStream(manifest).use {input->InputStreamReader(BundleEntries.cancellable(input,cancelled),Charsets.UTF_8).use {reader->
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
                                audio[number]=requireNotNull(payloads[name]) {"Missing recording payload"}
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
                }}
            }
            val kind=when(format) {"xnote"->DocumentKind.NOTE;"xcanvas"->DocumentKind.CANVAS;else->error("Invalid recording body kind")}
            checkActive();require(BundleMigration.hash(file,cancelled)==before) {"Recording body changed during inspection"}
            return RecordingBodyEvidence(kind,identity.documentId,before,Collections.unmodifiableMap(audio))
        }
    }
}
