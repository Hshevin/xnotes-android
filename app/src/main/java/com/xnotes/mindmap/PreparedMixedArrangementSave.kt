package com.xnotes.mindmap

import com.xnotes.ui.MixedSplitRequest
import java.io.File

/** An operation-local proof; neither it nor its closures belong in a retained Entry. */
internal class PreparedMixedArrangementSave internal constructor(
    val request:MixedSplitRequest,
    val name:String,
    private val check:()->Boolean,
    private val publishChecked:suspend ((()->File?))->File?,
) {
    fun current():Boolean=check()
    suspend fun publish(commit:()->File?):File?=if(current()) publishChecked(commit) else null
}
