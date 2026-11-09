package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.ui.PageReferenceEnableResult
import com.xnotes.ui.PageReferencePreparation
import com.xnotes.ui.PageReferenceRequest
import com.xnotes.ui.theme.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow

internal enum class PageToMapStep {
    PREPARING,SAVE_REQUIRED,ENABLE_REQUIRED,ENABLE_RETAINED,REOPEN_REQUIRED,DESTINATION,
    TARGET_LOADING,TARGET,CONFIRMING,SAVING,ADDED,SAVE_FAILED,CREATE_RETAINED,SOURCE_ERROR,TARGET_ERROR,CLOSED,
}

/** The reading pane keeps its place. Only a lease joins the destination's actual editing actor. */
internal class PageToMapController(context:Context,val request:PageReferenceRequest,private val scope:CoroutineScope) {
    private val app=context.applicationContext
    private var active=true
    private var generation=0L
    private var work:Job?=null
    private var lease:MapDocumentLease?=null
    private var enableOffer:MapSourceOffer?=null
    private var imported:MapSourceImportResult.Applied?=null
    private var importEpoch:Long?=null
    private var importDocument:String?=null
    private var lastTarget:Uri?=null
    private var createdId:String?=null
    private var targetEpoch:Long?=null
    private var targetDocument:String?=null
    private var selectedTicket:MapSourceImportTicket?=null
    var targetContext by mutableStateOf<Any?>(null);private set
    var confirmationToken by mutableStateOf<Any?>(null);private set
    var step by mutableStateOf(PageToMapStep.PREPARING);private set
    var source by mutableStateOf<MapSource?>(null);private set
    var destination by mutableStateOf<MapReferenceDestination?>(null);private set
    var saveOutcome by mutableStateOf<MapSaveOutcome?>(null);private set
    var targetUri by mutableStateOf<Uri?>(null);private set
    val targetSession:MapSession? get()=lease?.takeIf {it.alive}?.session
    val newNodeTitle get()=(destination as? MapReferenceDestination.New)?.node?.text.orEmpty()
    val hasImportedReference get()=imported!=null
    val sourceNeedsIndependentSave get()=request.model.path!=null
    val busy get()=step in setOf(PageToMapStep.PREPARING,PageToMapStep.TARGET_LOADING,PageToMapStep.CONFIRMING,PageToMapStep.SAVING)
    private fun current()=active && request.pane.pageReferenceCurrent(request)

