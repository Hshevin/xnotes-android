package com.xnotes.mindmap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.ref.WeakReference

/** Per-host, runtime presentation. This is neither document content nor a selection snapshot. */
class MapPresentationState internal constructor() {
    private var shown by mutableStateOf(true)
    internal var visible:Boolean
        get()=shown
        set(value) {if(!value) cancelPendingBacklinkFocus();shown=value}
    internal var sourcesFor by mutableStateOf<String?>(null)
    private var owner:MapSession?=null
    private var epoch=-1L
    private var documentId:String?=null
    private data class Camera(val viewport:MindMapView.Viewport,val width:Int,val height:Int,val density:Float)
    private var camera:Camera?=null
    private var restoredView=WeakReference<MindMapView>(null)
    private var protectedCamera=false
    internal var restoreVersion by mutableStateOf(0L);private set
    internal var sourceFocus by mutableStateOf<MapSourcePanelFocus?>(null);private set
    internal var pendingBacklinkFocus by mutableStateOf<MapBacklinkFocusTicket?>(null);private set
    private var pendingSelectionSerial=-1L

    internal fun synchronize(session:MapSession) {
        val id=session.document?.id
        if(owner!==session || epoch!=session.interactionEpoch || documentId!=id) {
            owner=session;epoch=session.interactionEpoch;documentId=id
            camera=null;restoredView.clear();sourcesFor=null;sourceFocus=null;protectedCamera=false
            cancelPendingBacklinkFocus()
        } else if(sourcesFor!=null && session.document?.nodes?.none {it.id==sourcesFor}!=false) closeSources()
    }
    private fun current(session:MapSession,view:MindMapView)=owner===session && documentId!=null &&
        epoch==session.interactionEpoch && documentId==session.document?.id &&
        view.documentEpoch==epoch && view.document.id==documentId && !session.closing && !session.replacingDocument
    private fun ordinary(session:MapSession,imeBottom:Int)=imeBottom==0 && session.textDraft==null &&
        session.textRestore==null && !session.inkContact

    /** A detached old-epoch View or an editing camera cannot replace the normal bookmark. */
    internal fun capture(session:MapSession,view:MindMapView,imeBottom:Int=0):Boolean {
        if(protectedCamera || !current(session,view) || !ordinary(session,imeBottom) || view.width<=0 || view.height<=0) return false
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
        protectedCamera=sourcesFor!=null
        return true
    }
    /** Freeze before a source-note keyboard can resize the ordinary graph View. */
    internal fun showSources(session:MapSession,view:MindMapView,nodeId:String,imeBottom:Int=0,cameraReady:Boolean=true):Boolean {
        if(!cameraReady || !current(session,view) || !ordinary(session,imeBottom) || session.document?.nodes?.none {it.id==nodeId}!=false) return false
        if(!protectedCamera && !capture(session,view,imeBottom)) return false
        if(camera==null) return false
        cancelPendingBacklinkFocus()
        sourcesFor=nodeId;sourceFocus=null;protectedCamera=true
        return true
    }
    internal fun parkSourcePanel() {sourcesFor=null;sourceFocus=null;protectedCamera=camera!=null}
    private fun requestCameraRestore() {
        protectedCamera=camera!=null;restoredView.clear();restoreVersion=Math.incrementExact(restoreVersion)
    }
    internal fun closeSources() {cancelPendingBacklinkFocus();sourcesFor=null;sourceFocus=null;requestCameraRestore()}
    internal fun requestBacklinkFocus(session:MapSession,ticket:MapBacklinkFocusTicket):Boolean {
        synchronize(session)
        if(!visible || !session.backlinkFocusCurrent(ticket) || session.selected!=ticket.nodeId || session.nodeSelectionMode) return false
        pendingBacklinkFocus=ticket;pendingSelectionSerial=session.selectionSerial
        sourcesFor=null;sourceFocus=null;protectedCamera=false
        return true
    }
    internal fun cancelPendingBacklinkFocus() {pendingBacklinkFocus=null;pendingSelectionSerial=-1L}
    /** Only the stable, visible host consumes this once; a fresh graph contact cancels it first. */
    internal fun applyPendingBacklinkFocus(session:MapSession,view:MindMapView,imeBottom:Int=0,cameraReady:Boolean=true):Boolean {
        val ticket=pendingBacklinkFocus ?: return false
        if(!visible || !current(session,view)) {cancelPendingBacklinkFocus();return false}
        if(!session.backlinkFocusCurrent(ticket,requireReady=false) || session.selectionSerial!=pendingSelectionSerial ||
            session.selected!=ticket.nodeId || session.nodeSelectionMode) {cancelPendingBacklinkFocus();return false}
        if(session.hasUnconfirmedContent) {cancelPendingBacklinkFocus();return false}
        if(!cameraReady || !ordinary(session,imeBottom) || !session.backlinkFocusCurrent(ticket) || view.width<=0 || view.height<=0) return false
        view.centerOn(ticket.nodeId)
        if(!capture(session,view,imeBottom)) return false
        cancelPendingBacklinkFocus()
        if(ticket.opensSource) {
            if(!showSources(session,view,ticket.nodeId,imeBottom,cameraReady)) return false
            sourceFocus=MapSourcePanelFocus(requireNotNull(ticket.source))
        }
        return true
    }
    private fun graphCurrent(session:MapSession)=owner===session && documentId!=null && documentId==session.document?.id && epoch==session.interactionEpoch
    internal fun reopenSourcePanel(session:MapSession,ticket:MapSourceNavigationTicket):Boolean {
        if(!graphCurrent(session) || ticket.issuer!==session || ticket.documentId!=documentId || ticket.source.epoch!=epoch) return false
        if(session.currentSourceNavigation(ticket)==null) {closeSources();return false}
        sourcesFor=ticket.nodeId;sourceFocus=MapSourcePanelFocus(ticket);requestCameraRestore()
        return true
    }
    internal fun applySourceReturn(session:MapSession,bookmark:MapSourceReturnBookmark):Boolean {
        if(!graphCurrent(session) || !session.restoreSourceReturn(bookmark)) return false
        val ticket=bookmark.source
        if(session.currentSourceNavigation(ticket)!=null) {
            sourcesFor=ticket.nodeId;sourceFocus=MapSourcePanelFocus(ticket)
        } else {sourcesFor=null;sourceFocus=null}
        requestCameraRestore()
        return true
    }
    internal fun consumeSourceFocus(token:Any) {if(sourceFocus?.token===token) sourceFocus=null}
}
