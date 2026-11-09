package com.xnotes.mindmap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class LocalNetworkGeometry(val width:Int,val height:Int,val density:Float) {
    val valid get()=width>0 && height>0 && density.isFinite() && density>0
}
/** Viewport is a three-float value, not a retained Android View. */
internal data class LocalNetworkCamera(val viewport:MindMapView.Viewport,val width:Int,val height:Int,val density:Float) {
    val geometry get()=LocalNetworkGeometry(width,height,density)
    val valid get()=geometry.valid && viewport.zoom.isFinite() && viewport.zoom>0 &&
        viewport.panX.isFinite() && viewport.panY.isFinite()
}
internal class LocalNetworkAttachment internal constructor(internal val owner:Any,val epoch:Long)
internal class LocalNetworkInputTicket internal constructor(
    private val issuer:LocalNetworkPresentation,internal val attachment:LocalNetworkAttachment,
    internal val serial:Long,internal val layoutSerial:Long,
) {
    fun current()=issuer.inputCurrent(this)
}
internal enum class LocalNetworkCameraMode { FIT,RESTORE,KEEP_CURRENT }
internal class LocalNetworkCameraPlan internal constructor(
    internal val owner:Any,val attachment:LocalNetworkAttachment,internal val serial:Long,internal val layoutSerial:Long,
    internal val geometry:LocalNetworkGeometry,val mode:LocalNetworkCameraMode,val camera:LocalNetworkCamera?,
)

/** Retained network state is domain keys, numbers and capabilities only. No Android/host references. */
internal class LocalNetworkPresentation {
    private val issuer=Any()
    private var attachment:LocalNetworkAttachment?=null
    private var nextEpoch=0L
    private var geometry:LocalNetworkGeometry?=null
    private var ime=0
    private var layoutSerial=0L
    private var readyGeometry:LocalNetworkGeometry?=null
    private var pending:LocalNetworkCameraPlan?=null
    private var keepCurrent=false
    private var forceFit=false
    private var shown by mutableStateOf(false)
    var visible:Boolean
        get()=shown
        set(value) {if(shown!=value) {shown=value;retireAttachment()}}
    var idle by mutableStateOf(true);private set
    var cameraReady by mutableStateOf(false);private set
    var inputSerial by mutableStateOf(0L);private set
    var cameraRevision by mutableStateOf(0L);private set
    var camera:LocalNetworkCamera?=null;private set
    var selectedNode by mutableStateOf<NetworkSceneNodeKey?>(null);private set
    var selectedEdge by mutableStateOf<NetworkSceneEdgeKey?>(null);private set
    var candidates by mutableStateOf<List<NetworkSceneEdgeKey>>(emptyList());private set
    private var nodes:Set<NetworkSceneNodeKey> = emptySet()
    private var edges:Set<NetworkSceneEdgeKey> = emptySet()

    fun attach():LocalNetworkAttachment {
        check(visible)
        retireAttachment()
        return LocalNetworkAttachment(issuer,++nextEpoch).also {attachment=it}
    }
    private fun retireAttachment() {
        attachment=null;inputSerial++;layoutSerial++;cameraRevision++
        idle=true;cameraReady=false;geometry=null;readyGeometry=null;ime=0;pending=null;keepCurrent=false;forceFit=false
    }
    fun detach(token:LocalNetworkAttachment):Boolean {
        if(attachment!==token || token.owner!==issuer) return false
        retireAttachment();return true
    }
    fun attachmentCurrent(token:LocalNetworkAttachment)=visible && token.owner===issuer && attachment===token
    fun graphContact(token:LocalNetworkAttachment) {
        if(!attachmentCurrent(token)) return
        inputSerial++;pending=null;forceFit=false
    }
    fun contact(token:LocalNetworkAttachment,active:Boolean) {
        if(!attachmentCurrent(token)) return
        if(active && !cameraReady) keepCurrent=true
        idle=!active
    }
    fun captureInput():LocalNetworkInputTicket? {
        val token=attachment ?: return null
        if(!attachmentCurrent(token) || !idle || !cameraReady || ime!=0) return null
        return LocalNetworkInputTicket(this,token,inputSerial,layoutSerial)
    }
    internal fun inputCurrent(ticket:LocalNetworkInputTicket)=attachmentCurrent(ticket.attachment) && idle && cameraReady &&
        ime==0 && ticket.serial==inputSerial && ticket.layoutSerial==layoutSerial

