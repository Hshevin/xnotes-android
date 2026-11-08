package com.xnotes.platform

import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewSettings
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.Document
import com.xnotes.core.model.ReferenceIdentity
import com.xnotes.core.model.prepareReferenceAnchors
import com.xnotes.core.model.copyWriteSnapshot
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.storage.PdfResources
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.PreparedNativeOpen
import com.xnotes.storage.ReferenceContractViolation
import com.xnotes.storage.StorageMsg
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import java.util.concurrent.atomic.AtomicReference

/** Codec/metadata adapter only. A loaded source description never grants authority to save a model. */
internal class NativeWorkingSession(
    directory:File,private val noteCodec:DocumentCodec,private val canvasCodec:CanvasCodec,private val resources:PdfResources,
    onEvent:(WorkingSessionCache.Event,File)->Unit={_,_->},
) {
    data class NoteView(val zoom:Double,val scrollX:Double,val scrollY:Double,val zoomLocked:Boolean,val overrides:ViewOverrides)
    data class SourceDescription(val kind:DocumentKind,val documentId:String,val uri:String,val locationKey:String,val sha256:String)
    data class Handoff(val id:String,val originalSha256:String,val candidateSha256:String,val recordDigest:String)
    data class Metadata(val dirty:Boolean,val displayName:String?,val source:SourceDescription?,val noteView:NoteView?,val canvasView:Waypoint?,val handoff:Handoff?=null)
    class Write internal constructor(internal val ticket:WorkingSessionCache.WriteRequest,internal val note:Document?,
                                     internal val canvas:InfiniteDocument?,internal val proof:PreparedNativeOpen.EditSource.Observation?,
                                     internal val handoff:Handoff?=null,private val captureSeal:Any?=null,internal val completion:Any?=null) {
        val requiresBody get()=ticket.requiresBody
        internal fun sourceCurrent()=proof?.isCurrent()!=false
        internal fun capturedBy(seal:Any)=captureSeal===seal
    }
    class Read internal constructor(private val issuer:Any,private val held:WorkingSessionCache.Read,val metadata:Metadata):AutoCloseable {
        private var closed=false
        val kind get()=held.kind
        val documentId get()=held.documentId
        val sha256 get()=held.sha256
        val body:File get()=synchronized(this) {check(!closed) {"Working session read is closed"};held.body}
        internal fun checkIssuer(owner:Any)=synchronized(this) {check(!closed && issuer===owner) {"Working session read is not owned by this adapter"}}
        internal fun owns(cache:WorkingSessionCache,owner:Any,minimumGeneration:Long=0)=cache.ownsPublished(held,owner,minimumGeneration)
        internal fun invalidate(cache:WorkingSessionCache,owner:Any)=cache.invalidateForRead(held,owner)
        internal fun verifyCurrent(cache:WorkingSessionCache,checkActive:()->Unit)=cache.verifyRead(held,checkActive)
        override fun close() {synchronized(this) {if(closed) return;closed=true};held.close()}
    }
    sealed interface Recovery {
        data object Empty:Recovery
        data class Unbound(val kind:WorkingSessionCache.Kind,val documentId:String,val metadata:Metadata):Recovery
        class Bound internal constructor(val prepared:PreparedNativeOpen,val metadata:Metadata):Recovery,AutoCloseable {
            override fun close()=prepared.close()
        }
    }
    private val readIssuer=Any()
    private class Intent(val staged:PreparedNativeOpen.SaveRequest.Receipt.Staged,val handoff:Handoff,val owner:Any)
    private class Completion(val intent:Intent,val proof:PreparedNativeOpen.EditSource.Observation) {
        fun matches(observation:PreparedNativeOpen.EditSource.Observation?,owner:Any)=
            owner===intent.owner && proof.sameEpoch(observation)
    }
    private class CaptureContext(val intent:Intent?,val completion:Completion?) {
        val handoff get()=if(completion==null) intent?.handoff else null
    }
    private class RootState {var intent:Intent?=null;var persisted=false;var completion:Completion?=null}
    private val rootState=rootState(directory.canonicalPath)
    private class ExpiredSource:CancellationException("Working source capture expired")
    private val cache=WorkingSessionCache(directory,validateBody={file,kind,id->
        try {
            val info=BundleReferenceInfo.inspect(file,true)
            require(info.format==extension(kind) && info.header.identity?.documentId==id)
            // Until durable cache resource pins exist, every cache PDF must be portable.
            require(info.header.identity?.externalPdf!=true)
        } catch(error:Exception) {
            if(error is CancellationException || error is WorkingSessionCache.Invalid) throw error
            throw WorkingSessionCache.Invalid(error)
        }
    },onEvent=onEvent,validateMetadata={kind,id,json->parseMetadata(kind,id,json)})

    private fun captureIntent(owner:Any,proof:PreparedNativeOpen.EditSource.Observation?,required:Intent?):CaptureContext=synchronized(rootState) {
        val intent=rootState.intent
        if(required!=null && required!==intent) throw WorkingSessionCache.Conflict()
        if(intent!=null) {
            val completing=rootState.completion
            if(completing!=null) {
                if(!completing.matches(proof,owner)) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
            } else if(intent.staged.acceptsCompletion(proof,owner)) {
                cache.invalidateReservations()
                rootState.completion=Completion(intent,requireNotNull(proof))
            } else intent.staged.checkCapture(proof,owner)
        }
        CaptureContext(intent,rootState.completion)
    }
    private fun needsHandoffBody(context:CaptureContext):Boolean=synchronized(rootState) {
        if(rootState.intent!==context.intent || rootState.completion!==context.completion) throw WorkingSessionCache.Conflict()
        context.intent!=null && context.completion==null && !rootState.persisted
    }
    private fun allowed(write:Write):Boolean=synchronized(rootState) {
        val intent=rootState.intent ?: return@synchronized write.handoff==null && write.completion==null
        val completing=rootState.completion
        if(completing!=null) return@synchronized write.completion===completing && write.handoff==null && completing.matches(write.proof,write.ticket.owner)
        if(write.completion!=null) return@synchronized false
        if(write.handoff!=intent.handoff || write.ticket.owner!==intent.owner) return@synchronized false
        try {intent.staged.checkCapture(write.proof,write.ticket.owner);true}
        catch(_:ReferenceContractViolation) {false}
    }
    private fun previousMetadata(previous:WorkingSessionCache.Previous?)=previous?.let {parseMetadata(it.kind,it.documentId,it.metadataJson)}
    private fun register(staged:PreparedNativeOpen.SaveRequest.Receipt.Staged,previous:Read):Intent=synchronized(rootState) {
        staged.checkCurrent();require(staged.usesResources(resources))
        val existing=rootState.intent
        if(existing!=null && existing.staged!==staged) throw WorkingSessionCache.Conflict()
        val handoff=Handoff(requireNotNull(staged.handoffId),staged.original.sha256,staged.candidate.sha256,staged.recordDigest)
        require(handoff.recordDigest==handoffDigest(staged.original.kind,handoff))
        // This order linearizes registration against a writer already past its last IO check.
        if(!previous.invalidate(cache,staged.ownerToken)) throw WorkingSessionCache.Conflict()
        if(existing!=null) return@synchronized existing
        Intent(staged,handoff,staged.ownerToken).also {rootState.intent=it;rootState.persisted=false}
    }
    private fun checkHead(read:Read,intent:Intent,checkActive:()->Unit,minimumGeneration:Long) {
        checkRecoveryRead(read);intent.staged.checkCurrent()
        synchronized(rootState) {if(rootState.intent!==intent) throw WorkingSessionCache.Conflict()}
        val source=read.metadata.source ?: throw WorkingSessionCache.Conflict()
        if(read.metadata.handoff!=intent.handoff || source.kind!=intent.staged.original.kind ||
            source.documentId!=intent.staged.original.documentId || source.locationKey!=intent.staged.original.locationKey ||
            source.sha256!=intent.staged.original.sha256 || !read.owns(cache,intent.owner,minimumGeneration)) throw WorkingSessionCache.Conflict()
        read.verifyCurrent(cache,checkActive);intent.staged.verifyWorkingBody(read.body,checkActive)
    }

    /** One owner-thread capture after the native candidate is fixed, never an old Write retagged. */
    class CapturePermit private constructor(private val store:NativeWorkingSession,private val intent:Intent) {
        private val seal=Any();private var used=false
        private fun take() {
            check(android.os.Looper.myLooper()===android.os.Looper.getMainLooper()) {"Handoff capture requires the owner thread"}
            check(!used) {"Handoff capture permit was already used"};used=true
        }
        fun capture(document:Document,source:PreparedNativeOpen.EditSource,view:NoteView):Write {
            take();return store.captureNote(document,source,view,seal,intent,forceBody=true)
        }
        fun capture(document:InfiniteDocument,source:PreparedNativeOpen.EditSource):Write {
            take();return store.captureCanvas(document,source,seal,intent,forceBody=true)
        }
        companion object {
            internal fun capture(store:NativeWorkingSession,staged:PreparedNativeOpen.SaveRequest.Receipt.Staged,
                                 previous:Read,capture:(CapturePermit)->Write):Pair<Write,Any> {
                val intent=store.register(staged,previous);val permit=CapturePermit(store,intent)
                val write=capture(permit)
                if(!permit.used || !write.capturedBy(permit.seal) || !store.allowed(write)) throw WorkingSessionCache.Conflict()
                return write to intent
            }
        }
    }
    /** Issued only after reading the adapter's durable current C+T head. No native write yet. */
    class HandoffPublication private constructor(private val store:NativeWorkingSession,private val intent:Intent,
                                                  private var read:Read,private val minimumGeneration:Long):AutoCloseable {
        private var closed=false
        val handoff get()=intent.handoff
        @Synchronized internal fun checkLive(staged:PreparedNativeOpen.SaveRequest.Receipt.Staged,checkActive:()->Unit) {
            checkActive()
            if(closed || staged!==intent.staged || synchronized(store.rootState) {store.rootState.intent!==intent} ||
                !read.owns(store.cache,intent.owner,minimumGeneration))
                throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
            staged.checkCurrent()
        }
        @Synchronized internal fun verify(staged:PreparedNativeOpen.SaveRequest.Receipt.Staged,checkActive:()->Unit={}) {
            check(!closed) {"Handoff publication is closed"}
            if(staged!==intent.staged) throw WorkingSessionCache.Conflict()
            staged.checkCurrent()
            // Editing may have persisted a newer C1+T since the proof was handed back. It must
            // meet the same actual-owner/generation/native contract; never silently use old C0.
            val current=store.load(checkActive) ?: throw WorkingSessionCache.Conflict()
            var transferred=false
            try {
                store.checkHead(current,intent,checkActive,minimumGeneration)
                val old=read;read=current;transferred=true;old.close()
            } finally {if(!transferred) current.close()}
        }
        @Synchronized override fun close() {if(closed) return;closed=true;read.close()}
        companion object {
            internal suspend fun persist(store:NativeWorkingSession,staged:PreparedNativeOpen.SaveRequest.Receipt.Staged,
                                         capture:(CapturePermit)->Write):HandoffPublication {
                val context=currentCoroutineContext();val check={context.ensureActive();staged.checkCurrent()}
                val preflight=AtomicReference<Read?>();val publication=AtomicReference<HandoffPublication?>()
                var failure:Throwable?=null
                try {
                    // An active checkpoint owned by this runtime model is required. First capture
                    // the ordinary working draft; an empty/cleared head is not an owner proof.
                    withContext(Dispatchers.IO) {
                        require(staged.usesResources(store.resources));check()
                        val old=store.load(check) ?: throw WorkingSessionCache.Conflict();preflight.set(old)
                        val source=old.metadata.source ?: throw WorkingSessionCache.Conflict()
                        if(source.kind!=staged.original.kind || source.documentId!=staged.original.documentId ||
                            source.locationKey!=staged.original.locationKey || source.sha256!=staged.original.sha256 ||
                            !old.owns(store.cache,staged.ownerToken)) throw WorkingSessionCache.Conflict()
                        if(old.metadata.handoff!=null && synchronized(store.rootState) {store.rootState.intent?.staged!==staged})
                            throw WorkingSessionCache.Conflict()
                    }
                    val (write,identity)=withContext(Dispatchers.Main.immediate) {
                        check();CapturePermit.capture(store,staged,requireNotNull(preflight.get()),capture)
                    }
                    withContext(Dispatchers.IO) {
                        check();store.publish(write,check)
                        // Only this attempt or a LATER same-owner publication can acknowledge it.
                        val held=store.load(check) ?: throw WorkingSessionCache.Conflict()
                        try {
                            val intent=synchronized(store.rootState) {store.rootState.intent?.takeIf {it===identity}}
                                ?: throw WorkingSessionCache.Conflict()
                            store.checkHead(held,intent,check,write.ticket.generation)
                            publication.set(HandoffPublication(store,intent,held,write.ticket.generation))
                        } catch(error:Throwable) {try {held.close()} catch(close:Throwable) {error.addSuppressed(close)};throw error}
                    }
                    // Keep ownership outside IO through every return-dispatch cancellation window.
                    withContext(NonCancellable+Dispatchers.IO) {preflight.getAndSet(null)?.close()}
                    check()
                    return requireNotNull(publication.getAndSet(null))
                } catch(error:Throwable) {failure=error;throw error}
                finally {
                    val old=preflight.getAndSet(null);val abandoned=publication.getAndSet(null)
                    val primary=failure
                    if(old!=null || abandoned!=null) withContext(NonCancellable+Dispatchers.IO) {
                        var cleanupFailure:Throwable?=null
                        for(resource in listOfNotNull<AutoCloseable>(old,abandoned)) try {resource.close()} catch(close:Throwable) {
                            if(primary!=null) primary.addSuppressed(close)
                            else if(cleanupFailure==null) cleanupFailure=close else cleanupFailure.addSuppressed(close)
                        }
                        cleanupFailure?.let {throw it}
                    }
                }
            }
        }
    }
    suspend fun persistHandoff(staged:PreparedNativeOpen.SaveRequest.Receipt.Staged,captureCurrent:(CapturePermit)->Write)=
        HandoffPublication.persist(this,staged,captureCurrent)

    /** Owner-thread capture/reservation. No filesystem/PDF/codec IO. */
    fun capture(document:Document,source:PreparedNativeOpen.EditSource?,view:NoteView):Write {
        return captureNote(document,source,view)
    }
    private fun captureNote(document:Document,source:PreparedNativeOpen.EditSource?,view:NoteView,
                            seal:Any?=null,required:Intent?=null,forceBody:Boolean=false):Write {
        validateView(view)
        val proof=source?.observe(document)
        if(proof==null && !document.path.isNullOrBlank()) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        document.prepareReferenceAnchors()
        val owner=document.snapshotOwnerToken;val id=document.identity.documentId;val version=document.bodyRevision
        val intent=captureIntent(owner,proof,required)
        val metadata=metadata(WorkingSessionCache.Kind.NOTE,document.dirty,document.displayName,
            proof?.expected?.let {SourceDescription(it.kind,it.documentId,requireNotNull(document.path),it.locationKey,it.sha256)},view,null,intent.handoff)
        parseMetadata(WorkingSessionCache.Kind.NOTE,id,metadata.toString())
        val ticket=cache.reserve(owner,WorkingSessionCache.Kind.NOTE,id,version,metadata,forceBody || needsHandoffBody(intent))
        val snapshot=if(ticket.requiresBody) document.copyWriteSnapshot(fixedItems=true) else null
        if(proof?.isCurrent()==false || snapshot!=null && (snapshot.snapshotOwnerToken!==owner || snapshot.identity.documentId!=id || snapshot.bodyRevision!=version))
            throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        return Write(ticket,snapshot,null,proof,intent.handoff,seal,intent.completion)
    }
    fun capture(document:InfiniteDocument,source:PreparedNativeOpen.EditSource?):Write {
        return captureCanvas(document,source)
    }
    private fun captureCanvas(document:InfiniteDocument,source:PreparedNativeOpen.EditSource?,seal:Any?=null,
                              required:Intent?=null,forceBody:Boolean=false):Write {
        document.lastView?.let(::validateWaypoint)
        val proof=source?.observe(document)
        if(proof==null && !document.path.isNullOrBlank()) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        val owner=document.snapshotOwnerToken;val id=document.identity.documentId;val version=document.bodyRevision
        val intent=captureIntent(owner,proof,required)
        val metadata=metadata(WorkingSessionCache.Kind.CANVAS,document.dirty,document.displayName,
            proof?.expected?.let {SourceDescription(it.kind,it.documentId,requireNotNull(document.path),it.locationKey,it.sha256)},null,document.lastView,intent.handoff)
        parseMetadata(WorkingSessionCache.Kind.CANVAS,id,metadata.toString())
        val ticket=cache.reserve(owner,WorkingSessionCache.Kind.CANVAS,id,version,metadata,forceBody || needsHandoffBody(intent))
        val snapshot=if(ticket.requiresBody) document.snapshotForWrite(fixedItems=true) else null
        if(proof?.isCurrent()==false || snapshot!=null && (snapshot.snapshotOwnerToken!==owner || snapshot.identity.documentId!=id || snapshot.bodyRevision!=version))
            throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        return Write(ticket,null,snapshot,proof,intent.handoff,seal,intent.completion)
    }
    fun isCurrent(write:Write)=cache.isCurrent(write.ticket) && write.sourceCurrent() && allowed(write)
    fun reserveClear()=cache.reserveClear()
    fun publishClear(request:WorkingSessionCache.ClearRequest,checkActive:()->Unit={}):WorkingSessionCache.Publication {
        val check={checkActive();synchronized(rootState) {if(rootState.intent!=null) throw WorkingSessionCache.Conflict()}}
        check()
        return cache.publishClearChecked(request,check) {previous->if(previousMetadata(previous)?.handoff!=null) throw WorkingSessionCache.Conflict()}
    }
    /** IO-only. Checks the source epoch even when WorkingSessionCache reuses a body. */
    fun publish(write:Write,checkActive:()->Unit={}):WorkingSessionCache.Publication {
        if(!write.sourceCurrent() || !allowed(write)) return WorkingSessionCache.Publication(write.ticket,false)
        val check={checkActive();if(!write.sourceCurrent() || !allowed(write)) throw ExpiredSource()}
        return try {cache.publishChecked(write.ticket,check,{previous->
            val metadata=previousMetadata(previous);val old=metadata?.handoff
            if(write.completion!=null) {
                val completion=synchronized(rootState) {rootState.completion?.takeIf {it===write.completion}} ?: throw WorkingSessionCache.Conflict()
                if(previous==null || !cache.ownsPrevious(previous,write.ticket.owner) || old!=null && old!=completion.intent.handoff)
                    throw WorkingSessionCache.Conflict()
                val source=metadata?.source ?: throw WorkingSessionCache.Conflict()
                val expected=if(old==null) completion.proof.expected else completion.intent.staged.original
                if(source.kind!=expected.kind || source.documentId!=expected.documentId || source.locationKey!=expected.locationKey || source.sha256!=expected.sha256)
                    throw WorkingSessionCache.Conflict()
            } else if(old!=null && old!=write.handoff) throw WorkingSessionCache.Conflict()
            if(write.completion==null && write.handoff!=null && previous!=null) {
                val intent=synchronized(rootState) {rootState.intent} ?: throw WorkingSessionCache.Conflict()
                val source=metadata?.source ?: throw WorkingSessionCache.Conflict()
                val expected=intent.staged.original
                if(!cache.ownsPrevious(previous,write.ticket.owner) || source.kind!=expected.kind || source.documentId!=expected.documentId ||
                    source.locationKey!=expected.locationKey || source.sha256!=expected.sha256) throw WorkingSessionCache.Conflict()
            }
        }) {output->
            kotlin.check(write.requiresBody) {"View-only publication must not encode a body"}
            val note=write.note
            if(note!=null) {
                val portable=note.copyWriteSnapshot()
                val pdf=portable.pdfFile
                val held=pdf?.let {resources.admitHeld(it,"PDF",check)}
                var failure:Throwable?=null;var encoded=false
                try {
                    if(held!=null) {portable.pdfFile=held.file;portable.identity=portable.identity.copy(pdfResource=held.file.nameWithoutExtension)}
                    check();noteCodec.write(portable,output,embedPdf=true,isCancelled={check();false});encoded=true
                } catch(error:Throwable) {failure=error;throw error}
                finally {
                    try {held?.lease?.close()} catch(close:Throwable) {
                        if(failure!=null) failure.addSuppressed(close)
                        else if(encoded) android.util.Log.w("xnotes.session","Retained PDF lease after cache encoding",close)
                        else throw close
                    }
                }
            } else canvasCodec.write(requireNotNull(write.canvas),output,isCancelled={check();false})
        }.also {result->if(result.published) synchronized(rootState) {
            // The source may have retired after the head committed. Runtime commit provenance,
            // rather than a live editing grant, authorizes this memory-only cleanup.
            if(write.completion!=null && rootState.completion===write.completion && cache.invalidateForPublication(write.ticket)) {
                rootState.intent=null;rootState.completion=null;rootState.persisted=false
            } else if(write.handoff!=null && rootState.intent?.handoff==write.handoff && rootState.completion==null) rootState.persisted=true
        }}} catch(_:ExpiredSource) {WorkingSessionCache.Publication(write.ticket,false)}
    }
    /** Lease + data only. The caller must still verify the actual source before any writable restoration. */
    fun load(checkActive:()->Unit={}):Read? {
        val held=cache.load(checkActive) ?: return null
        try {return Read(readIssuer,held,parseMetadata(held.kind,held.documentId,held.metadataJson))}
        catch(error:Throwable) {try {held.close()} catch(close:Throwable) {error.addSuppressed(close)};throw error}
    }

    /** Internal proof check for the decoding factory; neither a tuple nor a closed lease can stand in for a load. */
    internal fun checkRecoveryRead(read:Read)=read.checkIssuer(readIssuer)

    /** IO-only. No UI replacement, cache consumption or native write. Only a verified bound source can issue a preparation. */
    fun prepareRecovery(storage:NotebookStorage,pdfDir:File,assetRoot:File,checkActive:()->Unit={}):Recovery {
        require(storage.pdfResources===resources)
        var prepared:PreparedNativeOpen?=null
        try {
            val read=load(checkActive) ?: return Recovery.Empty
            read.use {
                if(it.metadata.handoff!=null) throw WorkingSessionCache.Conflict() // Actual-B recovery/claim follows the persistence boundary.
                val origin=it.metadata.source ?: return Recovery.Unbound(it.kind,it.documentId,it.metadata)
                storage.openSource(origin.uri,origin.kind,checkActive).use {source->
                    prepared=PreparedNativeOpen.readWorking(this,it,source,resources,noteCodec,canvasCodec,pdfDir,assetRoot,checkActive)
                }
                checkActive();checkRecoveryRead(it)
                return Recovery.Bound(requireNotNull(prepared),it.metadata)
            }
        } catch(error:Throwable) {
            try {prepared?.close()} catch(close:Throwable) {error.addSuppressed(close)}
            throw error
        }
    }

    private fun metadata(kind:WorkingSessionCache.Kind,dirty:Boolean,name:String?,source:SourceDescription?,note:NoteView?,canvas:Waypoint?,handoff:Handoff?=null):JSONObject {
        val out=JSONObject().put("version",2).put("mode",extension(kind)).put("binding",if(source==null) "unbound" else "bound").put("dirty",dirty || handoff!=null)
        name?.let {out.put("displayName",it)}
        source?.let {out.put("source",JSONObject().put("kind",it.kind.extension).put("documentId",it.documentId).put("uri",it.uri)
            .put("locationKey",it.locationKey).put("sha256",it.sha256))}
        handoff?.let {out.put("handoff",JSONObject().put("id",it.id).put("originalSha256",it.originalSha256)
            .put("candidateSha256",it.candidateSha256).put("recordDigest",it.recordDigest))}
        if(note!=null) out.put("view",JSONObject().put("zoom",note.zoom).put("scrollX",note.scrollX).put("scrollY",note.scrollY)
            .put("zoomLocked",note.zoomLocked).put("overrides",ViewOverridesJson.write(JSONObject(),note.overrides)))
        if(canvas!=null) out.put("view",JSONObject().put("name",canvas.name).put("cx",canvas.cx).put("cy",canvas.cy).put("zoom",canvas.zoom))
        return out
    }
    private fun parseMetadata(kind:WorkingSessionCache.Kind,id:String,json:String):Metadata = try {
        val o=JSONObject(json);val version=integer(o,"version");require(version==1 || version==2)
        keys(o,setOf("version","mode","binding","dirty","displayName","source","view")+if(version==2) setOf("handoff") else emptySet())
        require(string(o,"mode")==extension(kind));ReferenceIdentity.requireUuid(id)
        val dirty=boolean(o,"dirty");val name=if(o.has("displayName")) string(o,"displayName") else null
        val source=when(string(o,"binding")) {
            "unbound" -> {require(!o.has("source"));null}
            "bound" -> {
                val s=obj(o,"source");keys(s,setOf("kind","documentId","uri","locationKey","sha256"))
                val sourceKind=when(string(s,"kind")) {"xnote"->DocumentKind.NOTE;"xcanvas"->DocumentKind.CANVAS;else->error("Unknown source kind")}
                val sourceId=string(s,"documentId");ReferenceIdentity.requireUuid(sourceId)
                require(sourceId==id && sourceKind.extension==extension(kind))
                val uri=string(s,"uri");val key=string(s,"locationKey");val sha=string(s,"sha256")
                require(uri.isNotBlank() && key.isNotBlank() && resources.registry.locationKey(uri)==key && HASH.matches(sha))
                SourceDescription(sourceKind,sourceId,uri,key,sha)
            }
            else -> error("Unknown source binding")
        }
        val note:NoteView?;val canvas:Waypoint?
        if(kind==WorkingSessionCache.Kind.NOTE) {
            val v=obj(o,"view");keys(v,setOf("zoom","scrollX","scrollY","zoomLocked","overrides"))
            val overrides=obj(v,"overrides");validateOverrides(overrides)
            note=NoteView(number(v,"zoom"),number(v,"scrollX"),number(v,"scrollY"),boolean(v,"zoomLocked"),ViewOverridesJson.read(overrides))
            validateView(note);canvas=null
        } else {
            note=null
            canvas=if(o.has("view")) {
                val v=obj(o,"view");keys(v,setOf("name","cx","cy","zoom"))
                Waypoint(string(v,"name"),number(v,"cx"),number(v,"cy"),number(v,"zoom")).also(::validateWaypoint)
            } else null
        }
        val handoff=if(o.has("handoff")) {
            requireNotNull(source);val h=obj(o,"handoff");keys(h,setOf("id","originalSha256","candidateSha256","recordDigest"))
            val value=Handoff(string(h,"id"),string(h,"originalSha256"),string(h,"candidateSha256"),string(h,"recordDigest"))
            ReferenceIdentity.requireUuid(value.id);require(HASH.matches(value.originalSha256) && HASH.matches(value.candidateSha256) && HASH.matches(value.recordDigest))
            require(value.originalSha256==source.sha256 && value.recordDigest==handoffDigest(source.kind,value))
            value
        } else null
        Metadata(dirty,name,source,note,canvas,handoff)
    } catch(error:WorkingSessionCache.Invalid) {throw error}
      catch(error:Exception) {throw WorkingSessionCache.Invalid(error)}

    private fun validateView(view:NoteView) {
        require(view.zoom.isFinite() && view.zoom>=0 && view.scrollX.isFinite() && view.scrollY.isFinite())
        listOfNotNull(view.overrides.multiply,view.overrides.screen).forEach {require(it.a==255 && it.r in 0..255 && it.g in 0..255 && it.b in 0..255)}
        validateOverrides(ViewOverridesJson.write(JSONObject(),view.overrides))
    }
    private fun validateWaypoint(view:Waypoint) {require(view.name.length<=Waypoint.MAX_NAME_LENGTH && view.cx.isFinite() && view.cy.isFinite() && view.zoom.isFinite() && view.zoom>0)}
    private fun validateOverrides(o:JSONObject) {
        keys(o,setOf("mode","verticalScroll","freeMovement","contrast","invert","brightness","sepia","multiply","screen","keepImages","rotation","scrollbar"))
        if(o.has("mode")) require(ViewingMode.entries.any {it.id==string(o,"mode")})
        for(key in listOf("verticalScroll","freeMovement","keepImages","scrollbar")) if(o.has(key)) boolean(o,key)
        for(key in listOf("contrast","brightness","sepia")) if(o.has(key)) require(integer(o,key) in 0..200)
        if(o.has("invert")) require(integer(o,"invert") in 0..100)
        if(o.has("rotation")) require(integer(o,"rotation") in ViewSettings.ROTATIONS)
        for(key in listOf("multiply","screen")) if(o.has(key)) require(COLOR.matches(string(o,key)))
    }
    private fun keys(o:JSONObject,allowed:Set<String>) {require(o.keys().asSequence().all {it in allowed})}
    private fun string(o:JSONObject,key:String)=o.opt(key) as? String ?: throw WorkingSessionCache.Invalid()
    private fun boolean(o:JSONObject,key:String)=o.opt(key) as? Boolean ?: throw WorkingSessionCache.Invalid()
    private fun obj(o:JSONObject,key:String)=o.opt(key) as? JSONObject ?: throw WorkingSessionCache.Invalid()
    private fun number(o:JSONObject,key:String):Double {
        val n=o.opt(key);require(n is Number);return n.toDouble().also {require(it.isFinite())}
    }
    private fun integer(o:JSONObject,key:String):Int {
        val n=o.opt(key);require(n is Int || n is Long)
        val value=(n as Number).toLong();require(value in Int.MIN_VALUE..Int.MAX_VALUE);return value.toInt()
    }
    companion object {
        private val HASH=Regex("[a-f0-9]{64}")
        private val COLOR=Regex("#[a-fA-F0-9]{6}")
        private fun extension(kind:WorkingSessionCache.Kind)=if(kind==WorkingSessionCache.Kind.NOTE) "xnote" else "xcanvas"
        private val roots=hashMapOf<String,WeakReference<RootState>>()
        private fun rootState(key:String):RootState=synchronized(roots) {
            roots.entries.removeAll {it.value.get()==null}
            roots[key]?.get() ?: RootState().also {roots[key]=WeakReference(it)}
        }
        private fun handoffDigest(kind:DocumentKind,h:Handoff):String {
            val purpose=when(kind) {DocumentKind.NOTE->"save-note";DocumentKind.CANVAS->"save-canvas";else->error("Unknown native handoff kind")}
            val text="4\n${h.originalSha256}\n${h.candidateSha256}\n$purpose\n${h.id}"
            return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
        }
    }
}
