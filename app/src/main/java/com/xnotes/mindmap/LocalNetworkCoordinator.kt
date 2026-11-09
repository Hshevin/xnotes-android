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
    private var owner:Any?=null
    private var problem by mutableStateOf<BacklinkProblem?>(null)
    var busy by mutableStateOf(false);private set
    fun failureFor(context:NativeBacklinkContext)=problem.takeIf {owner===context && context.current()}
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
    fun resume(entry:FileNetworkWorkspace.Entry,from:MapWorkspace.Entry?=null,closeMap:Boolean=false):Boolean {
        if(!hostCurrent() || busy || networks.visible || !networks.current(binding,entry)) return false
        fun valid()=networks.current(binding,entry) && if(from==null) !maps.visible
            else maps.current===from && maps.visible && networks.returnEntry(from)===entry && maps.canHide(from)
        if(!valid()) {owner=entry;problem=BacklinkProblem.BUSY;return false}
        return present(entry,::valid) {
            if(from!=null) {
                if(closeMap) {if(maps.close(from)==null) return@present false}
                else if(!maps.hide(from)) return@present false
            }
            networks.show(binding,entry)
        }
    }
    private fun present(expectedOwner:Any,origin:()->Boolean,commit:()->Boolean):Boolean {
        val host=captureHost()
        if(!host()) return false
        onStarting();maps.cancelOpen()
        val token=++generation;owner=expectedOwner;problem=null;busy=true
        fun current()=hostCurrent() && generation==token && origin() && host()
        val pending=scope.launch(start=CoroutineStart.LAZY) {
            var committed=false
            val watcher=scope.launch {
                while(isActive && generation==token && !committed) {
                    if(!current()) {problem=BacklinkProblem.CHANGED;work?.cancel();break}
                    withContext(Dispatchers.Default) {delay(32)}
                }
            }
            try {
                val proof=if(current()) editor.awaitHostParking() else null
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
    fun cancel() {generation++;work?.cancel();work=null;busy=false;owner=null;problem=null}
    fun dispose() {cancel();active=false}
}
