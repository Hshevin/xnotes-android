package com.xnotes.ui

import com.xnotes.storage.NativeSourceVersion

/** One operation's actual native publication and runtime proof; never persisted as save authority. */
internal class PreparedMixedNoteArrangementSave internal constructor(
    val nativePane:Pane,
    val source:SplitSourceRef,
    val position:Editor.ReadingPosition,
    val version:NativeSourceVersion,
    private val check:()->Boolean,
    private val verify:suspend ()->(()->Boolean)?,
) {
    fun current():Boolean=check()
    suspend fun verifySource():(()->Boolean)?=if(current()) verify() else null
}
