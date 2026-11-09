package com.xnotes.mindmap

internal enum class SourcePushResult {APPLIED,SELF,CHANGED,BUSY,LIMIT}

/** Immutable camera data; neither a physical View nor an old native parking callback. */
internal data class MapPresentationCamera(val viewport:MindMapView.Viewport,val width:Int,val height:Int,val density:Float)

internal class MapPresentationDeparture internal constructor(
    private val presentation:MapPresentationState,private val session:MapSession,
    private val documentId:String?,private val epoch:Long,internal val camera:MapPresentationCamera?,
) {
    internal fun current(presentation:MapPresentationState,session:MapSession)=this.presentation===presentation &&
        this.session===session && documentId!=null && documentId==session.document?.id && epoch==session.interactionEpoch
}

/** Repeated visits to the same Entry still have separate, immutable departure bookmarks. */
internal class MapSourceNavigationFrame(val origin:MapWorkspace.Entry,val destination:MapWorkspace.Entry,
    val bookmark:MapSourceReturnBookmark,val departure:MapPresentationDeparture) {
    val token=Any()
}
