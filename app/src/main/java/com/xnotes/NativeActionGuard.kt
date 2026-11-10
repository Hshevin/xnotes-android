package com.xnotes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.ui.Editor
import com.xnotes.core.util.DocumentKind
import kotlinx.coroutines.CompletableDeferred

internal enum class NativeActionStop {CANCELLED,FAILED,CHANGED}

/** The actual native model bound to the existing Save as picker and its one terminal outcome. */
internal class SavePickerRequest(val proof:Editor.OpenDiscard,private val afterSave:(()->Unit)?,
    private val onStopped:(NativeActionStop)->Unit,private val stillCurrent:()->Boolean) {
    val kind=if(proof.owner.canvasOpen) DocumentKind.CANVAS else DocumentKind.NOTE
    private val identity=if(proof.owner.canvasOpen) proof.owner.infinite.document.identity else proof.owner.state.document.identity
    private val path=proof.owner.visibleDocumentUri
    val suggestedName=DocumentKind.withSuffix(if(proof.owner.canvasOpen) proof.owner.infinite.title else proof.owner.title,kind)
    private var finished=false
    fun current()=!finished && stillCurrent()
    fun readyToSave()=current() && proof.applies() &&
        proof.owner.canvasOpen==(kind==DocumentKind.CANVAS) && proof.owner.visibleDocumentUri==path &&
        (if(proof.owner.canvasOpen) proof.owner.infinite.document.identity else proof.owner.state.document.identity)==identity
    fun saveTo(uri:String,name:String?):Boolean {
        if(!readyToSave()) {stop(NativeActionStop.CHANGED);return false}
        proof.owner.saveAsToThen(uri,name) {ok->when {
            !current()->stop(NativeActionStop.CHANGED)
            !ok->stop(NativeActionStop.FAILED)
            proof.afterSaveApplies()->saved()
            else->stop(NativeActionStop.CHANGED)
        }}
        return true
    }
    fun saved() {if(!finished) {finished=true;afterSave?.invoke()}}
    fun stop(reason:NativeActionStop) {if(!finished) {finished=true;onStopped(reason)}}
}

/** Capture before awaiting drafts so a later model can never inherit an earlier picker request. */
internal class NativeSavePickerCapture(target:Editor) {
    private val owner=target.documentOwner
    private val initial=owner.discardForOpen()
    private val canvas=owner.canvasOpen
    private val identity=if(canvas) owner.infinite.document.identity else owner.state.document.identity
    private val path=owner.visibleDocumentUri
    suspend fun prepare(afterSave:(()->Unit)?,onStopped:(NativeActionStop)->Unit,stillCurrent:()->Boolean):SavePickerRequest? {
        if(!stillCurrent() || !owner.awaitStableForSave() || !stillCurrent() || !initial.sameModel() ||
            owner.canvasOpen!=canvas || owner.visibleDocumentUri!=path ||
            (if(canvas) owner.infinite.document.identity else owner.state.document.identity)!=identity) {
            onStopped(NativeActionStop.CHANGED);return null
        }
        return SavePickerRequest(owner.discardForOpen(),afterSave,onStopped,stillCurrent)
    }
}

internal fun requiresNativeClosePrompt(target:Editor):Boolean = when {
    target.canvasOpen && !target.requiresIndependentSave->false
    target.autosaveUri!=null && !target.requiresIndependentSave->false
    else->target.requiresIndependentSave || if(target.canvasOpen) target.infinite.document.dirty else target.dirty
}

/** Main's ordinary Save dispatch uses the writer for the actual displayed native model. */
internal fun captureDisplayedNativeSave(target:Editor):()->Boolean {
    val owner=target.documentOwner
    val proof=owner.discardForOpen()
    val identity=owner.captureNativePresentationIdentity()
    return {proof.sameModel() && owner.matchesNativePresentationIdentity(identity)}
}

internal suspend fun saveDisplayedNative(target:Editor,stillCurrent:()->Boolean={true}):Boolean {
    if(!stillCurrent()) return false
    val owner=target.documentOwner
    val proof=owner.discardForOpen()
    val identity=owner.captureNativePresentationIdentity()
    val path=owner.visibleDocumentUri ?: return false
    if(!stillCurrent() || owner.requiresIndependentSave) return false
    if(owner.canvasOpen) {
        val model=owner.infinite.document
        val saved=owner.saveCanvasBeforeReplacement {stillCurrent() && proof.sameModel() && owner.matchesNativePresentationIdentity(identity)}
        return saved && stillCurrent() && proof.sameModel() && owner.canvasOpen &&
            owner.infinite.document===model && model.path==path
    }
    val completed=CompletableDeferred<Boolean>()
    owner.saveToThen(path) {completed.complete(it)}
    return completed.await() && stillCurrent() && proof.sameModel()
}

/** One existing save/discard prompt, bound to its original native model. */
internal class GuardRequest(val editor:Editor,private val action:()->Unit,val onDiscard:(Editor.OpenDiscard)->Unit,
    private val onStopped:(NativeActionStop)->Unit,val sourceNavigation:Any?,private val stillCurrent:()->Boolean) {
    val discard=editor.discardForOpen()
    private var started=false
    private var finished=false
    fun current()=!finished && stillCurrent()
    fun begin():Boolean {if(started || finished) return false;started=true;return true}
    fun proceed() {if(!finished) {finished=true;action()}}
    fun stop(reason:NativeActionStop) {if(!finished) {finished=true;onStopped(reason)}}
}

/** A callback belonging to a retired dialog cannot dismiss a newer pending prompt. */
internal class NativeGuardState {
    private var pending by mutableStateOf<GuardRequest?>(null)
    var current:GuardRequest?
        get()=pending
        set(value) {
            val previous=pending
            pending=value
            if(value!=null && previous!==value) previous?.stop(NativeActionStop.CHANGED)
        }
    fun consume(expected:GuardRequest):Boolean {
        if(current!==expected) return false
        current=null
        return expected.begin()
    }
}