    private fun run(next:PageToMapStep,block:suspend (Long)->Unit):Boolean {
        if(!current() || busy && work?.isActive==true) return false
        val token=++generation
        work?.cancel();step=next
        work=scope.launch(start=CoroutineStart.LAZY) {
            try {block(token)} catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:Exception) {if(valid(token)) step=if(next==PageToMapStep.PREPARING) PageToMapStep.SOURCE_ERROR else PageToMapStep.TARGET_ERROR}
        }.also {it.start()}
        return true
    }
    private fun valid(token:Long)=current() && generation==token

    fun start():Boolean {
        if(imported!=null) return false
        return run(PageToMapStep.PREPARING) {token->acceptPreparation(request.pane.preparePageReference(request),token)}
    }
    private fun acceptPreparation(result:PageReferencePreparation,token:Long) {
        if(!valid(token)) return
        step=when(result) {
            is PageReferencePreparation.Ready->{source=result.source;if(targetSession?.document!=null) PageToMapStep.TARGET else PageToMapStep.DESTINATION}
            PageReferencePreparation.SaveRequired->PageToMapStep.SAVE_REQUIRED
            is PageReferencePreparation.EnableRequired->{enableOffer=result.offer;PageToMapStep.ENABLE_REQUIRED}
            PageReferencePreparation.ReopenRequired->reopenStep()
            PageReferencePreparation.Changed,PageReferencePreparation.Failed->PageToMapStep.SOURCE_ERROR
        }
    }
    private fun reopenStep()=if(request.model.dirty) PageToMapStep.SAVE_REQUIRED else PageToMapStep.REOPEN_REQUIRED
    fun enablePages():Boolean {
        if(step!=PageToMapStep.ENABLE_REQUIRED) return false
        val offer=enableOffer ?: return false
        return run(PageToMapStep.PREPARING) {token->acceptEnable(request.pane.enablePageReference(request,offer),token)}
    }
    fun continueEnable():Boolean {
        if(step!=PageToMapStep.ENABLE_RETAINED) return false
        return run(PageToMapStep.PREPARING) {token->acceptEnable(request.pane.continuePageReferenceEnable(request),token)}
    }
    private fun acceptEnable(result:PageReferenceEnableResult,token:Long) {
        if(!valid(token)) return
        step=when(result) {
            PageReferenceEnableResult.Enabled->reopenStep()
            PageReferenceEnableResult.Retained->PageToMapStep.ENABLE_RETAINED
            PageReferenceEnableResult.Changed,PageReferenceEnableResult.Failed->PageToMapStep.SOURCE_ERROR
        }
    }
    fun reopen(onOpened:()->Unit):Boolean {
        if(step!=PageToMapStep.REOPEN_REQUIRED) return false
        if(request.model.dirty) {step=PageToMapStep.SAVE_REQUIRED;return false}
        return run(PageToMapStep.PREPARING) {token->
            // A successful actual adoption intentionally retires the old page/request.
            val opened=request.pane.reopenPageReference(request)
            if(active && generation==token) {
                if(opened) {onOpened();close()} else step=if(request.model.dirty) PageToMapStep.SAVE_REQUIRED else PageToMapStep.SOURCE_ERROR
            }
        }
    }

    private fun canChoose()=current() && imported==null && !busy && source!=null
    fun selectMap(uri:Uri):Boolean {
        if(!canChoose()) return false
        lastTarget=uri
        return run(PageToMapStep.TARGET_LOADING) {token->adoptTarget(MapDocuments.acquire(app,uri),token,null)}
    }
    fun createMap(uri:Uri):Boolean {
        if(!canChoose()) return false
        lastTarget=uri
        val fixed=MindMapDocument(nodes=listOf(MapNode(parent=null,text=app.getString(com.xnotes.R.string.map_topic))))
        return run(PageToMapStep.TARGET_LOADING) {token->
            when(val result=MapDocuments.create(app,uri,fixed)) {
                is MapCreateOutcome.Created->adoptTarget(result.lease,token,fixed.id)
                is MapCreateOutcome.Retained->if(valid(token)) {targetUri=result.uri;createdId=result.documentId;step=PageToMapStep.CREATE_RETAINED}
                is MapCreateOutcome.Failed->if(valid(token)) step=PageToMapStep.TARGET_ERROR
            }
        }
    }
    /** Only an explicit retry opens retained creation data; it never invokes create a second time. */
    fun openRetained():Boolean {
        if(step!=PageToMapStep.CREATE_RETAINED) return false
        val uri=targetUri ?: return false;val expected=createdId ?: return false
        return run(PageToMapStep.TARGET_LOADING) {token->adoptTarget(MapDocuments.acquire(app,uri),token,expected)}
    }
    private suspend fun adoptTarget(incoming:MapDocumentLease,token:Long,expectedId:String?) {
        var accepted=false
        try {
            val session=incoming.session
            snapshotFlow {session.loading to incoming.alive}.first {!it.first || !it.second}
            if(!valid(token)) return
            val doc=session.document
            if(!incoming.alive || session.loadFailed || doc==null || expectedId!=null && doc.id!=expectedId) {
                step=PageToMapStep.TARGET_ERROR;return
            }
            val prior=lease;lease=incoming;accepted=true
            if(prior!==incoming) prior?.close()
            targetEpoch=session.interactionEpoch;targetDocument=doc.id;targetContext=Any()
            selectedTicket=null;confirmationToken=null
            targetUri=incoming.uri;destination=null;saveOutcome=null;step=PageToMapStep.TARGET
        } finally {if(!accepted) incoming.close()}
    }
    private fun targetCurrent()=targetSession?.let {it.interactionEpoch==targetEpoch && it.document?.id==targetDocument}==true
    private fun selecting(expectedContext:Any?):Boolean {
        if(!current() || step!=PageToMapStep.TARGET || expectedContext!==targetContext) return false
        if(!targetCurrent()) {confirmationToken=null;step=PageToMapStep.TARGET_ERROR;return false}
        return true
    }
    private fun choose(destination:MapReferenceDestination):Boolean {
        val ticket=targetSession?.captureSourceImport(destination) ?: return false
        this.destination=destination;selectedTicket=ticket;confirmationToken=Any();return true
    }
    fun chooseExistingNode(id:String,expectedContext:Any?=targetContext):Boolean {
        if(!selecting(expectedContext) || targetSession?.document?.nodes?.none {it.id==id}!=false) return false
        return choose(MapReferenceDestination.Existing(id))
    }
    fun chooseNewReference(branch:Boolean,title:String="",expectedContext:Any?=targetContext):Boolean {
        if(!selecting(expectedContext)) return false
        val session=targetSession ?: return false;val doc=session.document ?: return false
        val previous=destination
        val chosenParent=when(previous) {
            is MapReferenceDestination.Existing->previous.nodeId
            is MapReferenceDestination.New->previous.node.parent
            null->null
        }
        if(branch && chosenParent!=null && selectedTicket?.let(session::sourceImportCurrent)!=true) {
            step=PageToMapStep.TARGET_ERROR;return false
        }
        val parent=doc.nodes.firstOrNull {it.id==(chosenParent ?: session.selected)} ?: doc.centralRoot
        if(branch && parent==null) return false
        val right=doc.visible().maxOfOrNull {it.bounds.right} ?: 0.0
        val node=MapNode(parent=if(branch) parent!!.id else null,free=!branch,kind=NodeKind.REFERENCE,text=title,
            x=if(branch) 0.0 else right+Space.s32.value,y=if(branch) 0.0 else doc.centralRoot?.y ?: 0.0)
        return choose(MapReferenceDestination.New(node))
    }
    fun renameNewReference(value:String,expectedNode:String?=(destination as? MapReferenceDestination.New)?.node?.id,expectedContext:Any?=targetContext):Boolean {
        val dest=destination as? MapReferenceDestination.New ?: return false
        if(!selecting(expectedContext) || dest.node.id!=expectedNode || value.length>20_000) return false
        val original=selectedTicket ?: return false
        if(targetSession?.sourceImportCurrent(original)!=true) return false
        destination=MapReferenceDestination.New(dest.node.copy(text=value));confirmationToken=Any();return true
    }
    fun changeMap():Boolean {
        if(!canChoose()) return false
        destination=null;selectedTicket=null;confirmationToken=null;step=PageToMapStep.DESTINATION;return true
    }
    fun retryTarget():Boolean=lastTarget?.let(::selectMap) ?: false

    fun confirm(expectedConfirmation:Any?=confirmationToken):Boolean {
        if(!selecting(targetContext) || imported!=null || expectedConfirmation==null || expectedConfirmation!==confirmationToken) return false
        val target=lease?.takeIf {it.alive} ?: return false
        val session=target.session;val chosen=destination ?: return false
        val selection=selectedTicket ?: return false
        if(!session.sourceImportCurrent(selection)) {step=PageToMapStep.TARGET_ERROR;return false}
        // Check the original selection before re-signing a changed draft title, without suspension.
        // The resulting target generation stays frozen throughout source IO; ordinary C1 merges.
        val ticket=session.captureSourceImport(chosen) ?: return false
        return run(PageToMapStep.CONFIRMING) {token->
            val checked=request.pane.preparePageReference(request)
            if(!valid(token) || lease!==target || !target.alive) return@run
            if(checked !is PageReferencePreparation.Ready) {acceptPreparation(checked,token);return@run}
            source=checked.source
            when(val result=session.importSource(checked.source,ticket)) {
                is MapSourceImportResult.Applied->{
                    imported=result;importEpoch=session.interactionEpoch;importDocument=session.document?.id
                    step=PageToMapStep.SAVING;saveImported(target,token)
                }
                MapSourceImportResult.Busy->step=PageToMapStep.TARGET
                MapSourceImportResult.Changed,MapSourceImportResult.Invalid->step=PageToMapStep.TARGET_ERROR
            }
        }
    }
    private fun importedCurrent(target:MapDocumentLease):Boolean {
        val applied=imported ?: return false;val session=target.session
        return target.alive && session.interactionEpoch==importEpoch && session.document?.id==importDocument &&
            session.document?.nodes?.firstOrNull {it.id==applied.nodeId}?.sources?.any {it.id==applied.sourceId && it.target==source?.target}==true
    }
    private suspend fun saveImported(target:MapDocumentLease,token:Long) {
        val outcome=target.session.saveAttempt().await()
        if(!valid(token) || lease!==target) return
        saveOutcome=outcome
        step=when {
            !importedCurrent(target)->PageToMapStep.TARGET_ERROR
            outcome is MapSaveOutcome.Written->PageToMapStep.ADDED
            else->PageToMapStep.SAVE_FAILED
        }
    }
    fun retrySave():Boolean {
        if(step!=PageToMapStep.SAVE_FAILED) return false
        val target=lease ?: return false
        if(!importedCurrent(target)) {step=PageToMapStep.TARGET_ERROR;return false}
        return run(PageToMapStep.SAVING) {token->saveImported(target,token)}
    }
    fun saveSource(action:(com.xnotes.ui.Editor)->Unit):Boolean {
        if(!current() || step!=PageToMapStep.SAVE_REQUIRED) return false
        val pane=request.pane;close();action(pane);return true
    }
    fun openMap(expectedUri:Uri?,expectedContext:Any?,action:(Uri)->Unit):Boolean {
        if(!current() || expectedUri==null || targetUri!=expectedUri || targetContext!==expectedContext || busy) return false
        if(step!=PageToMapStep.CREATE_RETAINED && lease?.alive!=true) return false
        action(expectedUri);return true
    }
    fun close():Job? {
        if(!active) return null
        active=false;generation++;work?.cancel();step=PageToMapStep.CLOSED
        request.pane.clearPageReference(request)
        val owned=lease;lease=null
        return owned?.close()
    }
}
