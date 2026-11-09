package com.xnotes.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.util.DocumentKind
import com.xnotes.platform.RecoveryLegacyArtifact
import com.xnotes.platform.RecoveryPreviewLoad
import com.xnotes.storage.NativeCreationPending
import com.xnotes.storage.PreparedRecoveryPreview
import com.xnotes.storage.RecoveryCopyRequest
import com.xnotes.storage.RecoveryCopyResult
import com.xnotes.ui.theme.MinTouchTarget
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

internal enum class RecoveryPreviewPhase { LOADING, ABSENT, CLEARED, SOURCE_REQUIRED, INVALID, READY }
internal enum class RecoveryPickerKind { CREATE, RESUME }
internal data class RecoveryPickerLaunch(val id:Long,val kind:RecoveryPickerKind,val filename:String?=null)
/** Opaque request-bound actions captured by one UI frame. */
internal class RecoveryPreviewActions internal constructor(
    private val chooseAction:(RecoveryLegacyArtifact)->Unit,private val pageAction:(Int)->Unit,
    private val copyAction:()->Unit,private val resumeAction:()->Unit,
    private val completeAction:()->Unit,private val closeAction:()->Unit,
) {
    fun choose(artifact:RecoveryLegacyArtifact)=chooseAction(artifact)
    fun page(index:Int)=pageAction(index)
    fun requestCopy()=copyAction()
    fun requestResumePicker()=resumeAction()
    fun completePending()=completeAction()
    fun closePreview()=closeAction()
}
internal data class RecoveryPreviewUi(
    val pane:Pane,val phase:RecoveryPreviewPhase=RecoveryPreviewPhase.LOADING,
    val artifacts:Set<RecoveryLegacyArtifact> = emptySet(),val displayName:String?=null,
    val kind:DocumentKind?=null,val byteSize:Long=0,val pageCount:Int=0,val page:Int=0,
    val image:ImageBitmap?=null,val rendering:Boolean=false,val renderFailed:Boolean=false,
    val busy:Boolean=false,val error:String?=null,val hasPending:Boolean=false,val retryFailed:Boolean=false,
    val saved:RecoveryCopyResult.Saved?=null,
)

/** One root-window consumer of historical bodies; it never adopts or saves a live model. */
internal class RecoveryPreviewController(context:Context) {
    private val context=context.applicationContext
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val cleanups=mutableListOf<Job>()
    @Volatile private var alive=true
    @Volatile private var current:Request?=null
    private var sequence=0L
    private var pickerSequence=0L
    private var picker:Picker?=null
    private var launcher:((RecoveryPickerLaunch)->Unit)?=null
    private var disposal:Job?=null
    val pickerNamespace:String=UUID.randomUUID().toString()
    var state by mutableStateOf<RecoveryPreviewUi?>(null);private set
    var pickerPending by mutableStateOf(false);private set
    var pickerAvailable by mutableStateOf(false);private set

