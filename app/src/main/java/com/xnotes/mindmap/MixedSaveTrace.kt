package com.xnotes.mindmap

/** Names the stage that refused a mixed arrangement save; never logs a path, title or content. */
internal object MixedSaveTrace {
    fun stopped(stage:String) {
        runCatching {android.util.Log.w("xnotes.split","Mixed arrangement save stopped: $stage")}
    }
}
