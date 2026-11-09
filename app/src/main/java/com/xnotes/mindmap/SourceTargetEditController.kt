package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.xnotes.core.geometry.Rect
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal enum class SourceTargetEditStep {CHOOSE_SOURCE,LOADING,VISUAL,RETAIN_REQUIRED,RESTORE_REQUIRED,CONFIRMING,SAVING,SAVED,SAVE_FAILED,FAILED,CHANGED}
internal enum class SourceTargetEditProblem {READ,CHANGED,INVALID_RANGE,PAGE_REQUIRED,ACTIVATION,RETAIN,SAVE}
internal data class SourceTargetEditState(val id:String,val ticket:MapSourceTargetEditTicket,val step:SourceTargetEditStep,
    val kind:SourceRangeKind?=null,val displayName:String="",val pages:List<SourceRangePage> = emptyList(),
    val needsPageActivation:Boolean=false,val frame:SourceRangeFrame?=null,
    val selectionScope:SourceRangeScope=SourceRangeScope.WHOLE_FILE,val region:Rect?=null,
    val offer:SourceTargetOffer?=null,val originalPage:OriginalPickerPage?=null,
    val problem:SourceTargetEditProblem?=null,val saveOutcome:MapSaveOutcome?=null,
    val canResumeActivation:Boolean=false,val camera:SourceRegionCamera?=null) {
    val busy get()=step in setOf(SourceTargetEditStep.LOADING,SourceTargetEditStep.CONFIRMING,SourceTargetEditStep.SAVING)
}

/** One OS slot keeps its original request even after the panel retires. No View/Activity is held. */
internal class SourceTargetPickerSlot internal constructor(val id:String,internal val requestId:String)

