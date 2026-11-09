package com.xnotes.mindmap

import android.content.Context
import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.ui.NativeBacklinkContext
import kotlinx.coroutines.CoroutineScope

/** Only the initial sidebar handoff depends on a particular native page/host. */
internal class LocalNetworkLaunchRequest private constructor(val context:NativeBacklinkContext,val query:NetworkQuery,
    private val valid:()->Boolean) {
    fun current()=context.current() && query.focusId==context.documentId && valid()
    companion object {
        fun capture(context:NativeBacklinkContext,title:String,valid:()->Boolean):LocalNetworkLaunchRequest? =
            LocalNetworkLaunchRequest(context,NetworkQuery(context.documentId,title,context.libraryRoots),valid).takeIf {it.current()}
    }
}

/** Retains exploration data across configuration changes, never native owners or presentation closures. */
@MainThread
internal class FileNetworkWorkspace(context:Context,private val scope:CoroutineScope) {
    internal class Entry internal constructor(val query:NetworkQuery,val controller:LocalFileNetworkController,
        val presentation:LocalNetworkPresentation=LocalNetworkPresentation(),val builder:LocalNetworkSceneBuilder=LocalNetworkSceneBuilder()) {
        internal var graphReturn by mutableStateOf<MapWorkspace.Entry?>(null)
        internal var alive=true
    }
    private val app=context.applicationContext
    private var active=true
    private var host:Any?=null
    var current by mutableStateOf<Entry?>(null);private set
    var visible by mutableStateOf(false);private set
    var resumeRequested by mutableStateOf(false);private set
    fun isHostCurrent(binding:Any)=active && host===binding
    fun current(binding:Any,entry:Entry)=isHostCurrent(binding) && current===entry && entry.alive
    fun bindHost(binding:Any) {
        if(host===binding) return
        if(visible) resumeRequested=true
        visible=false;current?.presentation?.visible=false;host=binding
    }
    fun unbindHost(binding:Any):Boolean {
        if(host!==binding) return false
        if(visible) resumeRequested=true
        visible=false;current?.presentation?.visible=false;host=null
        return true
    }
    internal fun admit(binding:Any,query:NetworkQuery):Entry? {
        if(!isHostCurrent(binding)) return null
        val prior=current
        val same=prior?.takeIf {it.alive && it.query.focusId==query.focusId && it.query.title==query.title && it.query.roots==query.roots}
        val next=same ?: Entry(query,LocalFileNetworkController(app,scope,query))
        if(prior!=null && prior!==next) {prior.alive=false;prior.presentation.visible=false;prior.controller.close()}
        current=next;next.graphReturn=null;visible=true;resumeRequested=false;next.presentation.visible=true
        if(same==null) next.controller.refresh()
        return next
    }
    internal fun show(binding:Any,entry:Entry):Boolean {
        if(!current(binding,entry)) return false
        entry.graphReturn=null;visible=true;resumeRequested=false;entry.presentation.visible=true
        return true
    }
    internal fun parkForMap(binding:Any,entry:Entry,map:MapWorkspace.Entry):Boolean {
        if(!current(binding,entry) || !visible) return false
        entry.graphReturn=map;entry.presentation.visible=false;visible=false;resumeRequested=false
        return true
    }
    internal fun returnEntry(map:MapWorkspace.Entry)=current?.takeIf {it.alive && it.graphReturn===map}
    fun retireGraphReturn() {current?.graphReturn=null}
    fun close(binding:Any,entry:Entry):Boolean {
        if(!current(binding,entry)) return false
        current=null;visible=false;resumeRequested=false
        entry.alive=false;entry.graphReturn=null;entry.presentation.visible=false;entry.controller.close()
        return true
    }
    fun dispose() {
        if(!active) return
        active=false;host=null;visible=false;resumeRequested=false
        current?.let {it.alive=false;it.graphReturn=null;it.presentation.visible=false;it.controller.close()};current=null
    }
}
