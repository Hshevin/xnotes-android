package com.xnotes.ui

import com.xnotes.core.util.DocumentKind
import com.xnotes.format.RecordingBodyEvidence
import java.io.File
import com.xnotes.platform.JsonStore
import org.json.JSONObject
import java.util.UUID

/** The original recording and its checked journal outlive temporary decoded editor assets. */
internal class RecordingRecoveryStore(val directory:File,
    private val publishMetadata:(File,JSONObject)->Boolean={file,json->JsonStore(file).writeChecked(json)}) {
    private val bindings=hashMapOf<String,Any>()
    enum class Phase { Pending, Applying, Committed }
    data class Record(val id:String,val file:String,val key:String,val name:String,val rid:Int,
        val kind:DocumentKind?,val documentId:String?,val phase:Phase=Phase.Pending,
        val targetKind:DocumentKind?=kind,val targetId:String?=documentId,val targetRid:Int=rid,
        val variants:Set<String> = emptySet())
    data class Candidate(val id:String,val raw:File?,val metadata:File?,val record:Record?,val problem:Problem?)
    enum class Problem { Unbound, Legacy, Invalid, Missing, Applying, Conflict }
    private fun safe(name:String)=name.isNotBlank() && File(name).name==name && name!="." && name!=".." && !name.contains('\\')
    private fun uuid(id:String)=runCatching {UUID.fromString(id).toString()==id}.getOrDefault(false)
    private fun sha(value:String)=Regex("[a-f0-9]{64}").matches(value)
    private fun raw(name:String):File {
        require(safe(name));val file=File(directory,name)
        require(file.canonicalFile.parentFile==directory.canonicalFile) {"Recording source escapes its directory"}
        return file
    }
    private fun kind(value:String?)=value?.let {DocumentKind.valueOf(it).also {k->require(k==DocumentKind.NOTE || k==DocumentKind.CANVAS)}}
    private fun read(file:File):Record {
        val o=JSONObject(file.readText())
        fun integer(key:String):Int {
            val value=o.get(key)
            require(value is Int || value is Long && value>=Int.MIN_VALUE.toLong() && value<=Int.MAX_VALUE.toLong()) {"Invalid recording integer"}
            return (value as Number).toInt()
        }
        val version=if(o.has("version")) integer("version") else 1
        require(version in 1..2)
        fun str(key:String):String=(o.get(key) as? String) ?: error("Invalid recording field")
        val name=str("file");require(safe(name) && name.endsWith(".aac"))
        val rid=integer("rid");require(rid>0)
        if(version==1) return Record(file.name,name,str("key"),str("name"),rid,null,null)
        val id=str("id");require(uuid(id))
        val documentId=(o.opt("documentId") as? String)?.also {require(uuid(it))}
        val sourceKind=kind(o.opt("kind") as? String);require((documentId==null)==(sourceKind==null))
        val targetId=(o.opt("targetId") as? String)?.also {require(uuid(it))}
        val targetKind=kind(o.opt("targetKind") as? String);require((targetId==null)==(targetKind==null))
        val targetRid=integer("targetRid");require(targetRid>0)
        val phase=Phase.valueOf(str("phase"));val hashes=linkedSetOf<String>()
        val variants=o.getJSONArray("variants");for(i in 0 until variants.length()) {val hash=variants.getString(i);require(sha(hash));hashes.add(hash)}
        require(phase==Phase.Pending || targetId!=null && hashes.isNotEmpty())
        return Record(id,name,str("key"),str("name"),rid,sourceKind,documentId,phase,targetKind,targetId,targetRid,hashes)
    }
    private fun metadata(id:String):File?=directory.listFiles {f->f.isFile && f.name.endsWith(".json")}.orEmpty()
        .firstOrNull {runCatching {read(it).id==id}.getOrDefault(false)}
    @Synchronized fun needsBodyEvidence(ownerToken:Any):Boolean = bindings.any {(id,owner)->
        owner===ownerToken && metadata(id)?.let {runCatching {read(it).phase==Phase.Applying}.getOrDefault(false)}==true
    }
    @Synchronized fun needsDurableEvidence(documentId:String):Boolean = directory.listFiles {f->f.isFile && f.name.endsWith(".json")}.orEmpty()
        .any {file->runCatching {read(file).let {it.phase==Phase.Applying && it.targetId==documentId}}.getOrDefault(false)}
    @Synchronized fun references(file:File):Boolean {
        if(file.parentFile.canonicalFile!=directory.canonicalFile) return false
        return directory.listFiles {f->f.isFile && f.name.endsWith(".json")}.orEmpty()
            .any {side->runCatching {read(side).file==file.name}.getOrDefault(false)}
    }
    @Synchronized fun deleteIfUnreferenced(file:File):Boolean = !references(file) && (!file.exists() || file.delete())
    private fun write(file:File,r:Record):Boolean=publishMetadata(file,JSONObject()
        .put("version",2).put("id",r.id).put("file",r.file).put("key",r.key).put("name",r.name).put("rid",r.rid)
        .put("kind",r.kind?.name ?: JSONObject.NULL).put("documentId",r.documentId ?: JSONObject.NULL)
        .put("phase",r.phase.name).put("targetKind",r.targetKind?.name ?: JSONObject.NULL).put("targetId",r.targetId ?: JSONObject.NULL)
        .put("targetRid",r.targetRid).put("variants",org.json.JSONArray(r.variants.toList())))
    @Synchronized fun list(live:File?=null):List<Candidate> {
        directory.mkdirs();val result=mutableListOf<Candidate>();val referenced=hashSetOf<String>()
        val liveKey=live?.canonicalPath
        val entries=directory.listFiles {f->f.isFile && f.name.endsWith(".json")}.orEmpty().sortedBy {it.name}
            .map {it to runCatching {read(it)}.getOrNull()}
        val counts=entries.mapNotNull {it.second}.groupingBy {it.file}.eachCount()
        for((file,r) in entries) {
            if(r==null) {result.add(Candidate(file.name,null,file,null,Problem.Invalid));continue}
            referenced.add(r.file)
            val source=runCatching {raw(r.file)}.getOrNull()
            if(source==null) {result.add(Candidate(r.id,null,file,r,Problem.Invalid));continue}
            if(source.canonicalPath==liveKey) continue
            if(r.phase==Phase.Committed) {cleanup(file,r);continue}
            val problem=when {
                !source.isFile || source.length()==0L->Problem.Missing
                counts[r.file]!=1->Problem.Conflict
                r.id==file.name->Problem.Legacy
                r.phase==Phase.Applying->Problem.Applying
                r.documentId==null->Problem.Unbound
                else->null
            }
            result.add(Candidate(r.id,source,file,r,problem))
        }
        for(file in directory.listFiles {f->f.isFile && f.name.endsWith(".aac")}.orEmpty().sortedBy {it.name})
            if(file.name !in referenced && file.canonicalPath!=liveKey && runCatching {raw(file.name)}.isSuccess)
                result.add(Candidate("orphan:${file.name}",file,null,null,Problem.Unbound))
        return result
    }
    @Synchronized fun create(raw:File,key:String,name:String,rid:Int,kind:DocumentKind?,documentId:String?):Record? {
        require(raw.parentFile.canonicalFile==directory.canonicalFile && this.raw(raw.name).canonicalFile==raw.canonicalFile && rid>0)
        require((kind==null)==(documentId==null));documentId?.let {require(uuid(it))}
        val r=Record(UUID.randomUUID().toString(),raw.name,key,name,rid,kind,documentId)
        return r.takeIf {write(File(directory,"${r.id}.json"),r)}
    }
    @Synchronized fun applying(candidate:Candidate,kind:DocumentKind,documentId:String,rid:Int,sha:String,ownerToken:Any):Record? {
        require(uuid(documentId) && rid>0 && this.sha(sha))
        val actual=list().firstOrNull {it.id==candidate.id && it.raw?.canonicalPath==candidate.raw?.canonicalPath} ?: return null
        val file=actual.raw?.takeIf {it.isFile && it.length()>0L} ?: return null
        val original=actual.record
        val r=(original ?: Record(UUID.randomUUID().toString(),file.name,"",file.nameWithoutExtension,rid,null,null))
            .copy(phase=Phase.Applying,targetKind=kind,targetId=documentId,targetRid=rid,variants=(original?.variants.orEmpty()+sha))
        val side=actual.metadata?.takeIf {actual.problem!=Problem.Invalid} ?: File(directory,"${r.id}.json")
        // Legacy is explicitly claimed under a real record ID; never invent an original document UUID.
        val typed=if(r.id==side.name) r.copy(id=UUID.randomUUID().toString()) else r
        if(!write(side,typed)) return null
        bindings[typed.id]=ownerToken
        return typed
    }
    @Synchronized fun register(id:String,sha:String):Boolean {
        require(this.sha(sha));val file=metadata(id) ?: return false;val r=read(file)
        return r.phase==Phase.Applying && write(file,r.copy(variants=r.variants+sha))
    }
    @Synchronized fun matches(ownerToken:Any,evidence:RecordingBodyEvidence):Map<String,Record> = list().mapNotNull {c->
        c.record?.takeIf {it.phase==Phase.Applying && bindings[it.id]===ownerToken && evidence.kind==it.targetKind &&
            evidence.recordings[it.targetRid] in it.variants}?.let {it.id to it}
    }.toMap()
    @Synchronized fun committed(evidence:RecordingBodyEvidence,ownerToken:Any?=null):Int =
        finish(list().mapNotNull {it.record}.filter {r->r.phase==Phase.Applying && r.targetKind==evidence.kind &&
            r.targetId==evidence.documentId && evidence.recordings[r.targetRid] in r.variants &&
            (ownerToken==null || bindings[r.id]===ownerToken)})
    @Synchronized fun createdCommitted(ownerToken:Any,oldId:String,newId:String,evidence:RecordingBodyEvidence,frozen:Map<String,Record>):Int {
        if(newId!=evidence.documentId || oldId==newId) return 0
        val actual=list().mapNotNull {it.record}.filter {r->
            val before=frozen[r.id]
            before!=null && before.targetKind==r.targetKind && before.targetId==r.targetId && before.targetRid==r.targetRid &&
                evidence.recordings[r.targetRid] in before.variants && bindings[r.id]===ownerToken &&
                r.phase==Phase.Applying && r.targetId==oldId && r.targetKind==evidence.kind && evidence.recordings[r.targetRid] in r.variants}
        return finish(actual)
    }
    private fun finish(records:List<Record>):Int {
        var count=0
        for(r in records) {val side=metadata(r.id) ?: continue;if(write(side,r.copy(phase=Phase.Committed))) {count++;cleanup(side,r);bindings.remove(r.id)}}
        return count
    }
    private fun anotherReference(id:String?,name:String)=directory.listFiles {f->f.isFile && f.name.endsWith(".json")}.orEmpty()
        .any {runCatching {read(it).let {r->r.id!=id && r.file==name && r.phase!=Phase.Committed}}.getOrDefault(false)}
    private fun cleanup(side:File,r:Record) {
        if(anotherReference(r.id,r.file)) return
        val source=raw(r.file);if(!source.exists() || source.delete()) side.delete()
    }
    @Synchronized fun discard(candidate:Candidate):Boolean {
        val actual=list().firstOrNull {it.id==candidate.id} ?: return false
        val source=actual.raw
        if(source!=null && !anotherReference(actual.record?.id,source.name) && source.exists() && !source.delete()) return false
        val side=actual.metadata
        if(side!=null && side.exists() && !side.delete()) return false
        bindings.remove(candidate.id);return true
    }
    /** Explicit live-recording discard keeps the bytes briefly for its existing Undo action. */
    @Synchronized fun discardMetadata(candidate:Candidate):Boolean {
        val actual=list().firstOrNull {it.id==candidate.id} ?: return false
        val side=actual.metadata ?: return true
        if(side.exists() && !side.delete()) return false
        bindings.remove(candidate.id);return true
    }
}
