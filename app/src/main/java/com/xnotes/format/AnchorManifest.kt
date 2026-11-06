package com.xnotes.format

import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Lossless insertion into an immutable UTF-8 source. Never constructs or reserializes ink models. */
internal object AnchorManifest {
    const val MAX_BYTES=512L*1024*1024
    const val MAX_ANCHORS=200_000
    private const val MAX_KEYS=4096
    private const val MAX_KEY_CHARS=1024
    private const val MAX_ACTIVE_KEYS=8192
    private const val MAX_ACTIVE_KEY_CHARS=262144
    data class Insertion(val position:Long,val text:String)
    data class Plan(val format:String,val insertions:List<Insertion>,val identities:Set<String>)
    private enum class Place { ROOT,PAGE,ITEM,OTHER }

    fun reader(input:InputStream,cancelled:()->Boolean={false}):InputStreamReader {
        val limited=object:FilterInputStream(input) {
            private var bytes=0L
            private fun count(n:Int):Int {
                check(!cancelled()) {"Anchor upgrade cancelled"}
                if(n>0) bytes+=n
                require(bytes<=MAX_BYTES) {"Manifest size limit exceeded"}
                return n
            }
            override fun read():Int {check(!cancelled());val n=super.read();count(if(n<0) 0 else 1);return n}
            override fun read(b:ByteArray,off:Int,len:Int):Int {check(!cancelled());return count(super.read(b,off,len))}
        }
        return InputStreamReader(limited,Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
    }
    /** Every open must address the same immutable snapshot, not the mutable external document. */
    fun inspect(input:()->InputStream,anchored:Boolean,cancelled:()->Boolean={false}):Plan {
        val format=reader(input(),cancelled).use { r ->
            val p=JsonPull(r,strict=true);p.beginObject()
            var found:String?=null
            while(p.hasNext()) {
                check(!cancelled())
                if(p.nextName()=="format") {found=p.nextString();break} else p.skipValue()
            }
            require(found=="xnote" || found=="xcanvas") {"Unsupported anchor manifest format"};found!!
        }
        val insertions=ArrayList<Insertion>()
        val ids=linkedSetOf<String>()
        var existing=0;var missing=0;var targets=0
        var activeKeys=0;var activeKeyChars=0
        reader(input(),cancelled).use { r ->
            val p=JsonPull(r,strict=true)
            fun array(place:Place,visit:(Place)->Unit) {
                p.beginArray()
                while(p.hasNext()) {check(!cancelled());require(p.peek()==JsonPull.Token.BEGIN_OBJECT) {"Invalid anchor object"};visit(place)}
                p.endArray()
            }
            fun value(place:Place) {
                check(!cancelled()) {"Anchor upgrade cancelled"}
                when(p.peek()) {
                    JsonPull.Token.BEGIN_OBJECT -> {
                        p.beginObject();val names=hashSetOf<String>();var hasId=false;var keyChars=0
                        if(place==Place.PAGE || place==Place.ITEM) {targets++;require(targets<=MAX_ANCHORS) {"Anchor count limit exceeded"}}
                        while(p.hasNext()) {
                            check(!cancelled())
                            val key=p.nextName()
                            require(key.length<=MAX_KEY_CHARS) {"JSON key length limit exceeded"}
                            require(names.add(key)) {"Duplicate JSON key"};require(names.size<=MAX_KEYS) {"JSON object key limit exceeded"}
                            activeKeys++;activeKeyChars+=key.length;keyChars+=key.length
                            require(activeKeys<=MAX_ACTIVE_KEYS && activeKeyChars<=MAX_ACTIVE_KEY_CHARS) {"JSON active key budget exceeded"}
                            when {
                                place==Place.ROOT && key=="format" -> require(p.nextString()==format) {"Manifest format changed"}
                                place==Place.ROOT && key=="version" -> require(p.nextInt()==1) {"Unsupported anchor manifest version"}
                                place==Place.ROOT && key=="pages" && format=="xnote" -> array(Place.PAGE,::value)
                                (place==Place.PAGE && key=="items") || (place==Place.ROOT && key=="items" && format=="xcanvas") -> array(Place.ITEM,::value)
                                (place==Place.PAGE || place==Place.ITEM) && key=="reference_id" -> {
                                    val id=p.nextString()
                                    require(UUID.fromString(id).toString()==id) {"Invalid anchor UUID"}
                                    require(ids.add(id)) {"Duplicate anchor UUID"};existing++;hasId=true
                                }
                                else -> value(Place.OTHER)
                            }
                        }
                        if(place==Place.ROOT) require(names.containsAll(listOf("format","version",if(format=="xnote") "pages" else "items"))) {"Missing anchor manifest structure"}
                        if((place==Place.PAGE || place==Place.ITEM) && !hasId) {
                            require(!anchored) {"Upgraded document lost an anchor identity"}
                            missing++;val id=UUID.randomUUID().toString();check(ids.add(id))
                            insertions+=Insertion(p.position,(if(names.isEmpty()) "" else ",")+"\"reference_id\":\"$id\"")
                        }
                        p.endObject()
                        activeKeys-=names.size;activeKeyChars-=keyChars
                    }
                    JsonPull.Token.BEGIN_ARRAY -> {p.beginArray();while(p.hasNext()) value(Place.OTHER);p.endArray()}
                    else -> p.skipValue()
                }
            }
            require(p.peek()==JsonPull.Token.BEGIN_OBJECT);value(Place.ROOT)
            require(p.peek()==JsonPull.Token.END_DOCUMENT)
        }
        require(existing==0 || missing==0) {"Partially missing anchor identities require explicit recovery"}
        return Plan(format,insertions.toList(),ids.toSet())
    }
    /** One continuous encoder preserves surrogate pairs split by either read or insertion chunks. */
    fun write(input:()->InputStream,out:OutputStream,plan:Plan,cancelled:()->Boolean={false}) {
        val encoder=Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val writer=OutputStreamWriter(out,encoder)
        reader(input(),cancelled).use { r ->
            val buffer=CharArray(16*1024);var consumed=0L
            for(insertion in plan.insertions) {
                require(insertion.position>=consumed)
                while(consumed<insertion.position) {
                    check(!cancelled())
                    val n=r.read(buffer,0,minOf(buffer.size.toLong(),insertion.position-consumed).toInt())
                    require(n>0) {"Snapshot truncated during anchor insertion"};writer.write(buffer,0,n);consumed+=n
                }
                writer.write(insertion.text)
            }
            while(true) {
                check(!cancelled());val n=r.read(buffer);if(n<0) break
                writer.write(buffer,0,n)
            }
        }
        writer.flush() // Own the reader, but not the caller's ZIP/output stream.
    }
}
