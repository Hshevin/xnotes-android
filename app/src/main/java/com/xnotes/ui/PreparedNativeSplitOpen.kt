package com.xnotes.ui

internal data class NativeSplitSource(val uri:String,val name:String?=null,val expectedId:String?=null)

internal data class NativeSplitOpenLayout(
    val ratio:Float=.5f,
    val sideBySide:Boolean?=null,
    val reversed:Boolean=false,
    val focused:Pane=Pane.PRIMARY,
    val firstPosition:Editor.ReadingPosition?=null,
    val secondPosition:Editor.ReadingPosition?=null,
)

/** A single native two-view adoption. Both actual models stay private until commit. */
internal class PreparedNativeSplitOpen internal constructor(
    val first:Editor,
    val second:Editor,
    val focusedPane:Pane,
    keyHandoffs:List<Pair<Editor,Editor>>,
    private val check:()->Boolean,
    private val adopt:(()->Boolean)->Boolean,
    private val release:suspend ()->Unit,
) {
    val firstPane:Pane get()=first.pane
    val secondPane:Pane get()=second.pane
    val keyHandoffs:List<Pair<Editor,Editor>> = java.util.Collections.unmodifiableList(ArrayList(keyHandoffs))
    private var consumed=false
    private var closed=false
    fun current()=!closed && !consumed && check()
    fun commit(beforeAdopt:()->Boolean={true}):Boolean {
        if(!current() || !adopt(beforeAdopt)) return false
        consumed=true;return true
    }
    suspend fun close() {if(!closed) {closed=true;release()}}
}
