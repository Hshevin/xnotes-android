package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import androidx.compose.runtime.snapshotFlow
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.storage.NativeReferenceFailure
import com.xnotes.storage.DocumentRegistryIdentityEpoch
import com.xnotes.storage.NativeReferenceIssue
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.OriginalPdfCatalog
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whole-map source access. Registry names are hints; actual JSON and the shared actor are
 * separately verified. This neither registers identity nor opens a second editing model. */
internal class MapDocumentSourceAccess(context:Context,storageOverride:NotebookStorage?=null) {
    private val app=context.applicationContext
    private val storage=storageOverride ?: NotebookStorage.get(app)
    private val catalog=OriginalPdfCatalog(storage.registry)
    private val roots=listOf(AppStorageDocumentsProvider.treeUri(app).toString())
    internal sealed interface Result {
        data object NotMap:Result
        data class Failed(val issue:NativeReferenceIssue):Result
        data class Ready(val held:Held):Result
    }
    internal data class Location(val uri:Uri,val title:String,val id:String,val key:String,val aliases:Set<String>,
        val domain:OriginalPdfCatalog.IdentityDomainsBatch,val identity:DocumentRegistryIdentityEpoch.Ticket)
    internal data class Disk(val sha256:String)
    private fun failed(issue:NativeReferenceIssue):Nothing=throw NativeReferenceFailure(issue)
    private fun requireSource(test:Boolean,issue:NativeReferenceIssue) {if(!test) failed(issue)}

