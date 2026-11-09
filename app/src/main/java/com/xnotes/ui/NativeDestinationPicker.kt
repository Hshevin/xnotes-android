package com.xnotes.ui

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
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
    internal val saved:DestinationPickerState=DestinationPickerState(),
    private val createContract:ActivityResultContract<String,Uri?> = ActivityResultContracts.CreateDocument("application/octet-stream"),
    private val openContract:ActivityResultContract<Array<String>,Uri?> = ActivityResultContracts.OpenDocument()) {
    private class Cell(val request:DestinationPickerRequest,var callback:((Uri?)->Unit)?,val current:()->Boolean,val restored:Boolean=false) {
        var unregister:(()->Unit)?=null
        var launch:(()->Unit)?=null
    }
    private var current:Cell?=null
    private var disposed=false
    var busy by mutableStateOf(false);private set

    init {saved.pending?.let {request->
        val cell=Cell(request,null,{false},restored=true)
        if(request.kind=="OPEN") register(cell,openContract,emptyArray()) else register(cell,createContract,request.name)
    }}

    @MainThread
    fun launch(name:String,owner:String="",isCurrent:()->Boolean={true},onResult:(Uri?)->Unit):Boolean =
        launch(createContract,name,"CREATE",name,owner,isCurrent,onResult)

    @MainThread
    fun launchOpen(mimeTypes:Array<String>,owner:String="",isCurrent:()->Boolean={true},onResult:(Uri?)->Unit):Boolean =
        launch(openContract,mimeTypes.copyOf(),"OPEN","",owner,isCurrent,onResult)

    private fun <I> launch(contract:ActivityResultContract<I,Uri?>,input:I,kind:String,name:String,owner:String,
        isCurrent:()->Boolean,onResult:(Uri?)->Unit):Boolean {
        if(disposed || current!=null || saved.interrupted!=null) return false
        val request=DestinationPickerRequest("native-destination-${UUID.randomUUID()}",kind,name,owner)
        val cell=Cell(request,onResult,isCurrent)
        saved.pending=request
        return try {
            register(cell,contract,input)
            if(disposed || current!==cell) false else {requireNotNull(cell.launch).invoke();true}
        } catch(_:Exception) {
            // A synchronous result callback may already have launched another request.
            // Failure of this launch can release only its original cell.
            release(cell,consume=true)
            false
        }
    }

    private fun <I> register(cell:Cell,contract:ActivityResultContract<I,Uri?>,input:I) {
        current=cell;busy=true
        val launcher=registry.register(cell.request.key,contract) {uri->
            if(!disposed && current===cell) {
                val callback=cell.callback
                val accepted=callback!=null && runCatching(cell.current).getOrDefault(false)
                release(cell,consume=true)
                if(accepted) callback?.invoke(uri) else saved.retain(cell.request,uri,retainOpen=cell.restored)
            }
        }
        // Registration can deliver a pending raw result synchronously.
        if(disposed || current!==cell) launcher.unregister() else {
            cell.unregister=launcher::unregister;cell.launch={launcher.launch(input)}
        }
    }

    private fun release(cell:Cell,consume:Boolean) {
        if(current===cell) {current=null;busy=false}
        if(consume && saved.pending==cell.request) saved.pending=null
        cell.callback=null
        cell.launch=null
        val unregister=cell.unregister;cell.unregister=null;unregister?.invoke()
    }

    @MainThread
    fun retire() {current?.callback=null}

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
