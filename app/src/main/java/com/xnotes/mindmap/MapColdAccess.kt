package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import com.xnotes.storage.DocumentRegistryIdentityEpoch
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.OriginalPdfCatalog
import com.xnotes.ui.SplitSourceRef
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow

/** An operation owns one canonical actor lease; recovery never creates another editing model. */
internal class MapColdAccess(context:Context) {
    private val app=context.applicationContext
    private val registry=NotebookStorage.get(app).registry
    private val catalog=OriginalPdfCatalog(registry)
    private class Changed:Exception()
    internal data class Identity(val ticket:DocumentRegistryIdentityEpoch.Ticket,
        val domain:OriginalPdfCatalog.IdentityDomainsBatch,val aliases:Set<String>) {
        fun current()=ticket.current() && domain.current()
    }

    private fun identity(uri:Uri,id:String,check:()->Unit):Identity {
        check()
        val ticket=registry.identityObservation(id)
        val domain=catalog.identityDomains(setOf(id),check)
        if(!domain.supported || domain.unverifiable || id in domain.reservedOriginalIds) throw Changed()
        val at=registry.expectationAt(uri.toString())
        if(at!=null && at.documentId!=id || at?.requiresPages==true || registry.expectationForId(id).requiresPages ||
            registry.kindForId(id)?.let {it!="xmap"}==true) throw Changed()
        val key=MapSaves.key(uri)
        val positions=registry.fileObservations(setOf(id),check)
        if(positions.limited) throw Changed()
        val locations=positions.files[id]?.locations.orEmpty().filter {it.available}
        if(locations.any {MapSaves.key(Uri.parse(it.uri))!=key}) throw Changed()
        val aliases=locations.mapTo(linkedSetOf(uri.toString())) {it.uri}
        if(aliases.size>32) throw Changed()
        check()
        return Identity(ticket,domain,aliases).also {if(!it.current()) throw Changed()}
    }

    @MainThread suspend fun prepare(ref:SplitSourceRef,stillCurrent:()->Boolean={true}):Held? {
        var owned:MapDocumentLease?=null
        var handedOff=false
        try {
            if(!stillCurrent() || UUID.fromString(ref.documentId).toString()!=ref.documentId) return null
            val uri=Uri.parse(ref.uri)
            if(uri.scheme !in setOf("file","content")) return null
            val coroutine=currentCoroutineContext();val check={coroutine.ensureActive()}
            // Identity protection precedes the actor's own recovery reconciliation/discovery.
            val initial=withContext(Dispatchers.IO) {identity(uri,ref.documentId,check)}
            if(!stillCurrent() || !initial.current()) return null
            val live=MapDocuments.observations(app)
            val key=MapSaves.key(uri)
            if(live.any {it.key!=key && it.document?.id==ref.documentId}) return null
            val existing=live.singleOrNull {it.key==key}
            if(existing?.loading==true || existing?.creating==true || existing?.closing==true || existing?.replacing==true ||
                existing?.document?.id?.let {it!=ref.documentId}==true) return null
            val lease=MapDocuments.acquireForCold(app,uri,ref.documentId,initial.aliases).also {owned=it}
            snapshotFlow {lease.session.loading to lease.alive}.first {!it.first || !it.second}
            check()
            val session=lease.session
            val document=session.document ?: return null
            val proof=session.loadedRecoveryProof ?: return null
            if(document.id!=ref.documentId || proof.documentId!=ref.documentId) return null
            val epoch=session.interactionEpoch;val revision=session.contentRevision
            val serial=MapDocuments.observationVersion(app,key)
            if(!stillCurrent() || !actorCurrent(lease,document,epoch,revision,serial)) return null
            val covered=session.recoveryKeys.toSet()
            // A fresh actor may have registered its verified raw identity; refresh only the
            // registry ticket. The source bytes remain the actor's immutable first-load proof.
            val observed=MapSaves.lockFor(uri).withLock {withContext(Dispatchers.IO) {
                inspect(lease,proof,covered,initial.aliases,check)
            }}
            if(!stillCurrent() || !observed.current() || !actorCurrent(lease,document,epoch,revision,serial)) return null
            val held=Held(lease,ref.title.orEmpty(),document,epoch,revision,serial,proof,covered,observed,stillCurrent)
            handedOff=true
            return held
        } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
        catch(_:Exception) {return null}
        finally {
            if(!handedOff) withContext(NonCancellable+Dispatchers.Main.immediate) {owned?.closeUnadoptedSource()?.join()}
        }
    }

    private fun inspect(lease:MapDocumentLease,proof:MapStore.RecoveryLoadProof,covered:Set<String>,aliases:Set<String>,
        check:()->Unit):Identity {
        val observed=identity(lease.uri,proof.documentId,check)
        val store=MapFiles.store(app,lease.uri,checkActive=check,knownAliases=aliases+observed.aliases)
        if(store.recoveryOutside(covered) || !lease.session.verifyColdLoad(proof,check)) throw Changed()
        check()
        if(!observed.current()) throw Changed()
        return observed
    }

    @MainThread private fun actorCurrent(lease:MapDocumentLease,document:MindMapDocument,epoch:Long,revision:Long,serial:Long):Boolean {
        val key=MapSaves.key(lease.uri);val session=lease.session
        if(!lease.alive || MapDocuments.observationVersion(app,key)!=serial || session.document!==document ||
            session.interactionEpoch!=epoch || session.contentRevision!=revision || session.saving || !session.canParkSourceNavigation) return false
        val live=MapDocuments.observations(app)
        return live.any {it.key==key && it.session===session && !it.loading && !it.creating && !it.failed && !it.closing && !it.replacing} &&
            live.none {it.key!=key && it.document?.id==document.id}
    }

    internal inner class Held internal constructor(
        val lease:MapDocumentLease,val title:String,private val document:MindMapDocument,
        private val epoch:Long,private val revision:Long,private val serial:Long,
        private val proof:MapStore.RecoveryLoadProof,private val covered:Set<String>,private val observed:Identity,
        private val stillCurrent:()->Boolean,
    ) {
        val uri:Uri get()=lease.uri
        val documentId:String get()=document.id
        private var transferred=false
        private var closed=false
        private var release:Job?=null
        @MainThread fun current()=!closed && !transferred && stillCurrent() && observed.current() &&
            actorCurrent(lease,document,epoch,revision,serial)

        /** Final source IO and the synchronous Main adoption share the canonical URI mutex. */
        @MainThread suspend fun finish(adopt:(MapDocumentLease)->Boolean):Boolean {
            try {
                if(!current()) return false
                val coroutine=currentCoroutineContext();val check={coroutine.ensureActive()}
                return MapSaves.lockFor(uri).withLock {
                    val latest=withContext(Dispatchers.IO) {inspect(lease,proof,covered,observed.aliases,check)}
                    withContext(Dispatchers.Main.immediate) {
                        check()
                        if(!current() || !latest.current()) false else adopt(lease).also {if(it) transferred=true}
                    }
                }
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:Exception) {return false}
            finally {if(!transferred) withContext(NonCancellable+Dispatchers.Main.immediate) {closeUnadopted().join()}}
        }

        @MainThread fun closeUnadopted():Job {
            release?.let {return it}
            if(transferred) return CompletableDeferred(Unit).also {release=it}
            closed=true
            return lease.closeUnadoptedSource().also {release=it}
        }
    }
}
