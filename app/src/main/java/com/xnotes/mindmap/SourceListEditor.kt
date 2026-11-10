package com.xnotes.mindmap

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Successful callbacks advance synchronously; each row patches only its own current source. */
internal class SourceListEditor private constructor(private val runtime:Runtime,private val host:Any) {
    /** Retained model data and opaque tokens only; never an Activity, View or composition callback. */
    internal class Runtime(internal val session:MapSession,internal var expected:SourceEditSnapshot) {
        private var active=true
        private var host:Any?=null
        var conflict by mutableStateOf(false)
        fun matches(session:MapSession,snapshot:SourceEditSnapshot)=active && this.session===session &&
            expected.epoch==snapshot.epoch && expected.documentId==snapshot.documentId && expected.node.id==snapshot.node.id
        fun alive()=active && session.sourceEditSnapshot(expected.node.id)?.let {
            it.epoch==expected.epoch && it.documentId==expected.documentId
        }==true
        fun bind(token:Any) {
            kotlin.check(active)
            host?.let {session.sourcePreviews.cancel(it)}
            host=token
        }
        fun owns(token:Any)=active && host===token
        fun detach(token:Any) {
            session.sourcePreviews.cancel(token)
            if(host===token) {host=null;session.sourcePreviews.cancel(this)}
        }
        fun close() {
            if(!active) return
            active=false;host?.let {session.sourcePreviews.cancel(it)};host=null
            session.retireSourceTargetEditOwner(this);session.sourcePreviews.cancel(this)
        }
        fun targetEditApplied(ticket:MapSourceTargetEditTicket) {
            if(alive() && ticket.nodeId==expected.node.id && ticket.accepted && session.sourceTargetEditCurrent(ticket)) {
                session.sourceEditSnapshot(expected.node.id)?.let {expected=it};conflict=false
            }
        }
        fun check(id:String,target:MapSourceTarget):Boolean {
            if(!alive()) return false
            val ticket=session.captureSourcePreview(expected.node.id,id)?.takeIf {it.target==target} ?: return false
            return session.sourcePreviews.request(this,listOf(ticket),MapSourcePreviewMode.CHECK,::alive)
        }
    }
    constructor(session:MapSession,initial:SourceEditSnapshot):this(Runtime(session,initial),Any()) {runtime.bind(host)}
    internal constructor(runtime:Runtime):this(runtime,Any()) {runtime.bind(host)}
    private val session get()=runtime.session
    private var expected:SourceEditSnapshot
        get()=runtime.expected
        set(value) {runtime.expected=value}
    private var active=true
    private data class NoteDraft(val ticket:MapSourcePreviewTicket?,val initial:String,val value:String)
    private var noteDrafts by mutableStateOf<Map<String,NoteDraft>>(emptyMap())
    val hasNoteDrafts:Boolean get()=noteDrafts.isNotEmpty()
    var conflict:Boolean
        get()=runtime.conflict
        private set(value) {runtime.conflict=value}
    val busySourceIds:Set<String> get()=session.sourcePreviews.busy(host)+session.sourcePreviews.busy(runtime)
    val failedSourceIds:Set<String> get()=session.sourcePreviews.failures(host)+session.sourcePreviews.failures(runtime)
    private fun alive()=active && runtime.owns(host) && runtime.alive()
    fun captureNavigation(id:String,target:MapSourceTarget):MapSourceNavigationTicket? {
        if(!alive()) return null
        return session.captureSourceNavigation(expected.node.id,id,target)
    }
    /** The UI captures this before click, alongside the displayed target and source lifetime. */
    fun captureTargetEdit(id:String,displayedTarget:MapSourceTarget,mode:SourceTargetEditMode):MapSourceTargetEditTicket? {
        if(!alive()) return null
        return session.captureSourceTargetEdit(expected.node.id,id,displayedTarget,mode)
    }
    fun targetEditCurrent(ticket:MapSourceTargetEditTicket)=alive() && ticket.nodeId==expected.node.id && session.sourceTargetEditCurrent(ticket)
    fun startTargetEdit(ticket:MapSourceTargetEditTicket):Boolean =
        !ticket.accepted && targetEditCurrent(ticket) && session.sourceTargetEdits.start(runtime,ticket)
    internal fun targetEditApplied(ticket:MapSourceTargetEditTicket) {
        if(ticket.accepted && targetEditCurrent(ticket)) {
            runtime.targetEditApplied(ticket)
        }
    }
    fun applyTargetEdit(ticket:MapSourceTargetEditTicket,choice:PreparedSourceTargetChoice):MapSourceTargetEditResult {
        if(!targetEditCurrent(ticket)) return MapSourceTargetEditResult.Changed
        return session.applySourceTargetEdit(ticket,choice).also {result->
            if(result is MapSourceTargetEditResult.Applied) targetEditApplied(ticket)
            else if(result==MapSourceTargetEditResult.Changed) conflict=true
        }
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
        return session.sourcePreviews.request(host,listOf(ticket),mode,::alive)
    }
    fun showPreview(id:String,target:MapSourceTarget?=null)=request(id,MapSourcePreviewMode.AUTO,target)
    fun check(id:String,target:MapSourceTarget?=null)=request(id,MapSourcePreviewMode.CHECK,target)
    fun refresh(id:String,target:MapSourceTarget?=null)=request(id,MapSourcePreviewMode.REFRESH,target)
    fun refreshGraph():Boolean {
        if(!alive()) return false
        val tickets=session.document?.nodes.orEmpty().flatMap {node->node.sources.mapNotNull {session.captureSourcePreview(node.id,it.id)}}
        return session.sourcePreviews.request(host,tickets,MapSourcePreviewMode.REFRESH,::alive)
    }
    fun checkGraph():Boolean {
        if(!alive()) return false
        val tickets=session.document?.nodes.orEmpty().flatMap {node->node.sources.mapNotNull {session.captureSourcePreview(node.id,it.id)}}
        return session.sourcePreviews.request(host,tickets,MapSourcePreviewMode.CHECK,::alive)
    }
    fun cancel() {if(alive()) {session.sourcePreviews.cancel(host);session.sourcePreviews.cancel(runtime)}}
    /** Composition disposal releases only this host. The fixed target request belongs to Runtime. */
    fun detach() {if(active) {active=false;runtime.detach(host)}}
    /** A user close can retire the runtime only while this is its current host. */
    fun close() {
        noteDrafts=emptyMap()
        if(active && runtime.owns(host)) {session.retireSourceTargetEditOwner(this);runtime.close()}
        detach()
    }
    fun sync(displayed:SourceEditSnapshot) {
        if(!active || !runtime.owns(host)) return
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
        if(index<0 || !alive()) return false
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