    /** IO only. Null is exclusively an already registered ordinary native document. */
    private fun locate(id:String,check:()->Unit):Location? {
        check()
        val identity=storage.registry.identityObservation(id)
        val domain=catalog.identityDomains(setOf(id),check)
        requireSource(domain.supported && !domain.unverifiable,NativeReferenceIssue.UNSUPPORTED)
        requireSource(domain.current(),NativeReferenceIssue.CHANGED)
        requireSource(id !in domain.reservedOriginalIds,NativeReferenceIssue.UNSUPPORTED)
        if(!File(storage.registry.databaseName).isFile) failed(NativeReferenceIssue.UNAVAILABLE)
        val files=storage.registry.fileObservations(setOf(id),check)
        requireSource(!files.limited,NativeReferenceIssue.UNAVAILABLE)
        val file=files.files[id] ?: failed(NativeReferenceIssue.UNAVAILABLE)
        if(file.kind in setOf("xnote","xcanvas")) return null
        requireSource(file.kind=="xmap" && !storage.registry.expectationForId(id).requiresPages,NativeReferenceIssue.UNSUPPORTED)
        val available=file.locations.filter {it.available}
        requireSource(available.isNotEmpty(),NativeReferenceIssue.UNAVAILABLE)
        val positions=available.groupBy {MapSaves.key(Uri.parse(it.uri))}
        requireSource(positions.size==1,NativeReferenceIssue.DUPLICATE)
        val aliases=available.mapTo(linkedSetOf()) {it.uri}
        requireSource(aliases.size<=32,NativeReferenceIssue.UNAVAILABLE)
        val uri=Uri.parse(available.first().uri)
        requireSource(uri.scheme in setOf("file","content"),NativeReferenceIssue.UNSUPPORTED)
        requireSource(storage.registry.expectationAt(uri.toString())?.documentId==id,NativeReferenceIssue.CHANGED)
        check();requireSource(domain.current() && identity.current(),NativeReferenceIssue.CHANGED)
        return Location(uri,file.name.orEmpty().take(4096),id,positions.keys.single(),aliases,domain,identity)
    }
    private suspend fun inspect(location:Location,covered:Set<String>?,check:()->Unit):Disk = withContext(Dispatchers.IO) {
        check();requireSource(location.domain.current() && location.identity.current(),NativeReferenceIssue.CHANGED)
        val place=backlinkLocationProblem(app,roots,BacklinkLocation(location.uri,location.aliases,location.key,location.id,location.title))
        place?.let {failed(when(it) {
            BacklinkProblem.DUPLICATE->NativeReferenceIssue.DUPLICATE
            BacklinkProblem.CHANGED,BacklinkProblem.IDENTITY->NativeReferenceIssue.CHANGED
            BacklinkProblem.TRASHED->NativeReferenceIssue.DELETED
            else->NativeReferenceIssue.UNAVAILABLE
        })}
        val bytes=MapFiles.target(app,location.uri,check).read()
        // A known Map location containing native ZIP bytes never falls back to a native open.
        requireSource(!(bytes.size>=2 && bytes[0]==80.toByte() && bytes[1]==75.toByte()),NativeReferenceIssue.UNSUPPORTED)
        val actual=MapCodec.decode(bytes)
        requireSource(actual.id==location.id,NativeReferenceIssue.CHANGED)
        val readOnly=object:MapTarget {
            override fun read()=bytes
            override fun write(bytes:ByteArray):Unit=error("A map source observation cannot write")
        }
        val store=MapFiles.store(app,location.uri,readOnly,check,location.aliases)
        if(covered!=null) requireSource(!store.recoveryOutside(covered),NativeReferenceIssue.CHANGED)
        val observed=store.observe(check)
        requireSource(observed.document.id==location.id,NativeReferenceIssue.CHANGED)
        check();requireSource(location.domain.current() && location.identity.current(),NativeReferenceIssue.CHANGED)
        Disk(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)})
    }
    /** A body/derived update is harmless; actor replacement, closing and duplicate identities are not. */
    @MainThread private fun actorCurrent(lease:MapDocumentLease,id:String,key:String,epoch:Long,serial:Long):Boolean {
        if(!lease.alive || MapDocuments.observationVersion(app,key)!=serial) return false
        val live=MapDocuments.observations(app)
        if(live.any {it.key!=key && it.document?.id==id}) return false
        val actual=live.singleOrNull {it.key==key && it.session===lease.session} ?: return false
        val session=actual.session
        return !actual.loading && !actual.creating && !actual.failed && !actual.closing && !actual.replacing &&
            session.interactionEpoch==epoch && session.document?.id==id
    }
    /** Caller runs on Main; checkActive may be called on IO. No host callback is run on IO. */
    @MainThread suspend fun acquire(target:MapSourceTarget,checkActive:()->Unit={}):Result {
        if(target.scope!=SourceScope.FILE) return Result.NotMap
        var owned:MapDocumentLease?=null
        var handedOff=false
        try {
            target.validate()
            val coroutine=currentCoroutineContext();val check={coroutine.ensureActive();checkActive()}
            val location=withContext(Dispatchers.IO) {locate(target.documentId,check)} ?: return Result.NotMap
            val initial=MapSaves.lockFor(location.uri).withLock {inspect(location,null,check)}
            val lease=withContext(Dispatchers.Main.immediate) {
                check()
                val live=MapDocuments.observations(app)
                requireSource(live.none {it.key!=location.key && it.document?.id==location.id},NativeReferenceIssue.DUPLICATE)
                val at=live.singleOrNull {it.key==location.key}
                requireSource(at?.creating!=true && at?.closing!=true && at?.replacing!=true,NativeReferenceIssue.BUSY)
                requireSource(at?.document==null || at.document.id==location.id,NativeReferenceIssue.CHANGED)
                MapDocuments.acquire(app,at?.uri ?: location.uri,location.aliases).also {owned=it}
            }
            snapshotFlow {lease.session.loading to lease.alive}.first {!it.first || !it.second}
            val held=withContext(Dispatchers.Main.immediate) {
                check()
                val epoch=lease.session.interactionEpoch;val serial=MapDocuments.observationVersion(app,location.key)
                requireSource(actorCurrent(lease,location.id,location.key,epoch,serial),NativeReferenceIssue.CHANGED)
                requireSource(location.domain.current() && location.identity.current(),NativeReferenceIssue.CHANGED)
                Held(location,initial,lease,epoch,serial)
            }
            // No dispatcher boundary after ownership is transferred to the returned holder.
            handedOff=true
            return Result.Ready(held)
        } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
        catch(error:NativeReferenceFailure) {return Result.Failed(error.issue)}
        catch(_:Exception) {return Result.Failed(NativeReferenceIssue.UNAVAILABLE)}
        finally {
            if(!handedOff) withContext(NonCancellable+Dispatchers.Main.immediate) {owned?.closeUnadoptedSource()?.join()}
        }
    }
    internal inner class Held internal constructor(private val initial:Location,private val disk:Disk,
        val lease:MapDocumentLease,private val epoch:Long,private val serial:Long) {
        val uri:Uri get()=lease.uri
        val title:String get()=initial.title
        val documentId:String get()=initial.id
        private var transferred=false
        private var closed=false
        private var release:Job?=null
        var failure:NativeReferenceIssue?=null;private set
        @MainThread fun current()=!closed && !transferred && initial.domain.current() && initial.identity.current() &&
            actorCurrent(lease,documentId,initial.key,epoch,serial)
        /** Final IO and synchronous Main adoption share the target's serialization lock. */
        @MainThread suspend fun finish(checkActive:()->Unit={},adopt:(MapDocumentLease)->Boolean):Boolean {
            try {
                if(!current()) {failure=NativeReferenceIssue.CHANGED;return false}
                failure=null
                val coroutine=currentCoroutineContext();val check={coroutine.ensureActive();checkActive()}
                return MapSaves.lockFor(uri).withLock {
                    val covered=lease.session.recoveryKeys
                    val latest=withContext(Dispatchers.IO) {
                        check();val location=locate(documentId,check) ?: failed(NativeReferenceIssue.CHANGED)
                        requireSource(location.key==initial.key && initial.domain.current() && initial.identity.current(),NativeReferenceIssue.CHANGED)
                        val verified=inspect(location,covered,check)
                        requireSource(verified.sha256==disk.sha256,NativeReferenceIssue.CHANGED)
                        location
                    }
                    withContext(Dispatchers.Main.immediate) {
                        check()
                        if(!current() || !latest.domain.current() || !latest.identity.current()) {failure=NativeReferenceIssue.CHANGED;false}
                        else adopt(lease).also {if(it) transferred=true else failure=NativeReferenceIssue.CHANGED}
                    }
                }
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(error:NativeReferenceFailure) {failure=error.issue;return false}
            catch(_:Exception) {failure=NativeReferenceIssue.UNAVAILABLE;return false}
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
