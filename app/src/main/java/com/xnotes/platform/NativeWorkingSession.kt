package com.xnotes.platform

import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewSettings
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.Document
import com.xnotes.core.model.ReferenceIdentity
import com.xnotes.core.model.snapshot
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

/** Codec/metadata adapter only. A loaded source description never grants authority to save a model. */
internal class NativeWorkingSession(
    directory:File,private val noteCodec:DocumentCodec,private val canvasCodec:CanvasCodec,private val resources:PdfResources,
    onEvent:(WorkingSessionCache.Event,File)->Unit={_,_->},
) {
    data class NoteView(val zoom:Double,val scrollX:Double,val scrollY:Double,val zoomLocked:Boolean,val overrides:ViewOverrides)
    data class SourceDescription(val kind:DocumentKind,val documentId:String,val uri:String,val locationKey:String,val sha256:String)
    data class Metadata(val dirty:Boolean,val displayName:String?,val source:SourceDescription?,val noteView:NoteView?,val canvasView:Waypoint?)
    class Write internal constructor(internal val ticket:WorkingSessionCache.WriteRequest,internal val note:Document?,
                                     internal val canvas:InfiniteDocument?,internal val proof:PreparedNativeOpen.SaveRequest?) {
        val requiresBody get()=ticket.requiresBody
        internal fun sourceCurrent()=proof?.isCurrentSource()!=false
    }
    class Read internal constructor(private val issuer:Any,private val held:WorkingSessionCache.Read,val metadata:Metadata):AutoCloseable {
        private var closed=false
        val kind get()=held.kind
        val documentId get()=held.documentId
        val sha256 get()=held.sha256
        val body:File get()=synchronized(this) {check(!closed) {"Working session read is closed"};held.body}
        internal fun checkIssuer(owner:Any)=synchronized(this) {check(!closed && issuer===owner) {"Working session read is not owned by this adapter"}}
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

    /** Owner-thread capture/reservation. No filesystem/PDF/codec IO. */
    fun capture(document:Document,source:PreparedNativeOpen.EditSource?,view:NoteView):Write {
        validateView(view)
        val proof=source?.capture(document)
        if(proof==null && !document.path.isNullOrBlank()) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        val snapshot=proof?.snapshot ?: document.snapshot()
        val metadata=metadata(WorkingSessionCache.Kind.NOTE,document.dirty,document.displayName,
            proof?.expected?.let {SourceDescription(it.kind,it.documentId,requireNotNull(document.path),it.locationKey,it.sha256)},view,null)
        parseMetadata(WorkingSessionCache.Kind.NOTE,snapshot.identity.documentId,metadata.toString())
        val ticket=cache.reserve(snapshot.snapshotOwnerToken,WorkingSessionCache.Kind.NOTE,snapshot.identity.documentId,snapshot.bodyRevision,metadata)
        return Write(ticket,snapshot,null,proof)
    }
    fun capture(document:InfiniteDocument,source:PreparedNativeOpen.EditSource?):Write {
        document.lastView?.let(::validateWaypoint)
        val proof=source?.capture(document)
        if(proof==null && !document.path.isNullOrBlank()) throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        val snapshot=proof?.snapshot ?: document.snapshotForWrite()
        val metadata=metadata(WorkingSessionCache.Kind.CANVAS,document.dirty,document.displayName,
            proof?.expected?.let {SourceDescription(it.kind,it.documentId,requireNotNull(document.path),it.locationKey,it.sha256)},null,document.lastView)
        parseMetadata(WorkingSessionCache.Kind.CANVAS,snapshot.identity.documentId,metadata.toString())
        val ticket=cache.reserve(snapshot.snapshotOwnerToken,WorkingSessionCache.Kind.CANVAS,snapshot.identity.documentId,snapshot.bodyRevision,metadata)
        return Write(ticket,null,snapshot,proof)
    }
    fun isCurrent(write:Write)=cache.isCurrent(write.ticket) && write.sourceCurrent()
    fun reserveClear()=cache.reserveClear()
    fun publishClear(request:WorkingSessionCache.ClearRequest,checkActive:()->Unit={})=cache.publishClear(request,checkActive)
    /** IO-only. Checks the source epoch even when WorkingSessionCache reuses a body. */
    fun publish(write:Write,checkActive:()->Unit={}):WorkingSessionCache.Publication {
        if(!write.sourceCurrent()) return WorkingSessionCache.Publication(write.ticket,false)
        val check={checkActive();if(!write.sourceCurrent()) throw ExpiredSource()}
        return try {cache.publish(write.ticket,check) {output->
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
        }} catch(_:ExpiredSource) {WorkingSessionCache.Publication(write.ticket,false)}
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

    private fun metadata(kind:WorkingSessionCache.Kind,dirty:Boolean,name:String?,source:SourceDescription?,note:NoteView?,canvas:Waypoint?):JSONObject {
        val out=JSONObject().put("version",1).put("mode",extension(kind)).put("binding",if(source==null) "unbound" else "bound").put("dirty",dirty)
        name?.let {out.put("displayName",it)}
        source?.let {out.put("source",JSONObject().put("kind",it.kind.extension).put("documentId",it.documentId).put("uri",it.uri)
            .put("locationKey",it.locationKey).put("sha256",it.sha256))}
        if(note!=null) out.put("view",JSONObject().put("zoom",note.zoom).put("scrollX",note.scrollX).put("scrollY",note.scrollY)
            .put("zoomLocked",note.zoomLocked).put("overrides",ViewOverridesJson.write(JSONObject(),note.overrides)))
        if(canvas!=null) out.put("view",JSONObject().put("name",canvas.name).put("cx",canvas.cx).put("cy",canvas.cy).put("zoom",canvas.zoom))
        return out
    }
    private fun parseMetadata(kind:WorkingSessionCache.Kind,id:String,json:String):Metadata = try {
        val o=JSONObject(json);keys(o,setOf("version","mode","binding","dirty","displayName","source","view"))
        require(integer(o,"version")==1 && string(o,"mode")==extension(kind));ReferenceIdentity.requireUuid(id)
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
        Metadata(dirty,name,source,note,canvas)
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
    }
}
