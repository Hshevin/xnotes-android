package com.xnotes.mindmap

/** Names which safety check refused a mixed arrangement save; logs no path, title or content. */
internal object MixedSaveTrace {
    fun <T> reject(reason:String):T? {
        runCatching {android.util.Log.w("xnotes.split","Mixed arrangement save stopped: $reason")}
        return null
    }
}
