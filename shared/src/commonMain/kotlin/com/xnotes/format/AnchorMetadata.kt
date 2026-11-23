package com.xnotes.format

import com.xnotes.core.model.ReferenceIdentity

/** Small, standalone envelope shared by byte-preserving migration and the native model codecs. */
data class AnchorMetadata(val documentId:String) {
    init {ReferenceIdentity.requireUuid(documentId)}
    fun bytes()="""{"format":"xnotes-page-anchors","version":1,"documentId":"$documentId"}""".encodeToByteArray()
    companion object {
        const val ENTRY="xnotes-anchors.json"
        const val MAX_BYTES=4096L
        fun decode(bytes:ByteArray):AnchorMetadata {
            require(bytes.size<=MAX_BYTES) {"Anchor marker too large"}
            val p=JsonPull(bytes.decodeToString(throwOnInvalidSequence=true),strict=true,tokenLimit=MAX_BYTES.toInt());val keys=hashSetOf<String>();var id:String?=null
                p.beginObject()
                while(p.hasNext()) {
                    val key=p.nextName();require(keys.add(key)) {"Duplicate anchor marker key"}
                    when(key) {
                        "format" -> require(p.nextString()=="xnotes-page-anchors")
                        "version" -> require(p.nextInt()==1) {"Unsupported anchor version"}
                        "documentId" -> id=p.nextString()
                        else -> throw IllegalArgumentException("Unknown anchor marker field")
                    }
                }
                p.endObject();require(keys==setOf("format","version","documentId"));require(p.peek()==JsonPull.Token.END_DOCUMENT)
                return AnchorMetadata(requireNotNull(id))
        }
    }
}

/** Tracks raw page identities independently of forgiving object materialization. */
internal class AnchorReadState(private val independentCopy:Boolean=false) {
    private val sourceIds=if(independentCopy) com.xnotes.core.util.identityMap<ReferenceIdentity,String>() else null
    private val ids=hashSetOf<String>()
    private var missing=0
    private var targets=0
    private var supportedVersion=false
    private var versionPresent=false
    private var invalidStructure=false
    fun invalid() {invalidStructure=true}
    fun version(p:JsonPull) {
        versionPresent=true
        supportedVersion=if(p.peek()==JsonPull.Token.NUMBER) p.nextNumberLiteral()=="1" else {p.skipValue();false}
    }
    fun key(keys:MutableSet<String>,key:String) {
        when(key) {"format","version","pages","items","reference_id" -> if(!keys.add(key)) invalidStructure=true}
    }
    fun root(keys:Set<String>,member:String) {
        if("format" !in keys || member !in keys) invalidStructure=true
    }
    fun observe(id:String?) {
        targets++
        if(id==null) missing++ else {
            ReferenceIdentity.requireUuid(id)
            require(ids.add(id)) {"Duplicate reference UUID"}
            require(ids.size<=com.xnotes.core.model.ReferenceAnchors.MAX_TARGETS) {"Reference target limit exceeded"}
        }
    }
    fun bind(identity:ReferenceIdentity,id:String?) {
        if(id==null) return
        if(independentCopy) require(sourceIds!!.put(identity,id)==null) else identity.restore(id)
    }
    fun complete(marker:AnchorMetadata?,identity:BundleIdentity?,actual:Sequence<ReferenceIdentity>):Boolean {
        if(marker==null) {
            require(ids.isEmpty()) {"Reference identities require lossless metadata upgrade"}
            if(independentCopy) {
                require(!invalidStructure && (!versionPresent || supportedVersion)) {"Cannot copy an unrecognized document"}
            }
            return false
        }
        require(identity!=null && marker.documentId==identity.documentId) {"Anchor metadata belongs to another document"}
        require(!invalidStructure && supportedVersion) {"Invalid anchored manifest structure or version"}
        if(independentCopy) {
            // A recovery copy validates page provenance before minting independent page IDs.
            require(targets<=com.xnotes.core.model.ReferenceAnchors.MAX_TARGETS) {"Reference target limit exceeded"}
            val materialized=hashSetOf<String>()
            for(item in actual) {
                sourceIds!![item]?.let {id -> require(id in ids && materialized.add(id)) {"Duplicate materialized identity"}}
            }
            return true
        }
        require(missing==0 && targets<=com.xnotes.core.model.ReferenceAnchors.MAX_TARGETS) {"Upgraded document lost a reference identity"}
        val materialized=hashSetOf<String>()
        for(item in actual) require(materialized.add(requireNotNull(item.id) {"Missing materialized identity"})) {"Duplicate materialized identity"}
        require(materialized.size==targets && materialized==ids) {"Cannot expose a partial writable anchored document"}
        return true
    }
}
