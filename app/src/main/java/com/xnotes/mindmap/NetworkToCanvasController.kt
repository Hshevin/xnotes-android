package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** A frozen last-known file selection, never permission to read or overwrite a source file. */
internal class NetworkCanvasSelection(seeds:List<MapFileCardSeed>,private val valid:()->Boolean) {
    val seeds:List<MapFileCardSeed> = Collections.unmodifiableList(seeds.toList())
    init {require(this.seeds.isNotEmpty() && this.seeds.size<=250 && this.seeds.map {it.documentId}.distinct().size==this.seeds.size)}
    fun current()=valid()
    companion object {
        fun capture(entry:FileNetworkWorkspace.Entry,scene:LocalNetworkScene):NetworkCanvasSelection? {
            val controller=entry.controller
            if(!entry.alive || controller.loading || controller.filtering || controller.browseMode!=NetworkBrowseMode.FILES) return null
            val revision=controller.filterRevision;val ids=controller.centreFiles.toSet()
            if(ids.isEmpty() || ids.size>250) return null
            val seeds=ids.sorted().map {id->
                val render=scene.nodes.entries.singleOrNull {it.value==NetworkSceneNodeKey.File(id)}?.key ?: return null
                val node=scene.document.nodes.firstOrNull {it.id==render} ?: return null
                val file=controller.fileChoices.firstOrNull {it.documentId==id} ?: return null
                MapFileCardSeed(id,file.observedNames.firstOrNull().orEmpty(),node.x,node.y)
            }
            return NetworkCanvasSelection(seeds) {entry.alive && controller.filterRevision==revision &&
                controller.browseMode==NetworkBrowseMode.FILES && controller.centreFiles==ids}
        }
    }
}

internal class NetworkCanvasOpenRequest internal constructor(val uri:Uri,val title:String,nodeIds:Set<String>,
    private val valid:()->Boolean,private val finish:(Boolean)->Unit) {
    val nodeIds:Set<String> = Collections.unmodifiableSet(nodeIds.toSet())
    private var ended=false
    fun current()=!ended && valid()
    fun finished(shown:Boolean) {if(!ended) {ended=true;finish(shown)}}
}

internal enum class NetworkCanvasStep {DESTINATION,CONFIRM,WORKING,ADDED,SAVE_FAILED,CREATE_RETAINED,FAILED,CLOSED}
internal class NetworkCanvasCreateRequest internal constructor(internal val issuer:Any,internal val candidate:MapFileCardsAdded)

/** No destination actor is acquired until confirmation; accepted publication outlives its dialog. */
internal class NetworkToCanvasController(context:Context,val selection:NetworkCanvasSelection,private val scope:CoroutineScope) {
    private val app=context.applicationContext
    val id=UUID.randomUUID().toString()
    private var active=true
    private var lease:MapDocumentLease?=null
    private var acceptedWork:Job?=null
    private var ticket:MapFileCardsTicket?=null
    private var targetEpoch:Long?=null
    private var targetId:String?=null
    private var fixed:MapFileCardsAdded?=null
    private var newTarget=false
    private var openAttempt:Any?=null
    private var newPreparation:NetworkCanvasCreateRequest?=null
    var step by mutableStateOf(NetworkCanvasStep.DESTINATION);private set
    var targetUri by mutableStateOf<Uri?>(null);private set
    var nodeIds by mutableStateOf<Set<String>>(emptySet());private set
    var saveOutcome by mutableStateOf<MapSaveOutcome?>(null);private set
    var problem by mutableStateOf(false);private set
    var opening by mutableStateOf(false);private set
    private var targetTitle by mutableStateOf(app.getString(com.xnotes.R.string.network_canvas_new_title))
    val targetSession:MapSession? get()=lease?.takeIf {it.alive}?.session
    val busy get()=step==NetworkCanvasStep.WORKING || opening
    val canRetrySave get()=current() && !busy && step==NetworkCanvasStep.SAVE_FAILED && matchingCards()
    val canView get()=current() && !busy && step in setOf(NetworkCanvasStep.ADDED,NetworkCanvasStep.SAVE_FAILED) && matchingCards()
    val title get()=targetTitle
    fun current()=active && scope.isActive && selection.current()

