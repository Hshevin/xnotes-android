package com.xnotes.mindmap

import androidx.annotation.MainThread
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.ui.Editor
import com.xnotes.ui.NativeBacklinkContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The new host supplies every native parking proof; none is retained in the network ViewModel. */
@MainThread
internal class LocalNetworkCoordinator(private val editor:Editor,private val maps:MapWorkspace,
    private val networks:FileNetworkWorkspace,private val binding:Any,private val scope:CoroutineScope,
    private val captureHost:()->(()->Boolean)={ {true} },private val onStarting:()->Unit={},
    private val onShown:()->Unit={}) {
    private var active=true
    private var generation=0L
    private var work:Job?=null
    private var canvasOperation:Any?=null
    private var owner:Any?=null
    private var problem by mutableStateOf<BacklinkProblem?>(null)
    var busy by mutableStateOf(false);private set
    fun failureFor(context:NativeBacklinkContext)=problem.takeIf {owner===context && context.current()}
    fun failureFor(request:LibraryNetworkLaunchRequest)=problem.takeIf {owner===request && request.current()}
    fun failureFor(entry:FileNetworkWorkspace.Entry)=problem.takeIf {owner===entry && entry.alive}
    fun canCancel(expected:Any)=busy && owner===expected
    fun cancel(expected:Any) {if(canCancel(expected)) cancel()}
    private fun hostCurrent()=active && maps.isHostCurrent(binding) && networks.isHostCurrent(binding)
    fun open(request:LocalNetworkLaunchRequest):Boolean {
        if(!hostCurrent() || busy || maps.visible || networks.visible || !request.current()) return false
        return present(request.context,{request.current() && !maps.visible && !networks.visible}) {
            networks.admit(binding,request.query)!=null
        }
    }
    fun openLibrary(request:LibraryNetworkLaunchRequest):Boolean {
        if(!hostCurrent() || busy || maps.visible || networks.visible || !request.current()) return false
        return present(request,{request.current() && !maps.visible && !networks.visible}) {
            networks.admit(binding,request.query)!=null
        }
    }
    fun openCanvas(entry:FileNetworkWorkspace.Entry,request:NetworkCanvasOpenRequest):Boolean {
        if(!hostCurrent() || busy || !networks.current(binding,entry) || !networks.visible || maps.visible || !request.current()) return false
        val prior=maps.current
        if(prior!=null && !maps.canHide(prior)) {owner=entry;problem=BacklinkProblem.BUSY;return false}
        val input=entry.presentation.captureInput() ?: return false
        val host=captureHost()
        if(!host()) return false
        val token=++generation;val operation=Any()
        owner=entry;problem=null;busy=true;canvasOperation=operation
        fun current()=hostCurrent() && generation==token && networks.current(binding,entry) && networks.visible &&
            maps.current===prior && (prior==null || maps.canHide(prior)) && host() && input.current() && request.current()
        onStarting()
        val started=maps.open(request.uri,request.title,preparePresentation={
            val proof=if(current()) editor.awaitHostParking() else null
            if(proof==null || !current() || !proof.current()) null else ({current() && proof.current()})
        },stillCurrent=::current,operation=operation,flushNativeOnPresent=false,
            onPresented={map->networks.parkForMap(binding,entry,map)},onFinished={shown->
                if(generation==token) {
                    busy=false;canvasOperation=null
                    if(shown) onShown() else problem=BacklinkProblem.CHANGED
                }
                request.finished(shown)
            })
        if(!started) {busy=false;canvasOperation=null;request.finished(false)}
        return started
    }
    fun resume(entry:FileNetworkWorkspace.Entry,from:MapWorkspace.Entry?=null,closeMap:Boolean=false,preserveMapRecovery:Boolean=false):Boolean {
        if(!hostCurrent() || busy || networks.visible || !networks.current(binding,entry)) return false
        val mapEpoch=from?.session?.interactionEpoch
        fun valid()=networks.current(binding,entry) && if(from==null) !maps.visible
            else maps.current===from && maps.visible && networks.returnEntry(from)===entry &&
                if(closeMap) from.lease.alive && from.session.interactionEpoch==mapEpoch &&
                    (from.lease.hasOtherOwners || from.session.canParkSourceNavigation)
                else maps.canHide(from)
        if(!valid()) {owner=entry;problem=BacklinkProblem.BUSY;return false}
        return present(entry,::valid,pureMapParking=closeMap) {
            if(from!=null) {
                if(closeMap) {if(maps.close(from,preserveRecovery=preserveMapRecovery)==null) return@present false}
                else if(!maps.hide(from)) return@present false
            }
            networks.show(binding,entry)
        }
    }
    private fun present(expectedOwner:Any,origin:()->Boolean,pureMapParking:Boolean=false,commit:()->Boolean):Boolean {
        val host=captureHost()
        if(!host()) return false
        onStarting();maps.cancelOpen()
        val mapGeneration=maps.sourceNavigationGeneration
        val token=++generation;owner=expectedOwner;problem=null;busy=true
        fun current()=hostCurrent() && generation==token && maps.sourceNavigationGeneration==mapGeneration && origin() && host()
        val pending=scope.launch(start=CoroutineStart.LAZY) {
            var committed=false
            val watcher=scope.launch {
                while(isActive && generation==token && !committed) {
                    if(!current()) {problem=BacklinkProblem.CHANGED;work?.cancel();break}
                    withContext(Dispatchers.Default) {delay(32)}
                }
            }
            try {
                val proof=if(!current()) null else if(pureMapParking) editor.awaitMapSourceParking() else editor.awaitHostParking()
                if(proof==null || !current() || !proof.current()) {
                    if(hostCurrent() && generation==token) problem=BacklinkProblem.CHANGED
                    return@launch
                }
                // Same Main dispatch: the fresh physical-input proof directly guards adoption.
                if(commit()) {committed=true;onShown()} else problem=BacklinkProblem.CHANGED
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:Exception) {if(!committed && hostCurrent() && generation==token) problem=BacklinkProblem.UNAVAILABLE}
            finally {watcher.cancel();if(generation==token) {work=null;busy=false}}
        }
        work=pending;pending.start();return true
    }
    fun close(entry:FileNetworkWorkspace.Entry):Boolean {
        if(!hostCurrent() || networks.current!==entry || !networks.visible) return false
        cancel();return networks.close(binding,entry)
    }
    fun cancel() {generation++;work?.cancel();work=null;canvasOperation?.let {maps.cancelOpen(it)};canvasOperation=null;busy=false;owner=null;problem=null}
    fun dispose() {cancel();active=false}
}
