package com.xnotes.ui

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.UUID

/** Saved registry identity survives recreation; runtime callbacks never acquire replacement owners. */
internal class NativeDestinationPicker(private val registry:ActivityResultRegistry,
    internal val saved:DestinationPickerState=DestinationPickerState()) {
    private class Cell(val request:DestinationPickerRequest,var callback:((Uri?)->Unit)?,val current:()->Boolean) {
        var launcher:ActivityResultLauncher<String>?=null
    }
    private var current:Cell?=null
    private var disposed=false
    var busy by mutableStateOf(false);private set

    init {saved.pending?.let {register(Cell(it,null,{false}))}}

    @MainThread
    fun launch(name:String,isCurrent:()->Boolean={true},onResult:(Uri?)->Unit):Boolean {
        if(disposed || current!=null || saved.interrupted!=null) return false
        val request=DestinationPickerRequest("native-destination-${UUID.randomUUID()}","CREATE",name)
        val cell=Cell(request,onResult,isCurrent)
        saved.pending=request
        return try {
            register(cell)
            if(disposed || current!==cell) false else {requireNotNull(cell.launcher).launch(name);true}
        } catch(_:Exception) {
            // A synchronous result callback may already have launched another request.
            // Failure of this launch can release only its original cell.
            release(cell,consume=true)
            false
        }
    }

    private fun register(cell:Cell) {
        current=cell;busy=true
        val launcher=registry.register(cell.request.key,ActivityResultContracts.CreateDocument("application/octet-stream")) {uri->
            if(!disposed && current===cell) {
                val callback=cell.callback
                val accepted=callback!=null && runCatching(cell.current).getOrDefault(false)
                release(cell,consume=true)
                if(accepted) callback?.invoke(uri) else saved.retain(cell.request,uri)
            }
        }
        // Registration can deliver a pending raw result synchronously.
        if(disposed || current!==cell) launcher.unregister() else cell.launcher=launcher
    }

    private fun release(cell:Cell,consume:Boolean) {
        if(current===cell) {current=null;busy=false}
        if(consume && saved.pending==cell.request) saved.pending=null
        cell.callback=null
        val launcher=cell.launcher;cell.launcher=null
        launcher?.unregister()
    }

    @MainThread
    fun dispose() {
        disposed=true
        current?.let {release(it,consume=false)}
    }
}

@Composable
internal fun rememberNativeDestinationPicker():NativeDestinationPicker {
    val registry=requireNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
    val saved=rememberDestinationPickerState()
    val picker=remember(registry,saved) {NativeDestinationPicker(registry,saved)}
    DisposableEffect(picker) {onDispose {picker.dispose()}}
    InterruptedDestinationNotice(saved)
    return picker
}
