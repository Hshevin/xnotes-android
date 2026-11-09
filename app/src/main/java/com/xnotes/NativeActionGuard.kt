package com.xnotes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.ui.Editor

internal enum class NativeActionStop {CANCELLED,FAILED,CHANGED}

/** Terminal notification for the existing native Save as picker; never starts a write itself. */
internal class SavePickerRequest(val proof:Editor.OpenDiscard,private val afterSave:(()->Unit)?,
    private val onStopped:(NativeActionStop)->Unit,private val stillCurrent:()->Boolean) {
    private var finished=false
    fun current()=!finished && stillCurrent()
    fun saved() {if(!finished) {finished=true;afterSave?.invoke()}}
    fun stop(reason:NativeActionStop) {if(!finished) {finished=true;onStopped(reason)}}
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
    var current by mutableStateOf<GuardRequest?>(null)
    fun consume(expected:GuardRequest):Boolean {
        if(current!==expected) return false
        current=null
        return expected.begin()
    }
}
