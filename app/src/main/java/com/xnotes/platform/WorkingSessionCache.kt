package com.xnotes.platform

import com.xnotes.core.model.ReferenceIdentity
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID

/** Private working snapshots, not portable backups or evidence that an external source is unchanged. */
internal class WorkingSessionCache(
    directory:File,
    private val validateBody:(File,Kind,String)->Unit,
    private val onEvent:(Event,File)->Unit,
    private val validateMetadata:(Kind,String,String)->Unit,
) {
    constructor(directory:File,validateBody:(File,Kind,String)->Unit,onEvent:(Event,File)->Unit={_,_->})
        :this(directory,validateBody,onEvent,{_,_,_->})
    enum class Kind { NOTE,CANVAS }
    enum class Event { BEFORE_HEAD_REPLACE,BEFORE_HEAD_COMMIT,BEFORE_DELETE }
    class Invalid(cause:Throwable?=null):IOException("Invalid working session cache",cause)
    class Conflict:IOException("Working session cache changed before publication")

    sealed class Request protected constructor(internal val coordinator:Any,val generation:Long)
    class WriteRequest internal constructor(
        coordinator:Any,generation:Long,internal val owner:Any,
        val kind:Kind,val documentId:String,val contentVersion:Long,val metadataJson:String,
        val requiresBody:Boolean,internal val reuseHeadHash:String?,
    ):Request(coordinator,generation)
    class ClearRequest internal constructor(coordinator:Any,generation:Long):Request(coordinator,generation)
    data class Publication(val request:Request,val published:Boolean)
    data class Previous(val kind:Kind,val documentId:String,val contentVersion:Long,val metadataJson:String,
                        val sha256:String,val headHash:String)

    class Read internal constructor(
        private val issuer:Any,val headHash:String,
        val body:File,val kind:Kind,val documentId:String,val metadataJson:String,val sha256:String,
        private val release:()->Unit,
    ):Closeable {
        private val closed=java.util.concurrent.atomic.AtomicBoolean(false)
        internal fun checkIssuer(owner:Any) {if(issuer!==owner || closed.get()) throw Invalid()}
        override fun close() {if(closed.compareAndSet(false,true)) release()}
    }

    private data class Body(val name:String,val sha:String,val kind:Kind,val documentId:String,val version:Long,val metadata:String)
    private data class Head(val hash:String?,val body:Body?)
    private data class Proof(val owner:Any,val headHash:String,val body:Body)
    private class Coordinator {
        var generation=0L
        var latest:Request?=null
        var committed:Proof?=null
        var observed=false
        var observedHeadHash:String?=null
        val readers=hashMapOf<String,Int>()
        val writers=hashSetOf<String>()
    }
    private val root=directory.canonicalFile
    private val headFile=File(root,"head.json")
    private val state=coordinator(root.path)
    private val readIssuer=Any()

    /** Owner-thread reservation; no filesystem access or encoder/validator callbacks. */
    fun reserve(owner:Any,kind:Kind,documentId:String,contentVersion:Long,metadata:JSONObject,forceBody:Boolean=false):WriteRequest {
        ReferenceIdentity.requireUuid(documentId)
        require(contentVersion>=0)
        val frozen=metadata.toString()
        require(frozen.toByteArray(Charsets.UTF_8).size<=MAX_METADATA) {"Session metadata exceeds its budget"}
        return synchronized(state) {
            val proof=state.committed?.takeIf {!forceBody && it.owner===owner && it.body.kind==kind && it.body.documentId==documentId && it.body.version==contentVersion}
            WriteRequest(state,nextGeneration(),owner,kind,documentId,contentVersion,frozen,proof==null,proof?.headHash).also {state.latest=it}
        }
    }
    fun reserveClear():ClearRequest=synchronized(state) {ClearRequest(state,nextGeneration()).also {state.latest=it}}
    fun isCurrent(request:Request):Boolean=synchronized(state) {request.coordinator===state && state.latest===request}
    /** Domain intent registration calls this before publishing its new in-memory boundary.
     * No filesystem, body or application callback runs under this short coordinator lock. */
    fun invalidateReservations()=synchronized(state) {nextGeneration();state.latest=null}
    private fun nextGeneration():Long {check(state.generation<Long.MAX_VALUE);return ++state.generation}

    /** Run on IO; the caller must also check [isCurrent] before applying a late success to UI state. */
    fun publish(request:WriteRequest,checkActive:()->Unit={},writeBody:(OutputStream)->Unit):Publication =
        publishChecked(request,checkActive,{},writeBody)

    fun publishChecked(request:WriteRequest,checkActive:()->Unit={},validatePrevious:(Previous?)->Unit,
                       writeBody:(OutputStream)->Unit):Publication {
        require(request.coordinator===state)
        val initial=begin(request) ?: return Publication(request,false)
        var pending:File?=null
        var candidate:File?=null
        var headTemp:File?=null
        try {
            checkActive()
            verify(initial.body,checkActive)
            validateMetadata(request.kind,request.documentId,request.metadataJson)
            validatePrevious(previous(initial));checkActive()
            if(!isCurrent(request)) return Publication(request,false)
            val next:Body
            if(request.requiresBody) {
                val name="body-${UUID.randomUUID()}.zip"
                candidate=File(root,name);pending=File(root,"$name.pending")
                synchronized(state) {state.writers+=name;state.writers+=pending.name}
                ensureDirectory()
                writeNew(pending) {out->checkActive();writeBody(out);checkActive()}
                val before=digest(pending,checkActive)
                validateBody(pending,request.kind,request.documentId)
                checkActive()
                if(digest(pending,checkActive)!=before) throw Conflict()
                Files.move(pending.toPath(),candidate.toPath(),ATOMIC_MOVE)
                pending=null
                next=Body(name,before,request.kind,request.documentId,request.contentVersion,request.metadataJson)
            } else {
                val old=initial.body ?: throw Conflict()
                if(initial.hash!=request.reuseHeadHash || old.kind!=request.kind || old.documentId!=request.documentId || old.version!=request.contentVersion) throw Conflict()
                next=old.copy(metadata=request.metadataJson)
            }
            if(!isCurrent(request)) return Publication(request,false)
            val bytes=encode(next)
            headTemp=prepareHead(bytes)
            onEvent(Event.BEFORE_HEAD_REPLACE,headTemp)
            // All callbacks and body hashing stay outside the reservation lock.
            verify(next,checkActive)
            checkActive()
            onEvent(Event.BEFORE_HEAD_COMMIT,headTemp)
            val published=synchronized(state) {
                if(state.latest!==request) false else {
                    if(checkedHead().hash!=initial.hash) throw Conflict()
                    verifyHeadCandidate(headTemp,bytes)
                    Files.move(headTemp.toPath(),headFile.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
                    state.observedHeadHash=sha(bytes)
                    state.committed=Proof(request.owner,state.observedHeadHash!!,next)
                    true
                }
            }
            return Publication(request,published)
        } finally {
            // A failed rename may still have exposed a head on some filesystems. Cleanup consults
            // that head instead of unconditionally deleting this request's complete candidate.
            release(initial.body)
            synchronized(state) {
                pending?.let {state.writers.remove(it.name)}
                candidate?.let {state.writers.remove(it.name);state.writers.remove("${it.name}.pending")}
                headTemp?.let {state.writers.remove(it.name)}
            }
            pending?.delete();headTemp?.delete()
            cleanup()
        }
    }

    fun publishClear(request:ClearRequest,checkActive:()->Unit={}):Publication = publishClearChecked(request,checkActive,{})

    fun publishClearChecked(request:ClearRequest,checkActive:()->Unit={},validatePrevious:(Previous?)->Unit):Publication {
        require(request.coordinator===state)
        val initial=begin(request) ?: return Publication(request,false)
        var temp:File?=null
        try {
            verify(initial.body,checkActive)
            validatePrevious(previous(initial));checkActive()
            if(!isCurrent(request)) return Publication(request,false)
            ensureDirectory();val bytes=encode(null);temp=prepareHead(bytes);onEvent(Event.BEFORE_HEAD_REPLACE,temp)
            verify(initial.body,checkActive);checkActive()
            onEvent(Event.BEFORE_HEAD_COMMIT,temp)
            val published=synchronized(state) {
                if(state.latest!==request) false else {
                    if(checkedHead().hash!=initial.hash) throw Conflict()
                    verifyHeadCandidate(temp,bytes)
                    Files.move(temp.toPath(),headFile.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)
                    state.observedHeadHash=sha(bytes);state.committed=null;true
                }
            }
            return Publication(request,published)
        } finally {
            release(initial.body)
            synchronized(state) {temp?.let {state.writers.remove(it.name)}}
            temp?.delete();cleanup()
        }
    }

    /** A missing head or explicit tombstone is empty. Corruption is an error, never empty. */
    fun load(checkActive:()->Unit={}):Read? {
        val head=synchronized(state) {checkedHead().also {it.body?.let(::acquire)}}
        val body=head.body ?: return null
        try {
            verify(body,checkActive)
            return Read(readIssuer,requireNotNull(head.hash),File(root,body.name),body.kind,body.documentId,body.metadata,body.sha) {release(body);cleanup()}
        } catch(error:Throwable) {release(body);throw error}
    }

    /** An opt-in current-head proof; ordinary held reads remain readable after head replacement. */
    fun verifyRead(read:Read,checkActive:()->Unit={}) {
        read.checkIssuer(readIssuer)
        val head=synchronized(state) {checkedHead()}
        if(head.hash!=read.headHash || head.body==null) throw Conflict()
        verify(head.body,checkActive)
        read.checkIssuer(readIssuer)
        synchronized(state) {read.checkIssuer(readIssuer);if(checkedHead().hash!=read.headHash) throw Conflict()}
    }
    private fun previous(head:Head)=head.body?.let {Previous(it.kind,it.documentId,it.version,it.metadata,it.sha,requireNotNull(head.hash))}

    private fun begin(request:Request):Head?=synchronized(state) {
        if(state.latest!==request) null else checkedHead().also {it.body?.let(::acquire)}
    }
    private fun acquire(body:Body) {state.readers[body.name]=(state.readers[body.name] ?: 0)+1}
    private fun release(body:Body?) {
        if(body==null) return
        synchronized(state) {
            val count=state.readers[body.name] ?: error("Session lease was not acquired")
            if(count==1) state.readers.remove(body.name) else state.readers[body.name]=count-1
        }
    }
    private fun verify(body:Body?,checkActive:()->Unit) {
        checkActive();if(body==null) return
        validateMetadata(body.kind,body.documentId,body.metadata)
        val file=File(root,body.name)
        if(!Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS)) throw Invalid()
        if(digest(file,checkActive)!=body.sha) throw Invalid()
        validateBody(file,body.kind,body.documentId)
        checkActive()
        if(digest(file,checkActive)!=body.sha) throw Invalid()
    }

    private fun ensureDirectory() {if(!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create session cache directory")}
    private fun prepareHead(bytes:ByteArray):File {
        require(bytes.size<=MAX_HEAD)
        val file=File(root,"head-${UUID.randomUUID()}.pending")
        synchronized(state) {state.writers+=file.name}
        try {writeNew(file) {it.write(bytes)};return file}
        catch(error:Throwable) {synchronized(state) {state.writers.remove(file.name)};file.delete();throw error}
    }
    private fun writeNew(file:File,write:(OutputStream)->Unit) {
        FileChannel.open(file.toPath(),CREATE_NEW,WRITE).use {channel->
            // Native ZIP writers close their output; keep ownership of the channel until fsync.
            val output=object:FilterOutputStream(Channels.newOutputStream(channel)) {
                override fun close() {flush()}
                override fun write(bytes:ByteArray,offset:Int,length:Int) {out.write(bytes,offset,length)}
            }
            write(output);output.flush();channel.force(true)
        }
    }
    private fun encode(body:Body?):ByteArray {
        val json=JSONObject().put("version",1).put("state",if(body==null) "cleared" else "active")
        if(body!=null) json.put("kind",body.kind.name).put("documentId",body.documentId)
            .put("body",body.name).put("sha256",body.sha).put("contentVersion",body.version).put("metadata",JSONObject(body.metadata))
        return json.toString().toByteArray(Charsets.UTF_8).also {require(it.size<=MAX_HEAD)}
    }
    /** Only the small head is read under the coordinator lock; no body/validator work here. */
    private fun readHead():Head {
        val bytes=try {readHeadBytes(headFile)} catch(_:NoSuchFileException) {return Head(null,null)}
        try {
            val text=Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            validateEnvelopeJson(text)
            val json=JSONObject(text)
            require(json.opt("version")==1)
            when(json.opt("state")) {
                "cleared" -> {
                    require(json.keys().asSequence().toSet()==setOf("version","state"))
                    return Head(sha(bytes),null)
                }
                "active" -> {
                    require(json.keys().asSequence().toSet()==setOf("version","state","kind","documentId","body","sha256","contentVersion","metadata"))
                    val kind=Kind.valueOf(json.opt("kind") as? String ?: throw Invalid())
                    val id=json.opt("documentId") as? String ?: throw Invalid();ReferenceIdentity.requireUuid(id)
                    val name=json.opt("body") as? String ?: throw Invalid();require(BODY_NAME.matches(name))
                    val hash=json.opt("sha256") as? String ?: throw Invalid();require(HASH.matches(hash))
                    val version=json.opt("contentVersion").let {require(it is Int || it is Long);(it as Number).toLong()};require(version>=0)
                    val metadata=json.opt("metadata") as? JSONObject ?: throw Invalid()
                    return Head(sha(bytes),Body(name,hash,kind,id,version,metadata.toString()))
                }
                else -> throw Invalid()
            }
        } catch(error:Invalid) {throw error}
        catch(error:Exception) {throw Invalid(error)}
    }

    /** JSONObject alone accepts duplicate/trailing fields differently on Android and the JVM. */
    private fun validateEnvelopeJson(text:String) {
        val parser=com.xnotes.format.JsonPull(java.io.StringReader(text),strict=true)
        fun value(depth:Int) {
            when(parser.peek()) {
                com.xnotes.format.JsonPull.Token.BEGIN_OBJECT -> {
                    require(depth<64) {"Working session metadata is too deeply nested"}
                    parser.beginObject();val names=hashSetOf<String>()
                    while(parser.hasNext()) {require(names.add(parser.nextName())) {"Duplicate working session field"};value(depth+1)}
                    parser.endObject()
                }
                com.xnotes.format.JsonPull.Token.BEGIN_ARRAY -> {
                    require(depth<64) {"Working session metadata is too deeply nested"}
                    parser.beginArray();while(parser.hasNext()) value(depth+1);parser.endArray()
                }
                else -> parser.skipValue()
            }
        }
        require(parser.peek()==com.xnotes.format.JsonPull.Token.BEGIN_OBJECT)
        value(0);require(parser.peek()==com.xnotes.format.JsonPull.Token.END_DOCUMENT)
    }

    private fun readHeadBytes(file:File):ByteArray {
        if(Files.isSymbolicLink(file.toPath())) throw Invalid()
        return Files.newInputStream(file.toPath()).use {input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
            while(out.size()<=MAX_HEAD) {
                val n=input.read(buffer,0,minOf(buffer.size,MAX_HEAD+1-out.size()))
                if(n<0) break
                out.write(buffer,0,n)
            }
            if(out.size()>MAX_HEAD) throw Invalid()
            out.toByteArray()
        }
    }
    /** Recheck the small staged head after all callbacks, immediately before replacement. */
    private fun verifyHeadCandidate(file:File,expected:ByteArray) {
        if(!Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS)) throw Invalid()
        if(!readHeadBytes(file).contentEquals(expected)) throw Conflict()
    }

    /** Under the coordinator lock, retain evidence independently of owner/body reuse. */
    private fun checkedHead():Head {
        val head=readHead()
        if(state.observed && head.hash!=state.observedHeadHash) throw Conflict()
        if(!state.observed) {state.observed=true;state.observedHeadHash=head.hash}
        return head
    }

    /** Best effort only after a known, intact head exists. Unknown heads never authorize GC. */
    private fun cleanup() {
        val files=try {root.listFiles()?.filter {OWNED_NAME.matches(it.name)}.orEmpty()} catch(_:Exception) {return}
        if(files.isEmpty()) return
        val work=try {synchronized(state) {
            if(!state.observed) return
            val head=checkedHead();if(head.hash==null) return
            val candidates=files.filter {it.name!=head.body?.name && it.name !in state.writers && (state.readers[it.name] ?: 0)==0}
            if(candidates.isEmpty()) return
            head.body?.let(::acquire)
            head to candidates
        }} catch(_:Exception) {return}
        try {
            // Verify the current body before collecting a potentially last good older body.
            // Both validation and hashing remain outside the reservation lock.
            verify(work.first.body,{})
            for(file in work.second) {
                try {
                    onEvent(Event.BEFORE_DELETE,file)
                    synchronized(state) {
                        val head=checkedHead()
                        if(head.hash!=work.first.hash) return
                        if(file.name!=head.body?.name && file.name !in state.writers && (state.readers[file.name] ?: 0)==0) {
                            Files.deleteIfExists(file.toPath())
                        }
                    }
                } catch(_:Exception) { /* Retain files; a committed head stays committed. */ }
            }
        } catch(_:Exception) { /* Preserve all bodies when the current head cannot be verified. */ }
        finally {release(work.first.body)}
    }

    companion object {
        private const val MAX_HEAD=65536
        private const val MAX_METADATA=60*1024
        private val UUID_PATTERN="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        private val BODY_NAME=Regex("body-$UUID_PATTERN\\.zip")
        private val OWNED_NAME=Regex("(?:body-$UUID_PATTERN\\.zip(?:\\.pending)?|head-$UUID_PATTERN\\.pending)")
        private val HASH=Regex("[0-9a-f]{64}")
        private val coordinators=hashMapOf<String,WeakReference<Coordinator>>()
        private fun coordinator(key:String):Coordinator=synchronized(coordinators) {
            coordinators.entries.removeAll {it.value.get()==null}
            coordinators[key]?.get() ?: Coordinator().also {coordinators[key]=WeakReference(it)}
        }
        private fun sha(bytes:ByteArray)=hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun digest(file:File,checkActive:()->Unit):String {
            val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
            Files.newInputStream(file.toPath()).use {input->
                while(true) {checkActive();val n=input.read(buffer);if(n<0) break;hash.update(buffer,0,n)}
            }
            checkActive();return hex(hash.digest())
        }
        private fun hex(bytes:ByteArray)=bytes.joinToString("") {"%02x".format(it)}
    }
}