/** Session-owned runtime draft. Accepted map saves outlive the panel and never re-apply a choice. */
internal class SourceTargetEditController(context:Context,private val session:MapSession,private val scope:CoroutineScope,
    private val access:MapSourceTargetAccess=MapSourceTargetAccess(context.applicationContext)) {
    private class Request(val owner:Any,val ticket:MapSourceTargetEditTicket) {
        val id=UUID.randomUUID().toString()
        @Volatile var retired=false
    }
    private class Ownership {
        val read=AtomicReference<SourceRangeRead?>()
        val offer=AtomicReference<SourceTargetOffer?>()
        val restore=AtomicReference<SourcePdfRestoreOffer?>()
        fun close() {
            try {read.getAndSet(null)?.closeAndAwait()}
            finally {try {offer.getAndSet(null)?.closeAndAwait()} finally {restore.getAndSet(null)?.closeAndAwait()}}
        }
    }
    private var closed=false
    private var request:Request?=null
    private var generation=0L
    private var work:Job?=null
    private var renderWork:Job?=null
    private var cleanupTail:Job?=null
    private var read:SourceRangeRead?=null
    private var offer:SourceTargetOffer?=null
    private var restore:SourcePdfRestoreOffer?=null
    private var host:Any?=null
    private var hostFrame:Any?=null
    private var renderGeneration=0L
    var state by mutableStateOf<SourceTargetEditState?>(null);private set
    var image by mutableStateOf<OwnedVisualFrame?>(null);private set
    var visualBusy by mutableStateOf(false);private set
    var pendingPicker by mutableStateOf<SourceTargetPickerSlot?>(null);private set
    val hasPending get()=state!=null || pendingPicker!=null
    val active get()=state!=null
    private fun live(r:Request)=!closed && request===r && !r.retired && state?.id==r.id && session.sourceTargetEditCurrent(r.ticket)
    private fun valid(r:Request,token:Long)=live(r) && generation==token
    private fun current(id:String?):Request?=request?.takeIf {id!=null && it.id==id && live(it)}
    private fun inputsBusy()=session.inkContact || session.hasUnconfirmedContent

    fun start(owner:Any,ticket:MapSourceTargetEditTicket):Boolean {
        if(closed || !scope.isActive || hasPending || ticket.accepted || session.sourceTargetEditPickerBusy || !session.sourceTargetEditCurrent(ticket)) return false
        val r=Request(owner,ticket);request=r
        state=SourceTargetEditState(r.id,ticket,SourceTargetEditStep.CHOOSE_SOURCE)
        if(ticket.mode==SourceTargetEditMode.RANGE) openRead(r,initial=true) {holder,check->
            access.openCurrent(ticket.initialTarget,check).also {holder.read.set(it)}
        }
        return true
    }
    fun capturePicker(expectedId:String?=state?.id):SourceTargetPickerSlot? {
        val r=current(expectedId) ?: return null
        if(r.ticket.mode!=SourceTargetEditMode.REPLACE || state?.busy==true || pendingPicker!=null || r.ticket.accepted) return null
        return SourceTargetPickerSlot(UUID.randomUUID().toString(),r.id).also {
            pendingPicker=it;pickerOwners[it.id]=WeakReference(this)
        }
    }
    fun receivePicker(slot:SourceTargetPickerSlot,uri:Uri?):Boolean {
        if(pendingPicker!==slot) return false
        pickerOwners.remove(slot.id);pendingPicker=null
        val r=current(slot.requestId) ?: return true
        if(uri==null) return true
        if(r.ticket.mode!=SourceTargetEditMode.REPLACE || r.ticket.accepted) return true
        inspect(r,uri);return true
    }
    private fun retireVisual():Job? {
        host=null;hostFrame=null;visualBusy=false;renderGeneration++
        val previous=renderWork;renderWork=null;previous?.cancel()
        image?.close();image=null
        return previous
    }
    private fun cleanup(read:SourceRangeRead?,offer:SourceTargetOffer?,restore:SourcePdfRestoreOffer?,jobs:List<Job?> = emptyList()) {
        val prior=cleanupTail
        cleanupTail=cleanupScope.launch {
            prior?.join();jobs.filterNotNull().distinct().forEach {it.cancelAndJoin()}
            try {read?.closeAndAwait()} finally {try {offer?.closeAndAwait()} finally {restore?.closeAndAwait()}}
        }
    }
    private fun releaseRead(wait:List<Job?> = emptyList()) {
        val oldRead=read;val oldOffer=offer;val oldRestore=restore
        read=null;offer=null;restore=null
        cleanup(oldRead,oldOffer,oldRestore,wait)
    }
    private fun run(r:Request,step:SourceTargetEditStep,problem:SourceTargetEditProblem,
        action:suspend (Long,Ownership,()->Unit)->Unit):Boolean {
        if(!live(r) || pendingPicker!=null || state?.busy==true && work?.isActive==true || r.ticket.accepted) return false
        val prior=work;prior?.cancel();val rendering=retireVisual();val tail=cleanupTail
        val token=++generation
        state=state!!.copy(step=step,problem=null)
        lateinit var task:Job
        task=scope.launch(start=CoroutineStart.LAZY) {
            val ownership=Ownership()
            try {
                prior?.join();rendering?.join();tail?.join()
                val coroutine=currentCoroutineContext()
                val check={coroutine.ensureActive();check(!r.retired)}
                action(token,ownership,check)
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {if(valid(r,token)) state=state!!.copy(
                step=if(problem==SourceTargetEditProblem.CHANGED) SourceTargetEditStep.CHANGED else SourceTargetEditStep.FAILED,
                problem=problem,canResumeActivation=problem==SourceTargetEditProblem.ACTIVATION)}
            finally {
                withContext(NonCancellable+Dispatchers.IO) {ownership.close()}
                if(work===task) work=null
            }
        }
        work=task;task.start();return true
    }
    private fun initialScope(target:MapSourceTarget)=when(target.scope) {
        SourceScope.FILE->SourceRangeScope.WHOLE_FILE
        SourceScope.PAGE->if(target.region==null) SourceRangeScope.WHOLE_PAGE else SourceRangeScope.REGION
        SourceScope.CANVAS_REGION->SourceRangeScope.REGION
        SourceScope.PDF_ORIGINAL->if(target.originalPdf?.userRegion==null) SourceRangeScope.WHOLE_PAGE else SourceRangeScope.REGION
    }
    private fun adopt(r:Request,token:Long,holder:Ownership,value:SourceRangeRead,frame:SourceRangeFrame,initial:Boolean) {
        if(!valid(r,token)) return
        if(!value.current()) {
            state=state!!.copy(step=SourceTargetEditStep.CHANGED,problem=SourceTargetEditProblem.CHANGED)
            return
        }
        val oldRead=read;val oldOffer=offer;val oldRestore=restore
        read=holder.read.getAndSet(null);offer=null;restore=null
        check(read===value)
        val selection=if(initial) initialScope(r.ticket.initialTarget) else if(value.kind==SourceRangeKind.PDF_ORIGINAL)
            SourceRangeScope.WHOLE_PAGE else SourceRangeScope.WHOLE_FILE
        val region=if(initial && selection==SourceRangeScope.REGION) frame.initialRegion else null
        state=SourceTargetEditState(r.id,r.ticket,SourceTargetEditStep.VISUAL,value.kind,value.displayName,value.pages,
            value.needsPageActivation,frame,selection,region,problem=when {
                selection!=SourceRangeScope.WHOLE_FILE && frame.pageId==null && value.kind in setOf(SourceRangeKind.NOTE,SourceRangeKind.PDF_ORIGINAL)->SourceTargetEditProblem.PAGE_REQUIRED
                selection==SourceRangeScope.REGION && !validRegion(frame,region)->SourceTargetEditProblem.INVALID_RANGE
                else->null
            })
        cleanup(oldRead,oldOffer,oldRestore)
    }
    private fun openRead(r:Request,initial:Boolean=false,loader:(Ownership,()->Unit)->SourceRangeRead):Boolean=
        run(r,SourceTargetEditStep.LOADING,SourceTargetEditProblem.READ) {token,holder,check->
            val loaded=withContext(Dispatchers.IO) {
                val value=loader(holder,check);holder.read.set(value)
                value to value.frame()
            }
            adopt(r,token,holder,loaded.first,loaded.second,initial)
        }
    private fun inspect(r:Request,uri:Uri):Boolean=run(r,SourceTargetEditStep.LOADING,SourceTargetEditProblem.READ) {token,holder,check->
        val result=withContext(Dispatchers.IO) {
            val found=access.inspectFile(uri,check).also(holder.offer::set)
            if(found is SourceTargetOffer.RawPdf) null else {
                val value=access.openOffer(found,check).also(holder.read::set);holder.offer.set(null)
                value to value.frame()
            }
        }
        if(result!=null) adopt(r,token,holder,result.first,result.second,false)
        else if(valid(r,token)) {
            releaseRead()
            offer=holder.offer.getAndSet(null)
            state=state!!.copy(step=SourceTargetEditStep.RETAIN_REQUIRED,displayName=offer!!.displayName,offer=offer,
                frame=null,region=null,camera=null,problem=null)
        }
    }
    fun chooseAnotherSource(expectedId:String?=state?.id):Boolean {
        val r=current(expectedId) ?: return false
        if(r.ticket.mode!=SourceTargetEditMode.REPLACE || r.ticket.accepted || state?.busy==true || pendingPicker!=null) return false
        val prior=work;prior?.cancel();work=null;val rendering=retireVisual();generation++
        releaseRead(listOf(prior,rendering))
        state=SourceTargetEditState(r.id,r.ticket,SourceTargetEditStep.CHOOSE_SOURCE);return true
    }
    fun reload(expectedId:String?=state?.id):Boolean {
        val r=current(expectedId) ?: return false
        if(r.ticket.accepted || state?.busy==true) return false
        return if(r.ticket.mode==SourceTargetEditMode.REPLACE) chooseAnotherSource(expectedId)
            else openRead(r,true) {holder,check->access.openCurrent(r.ticket.initialTarget,check).also(holder.read::set)}
    }
    fun loadOriginals(cursor:OriginalPickerCursor?=null,expectedId:String?=state?.id):Boolean {
        val r=current(expectedId) ?: return false
        if(r.ticket.mode!=SourceTargetEditMode.REPLACE || state?.busy==true || r.ticket.accepted) return false
        return run(r,SourceTargetEditStep.LOADING,SourceTargetEditProblem.READ) {token,_,check->
            val page=withContext(Dispatchers.IO) {access.originalPickerPage(cursor,check)}
            if(valid(r,token)) state=state!!.copy(step=SourceTargetEditStep.CHOOSE_SOURCE,originalPage=page)
        }
    }
    fun chooseOriginal(entry:OriginalPickerEntry,expectedId:String?=state?.id):Boolean {
        val r=current(expectedId) ?: return false
        if(r.ticket.mode!=SourceTargetEditMode.REPLACE || !entry.current() || r.ticket.accepted) return false
        return openRead(r) {holder,check->access.openOriginal(entry,check).also(holder.read::set)}
    }
    fun retainPdf(expectedId:String?=state?.id):Boolean {
        val r=current(expectedId) ?: return false
        val raw=offer as? SourceTargetOffer.RawPdf ?: return false
        if(state?.step!=SourceTargetEditStep.RETAIN_REQUIRED || !raw.current()) return false
        return run(r,SourceTargetEditStep.LOADING,SourceTargetEditProblem.RETAIN) {token,holder,check->
            val result=withContext(Dispatchers.IO) {access.retainPdf(raw,check).also {when(it) {
                is SourcePdfRetention.Retained->holder.read.set(it.read)
                is SourcePdfRetention.RestoreRequired->holder.restore.set(it.offer)
            }}}
            when(result) {
                is SourcePdfRetention.Retained->{
                    val frame=withContext(Dispatchers.IO) {result.read.frame()}
                    adopt(r,token,holder,result.read,frame,false)
                }
                is SourcePdfRetention.RestoreRequired->if(valid(r,token)) {
                    releaseRead();restore=holder.restore.getAndSet(null)
                    state=state!!.copy(step=SourceTargetEditStep.RESTORE_REQUIRED,offer=null,frame=null,region=null)
                }
            }
        }
    }
    fun restorePdf(expectedId:String?=state?.id):Boolean {
        val r=current(expectedId) ?: return false;val expected=restore ?: return false
        if(state?.step!=SourceTargetEditStep.RESTORE_REQUIRED || !expected.current()) return false
        return openRead(r) {holder,check->access.restorePdf(expected,check).also(holder.read::set)}
    }
    fun activatePages(expectedId:String?=state?.id)=activate(expectedId,false)
    fun completeActivation(expectedId:String?=state?.id)=activate(expectedId,true)
    private fun activate(id:String?,resume:Boolean):Boolean {
        val r=current(id) ?: return false;val old=read ?: return false;val native=old.nativeOffer ?: return false
        if(!old.needsPageActivation && !resume || r.ticket.accepted) return false
        return run(r,SourceTargetEditStep.LOADING,SourceTargetEditProblem.ACTIVATION) {token,holder,check->
            val loaded=withContext(Dispatchers.IO) {
                val next=(if(resume) access.completePageActivation(native,check) else access.activatePages(native,check)).also(holder.offer::set)
                val value=access.openOffer(next,check).also(holder.read::set);holder.offer.set(null)
                value to value.frame()
            }
            adopt(r,token,holder,loaded.first,loaded.second,false)
        }
    }
    private fun changeFrame(r:Request,page:String?,turns:Int,selection:SourceRangeScope):Boolean {
        val value=read ?: return false
        return run(r,SourceTargetEditStep.LOADING,SourceTargetEditProblem.READ) {token,_,check->
            val frame=withContext(Dispatchers.IO) {check();value.frame(page,turns).also {check()}}
            if(valid(r,token) && read===value) state=if(value.current()) state!!.copy(step=SourceTargetEditStep.VISUAL,
                frame=frame,selectionScope=selection,region=null,camera=null,problem=null)
                else state!!.copy(step=SourceTargetEditStep.CHANGED,problem=SourceTargetEditProblem.CHANGED)
        }
    }
    fun choosePage(pageId:String,viewTurns:Int=0,expectedId:String?=state?.id):Boolean {
        val r=current(expectedId) ?: return false;val value=read ?: return false
        if(value.pages.none {it.id==pageId} || viewTurns !in 0..3 || r.ticket.accepted) return false
        return changeFrame(r,pageId,viewTurns,SourceRangeScope.WHOLE_PAGE)
    }
    fun chooseScope(selection:SourceRangeScope,expectedId:String?=state?.id):Boolean {
        val r=current(expectedId) ?: return false;val shown=state ?: return false;val frame=shown.frame ?: return false
        if(r.ticket.accepted || shown.step!=SourceTargetEditStep.VISUAL) return false
        val allowed=when(selection) {
            SourceRangeScope.REGION->frame.regionAllowed
            SourceRangeScope.WHOLE_PAGE->shown.kind in setOf(SourceRangeKind.NOTE,SourceRangeKind.PDF_ORIGINAL) && frame.pageId!=null
            SourceRangeScope.WHOLE_FILE->shown.kind!=SourceRangeKind.PDF_ORIGINAL
        }
        if(!allowed) return false
        if(selection==shown.selectionScope) return true
        return changeFrame(r,frame.pageId,frame.viewTurns,selection)
    }
    private fun validViewport(rect:Rect)=rect.w>0 && rect.h>0 &&
        listOf(rect.x,rect.y,rect.w,rect.h,rect.right,rect.bottom).all {it.isFinite()}
    private fun validRect(rect:Rect)=validViewport(rect) && listOf(rect.x,rect.y,rect.w,rect.h)
        .all {kotlin.math.abs(it)<=MindMapDocument.MAX_COORD}
    private fun validRegion(frame:SourceRangeFrame,rect:Rect?)=rect!=null && validRect(rect) && frame.regionAllowed &&
        (frame.allowsOutsideRegions || rect.left>=frame.bounds.left && rect.top>=frame.bounds.top && rect.right<=frame.bounds.right && rect.bottom<=frame.bounds.bottom)
    fun setRegion(frameIdentity:Any,region:Rect?):Boolean {
        val r=current(state?.id) ?: return false;val shown=state ?: return false;val frame=shown.frame ?: return false
        if(frame!==frameIdentity || shown.step!=SourceTargetEditStep.VISUAL || shown.selectionScope!=SourceRangeScope.REGION || r.ticket.accepted) return false
        val valid=validRegion(frame,region)
        state=shown.copy(region=region?.takeIf {valid}?.copy(),problem=if(valid) null else SourceTargetEditProblem.INVALID_RANGE)
        return valid
    }
    fun attachVisualHost(hostToken:Any,frameIdentity:Any):Boolean {
        val r=current(state?.id) ?: return false
        if(state?.step!=SourceTargetEditStep.VISUAL || state?.frame!==frameIdentity || r.ticket.accepted) return false
        if(host!==hostToken || hostFrame!==frameIdentity) retireVisual()
        host=hostToken;hostFrame=frameIdentity;return true
    }
    fun detachVisualHost(hostToken:Any) {if(host===hostToken) retireVisual()}
    fun visualCurrent(hostToken:Any,frameIdentity:Any)=host===hostToken && hostFrame===frameIdentity && state?.frame===frameIdentity &&
        state?.step==SourceTargetEditStep.VISUAL && request?.let(::live)==true && read?.current()==true
    fun setVisualBusy(hostToken:Any,frameIdentity:Any,busy:Boolean) {
        if(host!==hostToken || hostFrame!==frameIdentity || state?.frame!==frameIdentity) return
        // An invalidated read still needs its own View's abort; old hosts have no such authority.
        if(!busy || visualCurrent(hostToken,frameIdentity)) visualBusy=busy
    }
    fun render(viewport:Rect,hostToken:Any,camera:SourceRegionCamera?=null):Boolean {
        val r=current(state?.id) ?: return false;val frame=state?.frame ?: return false;val value=read ?: return false
        if(!visualCurrent(hostToken,frame) || !validViewport(viewport)) return false
        if(camera!=null && listOf(camera.centerX,camera.centerY,camera.zoom).all {it.isFinite()} && camera.zoom>0)
            state=state!!.copy(camera=camera)
        val previous=renderWork;previous?.cancel();val token=++renderGeneration
        lateinit var task:Job
        task=scope.launch(start=CoroutineStart.LAZY) {
            val owned=AtomicReference<OwnedVisualFrame?>()
            try {
                previous?.join();delay(120)
                val coroutine=currentCoroutineContext()
                val result=withContext(Dispatchers.IO) {value.render(frame,viewport,2048) {coroutine.ensureActive();check(!r.retired)}.also(owned::set)}
                if(live(r) && visualCurrent(hostToken,frame) && read===value && value.current() && token==renderGeneration) {
                    result.publish();image?.close();image=owned.getAndSet(null);check(image===result)
                }
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {if(live(r) && token==renderGeneration) state=state!!.copy(problem=SourceTargetEditProblem.READ)}
            finally {withContext(NonCancellable+Dispatchers.IO) {owned.getAndSet(null)?.close()};if(renderWork===task) renderWork=null}
        }
        renderWork=task;task.start();return true
    }
    fun canConfirm(expectedId:String?=state?.id):Boolean {
        val r=current(expectedId) ?: return false;val shown=state ?: return false;val value=read ?: return false;val frame=shown.frame ?: return false
        if(shown.step!=SourceTargetEditStep.VISUAL || visualBusy || pendingPicker!=null || r.ticket.accepted || !value.current()) return false
        return when(shown.selectionScope) {
            SourceRangeScope.WHOLE_FILE->shown.kind!=SourceRangeKind.PDF_ORIGINAL
            SourceRangeScope.WHOLE_PAGE->frame.pageId!=null && shown.kind in setOf(SourceRangeKind.NOTE,SourceRangeKind.PDF_ORIGINAL)
            SourceRangeScope.REGION->validRegion(frame,shown.region)
        }
    }
    /** Pure UI query: a recomposition must explain why an invalidated read disabled confirmation. */
    fun displayedProblem(expectedId:String?=state?.id):SourceTargetEditProblem? {
        val shown=state?.takeIf {it.id==expectedId} ?: return null
        return if(shown.step==SourceTargetEditStep.VISUAL && read?.current()==false) SourceTargetEditProblem.CHANGED else shown.problem
    }
    fun confirm(expectedId:String?=state?.id):Boolean {
        val r=current(expectedId) ?: return false;val shown=state ?: return false;val value=read ?: return false;val frame=shown.frame ?: return false
        if(shown.step!=SourceTargetEditStep.VISUAL || visualBusy || pendingPicker!=null || r.ticket.accepted) return false
        if(!value.current()) {
            state=shown.copy(step=SourceTargetEditStep.CHANGED,problem=SourceTargetEditProblem.CHANGED);return false
        }
        if(shown.selectionScope==SourceRangeScope.REGION && !validRegion(frame,shown.region)) {
            state=shown.copy(region=null,problem=SourceTargetEditProblem.INVALID_RANGE);return false
        }
        if(shown.selectionScope==SourceRangeScope.WHOLE_PAGE && frame.pageId==null) {
            state=shown.copy(problem=SourceTargetEditProblem.PAGE_REQUIRED);return false
        }
        return run(r,SourceTargetEditStep.CONFIRMING,SourceTargetEditProblem.CHANGED) {token,_,check->
            while(valid(r,token)) {
                snapshotFlow {inputsBusy()}.first {!it}
                if(!valid(r,token)) return@run
                val choice=withContext(Dispatchers.IO) {value.prepareChoice(frame,shown.selectionScope,shown.region,check)}
                if(!valid(r,token)) return@run
                if(inputsBusy()) continue
                when(val result=session.applySourceTargetEdit(r.ticket,choice)) {
                    is MapSourceTargetEditResult.Applied->{
                        when(val owner=r.owner) {
                            is SourceListEditor.Runtime->{owner.targetEditApplied(r.ticket);owner.check(r.ticket.sourceId,choice.target)}
                            is SourceListEditor->{owner.targetEditApplied(r.ticket);owner.check(r.ticket.sourceId,choice.target)}
                        }
                        releaseRead()
                        if(result.changed) saveAccepted(r) else state=state!!.copy(step=SourceTargetEditStep.SAVED,problem=null)
                    }
                    MapSourceTargetEditResult.Busy->continue
                    MapSourceTargetEditResult.Changed->state=state!!.copy(step=SourceTargetEditStep.CHANGED,problem=SourceTargetEditProblem.CHANGED)
                    MapSourceTargetEditResult.Invalid->state=state!!.copy(step=SourceTargetEditStep.FAILED,problem=SourceTargetEditProblem.INVALID_RANGE)
                }
                return@run
            }
        }
    }
    private fun saveAccepted(r:Request) {
        state=state!!.copy(step=SourceTargetEditStep.SAVING,problem=null)
        val attempt=session.saveAttempt()
        MapSaves.scope.launch {
            val outcome=attempt.await()
            if(live(r) && r.ticket.accepted) state=state!!.copy(step=if(outcome is MapSaveOutcome.Written) SourceTargetEditStep.SAVED else SourceTargetEditStep.SAVE_FAILED,
                saveOutcome=outcome,problem=if(outcome is MapSaveOutcome.Written) null else SourceTargetEditProblem.SAVE)
        }
    }
    fun retrySave(expectedId:String?=state?.id):Boolean {
        val r=current(expectedId) ?: return false
        if(state?.step!=SourceTargetEditStep.SAVE_FAILED || !r.ticket.accepted) return false
        saveAccepted(r);return true
    }
    fun cancel(expectedId:String?=state?.id):Boolean {
        val r=request?.takeIf {it.id==expectedId} ?: return false
        r.retired=true;generation++;request=null;state=null
        val prior=work;work=null;prior?.cancel();val rendering=retireVisual()
        releaseRead(listOf(prior,rendering));return true
    }
    fun retireOwner(owner:Any) {request?.takeIf {it.owner===owner}?.let {cancel(it.id)}}
    fun documentChanged() {request?.takeIf {!session.sourceTargetEditCurrent(it.ticket)}?.let {cancel(it.id)}}
    fun close() {if(!closed) {request?.let {cancel(it.id)};closed=true}}
    companion object {
        private val cleanupScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private val pickerOwners=ConcurrentHashMap<String,WeakReference<SourceTargetEditController>>()
        /** Main only. Retired/closed owners consume their old slot without reading the late URI. */
        fun deliverPicker(id:String,uri:Uri?):Boolean {
            val owner=pickerOwners.remove(id)?.get() ?: return false
            val slot=owner.pendingPicker?.takeIf {it.id==id} ?: return false
            return owner.receivePicker(slot,uri)
        }
    }
}
