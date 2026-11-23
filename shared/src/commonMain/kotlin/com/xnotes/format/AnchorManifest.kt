package com.xnotes.format

import com.xnotes.kio.Uuids
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.Sink
import okio.buffer
import okio.use

/** Lossless insertion into an immutable UTF-8 source. Never constructs or reserializes ink models. */
object AnchorManifest {
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
        val orderedPageIds:List<String> = buildList { addAll(orderedPageIds) }
        val identities:Set<String> = buildSet { addAll(this@Plan.orderedPageIds) }
        init {require(this.orderedPageIds.size==identities.size) {"Duplicate page identity in plan"}}
    }
    private enum class Place { ROOT,PAGE,OTHER }

    private fun <T> withReader(input: () -> Source, cancelled: () -> Boolean, block: (JsonChars) -> T): T {
        return object : ForwardingSource(input()) {
            private var bytes = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                check(!cancelled()) { "Anchor upgrade cancelled" }
                val count = super.read(sink, byteCount)
                check(!cancelled()) { "Anchor upgrade cancelled" }
                if (count > 0) bytes += count
                require(bytes <= MAX_BYTES) { "Manifest size limit exceeded" }
                return count
            }
        }.use { block(jsonChars(it, strict = true)) }
    }
    /** Caller verifies the bundle marker and binding; every open must use one immutable snapshot. */
    fun inspectCopySource(input:()->Source,cancelled:()->Boolean={false}):Plan = plan(input,true,true,cancelled)

    /** Every open must address the same immutable snapshot, not the mutable external document. */
    fun inspectSource(input:()->Source,anchored:Boolean,cancelled:()->Boolean={false}):Plan = plan(input,anchored,false,cancelled)

    /** Projects actual candidate IDs into an unmarked source without minting or adopting orphan IDs. */
    fun inspectActivationSource(input:()->Source,orderedPageIds:List<String>,cancelled:()->Boolean={false}):Plan {
        val supplied=orderedPageIds.toList();val unique=hashSetOf<String>()
        require(supplied.isNotEmpty() && supplied.size<=MAX_ANCHORS) {"Invalid activation page count"}
        for(id in supplied) {
            check(!cancelled()) {"Anchor upgrade cancelled"}
            require(Uuids.isValid(id) && id.lowercase()==id && unique.add(id)) {"Invalid or duplicate activation UUID"}
        }
        return plan(input,false,false,cancelled,supplied)
    }

    private fun plan(input:()->Source,anchored:Boolean,rekey:Boolean,cancelled:()->Boolean,activationIds:List<String>?=null):Plan {
        val format=withReader(input,cancelled) { r ->
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
        withReader(input,cancelled) { r ->
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
                                    require(Uuids.isValid(id) && id.lowercase()==id) {"Invalid anchor UUID"}
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
                            val id=if(activationIds==null) Uuids.random() else
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
                    id=Uuids.random()
                } while(id in ids || !issued.add(id))
                fresh.add(id)
                Insertion(position,"\"$id\"",length)
            }
            return Plan(format,edits,fresh)
        }
        return Plan(format,insertions.toList(),orderedIds)
    }
    /** One continuous encoder preserves surrogate pairs split by either read or insertion chunks. */
    fun write(input:()->Source,out:Sink,plan:Plan,cancelled:()->Boolean={false}) {
        var end=0L
        for(edit in plan.insertions) {
            require(edit.position>=end && edit.skip>=0 && edit.skip<=Long.MAX_VALUE-edit.position) {"Invalid or overlapping manifest edits"}
            end=edit.position+edit.skip
        }
        val output = out.buffer()
        var pending = ""
        fun emit(text: String, finish: Boolean = false) {
            val combined = if (pending.isEmpty()) text else pending + text
            val stop = if (!finish && combined.lastOrNull()?.isHighSurrogate() == true) combined.length - 1 else combined.length
            if (stop > 0) output.write(combined.encodeToByteArray(0, stop, throwOnInvalidSequence = true))
            pending = combined.substring(stop)
        }
        withReader(input, cancelled) { reader ->
            val buffer = CharArray(16 * 1024)
            var index = 0
            var count = 0
            var consumed = 0L
            fun transfer(limit: Long, keep: Boolean): Boolean {
                while (consumed < limit) {
                    check(!cancelled())
                    if (index == count) {
                        count = reader.read(buffer); index = 0
                        if (count < 0) return false
                        check(count > 0)
                    }
                    val length = minOf(limit - consumed, (count - index).toLong()).toInt()
                    if (keep) emit(buffer.concatToString(index, index + length))
                    consumed += length; index += length
                }
                return true
            }
            for (edit in plan.insertions) {
                require(transfer(edit.position, true)) { "Snapshot truncated during anchor insertion" }
                emit(edit.text)
                require(transfer(edit.position + edit.skip, false)) { "Snapshot truncated during page identity replacement" }
            }
            transfer(Long.MAX_VALUE, true)
        }
        emit("", finish = true)
        output.flush()
    }
}
