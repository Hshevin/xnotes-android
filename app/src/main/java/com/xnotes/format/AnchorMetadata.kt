package com.xnotes.format

import com.xnotes.core.model.ReferenceIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small, standalone envelope shared by byte-preserving migration and the native model codecs. */
internal data class AnchorMetadata(val documentId:String) {
    init {ReferenceIdentity.requireUuid(documentId)}
    fun bytes()="""{"format":"xnotes-object-anchors","version":1,"documentId":"$documentId"}""".toByteArray(Charsets.UTF_8)
    fun write(zip:ZipOutputStream) {zip.putNextEntry(ZipEntry(ENTRY));zip.write(bytes());zip.closeEntry()}
    companion object {
        const val ENTRY="xnotes-anchors.json"
        const val MAX_BYTES=4096L
        fun read(input:InputStream):AnchorMetadata {
            val out=ByteArrayOutputStream();val buffer=ByteArray(512)
            while(true) {val n=input.read(buffer);if(n<0) break;out.write(buffer,0,n);require(out.size()<=MAX_BYTES) {"Anchor marker too large"}}
            return AnchorManifest.reader(ByteArrayInputStream(out.toByteArray())).use {r->
                val p=JsonPull(r,strict=true,tokenLimit=MAX_BYTES.toInt());val keys=hashSetOf<String>();var id:String?=null
                p.beginObject()
                while(p.hasNext()) {
                    val key=p.nextName();require(keys.add(key)) {"Duplicate anchor marker key"}
                    when(key) {
                        "format" -> require(p.nextString()=="xnotes-object-anchors")
                        "version" -> require(p.nextInt()==1) {"Unsupported anchor version"}
                        "documentId" -> id=p.nextString()
                        else -> throw IllegalArgumentException("Unknown anchor marker field")
                    }
                }
                p.endObject();require(keys==setOf("format","version","documentId"));require(p.peek()==JsonPull.Token.END_DOCUMENT)
                AnchorMetadata(requireNotNull(id))
            }
        }
    }
}

/** Counts raw targets, including unsupported/pending objects, before any forgiving model filtering. */
internal class AnchorReadState(private val independentCopy:Boolean=false) {
    private val sourceIds=if(independentCopy) java.util.IdentityHashMap<ReferenceIdentity,String>() else null
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
            if(independentCopy) require(!invalidStructure && (!versionPresent || supportedVersion) && actual.count()==targets) {"Cannot copy a partial document"}
            return false
        }
        require(identity!=null && marker.documentId==identity.documentId) {"Anchor metadata belongs to another document"}
        require(!invalidStructure && supportedVersion) {"Invalid anchored manifest structure or version"}
        require(missing==0 && targets<=com.xnotes.core.model.ReferenceAnchors.MAX_TARGETS) {"Upgraded document lost a reference identity"}
        val materialized=hashSetOf<String>()
        for(item in actual) require(materialized.add(requireNotNull(if(independentCopy) sourceIds!![item] else item.id) {"Missing materialized identity"})) {"Duplicate materialized identity"}
        require(materialized.size==targets && materialized==ids) {"Cannot expose a partial writable anchored document"}
        return true
    }
}
