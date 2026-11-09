package com.xnotes.mindmap

/** One observed membership, bound to the broker entry and its source lifetime. */
internal class MapBacklinkMembership internal constructor(
    internal val issuer:MapSession,
    internal val documentId:String,
    internal val source:MapSourcePreviewTicket,
    private val entryCurrent:()->Boolean,
) {
    internal fun current(session:MapSession,graphId:String,nodeId:String,sourceId:String,target:MapSourceTarget):Boolean =
        issuer===session && documentId==graphId && source.nodeId==nodeId && source.sourceId==sourceId &&
            source.target==target && entryCurrent() && session.currentSourcePreview(source)!=null
}

internal class MapBacklinkFocusTicket internal constructor(
    internal val source:MapSourceNavigationTicket?,
    internal val membership:MapBacklinkMembership?=null,
    internal val removal:MapBacklinkRemovalTicket?=null,
) {
    val nodeId get()=source?.nodeId ?: requireNotNull(removal).nodeId
    val sourceId get()=source?.sourceId ?: requireNotNull(removal).sourceId
    val target get()=source?.target ?: requireNotNull(removal).target
    val opensSource get()=source!=null
}

internal class MapBacklinkRemovalTicket internal constructor(
    internal val issuer:MapSession,
    internal val documentId:String,
    internal val source:MapSourcePreviewTicket,
    internal val membership:MapBacklinkMembership?,
    internal val nodeLifetime:Any,
) {
    internal var applied=false
    val nodeId get()=source.nodeId
    val sourceId get()=source.sourceId
    val target get()=source.target
}

internal sealed interface MapBacklinkRemovalResult {
    data object Applied:MapBacklinkRemovalResult
    data object Busy:MapBacklinkRemovalResult
    data object Changed:MapBacklinkRemovalResult
    data object Invalid:MapBacklinkRemovalResult
}
