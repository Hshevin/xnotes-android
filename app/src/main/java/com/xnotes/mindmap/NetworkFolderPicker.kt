package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** A canceled filter draft loses its callback while the original system request still owns its slot. */
@MainThread
internal class NetworkFolderPicker(private val registry:ActivityResultRegistry) {
    private class Cell(var callback:((Uri?)->Unit)?) {
        var launcher:ActivityResultLauncher<android.net.Uri?>?=null
    }
    private var cell:Cell?=null
    private var disposed=false
    var busy by mutableStateOf(false);private set

    fun launch(onResult:(Uri?)->Unit):Boolean {
        if(disposed || cell!=null) return false
        val request=Cell(onResult);cell=request;busy=true
        return try {
            // A restored registry may still hold an old request code. Never give its key a new payload.
            val launcher=registry.register("network-folder-${UUID.randomUUID()}",ActivityResultContracts.OpenDocumentTree()) {uri->
                if(!disposed && cell===request) {
                    val callback=request.callback
                    release(request)
                    callback?.invoke(uri)
                }
            }
            request.launcher=launcher
            if(disposed || cell!==request) {release(request);false}
            else {launcher.launch(null);true}
        } catch(_:Exception) {release(request);false}
    }
    fun retire() {cell?.callback=null}
    fun dispose() {disposed=true;cell?.let(::release)}
    private fun release(request:Cell) {
        if(cell===request) {cell=null;busy=false}
        request.callback=null
        val launcher=request.launcher;request.launcher=null;launcher?.unregister()
    }
}

/** One cancellable display-name observation, never a directory walk or a persisted URI grant. */
internal suspend fun readNetworkFolder(context:Context,tree:Uri,fallback:String):NetworkFolder {
    val app=context.applicationContext
    return withContext(Dispatchers.IO) {
        suspendCancellableCoroutine {waiting->
            val cancellation=CancellationSignal()
            waiting.invokeOnCancellation {cancellation.cancel()}
            try {
                cancellation.throwIfCanceled()
                val document=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
                val name=app.contentResolver.query(document,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null,cancellation)
                    ?.use {if(it.moveToFirst()) it.getString(0) else null}
                cancellation.throwIfCanceled()
                waiting.resumeWith(Result.success(NetworkFolder(tree.toString(),name?.takeIf {it.isNotBlank()} ?: fallback)))
            } catch(error:Exception) {waiting.resumeWith(Result.failure(error))}
        }
    }
}
