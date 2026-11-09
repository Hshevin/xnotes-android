package com.xnotes.mindmap

/** Fixed destination intent; a source request never retargets itself after asynchronous source IO. */
internal sealed interface MapReferenceDestination {
    data class Existing(val nodeId:String):MapReferenceDestination
    data class New(val node:MapNode):MapReferenceDestination
}

internal class MapSourceImportTicket internal constructor(
    internal val issuer:Any,
    internal val epoch:Long,
    internal val documentId:String,
    internal val destination:MapReferenceDestination,
    internal val nodeLifetime:Any?,
    internal val parentLifetime:Any?,
) {
    internal var importedNodeLifetime:Any?=null
}

internal sealed interface MapSourceImportResult {
    data class Applied(val nodeId:String,val sourceId:String,val alreadyApplied:Boolean):MapSourceImportResult
    data object Busy:MapSourceImportResult
    data object Changed:MapSourceImportResult
    data object Invalid:MapSourceImportResult
}

/** One accepted write, rather than the aggregate toolbar saving indicator. */
internal sealed interface MapSaveOutcome {
    val capturedRevision:Long
    data class Written(override val capturedRevision:Long,val latest:Boolean):MapSaveOutcome
    data class Retained(override val capturedRevision:Long,val latest:Boolean,val cause:Throwable):MapSaveOutcome
    data class Conflict(override val capturedRevision:Long,val retained:Boolean,val cause:Throwable):MapSaveOutcome
    data class Changed(override val capturedRevision:Long):MapSaveOutcome
    data class Failed(override val capturedRevision:Long,val cause:Throwable):MapSaveOutcome
}