    fun reconcile(scene:LocalNetworkScene) {
        nodes=scene.nodes.values.toSet();edges=scene.edges.values.toSet()
        val node=selectedNode?.takeIf {it in nodes};val edge=selectedEdge?.takeIf {it in edges}
        val options=candidates.filter {it in edges}
        if(node!=selectedNode || edge!=selectedEdge || options!=candidates) {
            selectedNode=node;selectedEdge=edge;candidates=options;retireSelectionInput()
        }
    }
    private fun retireSelectionInput() {inputSerial++;pending=null;forceFit=false}
    fun selectNode(key:NetworkSceneNodeKey?):Boolean {
        if(!visible || key!=null && key !in nodes) return false
        if(selectedNode!=key || selectedEdge!=null || candidates.isNotEmpty()) {
            selectedNode=key;selectedEdge=null;candidates=emptyList();retireSelectionInput()
        }
        return true
    }
    fun selectEdge(key:NetworkSceneEdgeKey?,candidates:List<NetworkSceneEdgeKey> = emptyList()):Boolean {
        if(!visible || key!=null && key !in edges || candidates.any {it !in edges}) return false
        val options=candidates.distinct().ifEmpty {key?.let(::listOf).orEmpty()}
        if(selectedEdge!=key || selectedNode!=null || this.candidates!=options) {
            selectedNode=null;selectedEdge=key;this.candidates=options;retireSelectionInput()
        }
        return true
    }
    fun requestFit():Boolean {
        val token=attachment ?: return false
        if(!attachmentCurrent(token) || !idle || ime!=0 || geometry?.valid!=true) return false
        inputSerial++;pending=null;keepCurrent=false;forceFit=true;cameraRevision++
        return true
    }
    fun layout(token:LocalNetworkAttachment,geometry:LocalNetworkGeometry,imeBottom:Int):Boolean {
        if(!attachmentCurrent(token)) return false
        if(this.geometry!=geometry || ime!=imeBottom) {
            this.geometry=geometry;ime=imeBottom;layoutSerial++;inputSerial++;pending=null;cameraRevision++
            cameraReady=false
        }
        return geometry.valid && imeBottom==0
    }
    fun prepareCamera(token:LocalNetworkAttachment,geometry:LocalNetworkGeometry,imeBottom:Int=0):LocalNetworkCameraPlan? {
        if(!layout(token,geometry,imeBottom) || !idle) return null
        if(cameraReady && readyGeometry==geometry && !forceFit) return null
        pending?.takeIf {cameraPlanCurrent(it,geometry,imeBottom)}?.let {return it}
        val mode=when {forceFit->LocalNetworkCameraMode.FIT;keepCurrent->LocalNetworkCameraMode.KEEP_CURRENT
            camera!=null->LocalNetworkCameraMode.RESTORE;else->LocalNetworkCameraMode.FIT}
        return LocalNetworkCameraPlan(issuer,token,inputSerial,layoutSerial,geometry,mode,camera).also {pending=it}
    }
    fun cameraPlanCurrent(plan:LocalNetworkCameraPlan,geometry:LocalNetworkGeometry,imeBottom:Int=0)=
        plan.owner===issuer && pending===plan && attachmentCurrent(plan.attachment) && idle && ime==0 && imeBottom==0 &&
            plan.serial==inputSerial && plan.layoutSerial==layoutSerial && plan.geometry==geometry && this.geometry==geometry && geometry.valid
    fun finishCamera(plan:LocalNetworkCameraPlan,camera:LocalNetworkCamera):Boolean {
        if(!camera.valid || !cameraPlanCurrent(plan,camera.geometry,0)) return false
        this.camera=camera;readyGeometry=camera.geometry;pending=null;keepCurrent=false;forceFit=false;cameraReady=true
        return true
    }
    fun captureCamera(token:LocalNetworkAttachment,camera:LocalNetworkCamera,imeBottom:Int=0):Boolean {
        if(!attachmentCurrent(token) || !cameraReady || ime!=0 || imeBottom!=0 || !camera.valid ||
            camera.geometry!=geometry || camera.geometry!=readyGeometry) return false
        this.camera=camera;return true
    }
}

/** Final camera consumer after the Canvas has observed two unchanged, normal-layout frames. */
internal fun applyLocalNetworkCamera(presentation:LocalNetworkPresentation,view:MindMapView,
    plan:LocalNetworkCameraPlan,imeBottom:Int=0):Boolean {
    val geometry=LocalNetworkGeometry(view.width,view.height,view.resources.displayMetrics.density)
    if(!view.isAttachedToWindow || view.documentEpoch!=plan.attachment.epoch ||
        !presentation.cameraPlanCurrent(plan,geometry,imeBottom)) return false
    when(plan.mode) {
        LocalNetworkCameraMode.FIT->view.fit()
        LocalNetworkCameraMode.RESTORE->requireNotNull(plan.camera).let {view.restoreViewport(it.viewport,it.width,it.height,it.density)}
        LocalNetworkCameraMode.KEEP_CURRENT->Unit
    }
    return presentation.finishCamera(plan,LocalNetworkCamera(view.viewport(),view.width,view.height,view.resources.displayMetrics.density))
}
