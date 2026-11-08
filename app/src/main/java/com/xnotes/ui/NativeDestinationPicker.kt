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

/** A destination result belongs to the launch that created it, including across UI changes. */
internal class NativeDestinationPicker(private val registry:ActivityResultRegistry) {
    private class Cell(var callback:((Uri?)->Unit)?) {
        var launcher:ActivityResultLauncher<String>?=null
    }
    private var current:Cell?=null
    private var disposed=false
    var busy by mutableStateOf(false);private set

    @MainThread
    fun launch(name:String,onResult:(Uri?)->Unit):Boolean {
        if(disposed || current!=null) return false
        val cell=Cell(onResult)
        current=cell;busy=true
        return try {
            // Never restore or reuse a key: an old ActivityResultRegistry mapping must not
            // acquire a callback from a newer request or a reconstructed UI host.
            val key="native-destination-${UUID.randomUUID()}"
            val launcher=registry.register(key,ActivityResultContracts.CreateDocument("application/octet-stream")) {uri->
                if(!disposed && current===cell) {
                    val callback=cell.callback
                    release(cell)
                    callback?.invoke(uri)
                }
            }
            cell.launcher=launcher
            if(disposed || current!==cell) {
                release(cell)
                false
            } else {
                launcher.launch(name)
                true
            }
        } catch(_:Exception) {
            // A synchronous result callback may already have launched another request.
            // Failure of this launch can release only its original cell.
            release(cell)
            false
        }
    }

    private fun release(cell:Cell) {
        if(current===cell) {current=null;busy=false}
        cell.callback=null
        val launcher=cell.launcher;cell.launcher=null
        launcher?.unregister()
    }

    @MainThread
    fun dispose() {
        disposed=true
        current?.let(::release)
    }
}

@Composable
internal fun rememberNativeDestinationPicker():NativeDestinationPicker {
    val registry=requireNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
    val picker=remember(registry) {NativeDestinationPicker(registry)}
    DisposableEffect(picker) {onDispose {picker.dispose()}}
    return picker
}
