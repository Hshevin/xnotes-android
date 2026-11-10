package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import com.xnotes.storage.DocumentRegistryIdentityEpoch
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.OriginalPdfCatalog
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class MapPairChanged:Exception()

/** A pair reads the real JSON independently of a live/recovered actor. No writer permission. */
internal class MapPairAccess(context:Context) {
    private val app=context.applicationContext
    private val registry=NotebookStorage.get(app).registry
    private val catalog=OriginalPdfCatalog(registry)
    internal data class Disk(val sha:String,val identity:DocumentRegistryIdentityEpoch.Ticket,
        val domain:OriginalPdfCatalog.IdentityDomainsBatch)

    internal data class Initial(val id:String,val sha256:String)
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    /** Capture before acquiring a fresh actor so its actual load cannot be rebound to later bytes. */
    @MainThread suspend fun initial(uri:Uri):Initial {
        val coroutine=currentCoroutineContext();val check={coroutine.ensureActive()}
        return MapSaves.lockFor(uri).withLock {withContext(Dispatchers.IO) {
            val bytes=MapFiles.target(app,uri,check).read()
            val document=MapCodec.decode(bytes);check()
            identity(uri,document.id,check)
            Initial(document.id,hash(bytes))
        }}
    }

    private data class Identity(val ticket:DocumentRegistryIdentityEpoch.Ticket,val domain:OriginalPdfCatalog.IdentityDomainsBatch)
    /** Before acquire: a failed pair must not let actor discovery register a reserved/native ID. */
    private fun identity(uri:Uri,id:String,check:()->Unit):Identity {
        val identity=registry.identityObservation(id)
        val domain=catalog.identityDomains(setOf(id),check)
        if(!domain.supported || domain.unverifiable || id in domain.reservedOriginalIds) throw MapPairChanged()
        val at=registry.expectationAt(uri.toString())
        if(at!=null && at.documentId!=id || at?.requiresPages==true || registry.expectationForId(id).requiresPages ||
            registry.kindForId(id)?.let {it!="xmap"}==true) throw MapPairChanged()
        val positions=registry.fileObservations(setOf(id),check)
        if(positions.limited || positions.files[id]?.locations.orEmpty().filter {it.available}
                .any {MapSaves.key(Uri.parse(it.uri))!=MapSaves.key(uri)}) throw MapPairChanged()
        check()
        if(!identity.current() || !domain.current()) throw MapPairChanged()
        return Identity(identity,domain)
    }

    private fun inspect(uri:Uri,id:String,covered:Set<String>,check:()->Unit):Disk {
        check()
        val identity=identity(uri,id,check)
        val bytes=MapFiles.target(app,uri,check).read()
        if(MapCodec.decode(bytes).id!=id) throw MapPairChanged()
        val fixed=object:MapTarget {
            override fun read()=bytes
            override fun write(bytes:ByteArray):Unit=error("A pair observation cannot write")
        }
        val store=MapFiles.store(app,uri,fixed,check,covered)
        if(store.recoveryOutside(covered) || store.observeForIdentity(id,check).document.id!=id) throw MapPairChanged()
        check()
        if(!identity.ticket.current() || !identity.domain.current()) throw MapPairChanged()
        return Disk(hash(bytes),identity.ticket,identity.domain)
    }

    @MainThread suspend fun prepare(uri:Uri,lease:MapDocumentLease,initial:Initial,requireLoadBaseline:Boolean):Held {
        val session=lease.session
        val document=session.document ?: throw MapPairChanged()
        val epoch=session.interactionEpoch;val revision=session.contentRevision
        val serial=MapDocuments.observationVersion(app,MapSaves.key(uri))
        val covered=session.recoveryKeys.toSet()
        fun current()=actorCurrent(uri,lease,document,epoch,revision,serial)
        if(!current()) throw MapPairChanged()
        val coroutine=currentCoroutineContext();val check={coroutine.ensureActive()}
        val disk=MapSaves.lockFor(uri).withLock {withContext(Dispatchers.IO) {inspect(uri,document.id,covered,check)}}
        if(!current() || document.id!=initial.id || disk.sha!=initial.sha256 ||
            requireLoadBaseline && session.loadedRawSha256!=initial.sha256 ||
            !disk.identity.current() || !disk.domain.current()) throw MapPairChanged()
        return Held(uri,lease,document,epoch,revision,serial,covered,disk)
    }

    @MainThread private fun actorCurrent(uri:Uri,lease:MapDocumentLease,document:MindMapDocument,epoch:Long,revision:Long,serial:Long):Boolean {
        val key=MapSaves.key(uri);val session=lease.session
        if(!lease.alive || MapDocuments.observationVersion(app,key)!=serial || session.document!==document ||
            session.interactionEpoch!=epoch || session.contentRevision!=revision || !session.canParkSourceNavigation) return false
        val live=MapDocuments.observations(app)
        return live.any {it.key==key && it.session===session && !it.creating && !it.failed && !it.closing && !it.replacing} &&
            live.none {it.key!=key && it.document?.id==document.id}
    }

    internal inner class Held internal constructor(
        private val uri:Uri,private val lease:MapDocumentLease,private val document:MindMapDocument,
        private val epoch:Long,private val revision:Long,private val serial:Long,
        private val covered:Set<String>,private val disk:Disk,
    ) {
        @MainThread fun current()=disk.identity.current() && disk.domain.current() &&
            actorCurrent(uri,lease,document,epoch,revision,serial)
        /** The actual URI lock covers final IO and the synchronous two-document adoption. */
        @MainThread suspend fun finish(adopt:()->Boolean):Boolean {
            if(!current()) return false
            val coroutine=currentCoroutineContext();val check={coroutine.ensureActive()}
            return MapSaves.lockFor(uri).withLock {
                val latest=withContext(Dispatchers.IO) {inspect(uri,document.id,covered,check)}
                withContext(Dispatchers.Main.immediate) {
                    check()
                    if(!current() || latest.sha!=disk.sha || !latest.identity.current() || !latest.domain.current()) false
                    else adopt()
                }
            }
        }
    }
}
