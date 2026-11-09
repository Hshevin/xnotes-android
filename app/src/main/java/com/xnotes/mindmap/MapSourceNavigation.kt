package com.xnotes.mindmap

/** A read/navigation capability for one source membership, independent of cached display fields. */
internal class MapSourceNavigationTicket internal constructor(
    internal val issuer:MapSession,
    internal val documentId:String,
    internal val source:MapSourcePreviewTicket,
) {
    val nodeId get()=source.nodeId
    val sourceId get()=source.sourceId
    val target get()=source.target
}

/** Only an explicit return consumes this selection. Ordinary View-map never restores it. */
internal class MapSourceReturnBookmark internal constructor(
    internal val source:MapSourceNavigationTicket,
    selectedNodes:Set<String>,
    val nodeSelectionMode:Boolean,
    val selectedRelation:String?,
) {
    val selectedNodes:Set<String> = selectedNodes.toSet()
}

internal class MapSourcePanelFocus internal constructor(internal val source:MapSourceNavigationTicket) {
    val nodeId get()=source.nodeId
    val sourceId get()=source.sourceId
    val token=Any()
}

/** Public only for the Screen signature; the actual Entry-bound host constructs the callbacks. */
class MapHostHooks internal constructor(
    internal val openSource:(MapSourceNavigationTicket,MapSourceReturnBookmark)->Boolean,
    internal val busy:()->Boolean={false},
    internal val cancel:()->Unit={},
    internal val feedback:()->Int?={null},
    internal val returnToNetwork:(()->Unit)?=null,
    internal val returnToSourceMap:(()->Unit)?=null,
    internal val closeEntry:(()->Unit)?=null,
    internal val closeRecoveryEntry:(()->Unit)?=null,
)
