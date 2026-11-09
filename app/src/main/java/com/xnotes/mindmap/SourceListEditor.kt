package com.xnotes.mindmap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Successful callbacks advance synchronously; each row patches only its own current source. */
internal class SourceListEditor(private val session:MapSession,initial:SourceEditSnapshot) {
    private var expected=initial
    private var active=true
    var conflict by mutableStateOf(false);private set
    val busySourceIds:Set<String> get()=session.sourcePreviews.busy(this)
    val failedSourceIds:Set<String> get()=session.sourcePreviews.failures(this)
    private fun alive()=active && session.sourceEditSnapshot(expected.node.id)?.let {
        it.epoch==expected.epoch && it.documentId==expected.documentId
    }==true
    fun previewAsset(id:String):MapPreviewAsset? {
        if(!alive()) return null
        val source=session.document?.nodes?.firstOrNull {it.id==expected.node.id}?.sources?.firstOrNull {it.id==id} ?: return null
        return source.lastPreview?.assetKey?.let {session.document?.previewAssets?.get(it)}
    }
    private fun request(id:String,mode:MapSourcePreviewMode,target:MapSourceTarget?=null):Boolean {
        if(!alive()) return false
        val ticket=session.captureSourcePreview(expected.node.id,id) ?: return false
        if(target!=null && ticket.target!=target) return false
        return session.sourcePreviews.request(this,listOf(ticket),mode,::alive)
    }
    fun showPreview(id:String,target:MapSourceTarget?=null)=request(id,MapSourcePreviewMode.AUTO,target)
    fun check(id:String,target:MapSourceTarget?=null)=request(id,MapSourcePreviewMode.CHECK,target)
    fun refresh(id:String,target:MapSourceTarget?=null)=request(id,MapSourcePreviewMode.REFRESH,target)
    fun refreshGraph():Boolean {
        if(!alive()) return false
        val tickets=session.document?.nodes.orEmpty().flatMap {node->node.sources.mapNotNull {session.captureSourcePreview(node.id,it.id)}}
        return session.sourcePreviews.request(this,tickets,MapSourcePreviewMode.REFRESH,::alive)
    }
    fun checkGraph():Boolean {
        if(!alive()) return false
        val tickets=session.document?.nodes.orEmpty().flatMap {node->node.sources.mapNotNull {session.captureSourcePreview(node.id,it.id)}}
        return session.sourcePreviews.request(this,tickets,MapSourcePreviewMode.CHECK,::alive)
    }
    fun cancel() {session.sourcePreviews.cancel(this)}
    fun close() {if(active) {active=false;cancel()}}
    fun sync(displayed:SourceEditSnapshot) {
        if(!active) return
        val actual=session.sourceEditSnapshot(expected.node.id) ?: return close()
        if(actual.epoch!=expected.epoch || actual.documentId!=expected.documentId || displayed.node.id!=expected.node.id) return close()
        // Do not rewind an already successful callback when an older composition finishes.
        if(displayed.epoch==actual.epoch && displayed.documentId==actual.documentId && displayed.node.sources==actual.node.sources &&
            displayed.node.primarySource==actual.node.primarySource) expected=actual
    }
    private fun apply(next:MapNode):Boolean {
        if(!alive()) return false
        if(!session.editSources(expected,next)) {conflict=true;return false}
        session.sourceEditSnapshot(expected.node.id)?.let {expected=it} ?: close()
        conflict=false;return true
    }
    fun note(id:String,value:String):Boolean {
        val node=expected.node
        if(node.sources.none {it.id==id}) return false
        return apply(node.copy(sources=node.sources.map {if(it.id==id) it.copy(note=value) else it}))
    }
    fun primary(id:String):Boolean {
        val node=expected.node
        if(node.sources.none {it.id==id}) return false
        return apply(node.copy(primarySource=id))
    }
    fun move(id:String,delta:Int):Boolean {
        val node=expected.node;val index=node.sources.indexOfFirst {it.id==id}
        if(index<0 || !active) return false
        val target=index+delta
        if(target !in node.sources.indices) return true
        val sources=node.sources.toMutableList();java.util.Collections.swap(sources,index,target)
        return apply(node.copy(sources=sources.toList()))
    }
    fun remove(id:String):Boolean {
        val node=expected.node
        if(node.sources.none {it.id==id}) return false
        return apply(node.copy(sources=node.sources.filterNot {it.id==id},primarySource=node.primarySource.takeUnless {it==id}))
    }
}
