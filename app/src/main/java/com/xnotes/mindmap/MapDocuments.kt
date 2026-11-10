package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface MapCreateOutcome {
    data class Created(val lease:MapDocumentLease):MapCreateOutcome
    data class Retained(val uri:Uri,val documentId:String,val cause:Throwable):MapCreateOutcome
    data class Failed(val cause:Throwable):MapCreateOutcome
}

/** A view owns a lease, never the shared ViewModel itself. All lease operations run on Main. */
internal class MapDocumentLease internal constructor(private val entry:MapDocuments.Entry) {
    val uri:Uri get()=entry.uri
    val session:MapSession get()=entry.session
    var alive:Boolean=true;private set
    private var release:Job?=null
    @get:MainThread internal val hasOtherOwners:Boolean get()=alive && entry.references>1
    /** A failed source-open may protect its sole recovery actor, never another owner's autosave. */
    @MainThread internal fun closeUnadoptedSource():Job {
        release?.let {return it}
        return close(preserveDirty=entry.references==1)
    }
    @MainThread fun close(preserveDirty:Boolean=false):Job {
        if(preserveDirty) {entry.retainDirty=true;session.cancelPendingAutosave()}
        release?.let {return it}
        alive=false
        return MapDocuments.release(entry,preserveDirty).also {release=it}
    }
}

/** A Main-thread observation grants no lease and never starts a save or an open. */
internal class MapDocumentObservation internal constructor(
    private val entry:MapDocuments.Entry,
    private val entryCurrent:()->Boolean,
) {
    val uri=entry.uri
    val key=MapSaves.key(uri)
    val session=entry.session
    val document=session.document
    val epoch=session.interactionEpoch
    val loading=session.loading
    val creating=entry.creating
    val failed=session.loadFailed
    val closing=session.closing
    val replacing=session.replacingDocument
    val knownAliases=session.recoveryKeys
    val discoveryFailed=session.discoveryFailed
    val conflict=session.conflict
    fun current()=entryCurrent() && session.interactionEpoch==epoch && session.document===document
    fun captureFileRelation(relationId:String):MapFileRelationMembership? =
        if(current()) session.captureFileRelationMembership(relationId,entryCurrent) else null
    fun captureMembership(nodeId:String,sourceId:String,target:MapSourceTarget):MapBacklinkMembership? {
        if(!current()) return null
        val doc=document ?: return null
        val source=session.captureSourcePreview(nodeId,sourceId)?.takeIf {it.target==target} ?: return null
        return MapBacklinkMembership(session,doc.id,source,entryCurrent)
    }
}

/** Canonical document actor directory. The only ViewModelStore owner is each private entry. */
internal object MapDocuments {
    internal class Entry(val key:String,val context:Context,val uri:Uri) {
        val store=ViewModelStore()
        val session=ViewModelProvider(store,ViewModelProvider.NewInstanceFactory())[MapSession::class.java]
        var references by mutableStateOf(0)
        var generation=0L
        var creating by mutableStateOf(false)
        var retired=false
        var retainDirty=false
    }
    private val entries=mutableMapOf<String,Entry>()
    private var inventorySerial by mutableStateOf(0L)
    private val positionSerial=mutableMapOf<String,Long>()
    private fun inventoryChanged(key:String) {inventorySerial++;positionSerial[key]=inventorySerial}
    @MainThread fun observationVersion():Long=inventorySerial
    /** Includes retired actors, so absent → live → absent cannot revive a stored path. */
    @MainThread fun observationVersion(context:Context,locationKey:String):Long =
        positionSerial[context.applicationContext.noBackupFilesDir.canonicalPath+"|"+locationKey] ?: 0L
    private fun key(context:Context,uri:Uri)=context.noBackupFilesDir.canonicalPath+"|"+MapSaves.key(uri)
    private fun lease(entry:Entry):MapDocumentLease {
        entry.references++;entry.generation++
        return MapDocumentLease(entry)
    }
    @MainThread fun observations(context:Context):List<MapDocumentObservation> {
        inventorySerial // Make directory creation/retirement observable alongside each session's state.
        val prefix=context.applicationContext.noBackupFilesDir.canonicalPath+"|"
        return entries.values.filter {!it.retired && it.key.startsWith(prefix)}.map {entry->
            MapDocumentObservation(entry) {entries[entry.key]===entry && !entry.retired}
        }
    }
    /** A fresh scope veto may retain an existing actor without acquiring or creating one. */
    @MainThread internal fun preserveCanonicalActor(context:Context,uri:Uri,expectedGraphId:String,
        expectedLiveSession:MapSession?=null):Boolean {
        val entry=entries[key(context.applicationContext,uri)] ?: return false
        if(entry.retired || entry.session.document?.id!=expectedGraphId ||
            expectedLiveSession!=null && entry.session!==expectedLiveSession) return false
        entry.retainDirty=true
        // An accepted save can finish, but an older queued release must not retire the retained actor.
        entry.generation++
        entry.session.cancelPendingAutosave()
        return true
    }
    @MainThread fun acquire(context:Context,uri:Uri,knownAliases:Set<String> = emptySet()):MapDocumentLease {
        return acquireActual(context,uri,knownAliases,null)
    }
    @MainThread internal fun acquireForCold(context:Context,uri:Uri,expectedDocumentId:String,
        knownAliases:Set<String> = emptySet()):MapDocumentLease = acquireActual(context,uri,knownAliases,expectedDocumentId)

