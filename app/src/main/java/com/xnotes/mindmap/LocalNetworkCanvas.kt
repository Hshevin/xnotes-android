package com.xnotes.mindmap

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R
import com.xnotes.ui.theme.LocalPalette

/** Native readonly scene surface. Android objects and callbacks remain in this visible composition. */
@Composable
internal fun LocalNetworkCanvas(scene:LocalNetworkScene,presentation:LocalNetworkPresentation,visible:Boolean,
    modifier:Modifier=Modifier) {
    presentation.visible=visible
    presentation.reconcile(scene)
    if(!visible) return
    key(presentation) {
        val dark=LocalPalette.current.isDark
        val density=LocalDensity.current
        val ime=WindowInsets.ime.getBottom(density)
        val latestIme by rememberUpdatedState(ime)
        val latestScene by rememberUpdatedState(scene)
        val latestVisible by rememberUpdatedState(visible)
        var view by remember {mutableStateOf<MindMapView?>(null)}
        var attachment by remember {mutableStateOf<LocalNetworkAttachment?>(null)}
        var geometryVersion by remember {mutableIntStateOf(0)}
        fun geometry(v:MindMapView)=LocalNetworkGeometry(v.width,v.height,v.resources.displayMetrics.density)
        fun snapshot(v:MindMapView)=LocalNetworkCamera(v.viewport(),v.width,v.height,v.resources.displayMetrics.density)

        DisposableEffect(view,presentation) {
            val owned=view
            if(owned==null) onDispose {} else {
                var token:LocalNetworkAttachment?=null
                var observer:ViewTreeObserver?=null
                lateinit var focusListener:ViewTreeObserver.OnWindowFocusChangeListener
                fun stopWatchingFocus() {
                    observer?.takeIf {it.isAlive}?.removeOnWindowFocusChangeListener(focusListener)
                    observer=null
                }
                fun watchFocus() {
                    val current=owned.viewTreeObserver
                    if(observer===current) return
                    stopWatchingFocus();observer=current
                    // A detached View's floating observer can already have merged this listener.
                    current.removeOnWindowFocusChangeListener(focusListener)
                    current.addOnWindowFocusChangeListener(focusListener)
                }
                fun unbind() {
                    val old=token;token=null
                    if(old!=null) {
                        presentation.detach(old)
                        if(attachment===old) attachment=null
                    }
                }
                fun bind() {
                    if(!latestVisible || !owned.isAttachedToWindow || !owned.hasWindowFocus()) return
                    if(token?.let(presentation::attachmentCurrent)==true) return
                    val bound=presentation.attach();token=bound;attachment=bound
                    owned.documentEpoch=bound.epoch
                    owned.onGraphContact={presentation.graphContact(bound)}
                    owned.onReadOnlyContact={presentation.contact(bound,it)}
                    owned.onSelect={id->if(presentation.attachmentCurrent(bound))
                        presentation.selectNode(id?.let {latestScene.nodes[it]})}
                    owned.onSelectRelation={id->if(presentation.attachmentCurrent(bound))
                        presentation.selectEdge(id?.let {latestScene.edges[it]})}
                    owned.onReadOnlyRelationCandidates={ids->if(presentation.attachmentCurrent(bound))
                        presentation.selectEdge(owned.selectedRelation?.let {latestScene.edges[it]},ids.mapNotNull {latestScene.edges[it]})}
                    owned.onViewportChanged={
                        if(presentation.attachmentCurrent(bound) && latestVisible) {
                            presentation.layout(bound,geometry(owned),latestIme)
                            presentation.captureCamera(bound,snapshot(owned),latestIme)
                            geometryVersion++
                        }
                    }
                    presentation.layout(bound,geometry(owned),latestIme)
                    geometryVersion++
                }
                val attachListener=object:View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v:View) {watchFocus();bind()}
                    override fun onViewDetachedFromWindow(v:View) {stopWatchingFocus();unbind()}
                }
                focusListener=ViewTreeObserver.OnWindowFocusChangeListener {focused->if(focused) bind() else unbind()}
                owned.addOnAttachStateChangeListener(attachListener)
                watchFocus()
                bind()
                onDispose {
                    unbind()
                    owned.removeOnAttachStateChangeListener(attachListener)
                    stopWatchingFocus()
                    owned.onGraphContact={};owned.onReadOnlyContact={};owned.onViewportChanged={}
                    owned.onSelect={};owned.onSelectRelation={};owned.onReadOnlyRelationCandidates=null
                    if(view===owned) view=null
                }
            }
        }
        val observedGeometry=remember(view,geometryVersion,density.density) {view?.let(::geometry)}
        LaunchedEffect(view,attachment,visible,observedGeometry,ime,presentation.cameraRevision,presentation.inputSerial,presentation.idle) {
            val actual=view ?: return@LaunchedEffect
            val token=attachment ?: return@LaunchedEffect
            val size=geometry(actual)
            val plan=presentation.prepareCamera(token,size,ime) ?: return@LaunchedEffect
            repeat(2) {withFrameNanos {}}
            if(!latestVisible || view!==actual || attachment!==token || latestIme!=0) return@LaunchedEffect
            applyLocalNetworkCamera(presentation,actual,plan,latestIme)
        }
        AndroidView(factory={context->MindMapView(context).also {
            it.automaticInitialFit=false;it.readOnlyNavigation=true;view=it
        }},modifier=modifier.alpha(if(presentation.cameraReady) 1f else 0f),update={v->
            v.automaticInitialFit=false;v.readOnlyNavigation=true;v.nodeSelectionMode=false
            v.dark=dark;v.document=scene.document
            val edge=presentation.selectedEdge?.let {selected->scene.edges.entries.firstOrNull {it.value==selected}?.key}
            if(edge!=null) v.selectedRelation=edge
            else v.selected=presentation.selectedNode?.let {selected->scene.nodes.entries.firstOrNull {it.value==selected}?.key}
            v.contentDescription=v.context.getString(R.string.network_canvas_description)
        })
    }
}
