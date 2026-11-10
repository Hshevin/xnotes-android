package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.ui.PageReferenceEnableResult
import com.xnotes.ui.PageReferencePreparation
import com.xnotes.ui.SourceCreationRequest
import com.xnotes.ui.theme.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow

internal enum class PageToMapStep {
    PREPARING,SOURCE_MODE,ORIGINAL_RESTORE_REQUIRED,SAVE_REQUIRED,SOURCE_COPY_RETAINED,SOURCE_COPY_WRITTEN,ENABLE_REQUIRED,ENABLE_RETAINED,REOPEN_REQUIRED,DESTINATION,
    TARGET_LOADING,TARGET,CONFIRMING,SAVING,ADDED,SAVE_FAILED,CREATE_RETAINED,SOURCE_ERROR,TARGET_ERROR,CLOSED,
}

/** The reading pane keeps its place. Only a lease joins the destination's actual editing actor. */
internal class PageToMapController(context:Context,val request:SourceCreationRequest,private val scope:CoroutineScope) {
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
    private var lastOpenMapRetry:(()->Boolean)?=null
    private var createdId:String?=null
    private var targetEpoch:Long?=null
    private var targetDocument:String?=null
    private var selectedTicket:MapSourceImportTicket?=null
    var targetContext by mutableStateOf<Any?>(null);private set
    var confirmationToken by mutableStateOf<Any?>(null);private set
    var step by mutableStateOf(PageToMapStep.PREPARING);private set
    var source by mutableStateOf<MapSource?>(null);private set
    var sourceModes by mutableStateOf<Set<com.xnotes.ui.SourceCreationMode>>(emptySet());private set
    var sourceModeIssue by mutableStateOf<com.xnotes.ui.SourceCreationIssue?>(null);private set
    var sourceModeContext by mutableStateOf<Any?>(null);private set
    var originalRestoreOffer by mutableStateOf<com.xnotes.storage.OriginalPdfCatalog.Observation?>(null);private set
    val canChangeSourceMode get()=sourceModes.isNotEmpty() && imported==null && !busy
    var destination by mutableStateOf<MapReferenceDestination?>(null);private set
    var saveOutcome by mutableStateOf<MapSaveOutcome?>(null);private set
    var targetUri by mutableStateOf<Uri?>(null);private set
    val targetSession:MapSession? get()=lease?.takeIf {it.alive}?.session
    val newNodeTitle get()=(destination as? MapReferenceDestination.New)?.node?.text.orEmpty()
    val hasImportedReference get()=imported!=null
    val sourceNeedsIndependentSave get()=request.hasSavedPath
    val canSaveSourceInPlace get()=request.canSaveInPlace
    val canSaveSourceCopy get()=request.canSaveCopy
    val busy get()=step in setOf(PageToMapStep.PREPARING,PageToMapStep.TARGET_LOADING,PageToMapStep.CONFIRMING,PageToMapStep.SAVING)
    private fun current()=active && request.current()

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
        return run(PageToMapStep.PREPARING) {token->acceptPreparation(request.prepare(),token)}
    }
    private fun acceptPreparation(result:PageReferencePreparation,token:Long) {
        if(!valid(token)) return
        step=when(result) {
            is PageReferencePreparation.ModeRequired->{
                source=null;sourceModes=result.modes;sourceModeIssue=result.issue;sourceModeContext=Any()
                originalRestoreOffer=null;PageToMapStep.SOURCE_MODE
            }
            is PageReferencePreparation.RestoreRequired->{originalRestoreOffer=result.offer;PageToMapStep.ORIGINAL_RESTORE_REQUIRED}
            is PageReferencePreparation.Ready->{source=result.source;if(targetSession?.document!=null) PageToMapStep.TARGET else PageToMapStep.DESTINATION}
            PageReferencePreparation.SaveRequired->PageToMapStep.SAVE_REQUIRED
            is PageReferencePreparation.EnableRequired->{enableOffer=result.offer;PageToMapStep.ENABLE_REQUIRED}
            PageReferencePreparation.ReopenRequired->reopenStep()
            PageReferencePreparation.Changed,PageReferencePreparation.Failed->PageToMapStep.SOURCE_ERROR
        }
    }
    fun selectSourceMode(mode:com.xnotes.ui.SourceCreationMode,expectedContext:Any?=sourceModeContext):Boolean {
        if(step!=PageToMapStep.SOURCE_MODE || imported!=null || expectedContext==null || expectedContext!==sourceModeContext || mode !in sourceModes) return false
        sourceModeContext=null
        return run(PageToMapStep.PREPARING) {token->acceptPreparation(request.selectMode(mode),token)}
    }
    fun changeSourceMode():Boolean {
        if(!current() || !canChangeSourceMode || !request.resetMode()) return false
        source=null;originalRestoreOffer=null;sourceModeContext=null
        return start()
    }
    fun restoreOriginal(expected:com.xnotes.storage.OriginalPdfCatalog.Observation?=originalRestoreOffer):Boolean {
        if(step!=PageToMapStep.ORIGINAL_RESTORE_REQUIRED || expected==null || expected!==originalRestoreOffer || imported!=null) return false
        return run(PageToMapStep.PREPARING) {token->acceptPreparation(request.restoreOriginal(expected),token)}
    }
    /** A mode change or another picker retires this callback, even though the outer request ID is unchanged. */
    internal fun mapDestination(create:Boolean):((Uri)->Boolean)? {
        if(!canChoose()) return null
        val token=generation;val target=source?.target;var consumed=false
        return {uri->
            if(consumed || !valid(token) || source?.target!=target || !canChoose()) false
            else {consumed=true;if(create) createMap(uri) else selectMap(uri)}
        }
    }
    private fun reopenStep()=if(request.dirty) PageToMapStep.SAVE_REQUIRED else PageToMapStep.REOPEN_REQUIRED
    fun enablePages():Boolean {
        if(step!=PageToMapStep.ENABLE_REQUIRED) return false
        val offer=enableOffer ?: return false
        return run(PageToMapStep.PREPARING) {token->acceptEnable(request.enablePages(offer),token)}
    }
    fun continueEnable():Boolean {
        if(step!=PageToMapStep.ENABLE_RETAINED) return false
        return run(PageToMapStep.PREPARING) {token->acceptEnable(request.continueEnablePages(),token)}
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
        if(request.dirty) {step=PageToMapStep.SAVE_REQUIRED;return false}
        return run(PageToMapStep.PREPARING) {token->
            // A successful actual adoption intentionally retires the old page/request.
            val opened=request.reopen()
            if(active && generation==token) {
                if(opened) {onOpened();close()} else step=if(request.dirty) PageToMapStep.SAVE_REQUIRED else PageToMapStep.SOURCE_ERROR
            }
        }
    }

    private fun canChoose()=current() && imported==null && !busy && source!=null
    fun selectMap(uri:Uri):Boolean {
        if(!canChoose()) return false
        lastTarget=uri;lastOpenMapRetry=null
        return run(PageToMapStep.TARGET_LOADING) {token->adoptTarget(MapDocuments.acquire(app,uri),token,null)}
    }
    /** Selecting the other pane names that exact actor, not whatever later occupies its URI. */
    fun selectOpenMap(entry:MapWorkspace.Entry,stillCurrent:()->Boolean={true}):Boolean {
        val session=entry.session;val documentId=session.document?.id ?: return false
        val epoch=session.interactionEpoch
        fun expected()=entry.lease.alive && entry.session===session && session.interactionEpoch==epoch &&
            session.document?.id==documentId && stillCurrent()
        if(!canChoose() || !expected()) return false
        lastTarget=entry.uri;lastOpenMapRetry={expected() && selectOpenMap(entry,stillCurrent)}
        return run(PageToMapStep.TARGET_LOADING) {token->
            adoptTarget(MapDocuments.acquire(app,entry.uri),token,documentId,session,::expected)
        }
    }
    fun createMap(uri:Uri):Boolean {
        if(!canChoose()) return false
        lastTarget=uri;lastOpenMapRetry=null
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
    private suspend fun adoptTarget(incoming:MapDocumentLease,token:Long,expectedId:String?,
        expectedSession:MapSession?=null,expectedCurrent:()->Boolean={true}) {
        var accepted=false
        try {
            val session=incoming.session
            snapshotFlow {session.loading to incoming.alive}.first {!it.first || !it.second}
            if(!valid(token)) return
            val doc=session.document
            if(!incoming.alive || session.loadFailed || doc==null || expectedId!=null && doc.id!=expectedId ||
                expectedSession!=null && session!==expectedSession || !expectedCurrent()) {
                step=PageToMapStep.TARGET_ERROR;return
            }
            val prior=lease;lease=incoming;accepted=true
            if(prior!==incoming) prior?.close()
            targetEpoch=session.interactionEpoch;targetDocument=doc.id;targetContext=Any()
            selectedTicket=null;confirmationToken=null
            targetUri=incoming.uri;destination=null;saveOutcome=null;step=PageToMapStep.TARGET
        } finally {if(!accepted) {
            if(expectedSession!=null) incoming.closeUnadoptedSource() else incoming.close()
        }}
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
    fun retryTarget():Boolean=lastOpenMapRetry?.invoke() ?: lastTarget?.let(::selectMap) ?: false

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
            val checked=request.prepare()
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
    /** A picker callback retains its original controller generation, never the latest UI request. */
    internal fun sourceCopyDestination(name:String):((Uri)->Boolean)? {
        if(!current() || step!=PageToMapStep.SAVE_REQUIRED || !request.canSaveCopy || request.pendingSaveCopy!=null) return null
        val token=generation;var consumed=false
        return {uri->
            if(consumed || !valid(token) || step!=PageToMapStep.SAVE_REQUIRED) false
            else {
                consumed=true
                runCatching {app.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
                    .onFailure {runCatching {app.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)}}
                run(PageToMapStep.PREPARING) {next->acceptSourceCopy(request.saveCopyTo(uri.toString(),name) {valid(next)},next)}
            }
        }
    }
    private fun acceptSourceCopy(result:com.xnotes.ui.SourceCopyResult,token:Long) {
        if(!valid(token)) return
        step=when(result) {
            com.xnotes.ui.SourceCopyResult.Adopted->PageToMapStep.CLOSED // Editor has already issued the new fixed-region request.
            com.xnotes.ui.SourceCopyResult.Retained->PageToMapStep.SOURCE_COPY_RETAINED
            is com.xnotes.ui.SourceCopyResult.WrittenNotAdopted->PageToMapStep.SOURCE_COPY_WRITTEN
            com.xnotes.ui.SourceCopyResult.Changed,com.xnotes.ui.SourceCopyResult.Failed->
                if(request.pendingSaveCopy!=null) PageToMapStep.SOURCE_COPY_RETAINED else PageToMapStep.SOURCE_ERROR
        }
    }
    fun continueSourceCopy():Boolean {
        if(step!=PageToMapStep.SOURCE_COPY_RETAINED || request.pendingSaveCopy==null) return false
        return run(PageToMapStep.PREPARING) {token->acceptSourceCopy(request.continueSaveCopy {valid(token)},token)}
    }
    fun saveSource(action:(com.xnotes.ui.Editor)->Unit):Boolean {
        if(!current() || step!=PageToMapStep.SAVE_REQUIRED) return false
        if(request.canSaveInPlace) return run(PageToMapStep.PREPARING) {token->acceptPreparation(request.saveInPlace(),token)}
        if(request.canSaveCopy) return false // Canvas uses its typed copy picker, never the NOTE-only Main Save As.
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
        request.clear()
        val owned=lease;lease=null
        return owned?.close()
    }
}