    private fun acquireActual(context:Context,uri:Uri,knownAliases:Set<String>,expectedDocumentId:String?):MapDocumentLease {
        val app=context.applicationContext
        val key=key(app,uri)
        var entry=entries[key]
        // An unsuccessful, empty first open has no draft to preserve. A fresh permission grant can retry it.
        if(entry!=null && !entry.creating && !entry.session.loading && entry.session.loadFailed && entry.session.document==null) {
            entries.remove(key);inventoryChanged(key);entry.retired=true;entry.store.clear();entry=null
        }
        if(entry==null) {
            entry=Entry(key,app,uri);entries[key]=entry;inventoryChanged(key)
            entry.session.open(app,uri,knownAliases,expectedDocumentId)
        }
        return lease(entry)
    }
    suspend fun create(context:Context,uri:Uri,fixedDoc:MindMapDocument):MapCreateOutcome {
        var reservation:MapDocumentLease?=null
        var reservedEntry:Entry?=null
        val entry=try {withContext(Dispatchers.Main.immediate) {
            val app=context.applicationContext;val key=key(app,uri)
            if(entries.containsKey(key)) null else Entry(key,app,uri).also {
                it.creating=true;entries[key]=it;inventoryChanged(key);reservedEntry=it;reservation=lease(it)
            }
        }} catch(error:java.util.concurrent.CancellationException) {
            withContext(NonCancellable+Dispatchers.Main.immediate) {
                reservedEntry?.let {it.creating=false;if(it.references>1) it.session.open(it.context,it.uri)}
                reservation?.close()
            }
            throw error
        } ?: return MapCreateOutcome.Failed(IOException("The destination is already open"))
        val provisional=requireNotNull(reservation)
        var handedOff=false
        try {
            val result=try {MapFiles.createAttempt(entry.context,uri,fixedDoc)}
                catch(error:java.util.concurrent.CancellationException) {throw error}
                catch(error:Exception) {return MapCreateOutcome.Failed(error)}
            val outcome=withContext(Dispatchers.Main.immediate) {
                entry.creating=false
                if(result.cause==null) {
                    entry.session.open(entry.context,uri)
                    MapCreateOutcome.Created(provisional)
                } else {
                    // Other explicit open owners, if any, may inspect the retained candidate via normal open.
                    if(entry.references>1) entry.session.open(entry.context,uri)
                    if(result.retained) MapCreateOutcome.Retained(uri,fixedDoc.id,result.cause)
                    else MapCreateOutcome.Failed(result.cause)
                }
            }
            // withContext can discard its value on cancellation while returning to the caller dispatcher.
            // Only a value received by this coroutine transfers its lease to the consumer.
            handedOff=outcome is MapCreateOutcome.Created
            return outcome
        } finally {
            if(!handedOff) withContext(NonCancellable+Dispatchers.Main.immediate) {
                entry.creating=false
                if(entry.references>1) entry.session.open(entry.context,uri)
                provisional.close()
            }
        }
    }
    @MainThread internal fun release(entry:Entry,preserveDirty:Boolean=false):Job {
        if(preserveDirty) {entry.retainDirty=true;entry.session.cancelPendingAutosave()}
        check(entry.references>0)
        entry.references--;val generation=++entry.generation
        return MapSaves.scope.launch {
            if(entry.references!=0 || entry.retired) return@launch
            if(!entry.creating && entry.session.loading && entry.session.document==null) {
                // A reserved creator has not called open yet; a failed create leaves all recovery files intact.
                if(entries[entry.key]!==entry) return@launch
                // For a normal acquire, wait for the already-owned open to settle.
                if(entry.session.hasStartedOpen) snapshotFlow {entry.session.loading}.first {!it}
            }
            if(entry.references!=0 || entry.generation!=generation || entry.retired) return@launch
            val session=entry.session
            if(session.document!=null) {
                if(entry.retainDirty && (session.dirty || session.saving || session.hasUnconfirmedContent)) return@launch
                if(session.inkContact) return@launch
                if(session.dirty || session.saving) session.saveAttempt().await()
                if(entry.references!=0 || entry.generation!=generation || entry.retired) return@launch
                if(session.hasUnconfirmedContent || (session.dirty && !session.recoverySafe)) return@launch
            }
            if(entries[entry.key]!==entry || entry.references!=0 || entry.generation!=generation) return@launch
            entries.remove(entry.key);inventoryChanged(entry.key);entry.retired=true
            val children=session.viewModelScope.coroutineContext[Job]
            entry.store.clear()
            children?.join()
        }
    }
}