    private fun release():Job? {
        val owned=lease;lease=null
        // Inspecting, canceling or failing a flow must never start a last-owner autosave.
        return owned?.close(preserveDirty=true)
    }
    private fun choose(uri:Uri,create:Boolean,label:String?,prepared:MapFileCardsAdded?=null):Boolean {
        if(!current() || busy || nodeIds.isNotEmpty() || step==NetworkCanvasStep.CREATE_RETAINED) return false
        release();targetUri=uri;newTarget=create;targetEpoch=null;targetId=null;ticket=null;fixed=prepared;newPreparation=null
        targetTitle=label?.takeIf {it.isNotBlank()}?.take(BacklinkBudget.LABEL_CHARS) ?:
            if(create) app.getString(com.xnotes.R.string.network_canvas_new_title) else
                uri.lastPathSegment.orEmpty().substringAfterLast('/').ifBlank {app.getString(com.xnotes.R.string.map_title)}.take(BacklinkBudget.LABEL_CHARS)
        saveOutcome=null;problem=false;step=NetworkCanvasStep.CONFIRM;return true
    }
    fun chooseExisting(uri:Uri,title:String?=null)=choose(uri,false,title)
    fun chooseNew(uri:Uri,title:String?=null)=choose(uri,true,title)
    /** The system Save button confirms this already-complete, validated candidate. */
    fun prepareNew():NetworkCanvasCreateRequest? {
        if(!current() || busy || step!=NetworkCanvasStep.DESTINATION || nodeIds.isNotEmpty()) return null
        return try {
            val candidate=MindMapDocument(nodes=emptyList(),organization=MapOrganization.RELATION_CANVAS).addFileCards(selection.seeds)
            NetworkCanvasCreateRequest(this,candidate).also {newPreparation=it}
        } catch(_:IllegalArgumentException) {problem=true;null}
    }
    fun creationCurrent(request:NetworkCanvasCreateRequest)=current() && !busy && step==NetworkCanvasStep.DESTINATION &&
        request.issuer===this && newPreparation===request
    fun cancelNew(request:NetworkCanvasCreateRequest) {if(newPreparation===request) newPreparation=null}
    fun completeNew(request:NetworkCanvasCreateRequest,uri:Uri,title:String?=null):Boolean {
        if(!creationCurrent(request)) return false
        return choose(uri,true,title,request.candidate) && confirm()
    }
    fun changeTarget():Boolean {
        if(!current() || busy || nodeIds.isNotEmpty() || step==NetworkCanvasStep.CREATE_RETAINED) return false
        release();targetUri=null;fixed=null;ticket=null;newPreparation=null;problem=false;step=NetworkCanvasStep.DESTINATION;return true
    }
    private fun runAccepted(action:suspend ()->Unit):Boolean {
        if(!current() || busy) return false
        problem=false;step=NetworkCanvasStep.WORKING
        lateinit var job:Job
        job=MapSaves.scope.launch(start=CoroutineStart.LAZY) {
            try {action()}
            catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {if(active) {problem=true;step=NetworkCanvasStep.FAILED}}
            finally {
                if(acceptedWork===job) acceptedWork=null
                if(!active || !scope.isActive) withContext(NonCancellable) {release()?.join()}
            }
        }
        acceptedWork=job;job.start();return true
    }
    private suspend fun loaded(incoming:MapDocumentLease,expected:String?=null):Boolean {
        lease=incoming
        snapshotFlow {incoming.session.loading to incoming.alive}.first {!it.first || !it.second}
        val session=incoming.session;val doc=session.document
        if(!current() || !incoming.alive || session.loadFailed || doc==null || expected!=null && doc.id!=expected) {
            release()?.join();if(active) {problem=true;step=NetworkCanvasStep.FAILED};return false
        }
        targetEpoch=session.interactionEpoch;targetId=doc.id;return true
    }
    fun confirm():Boolean {
        if(step!=NetworkCanvasStep.CONFIRM || nodeIds.isNotEmpty()) return false
        val uri=targetUri ?: return false
        if(newTarget) {
            val candidate=try {fixed ?: MindMapDocument(nodes=emptyList(),organization=MapOrganization.RELATION_CANVAS)
                .addFileCards(selection.seeds).also {fixed=it}} catch(_:IllegalArgumentException) {problem=true;return false}
            return runAccepted {
                when(val result=MapDocuments.create(app,uri,candidate.document)) {
                    is MapCreateOutcome.Created->if(loaded(result.lease,candidate.document.id)) acceptCreated(candidate)
                    is MapCreateOutcome.Retained->if(active) {targetId=result.documentId;problem=true;step=NetworkCanvasStep.CREATE_RETAINED}
                    is MapCreateOutcome.Failed->if(active) {problem=true;step=NetworkCanvasStep.FAILED}
                }
            }
        }
        return runAccepted {
            val held=lease?.takeIf {it.alive}
            if(held==null && !loaded(MapDocuments.acquire(app,uri))) return@runAccepted
            if(!current()) {release()?.join();return@runAccepted}
            val session=requireNotNull(targetSession)
            if(session.document?.id!=targetId || session.hasUnconfirmedContent) {
                problem=true;step=NetworkCanvasStep.CONFIRM;return@runAccepted
            }
            // Each explicit retry authorizes the current interaction, not a retired busy attempt.
            targetEpoch=session.interactionEpoch
            val prepared=session.captureFileCards(selection.seeds)
            if(prepared==null) {problem=true;step=NetworkCanvasStep.CONFIRM;return@runAccepted}
            when(val result=session.importFileCards(prepared)) {
                is MapFileCardsImportResult.Applied->{ticket=prepared;nodeIds=result.nodeIds.toSet();saveImported()}
                MapFileCardsImportResult.Busy->{problem=true;step=NetworkCanvasStep.CONFIRM}
                MapFileCardsImportResult.Changed,MapFileCardsImportResult.Invalid->{problem=true;step=NetworkCanvasStep.FAILED}
            }
        }
    }
    private fun matchingCards():Boolean {
        val session=targetSession ?: return false;val doc=session.document ?: return false
        if(session.interactionEpoch!=targetEpoch || doc.id!=targetId || nodeIds.isEmpty()) return false
        ticket?.let {if(!session.fileCardsCurrent(it)) return false}
        fixed?.let {candidate->
            val expected=candidate.document.nodes.associate {it.id to it.fileCardId}
            if(nodeIds.any {id->doc.nodes.none {it.id==id && it.fileCardId==expected[id]}}) return false
        }
        val files=selection.seeds.mapTo(hashSetOf()) {it.documentId}
        return nodeIds.all {node->doc.nodes.any {it.id==node && it.fileCardId in files}} &&
            doc.nodes.filter {it.id in nodeIds}.mapTo(hashSetOf()) {it.fileCardId}==files
    }
    private fun acceptCreated(candidate:MapFileCardsAdded) {
        if(!attachCreatedReceipt(candidate)) {problem=true;step=NetworkCanvasStep.FAILED}
        else {saveOutcome=null;step=NetworkCanvasStep.ADDED}
    }
    private fun attachCreatedReceipt(candidate:MapFileCardsAdded):Boolean {
        nodeIds=candidate.nodeIds.toSet()
        if(!matchingCards()) return false
        val session=targetSession ?: return false
        val prepared=session.captureFileCards(selection.seeds) ?: return false
        val result=session.importFileCards(prepared) as? MapFileCardsImportResult.Applied ?: return false
        if(!result.alreadyPresent || result.nodeIds!=nodeIds) return false
        ticket=prepared
        return matchingCards()
    }
    private suspend fun saveImported() {
        val session=targetSession ?: return
        val result=session.saveAttempt().await()
        if(!active) return
        saveOutcome=result
        step=if((result is MapSaveOutcome.Written || result is MapSaveOutcome.Changed) && matchingCards())
            NetworkCanvasStep.ADDED else NetworkCanvasStep.SAVE_FAILED
        problem=step==NetworkCanvasStep.SAVE_FAILED
    }
    fun retrySave():Boolean {
        if(step!=NetworkCanvasStep.SAVE_FAILED || !matchingCards()) return false
        return runAccepted {saveImported()}
    }
    fun openRetained():Boolean {
        if(step!=NetworkCanvasStep.CREATE_RETAINED) return false
        val uri=targetUri ?: return false;val candidate=fixed ?: return false;val expected=targetId ?: return false
        return runAccepted {
            if(loaded(MapDocuments.acquire(app,uri),expected)) {
                if(!attachCreatedReceipt(candidate)) {problem=true;step=NetworkCanvasStep.FAILED}
                else saveImported()
            }
        }
    }
    fun captureOpen():NetworkCanvasOpenRequest? {
        if(!current() || busy || step !in setOf(NetworkCanvasStep.ADDED,NetworkCanvasStep.SAVE_FAILED) || !matchingCards()) return null
        val uri=targetUri ?: return null;val token=Any();openAttempt=token;opening=true
        return NetworkCanvasOpenRequest(uri,title,nodeIds,{current() && openAttempt===token && matchingCards()}) {shown->
            if(openAttempt===token) {
                openAttempt=null;opening=false
                if(shown) close() else if(active) problem=true
            }
        }
    }
    fun close():Job? {
        if(!active) return acceptedWork
        active=false;openAttempt=null;newPreparation=null;opening=false;step=NetworkCanvasStep.CLOSED
        return acceptedWork?.takeIf {it.isActive} ?: release()
    }
}
