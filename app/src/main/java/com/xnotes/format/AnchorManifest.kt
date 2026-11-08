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
    const val MAX_ANCHORS=com.xnotes.core.model.ReferenceAnchors.MAX_TARGETS
    private const val MAX_KEYS=4096
    private const val MAX_KEY_CHARS=1024
    private const val MAX_ACTIVE_KEYS=8192
    private const val MAX_ACTIVE_KEY_CHARS=262144
    /** UTF-16 source offset and length, matching JsonPull.position. skip=0 is a plain insertion. */
    data class Insertion(val position:Long,val text:String,val skip:Long=0)
    /** identities always describes the output, including newly minted IDs in a copy plan. */
    class Plan(val format:String,val insertions:List<Insertion>,orderedPageIds:List<String>) {
        val orderedPageIds:List<String> = java.util.Collections.unmodifiableList(ArrayList(orderedPageIds))
        val identities:Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(this.orderedPageIds))
        init {require(this.orderedPageIds.size==identities.size) {"Duplicate page identity in plan"}}
    }
    private enum class Place { ROOT,PAGE,OTHER }

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
    /** Caller verifies the bundle marker and binding; every open must use one immutable snapshot. */
    fun inspectCopy(input:()->InputStream,cancelled:()->Boolean={false}):Plan = plan(input,true,true,cancelled)

    /** Every open must address the same immutable snapshot, not the mutable external document. */
    fun inspect(input:()->InputStream,anchored:Boolean,cancelled:()->Boolean={false}):Plan = plan(input,anchored,false,cancelled)

    /** Projects actual candidate IDs into an unmarked source without minting or adopting orphan IDs. */
    fun inspectActivation(input:()->InputStream,orderedPageIds:List<String>,cancelled:()->Boolean={false}):Plan {
        val supplied=orderedPageIds.toList();val unique=hashSetOf<String>()
        require(supplied.isNotEmpty() && supplied.size<=MAX_ANCHORS) {"Invalid activation page count"}
        for(id in supplied) {
            check(!cancelled()) {"Anchor upgrade cancelled"}
            require(UUID.fromString(id).toString()==id && unique.add(id)) {"Invalid or duplicate activation UUID"}
        }
        return plan(input,false,false,cancelled,supplied)
    }

    private fun plan(input:()->InputStream,anchored:Boolean,rekey:Boolean,cancelled:()->Boolean,activationIds:List<String>?=null):Plan {
        val format=reader(input(),cancelled).use { r ->
            val p=JsonPull(r,strict=true);p.beginObject()
            var found:String?=null
            while(p.hasNext()) {
                check(!cancelled())
                if(p.nextName()=="format") {found=p.nextString();break} else p.skipValue()
            }
            require(found=="xnote") {"Unsupported anchor manifest format"};found!!
        }
        val insertions=ArrayList<Insertion>()
        val literals=ArrayList<Pair<Long,Long>>()
        val ids=hashSetOf<String>();val orderedIds=ArrayList<String>()
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
                        if(place==Place.PAGE) {targets++;require(targets<=MAX_ANCHORS) {"Anchor count limit exceeded"}}
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
                                place==Place.PAGE && key=="reference_id" -> {
                                    require(activationIds==null) {"Unmarked page identities require explicit recovery"}
                                    require(p.peek()==JsonPull.Token.STRING) {"Page identity must be a string"}
                                    val start=p.position
                                    val id=p.nextString()
                                    val end=p.position
                                    require(UUID.fromString(id).toString()==id) {"Invalid anchor UUID"}
                                    require(ids.add(id)) {"Duplicate anchor UUID"};orderedIds.add(id);existing++;hasId=true
                                    if(rekey) literals+=start to (end-start)
                                }
                                else -> value(Place.OTHER)
                            }
                        }
                        if(place==Place.ROOT) require(names.containsAll(listOf("format","version","pages"))) {"Missing anchor manifest structure"}
                        if(place==Place.PAGE && !hasId) {
                            require(!anchored) {"Upgraded document lost an anchor identity"}
                            missing++
                            val id=if(activationIds==null) UUID.randomUUID().toString() else
                                requireNotNull(activationIds.getOrNull(orderedIds.size)) {"Activation page count changed"}
                            check(ids.add(id))
                            orderedIds.add(id)
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
        require(targets>0) {"A referenced note must have a page"}
        require(existing==0 || missing==0) {"Partially missing anchor identities require explicit recovery"}
        require(activationIds==null || targets==activationIds.size) {"Activation page count changed"}
        check(!cancelled()) {"Anchor upgrade cancelled"}
        if(rekey) {
            // Generate only after validating every old ID, including pages later in the manifest.
            val fresh=ArrayList<String>();val issued=hashSetOf<String>()
            val edits=literals.map {(position,length)->
                var id:String
                do {
                    check(!cancelled()) {"Page identity copy cancelled"}
                    id=UUID.randomUUID().toString()
                } while(id in ids || !issued.add(id))
                fresh.add(id)
                Insertion(position,"\"$id\"",length)
            }
            return Plan(format,edits,fresh)
        }
        return Plan(format,insertions.toList(),orderedIds)
    }
    /** One continuous encoder preserves surrogate pairs split by either read or insertion chunks. */
    fun write(input:()->InputStream,out:OutputStream,plan:Plan,cancelled:()->Boolean={false}) {
        var end=0L
        for(edit in plan.insertions) {
            require(edit.position>=end && edit.skip>=0 && edit.skip<=Long.MAX_VALUE-edit.position) {"Invalid or overlapping manifest edits"}
            end=edit.position+edit.skip
        }
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
                var remaining=insertion.skip
                while(remaining>0) {
                    check(!cancelled())
                    val n=r.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt())
                    require(n>0) {"Snapshot truncated during page identity replacement"}
                    remaining-=n;consumed+=n
                }
            }
            while(true) {
                check(!cancelled());val n=r.read(buffer);if(n<0) break
                writer.write(buffer,0,n)
            }
        }
        writer.flush() // Own the reader, but not the caller's ZIP/output stream.
    }
}
