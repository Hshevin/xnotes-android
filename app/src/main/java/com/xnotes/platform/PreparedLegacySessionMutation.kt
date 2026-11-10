package com.xnotes.platform

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.format.BundleMigration
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Canonical-slot permission only; never a native source or handoff save receipt. */
internal class LegacyAbsentObservation internal constructor(internal val access:LegacyAccess,
    internal val cache:WorkingSessionCache.AbsentObservation)
internal sealed interface LegacyAbsentResult {
    class Granted internal constructor(val observation:LegacyAbsentObservation):LegacyAbsentResult
    data object ProtectedRecovery:LegacyAbsentResult
    class Failed internal constructor(val cause:Throwable):LegacyAbsentResult
}
internal enum class LegacyMutationStep { NOTE_BODY,NOTE_METADATA,CANVAS_BODY,CANVAS_METADATA }
internal class LegacyPublication internal constructor(
    val bodyFile:File?,val snapshotOwnerToken:Any?,val bodySha256:String?,val documentId:String?,
)
internal sealed interface LegacyMutationResult {
    class Published internal constructor(val publication:LegacyPublication):LegacyMutationResult
    data object NeedsBody:LegacyMutationResult
    data object Changed:LegacyMutationResult
    data object ProtectedRecovery:LegacyMutationResult
    class Failed internal constructor(val completed:List<LegacyMutationStep>,val cause:Throwable):LegacyMutationResult
}

/** The only nested lock order here is NativeRootState -> the existing Cache coordinator. */
internal class LegacyAccess internal constructor(internal val directory:File,private val nativeRoot:Any,
    private val nativeAvailable:()->Boolean,private val boundary:WorkingSessionCache.LegacyBoundary) {
    fun observeAbsent():LegacyAbsentResult=try {synchronized(nativeRoot) {
        if(!nativeAvailable()) LegacyAbsentResult.ProtectedRecovery
        else boundary.observe()?.let {LegacyAbsentResult.Granted(LegacyAbsentObservation(this,it))}
            ?: LegacyAbsentResult.ProtectedRecovery
    }} catch(error:Exception) {LegacyAbsentResult.Failed(error)}
    fun publish(observation:LegacyAbsentObservation,mutation:PreparedLegacySessionMutation):LegacyMutationResult {
        if(observation.access!==this || mutation.directory!=directory) return LegacyMutationResult.Changed
        if(!mutation.claim()) return LegacyMutationResult.Changed
        return try {synchronized(nativeRoot) {
            if(!nativeAvailable()) LegacyMutationResult.ProtectedRecovery else boundary.publish(observation.cache,mutation)
        }} catch(error:Exception) {LegacyMutationResult.Failed(emptyList(),error)}
        finally {mutation.finishPublishing()}
    }
    internal fun consumeForRestore(observation:LegacyAbsentObservation):Boolean=try {synchronized(nativeRoot) {
        observation.access===this && nativeAvailable() && boundary.consume(observation.cache)
    }} catch(_:Exception) {false}
    fun isCurrent(observation:LegacyAbsentObservation):Boolean=try {synchronized(nativeRoot) {
        observation.access===this && nativeAvailable() && boundary.isCurrent(observation.cache)
    }} catch(_:Exception) {false}
    fun consumeForNewDraft(observation:LegacyAbsentObservation)=consumeForRestore(observation)
}

