package com.xnotes.mindmap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.ref.WeakReference

/** Per-host, runtime presentation. This is neither document content nor a selection snapshot. */
class MapPresentationState internal constructor() {
    internal var visible by mutableStateOf(true)
    internal var sourcesFor by mutableStateOf<String?>(null)
    private var owner:MapSession?=null
    private var epoch=-1L
    private var documentId:String?=null
    private data class Camera(val viewport:MindMapView.Viewport,val width:Int,val height:Int,val density:Float)
    private var camera:Camera?=null
    private var restoredView=WeakReference<MindMapView>(null)

    internal fun synchronize(session:MapSession) {
        val id=session.document?.id
        if(owner!==session || epoch!=session.interactionEpoch || documentId!=id) {
            owner=session;epoch=session.interactionEpoch;documentId=id
            camera=null;restoredView.clear();sourcesFor=null
        } else if(sourcesFor!=null && session.document?.nodes?.none {it.id==sourcesFor}!=false) sourcesFor=null
    }
    private fun current(session:MapSession,view:MindMapView)=owner===session && documentId!=null &&
        epoch==session.interactionEpoch && documentId==session.document?.id &&
        view.documentEpoch==epoch && view.document.id==documentId && !session.closing && !session.replacingDocument
    private fun ordinary(session:MapSession,imeBottom:Int)=imeBottom==0 && session.textDraft==null &&
        session.textRestore==null && !session.inkContact

    /** A detached old-epoch View or an editing camera cannot replace the normal bookmark. */
    internal fun capture(session:MapSession,view:MindMapView,imeBottom:Int=0):Boolean {
        if(!current(session,view) || !ordinary(session,imeBottom) || view.width<=0 || view.height<=0) return false
        val density=view.resources.displayMetrics.density
        val viewport=view.viewport()
        if(!density.isFinite() || density<=0 || !viewport.zoom.isFinite() || viewport.zoom<=0 ||
            !viewport.panX.isFinite() || !viewport.panY.isFinite()) return false
        camera=Camera(viewport,view.width,view.height,density)
        return true
    }
    /** The caller waits for IME and actual layout stability. Each attached View restores once. */
    internal fun restore(session:MapSession,view:MindMapView,imeBottom:Int=0):Boolean {
        if(!current(session,view) || !ordinary(session,imeBottom) || view.width<=0 || view.height<=0 || restoredView.get()===view) return false
        val saved=camera ?: return false
        view.restoreViewport(saved.viewport,saved.width,saved.height,saved.density)
        restoredView=WeakReference(view)
        return true
    }
}
