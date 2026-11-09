package com.xnotes.mindmap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Successful callbacks advance synchronously; each row patches only its own current source. */
internal class SourceListEditor(private val session:MapSession,initial:SourceEditSnapshot) {
    private var expected=initial
    private var active=true
    private data class NoteDraft(val ticket:MapSourcePreviewTicket?,val initial:String,val value:String)
    private var noteDrafts by mutableStateOf<Map<String,NoteDraft>>(emptyMap())
    val hasNoteDrafts:Boolean get()=noteDrafts.isNotEmpty()
    var conflict by mutableStateOf(false);private set
    val busySourceIds:Set<String> get()=session.sourcePreviews.busy(this)
    val failedSourceIds:Set<String> get()=session.sourcePreviews.failures(this)
    private fun alive()=active && session.sourceEditSnapshot(expected.node.id)?.let {
        it.epoch==expected.epoch && it.documentId==expected.documentId
    }==true
    fun captureNavigation(id:String,target:MapSourceTarget):MapSourceNavigationTicket? {
        if(!alive()) return null
        return session.captureSourceNavigation(expected.node.id,id,target)
    }
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
    fun close() {if(active) {active=false;noteDrafts=emptyMap();cancel()}}
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
    fun noteDraft(id:String):String?=noteDrafts[id]?.value
    /** Capture from the displayed row, before a late field callback can encounter a new member. */
    fun captureNote(shown:MapSource):MapSourcePreviewTicket? {
        if(!alive()) return null
        val ticket=session.captureSourcePreview(expected.node.id,shown.id) ?: return null
        val actual=session.currentSourcePreview(ticket) ?: return null
        return ticket.takeIf {actual.target==shown.target && actual.note==shown.note}
    }
    /** Keystrokes only change this row's draft; sync must never replace its original baseline. */
    fun draftNote(shown:MapSource,ticket:MapSourcePreviewTicket?,value:String):Boolean {
        if(!alive() || value.length>20_000) return false
        val id=shown.id
        val draft=noteDrafts[id]
        if(draft!=null) {
            if(draft.ticket!=ticket) {conflict=true;return false}
            noteDrafts=noteDrafts+(id to draft.copy(value=value));return true
        }
        val bound=ticket?.takeIf {it.nodeId==expected.node.id && it.sourceId==id && it.target==shown.target}
        noteDrafts=noteDrafts+(id to NoteDraft(bound,shown.note,value))
        val actual=bound?.let(session::currentSourcePreview)
        if(actual==null || actual.note!=shown.note) {conflict=true;return false}
        return true
    }
    fun commitNote(id:String,ticket:MapSourcePreviewTicket?):Boolean {
        val draft=noteDrafts[id] ?: return true
        if(draft.ticket!=ticket) {conflict=true;return false}
        return commitNotes(setOf(id))
    }
    fun commitNotes():Boolean=commitNotes(noteDrafts.keys)
    private fun commitNotes(ids:Set<String>):Boolean {
        val pending=noteDrafts.filterKeys {it in ids}
        if(pending.isEmpty()) return true
        if(!alive()) return false
        val current=session.sourceEditSnapshot(expected.node.id) ?: return false
        val changed=pending.filterValues {it.value!=it.initial}
        if(changed.values.any {draft->draft.ticket?.let(session::currentSourcePreview)?.note!=draft.initial}) {conflict=true;return false}
        if(changed.isNotEmpty()) {
            val next=current.node.copy(sources=current.node.sources.map {source->
                changed[source.id]?.let {source.copy(note=it.value)} ?: source
            })
            if(!session.editSources(current,next)) {conflict=true;return false}
        }
        session.sourceEditSnapshot(expected.node.id)?.let {expected=it} ?: return false
        noteDrafts=noteDrafts.filterKeys {it !in pending};conflict=false
        return true
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
        return apply(node.copy(sources=node.sources.filterNot {it.id==id},primarySource=node.primarySource.takeUnless {it==id})).also {
            if(it) noteDrafts=noteDrafts-id
        }
    }
}