/** Fixed legacy filenames only. Encoding and candidate ownership precede the final lock. */
internal class PreparedLegacySessionMutation private constructor(internal val directory:File,
    private val fileOps:FileOps):AutoCloseable {
    internal interface FileOps {
        fun replace(source:File,target:File)
        fun delete(target:File)
    }
    internal object SystemFileOps:FileOps {
        override fun replace(source:File,target:File) {Files.move(source.toPath(),target.toPath(),ATOMIC_MOVE,REPLACE_EXISTING)}
        override fun delete(target:File) {Files.deleteIfExists(target.toPath())}
    }
    private class Candidate(val file:File,private val unregister:()->Unit) {
        val attributes:BasicFileAttributes=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
        var published=false
        var uncertain=false
        fun ownsPath():Boolean=runCatching {
            val actual=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            actual.isRegularFile && attributes.fileKey()!=null && actual.fileKey()==attributes.fileKey()
        }.getOrDefault(false)
        fun cleanup() {try {if(ownsPath()) Files.deleteIfExists(file.toPath())} finally {unregister()}}
        fun matches(target:File):Boolean=runCatching {
            val actual=Files.readAttributes(target.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            actual.isRegularFile && attributes.fileKey()!=null && actual.fileKey()==attributes.fileKey() && actual.size()==attributes.size()
        }.getOrDefault(false)
    }
    private class Operation(val step:LegacyMutationStep,val target:File,val candidate:Candidate?)
    private val operations=ArrayList<Operation>()
    private val candidates=ArrayList<Candidate>()
    private val state=AtomicInteger(0) // prepared, publishing, finished, closed
    private val closeRequested=AtomicBoolean(false)
    private val cleaned=AtomicBoolean(false)
    private var preparationFailure:Throwable?=null
    private var needsBody=false
    private var retainedBody:Pair<File,BasicFileAttributes>?=null
    private var bodyTarget:File?=null
    private var bodyToken:Any?=null
    private var bodyHash:String?=null
    private var bodyId:String?=null

    internal fun claim()=state.compareAndSet(0,1)
    internal fun finishPublishing() {state.set(2);if(closeRequested.get()) cleanup()}
    override fun close() {
        closeRequested.set(true)
        if(state.compareAndSet(0,3) || state.compareAndSet(2,3)) cleanup()
    }
    private fun cleanup() {
        if(!cleaned.compareAndSet(false,true)) return
        for(candidate in candidates) if(!candidate.uncertain) candidate.cleanup()
    }
    private fun target(name:String):File {
        require(name in setOf("document.xnote","session.json","canvas.xcanvas","canvas-session.json"))
        return File(directory,name)
    }
    private fun regularOrAbsent(file:File):BasicFileAttributes?=try {
        Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            .also {require(it.isRegularFile) {"Invalid legacy session target"}}
    } catch(_:NoSuchFileException) {null}
    private fun candidate(step:LegacyMutationStep,name:String,file:File,cleanup:()->Unit) {
        val held=Candidate(file,cleanup);candidates.add(held);operations.add(Operation(step,target(name),held))
    }
    private fun stageMetadata(step:LegacyMutationStep,name:String,json:JSONObject) {
        check(directory.isDirectory || directory.mkdirs())
        val bytes=json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=64*1024) {"Legacy session metadata exceeds its budget"}
        val file=File.createTempFile("legacy-meta-",".pending",directory)
        val original=Candidate(file) {}
        try {
            FileOutputStream(file).use {it.write(bytes);it.fd.sync()}
            check(original.ownsPath()) {"Legacy metadata candidate was replaced"}
            candidate(step,name,file) {}
        } catch(error:Throwable) {original.cleanup();throw error}
    }
    private fun encodeBody(step:LegacyMutationStep,name:String,owner:Any,id:String,write:(java.io.OutputStream)->Unit) {
        check(directory.isDirectory || directory.mkdirs())
        val held=LegacySessionBodyCandidates.create(directory,if(step==LegacyMutationStep.NOTE_BODY) "document-" else "canvas-",
            if(step==LegacyMutationStep.NOTE_BODY) ".xnote.tmp" else ".xcanvas.tmp")
        try {
            FileOutputStream(held.file).use {output->
                write(object:FilterOutputStream(output) {
                    override fun write(bytes:ByteArray,offset:Int,length:Int)=output.write(bytes,offset,length)
                    override fun close() {flush()}
                });output.fd.sync()
            }
            check(held.ownsFile()) {"Legacy body candidate was replaced"}
            val hash=BundleMigration.hash(held.file)
            candidate(step,name,held.file,held::unregister)
            bodyTarget=target(name);bodyToken=owner;bodyHash=hash;bodyId=id
        } catch(error:Throwable) {held.close();throw error}
    }
    private fun requireBody(name:String) {
        val file=target(name);val attributes=regularOrAbsent(file)
        if(attributes==null) needsBody=true else retainedBody=file to attributes
    }
    private fun delete(step:LegacyMutationStep,name:String) {operations.add(Operation(step,target(name),null))}

    /** No file was published yet: NEEDS_BODY/failure must not invalidate an older active encoder. */
    internal fun preflightBounded():LegacyMutationResult? {
        preparationFailure?.let {return LegacyMutationResult.Failed(emptyList(),it)}
        if(needsBody) return LegacyMutationResult.NeedsBody
        return try {
            retainedBody?.let {(file,before)->
                val actual=regularOrAbsent(file) ?: return LegacyMutationResult.NeedsBody
                if(actual.fileKey()!=before.fileKey() || actual.size()!=before.size() || actual.lastModifiedTime()!=before.lastModifiedTime())
                    return LegacyMutationResult.Changed
            }
            null
        } catch(error:Exception) {LegacyMutationResult.Failed(emptyList(),error)}
    }
    /** Publishing ownership is already held. No lifecycle monitor or application callback. */
    internal fun executeBounded():LegacyMutationResult {
        val completed=ArrayList<LegacyMutationStep>()
        try {
            for(operation in operations) {
                regularOrAbsent(operation.target)
                val held=operation.candidate
                if(held==null) fileOps.delete(operation.target)
                else {
                    check(held.matches(held.file)) {"Legacy candidate changed before publication"}
                    try {fileOps.replace(held.file,operation.target);held.published=true}
                    catch(error:Throwable) {
                        if(held.matches(operation.target)) {held.published=true;completed.add(operation.step)}
                        else if(!held.matches(held.file)) held.uncertain=true
                        throw error
                    }
                }
                completed.add(operation.step)
            }
            return LegacyMutationResult.Published(LegacyPublication(bodyTarget,bodyToken,bodyHash,bodyId))
        } catch(error:Exception) {return LegacyMutationResult.Failed(completed.toList(),error)}
    }
    companion object {
        private fun prepare(directory:File,fileOps:FileOps,build:PreparedLegacySessionMutation.()->Unit):PreparedLegacySessionMutation {
            val result=PreparedLegacySessionMutation(directory.canonicalFile,fileOps)
            try {LegacySessionBodyCandidates.cleanup(result.directory);result.build()} catch(error:Exception) {result.preparationFailure=error}
            return result
        }
        internal fun prepareNote(directory:File,codec:DocumentCodec,body:Document?,metadata:SessionStore.Metadata,
            clearCanvas:Boolean=true,fileOps:FileOps=SystemFileOps)=prepare(directory,fileOps) {
            if(body==null) requireBody("document.xnote") else encodeBody(LegacyMutationStep.NOTE_BODY,"document.xnote",
                body.snapshotOwnerToken,body.identity.documentId) {codec.write(body,it)}
            val json=JSONObject().put("dirty",metadata.dirty).put("zoom",metadata.zoom).put("scrollX",metadata.scrollX)
                .put("scrollY",metadata.scrollY).put("zoomLocked",metadata.zoomLocked)
            metadata.path?.let {json.put("path",it)};metadata.displayName?.let {json.put("displayName",it)}
            ViewOverridesJson.write(json,metadata.viewOverrides)
            stageMetadata(LegacyMutationStep.NOTE_METADATA,"session.json",json)
            if(clearCanvas) {delete(LegacyMutationStep.CANVAS_BODY,"canvas.xcanvas");stageMetadata(LegacyMutationStep.CANVAS_METADATA,"canvas-session.json",JSONObject())}
        }
        internal fun prepareCanvas(directory:File,codec:CanvasCodec,body:InfiniteDocument?,metadata:CanvasSessionStore.Metadata,
            clearNote:Boolean=true,fileOps:FileOps=SystemFileOps)=prepare(directory,fileOps) {
            if(body==null) requireBody("canvas.xcanvas") else encodeBody(LegacyMutationStep.CANVAS_BODY,"canvas.xcanvas",
                body.snapshotOwnerToken,body.identity.documentId) {codec.write(body,it)}
            val json=JSONObject().put("dirty",metadata.dirty)
            metadata.path?.let {json.put("path",it)};metadata.displayName?.let {json.put("display_name",it)}
            stageMetadata(LegacyMutationStep.CANVAS_METADATA,"canvas-session.json",json)
            if(clearNote) {delete(LegacyMutationStep.NOTE_BODY,"document.xnote");delete(LegacyMutationStep.NOTE_METADATA,"session.json")}
        }
        fun prepareClosed(directory:File)=prepare(directory,SystemFileOps) {
            delete(LegacyMutationStep.NOTE_BODY,"document.xnote");delete(LegacyMutationStep.NOTE_METADATA,"session.json")
            delete(LegacyMutationStep.CANVAS_BODY,"canvas.xcanvas");stageMetadata(LegacyMutationStep.CANVAS_METADATA,"canvas-session.json",JSONObject())
        }
        internal fun prepareNoteClear(directory:File)=prepare(directory,SystemFileOps) {
            delete(LegacyMutationStep.NOTE_BODY,"document.xnote");delete(LegacyMutationStep.NOTE_METADATA,"session.json")
        }
        internal fun prepareCanvasClear(directory:File)=prepare(directory,SystemFileOps) {
            delete(LegacyMutationStep.CANVAS_BODY,"canvas.xcanvas");stageMetadata(LegacyMutationStep.CANVAS_METADATA,"canvas-session.json",JSONObject())
        }
    }
}

/** Old store entry points share the same bounded publication gate as whole-workspace plans. */
internal fun publishLegacyMutation(directory:File,prepare:()->PreparedLegacySessionMutation):LegacyMutationResult=try {
    val access=NativeWorkingSession.legacyAccess(directory)
    when(val observed=access.observeAbsent()) {
        is LegacyAbsentResult.Granted->prepare().use {access.publish(observed.observation,it)}
        LegacyAbsentResult.ProtectedRecovery->LegacyMutationResult.ProtectedRecovery
        is LegacyAbsentResult.Failed->LegacyMutationResult.Failed(emptyList(),observed.cause)
    }
} catch(error:Exception) {LegacyMutationResult.Failed(emptyList(),error)}

internal fun LegacyMutationResult.legacyWrite():LegacySessionWrite=when(this) {
    is LegacyMutationResult.Published->LegacySessionWrite.SAVED
    LegacyMutationResult.NeedsBody->LegacySessionWrite.NEEDS_BODY
    else->LegacySessionWrite.FAILED
}