    private class Request(val id:Long,val editor:Editor,parent:Job) {
        val cancelled=AtomicBoolean(false)
        val job=SupervisorJob(parent)
        val scope=CoroutineScope(job+Dispatchers.Main.immediate)
        var prepared:PreparedRecoveryPreview?=null
        var render:Job?=null
        var renderGeneration=0L
        var pending:NativeCreationPending?=null
    }
    private sealed interface Payload {
        val request:Request
        class Create(override val request:Request,val copy:RecoveryCopyRequest):Payload
        class Resume(override val request:Request):Payload
    }
    private class Picker(val launch:RecoveryPickerLaunch,var payload:Payload?)
    private fun stillCurrent(r:Request)=alive && current===r && !r.cancelled.get() && r.job.isActive
    fun actions():RecoveryPreviewActions? {
        val captured=current ?: return null
        fun ifCurrent(action:()->Unit) {if(stillCurrent(captured)) action()}
        return RecoveryPreviewActions({artifact->ifCurrent {choose(artifact)}},{index->ifCurrent {page(index)}},
            {ifCurrent {requestCopy()}},{ifCurrent {requestResumePicker()}},
            {ifCurrent {completePending()}},{ifCurrent {closePreview()}})
    }
    private fun check(r:Request) {
        if(!alive || current!==r || r.cancelled.get()) throw CancellationException("Recovery preview retired")
    }
    private fun update(r:Request,change:(RecoveryPreviewUi)->RecoveryPreviewUi) {
        if(alive && current===r && !r.cancelled.get()) state=state?.let(change)
    }
    private fun invalidatePicker() {picker?.payload=null}
    private fun retire() {
        val old=current ?: return
        current=null;old.cancelled.set(true);old.job.cancel()
        cleanups+=scope.launch(NonCancellable+Dispatchers.IO) {
            old.job.join()
            try {old.prepared?.close()}
            catch(error:Throwable) {android.util.Log.w("xnotes.recovery","Recovery preview cleanup retained resources",error)}
            finally {old.prepared=null}
        }
    }
    fun open(editor:Editor) {open(editor,null)}
    private fun open(editor:Editor,artifact:RecoveryLegacyArtifact?) {
        if(!alive) return
        invalidatePicker();retire()
        val r=Request(++sequence,editor,requireNotNull(scope.coroutineContext[Job]))
        current=r;state=RecoveryPreviewUi(editor.pane)
        r.scope.launch {
            try {
                when(val result=editor.prepareRecoveryPreview(artifact){check(r)}) {
                    is RecoveryPreviewLoad.Ready->{
                        r.prepared=result.prepared
                        check(r)
                        update(r){it.copy(phase=RecoveryPreviewPhase.READY,displayName=result.prepared.displayName,
                            kind=result.prepared.kind,byteSize=result.prepared.byteSize,pageCount=result.prepared.pageCount)}
                        render(r,0)
                    }
                    is RecoveryPreviewLoad.Absent->update(r){it.copy(phase=RecoveryPreviewPhase.ABSENT,artifacts=result.legacy)}
                    RecoveryPreviewLoad.Cleared->update(r){it.copy(phase=RecoveryPreviewPhase.CLEARED)}
                    RecoveryPreviewLoad.SourceRecoveryRequired->update(r){it.copy(phase=RecoveryPreviewPhase.SOURCE_REQUIRED)}
                    is RecoveryPreviewLoad.Invalid->update(r){it.copy(phase=RecoveryPreviewPhase.INVALID,error=result.message)}
                }
            } catch(_:CancellationException) {
                if(stillCurrent(r)) update(r){it.copy(phase=RecoveryPreviewPhase.INVALID,error=context.getString(R.string.recovery_preview_failed))}
            }
            catch(_:Throwable) {update(r){it.copy(phase=RecoveryPreviewPhase.INVALID,error=context.getString(R.string.recovery_preview_failed))}}
        }
    }
    fun choose(artifact:RecoveryLegacyArtifact) {current?.let {open(it.editor,artifact)}}
    fun closePreview() {invalidatePicker();retire();state=null}
    fun page(index:Int) {current?.let {r->if(index in 0 until (state?.pageCount ?: 0) && state?.busy!=true) render(r,index)}}
    private fun render(r:Request,index:Int) {
        val prepared=r.prepared ?: return
        val generation=++r.renderGeneration
        r.render?.cancel()
        update(r){it.copy(page=index,image=null,rendering=true,renderFailed=false)}
        r.render=r.scope.launch {
            var image:android.graphics.Bitmap?=null
            try {
                image=r.editor.renderRecoveryPreviewPage(prepared,index,1024){check(r);if(r.renderGeneration!=generation) throw CancellationException("Preview page changed")}
                check(r);ensureActive()
                val ready=requireNotNull(image).asImageBitmap()
                update(r){it.copy(image=ready,rendering=false)}
                // Published pixels are now Compose-owned, like the existing file thumbnails.
                image=null
            } catch(_:CancellationException) {
                if(stillCurrent(r) && r.renderGeneration==generation) update(r){it.copy(rendering=false,renderFailed=true)}
            }
            catch(_:Throwable) {if(r.renderGeneration==generation) update(r){it.copy(rendering=false,renderFailed=true)}}
            finally {image?.recycle()}
        }
    }
    fun attachPicker(launch:(RecoveryPickerLaunch)->Unit) {if(alive) {launcher=launch;pickerAvailable=true}}
    fun detachPicker() {launcher=null;pickerAvailable=false}
    private fun launch(payload:Payload,kind:RecoveryPickerKind,filename:String?=null) {
        if(picker!=null || launcher==null || !alive) return
        val cell=Picker(RecoveryPickerLaunch(++pickerSequence,kind,filename),payload)
        picker=cell;pickerPending=true
        update(payload.request){it.copy(error=null,retryFailed=false,saved=null)}
        try {requireNotNull(launcher).invoke(cell.launch)}
        catch(_:Throwable) {
            if(picker===cell) {cell.payload=null;picker=null;pickerPending=false}
            update(payload.request){it.copy(error=context.getString(R.string.recovery_picker_failed))}
        }
    }
    fun requestCopy() {
        val r=current ?: return;val prepared=r.prepared ?: return
        if(state?.busy==true || picker!=null || launcher==null || r.pending!=null) return
        try {
            val copy=r.editor.beginRecoveryCopy(prepared)
            val fallback=context.getString(if(copy.kind==DocumentKind.CANVAS) R.string.recovery_copy_canvas_name else R.string.recovery_copy_note_name)
            val title=DocumentKind.stripSuffix(copy.displayName?.takeIf {it.isNotBlank()} ?: fallback)
            launch(Payload.Create(r,copy),RecoveryPickerKind.CREATE,
                context.getString(R.string.recovery_copy_filename,title,copy.kind.extension))
        } catch(_:Throwable) {update(r){it.copy(error=context.getString(R.string.recovery_copy_failed))}}
    }
    fun requestResumePicker() {
        val r=current ?: return
        if(state?.busy!=true && r.pending==null && picker==null && launcher!=null) launch(Payload.Resume(r),RecoveryPickerKind.RESUME)
    }
    fun ownsPickerResult(id:Long,kind:RecoveryPickerKind):Boolean {
        val payload=picker?.takeIf {it.launch.id==id && it.launch.kind==kind}?.payload ?: return false
        return stillCurrent(payload.request)
    }
    /** Only the original outstanding request can consume a raw registry result. */
    fun receivePickerResult(id:Long,kind:RecoveryPickerKind,uri:Uri?) {
        val cell=picker?.takeIf {it.launch.id==id && it.launch.kind==kind} ?: return
        picker=null;pickerPending=false
        val payload=cell.payload;cell.payload=null
        if(uri==null || payload==null) return
        val r=payload.request
        try {check(r)} catch(_:CancellationException) {return}
        update(r){it.copy(busy=true,error=null,retryFailed=false,saved=null)}
        r.scope.launch {
            try {
                // Validate before grants/query, and again on Main before any publication.
                val name=withContext(Dispatchers.IO) {
                    check(r);ensureActive()
                    runCatching {context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
                    val value=runCatching {context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {c->
                        if(c.moveToFirst()) c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf {it>=0}?.let(c::getString) else null
                    }}.getOrNull() ?: uri.lastPathSegment ?: context.getString(R.string.recovery_copy_note_name)
                    check(r);value
                }
                check(r);ensureActive()
                val result=when(payload) {
                    is Payload.Create->r.editor.publishRecoveryCopy(payload.copy,uri.toString(),name){check(r)}
                    is Payload.Resume->r.editor.completeRecoveryCopy(uri.toString(),name){check(r)}
                }
                accept(r,result,false)
            } catch(_:CancellationException) {
                if(stillCurrent(r)) update(r){it.copy(busy=false,hasPending=r.pending!=null,error=context.getString(R.string.recovery_copy_failed))}
            }
            catch(_:Throwable) {update(r){it.copy(busy=false,error=context.getString(R.string.recovery_copy_failed))}}
        }
    }
    fun completePending() {
        val r=current ?: return;val pending=r.pending ?: return
        if(state?.busy==true) return
        update(r){it.copy(busy=true,error=null,retryFailed=false,saved=null)}
        r.scope.launch {
            try {accept(r,r.editor.completeRecoveryCopy(pending){check(r)},true)}
            catch(_:CancellationException) {
                if(stillCurrent(r)) update(r){it.copy(busy=false,hasPending=r.pending!=null,retryFailed=true,error=context.getString(R.string.recovery_copy_failed))}
            }
            catch(_:Throwable) {update(r){it.copy(busy=false,retryFailed=true,error=context.getString(R.string.recovery_copy_failed))}}
        }
    }
    private fun accept(r:Request,result:RecoveryCopyResult,retry:Boolean) {
        check(r)
        when(result) {
            is RecoveryCopyResult.Saved->{r.pending=null;update(r){it.copy(busy=false,hasPending=false,retryFailed=false,error=null,saved=result)}}
            is RecoveryCopyResult.Retained->{r.pending=result.pending;update(r){it.copy(busy=false,hasPending=true,error=result.message)}}
            is RecoveryCopyResult.Failed->update(r){it.copy(busy=false,hasPending=r.pending!=null,retryFailed=retry,error=result.message)}
        }
    }
    fun dispose():Job {
        disposal?.let {return it}
        alive=false;invalidatePicker();picker=null;pickerPending=false;detachPicker();retire();state=null
        val jobs=cleanups.toList();scope.cancel()
        return CoroutineScope(NonCancellable+Dispatchers.IO).launch {jobs.joinAll()}.also {disposal=it}
    }
}

/** Pending registration is saved independently of the preview's non-restorable publication owner. */
@Composable
internal fun RecoveryPreviewHost(controller:RecoveryPreviewController) {
    val picker=rememberNativeDestinationPicker()
    val saved=picker.saved
    DisposableEffect(controller,picker) {
        controller.attachPicker {request->
            val owner="${controller.pickerNamespace}:${request.id}"
            val current={controller.ownsPickerResult(request.id,request.kind).also {valid->
                if(!valid) controller.receivePickerResult(request.id,request.kind,null)
            }}
            val receive:(Uri?)->Unit={uri->controller.receivePickerResult(request.id,request.kind,uri)}
            val launched=when(request.kind) {
                RecoveryPickerKind.CREATE->picker.launch(requireNotNull(request.filename),owner,current,receive)
                RecoveryPickerKind.RESUME->picker.launchOpen(arrayOf("*/*"),owner,current,receive)
            }
            check(launched) {"A destination is still outstanding or unavailable"}
        }
        onDispose {controller.detachPicker();controller.dispose()}
    }
    val state=controller.state ?: return
    val actions=controller.actions() ?: return
    val context=LocalContext.current
    val previewHeight=LocalConfiguration.current.screenHeightDp.dp/3
    AlertDialog(onDismissRequest=actions::closePreview,title={Text(stringResource(R.string.recovery_materials),fontSize=TextSize.heading)},text={
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(Space.s12)) {
            Text(stringResource(if(state.pane==Pane.PRIMARY) R.string.recovery_preview_primary else R.string.recovery_preview_secondary),fontSize=TextSize.caption)
            when(state.phase) {
                RecoveryPreviewPhase.LOADING->Text(stringResource(R.string.recovery_preview_loading),fontSize=TextSize.body)
                RecoveryPreviewPhase.ABSENT->{
                    Text(stringResource(if(state.artifacts.isEmpty()) R.string.recovery_preview_empty else R.string.recovery_preview_choose),fontSize=TextSize.body)
                    for(artifact in RecoveryLegacyArtifact.entries) if(artifact in state.artifacts) {
                        RecoveryButton(if(artifact==RecoveryLegacyArtifact.NOTE) R.string.recovery_preview_note else R.string.recovery_preview_canvas) {actions.choose(artifact)}
                    }
                }
                RecoveryPreviewPhase.CLEARED->Text(stringResource(R.string.recovery_preview_cleared),fontSize=TextSize.body)
                RecoveryPreviewPhase.SOURCE_REQUIRED->Text(stringResource(R.string.recovery_preview_source_required),fontSize=TextSize.body)
                RecoveryPreviewPhase.INVALID->Text(stringResource(R.string.recovery_preview_failed),fontSize=TextSize.body)
                RecoveryPreviewPhase.READY->{
                    Text(state.displayName ?: stringResource(R.string.recovery_preview_historical),fontSize=TextSize.title)
                    Text(stringResource(R.string.recovery_preview_details,
                        stringResource(if(state.kind==DocumentKind.CANVAS) R.string.kind_canvas else R.string.kind_note),
                        android.text.format.Formatter.formatShortFileSize(context,state.byteSize)),fontSize=TextSize.caption)
                    Text(stringResource(R.string.recovery_preview_body),fontSize=TextSize.body)
                    if(state.rendering) Text(stringResource(R.string.recovery_preview_loading),fontSize=TextSize.body)
                    if(state.renderFailed) Text(stringResource(R.string.recovery_preview_failed),fontSize=TextSize.body)
                    state.image?.let {Image(it,null,Modifier.fillMaxWidth().height(previewHeight),contentScale=ContentScale.Fit)}
                    Row(horizontalArrangement=Arrangement.spacedBy(Space.s8)) {
                        RecoveryButton(R.string.recovery_preview_previous,state.page>0 && !state.busy) {actions.page(state.page-1)}
                        Text(stringResource(R.string.recovery_preview_page,state.page+1,state.pageCount),fontSize=TextSize.body)
                        RecoveryButton(R.string.recovery_preview_next,state.page+1<state.pageCount && !state.busy) {actions.page(state.page+1)}
                    }
                    RecoveryButton(R.string.recovery_preview_save_copy,!state.busy && !state.hasPending && saved.pending==null && saved.interrupted==null && !controller.pickerPending && controller.pickerAvailable) {actions.requestCopy()}
                }
            }
            if(state.busy) Text(stringResource(R.string.recovery_copy_working),fontSize=TextSize.body)
            if(controller.pickerPending || saved.pending!=null) Text(stringResource(R.string.recovery_picker_waiting),fontSize=TextSize.caption)
            if(state.retryFailed) Text(stringResource(R.string.recovery_copy_retry_failed),fontSize=TextSize.body)
            state.error?.let {Text(it,fontSize=TextSize.body)}
            if(state.hasPending) RecoveryButton(R.string.recovery_copy_continue,!state.busy) {actions.completePending()}
            state.saved?.let {Text(stringResource(R.string.recovery_copy_saved),fontSize=TextSize.title);Text(it.name,fontSize=TextSize.body);Text(it.uri,fontSize=TextSize.caption)}
            RecoveryButton(R.string.recovery_copy_resume_pick,!state.busy && !state.hasPending && saved.pending==null && saved.interrupted==null && !controller.pickerPending && controller.pickerAvailable) {actions.requestResumePicker()}
        }
    },confirmButton={RecoveryButton(R.string.close_preview){actions.closePreview()}})
}

@Composable
private fun RecoveryButton(label:Int,enabled:Boolean=true,onClick:()->Unit) {
    TextButton(onClick=onClick,enabled=enabled,modifier=Modifier.heightIn(min=MinTouchTarget)) {Text(stringResource(label),fontSize=TextSize.body)}
}
