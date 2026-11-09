package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.xnotes.storage.NotebookStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Best-effort, rebuildable discovery. Only callers with an actually read/written target call this.
 * A recovery winner whose target was not verified must never claim the target's identity here.
 * This neither reads a fresh source version nor grants any map/native writer authority.
 */
internal object MapBacklinkDiscovery {
    suspend fun rememberActual(context:Context,uri:Uri,documentId:String,title:String?=null):Boolean =
        withContext(NonCancellable+Dispatchers.IO) {
            try {
                val name=title?.takeIf {it.isNotBlank()} ?: runCatching {
                    context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                        if(it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment.orEmpty()
                NotebookStorage.get(context).registry.registerMapObserved(documentId,name,uri.toString())
                true
            } catch(_:Exception) {false} // Derived-index failure cannot relabel a successful document write.
        }
}
