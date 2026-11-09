package com.xnotes.mindmap

internal enum class SourceTargetEditMode { RANGE,REPLACE }

/** One displayed source membership, and after acceptance only its exact application receipt. */
internal class MapSourceTargetEditTicket internal constructor(
    internal val issuer:MapSession,internal val graphId:String,internal val epoch:Long,
    internal val nodeLifetime:Any,internal val source:MapSourcePreviewTicket,
    val mode:SourceTargetEditMode,
) {
    val nodeId get()=source.nodeId
    val sourceId get()=source.sourceId
    val initialTarget get()=source.target
    internal var applied:MapSourcePreviewTicket?=null
    val accepted get()=applied!=null
}

internal sealed interface MapSourceTargetEditResult {
    data class Applied(val changed:Boolean):MapSourceTargetEditResult
    data object Changed:MapSourceTargetEditResult
    data object Busy:MapSourceTargetEditResult
    data object Invalid:MapSourceTargetEditResult
}
