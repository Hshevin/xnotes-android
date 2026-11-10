package com.xnotes.ui

/** One Main-thread preparation. Its candidate and native owners never escape into Map state. */
internal class PreparedPagedPairOpen internal constructor(
    val target:Editor,
    keyHandoffs:List<Pair<Editor,Editor>>,
    private val check:()->Boolean,
    private val adopt:(()->Boolean)->Boolean,
    private val release:suspend ()->Unit,
) {
    val keyHandoffs:List<Pair<Editor,Editor>> = java.util.Collections.unmodifiableList(ArrayList(keyHandoffs))
    val nativePane:Pane get()=target.pane
    private var consumed=false
    private var closed=false
    fun current()=!closed && !consumed && check()
    fun commit(beforeAdopt:()->Boolean={true}):Boolean {
        if(!current()) return false
        if(!adopt(beforeAdopt)) return false
        consumed=true;return true
    }
    suspend fun close() {
        if(closed) return
        closed=true;release()
    }
}
