package com.xnotes.mindmap

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal data class MapSourcePreviewTicket(val nodeId:String,val sourceId:String,val target:MapSourceTarget,
    val epoch:Long,val lifetime:Any)
internal enum class MapSourcePreviewMode { AUTO,CHECK,REFRESH }

/** Session-owned work; a panel is only an opaque owner and never owns a second document model. */
internal class MapSourcePreviewController(private val session:MapSession,private val context:Context,private val scope:CoroutineScope) {
    private class Work(val owner:Any,val tickets:List<MapSourcePreviewTicket>,val mode:MapSourcePreviewMode,val ownerCurrent:()->Boolean) {
        val retired=AtomicBoolean(false)
        lateinit var job:Job
    }
    private class Auto(val owner:Any,val ownerCurrent:()->Boolean) {
        val tickets=linkedMapOf<String,MapSourcePreviewTicket>()
        lateinit var job:Job
    }
    private val work=mutableSetOf<Work>()
    private val latest=mutableMapOf<String,Work>()
    private val queued=mutableMapOf<Any,Auto>()
    private var busy by mutableStateOf<Map<String,Any>>(emptyMap())
    private var failures by mutableStateOf<Map<String,Any>>(emptyMap())
    fun busy(owner:Any)=busy.filterValues {it===owner}.keys
    fun failures(owner:Any)=failures.filterValues {it===owner}.keys
    private fun publishBusy() {busy=latest.mapValues {it.value.owner}+queued.values.flatMap {batch->batch.tickets.keys.map {it to batch.owner}}.toMap()}
    private fun valid(task:Work,ticket:MapSourcePreviewTicket)=!task.retired.get() && task.ownerCurrent() &&
        latest[ticket.sourceId]===task && session.currentSourcePreview(ticket)!=null
    fun request(owner:Any,tickets:List<MapSourcePreviewTicket>,mode:MapSourcePreviewMode,ownerCurrent:()->Boolean):Boolean {
        if(!ownerCurrent()) return false
        if(mode==MapSourcePreviewMode.AUTO) return queue(owner,tickets,ownerCurrent)
        removeQueued(tickets.mapTo(hashSetOf()) {it.sourceId})
        return start(owner,tickets,mode,ownerCurrent)
    }
    /** One composition's visible rows share one source snapshot/decoder for each document. */
    private fun queue(owner:Any,tickets:List<MapSourcePreviewTicket>,ownerCurrent:()->Boolean):Boolean {
        if(!ownerCurrent()) return false
        val pendingIds=queued.values.flatMapTo(hashSetOf()) {it.tickets.keys}
        val accepted=tickets.filter {session.currentSourcePreview(it)!=null && it.sourceId !in latest && it.sourceId !in pendingIds}
        if(accepted.isEmpty()) return false
        var batch=queued[owner]
        if(batch==null) {
            val created=Auto(owner,ownerCurrent);batch=created;queued[owner]=created
            created.job=scope.launch(start=CoroutineStart.LAZY) {
                yield()
                if(queued[owner]!==created) return@launch
                queued.remove(owner)
                start(owner,created.tickets.values.toList(),MapSourcePreviewMode.AUTO,created.ownerCurrent)
                publishBusy()
            }
        }
        val ready=requireNotNull(batch)
        accepted.forEach {ready.tickets[it.sourceId]=it}
        publishBusy();ready.job.start();return true
    }
    private fun removeQueued(ids:Set<String>) {
        queued.values.toList().forEach {batch->
            batch.tickets.keys.removeAll(ids)
            if(batch.tickets.isEmpty()) {queued.remove(batch.owner);batch.job.cancel()}
        }
        publishBusy()
    }
    private fun start(owner:Any,tickets:List<MapSourcePreviewTicket>,mode:MapSourcePreviewMode,ownerCurrent:()->Boolean):Boolean {
        if(!ownerCurrent()) return false
        val accepted=tickets.filter {session.currentSourcePreview(it)!=null && (mode!=MapSourcePreviewMode.AUTO || it.sourceId !in latest)}
        if(accepted.isEmpty()) return false
        val task=Work(owner,accepted,mode,ownerCurrent)
        accepted.forEach {latest[it.sourceId]=task}
        val requestedIds=accepted.mapTo(hashSetOf()) {it.sourceId}
        failures=failures.filterKeys {it !in requestedIds}
        work.add(task);publishBusy()
        task.job=scope.launch(start=CoroutineStart.LAZY) {
            val coroutine=currentCoroutineContext()
            val check={coroutine.ensureActive();if(task.retired.get()) throw java.util.concurrent.CancellationException("Source preview retired")}
            try {
                for(group in accepted.groupBy {it.target.documentId}.values) {
                    val current=group.filter {valid(task,it)}
                    if(current.isEmpty()) continue
                    val render=current.filter {ticket->
                        mode==MapSourcePreviewMode.REFRESH || mode==MapSourcePreviewMode.AUTO &&
                            session.currentSourcePreview(ticket)?.lastPreview?.assetKey?.let {session.document?.previewAssets?.containsKey(it)}!=true
                    }.mapTo(hashSetOf()) {it.target}
                    val reads=withContext(Dispatchers.IO) {
                        MapSourcePreviewBackend.load(context,current.map {it.target}.distinct(),render,check)
                    }.associateBy {it.target}
                    val active=current.filter {valid(task,it)}
                    val patches=active.mapNotNull {ticket->
                        val source=session.currentSourcePreview(ticket) ?: return@mapNotNull null
                        val read=reads[ticket.target] ?: return@mapNotNull null
                        if(read.failedPreview) failures=failures+(ticket.sourceId to owner)
                        patch(ticket,source,read,mode)
                    }
                    val byId=active.associateBy {it.sourceId}
                    try {
                        if(patches.isNotEmpty()) session.mergeSourceDerived(patches) {patch->byId[patch.sourceId]?.let {valid(task,it)}==true}
                    } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
                    catch(_:Exception) {failures=failures+active.associate {it.sourceId to owner}}
                }
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:Exception) {
                failures=failures+accepted.filter {valid(task,it)}.associate {it.sourceId to owner}
            } finally {
                latest.keys.removeAll {latest[it]===task};work.remove(task);publishBusy()
            }
        }
        // Old work can share a PDF holder with other still-current source rows.
        work.filter {it!==task && it.tickets.none {ticket->valid(it,ticket)}}.forEach(::retire)
        task.job.start();return true
    }
    private fun patch(ticket:MapSourcePreviewTicket,source:MapSource,read:MapSourcePreviewRead,mode:MapSourcePreviewMode):MapSourceDerivedPatch {
        val semantic=read.renderedVersion?.startsWith("xmap-render-v1:")==true
        val observed=read.renderedVersion
        val matches=if(semantic && source.acceptedPreviewVersion!=null) source.acceptedPreviewVersion==read.renderedVersion
            else source.sourceVersion!=null && source.sourceVersion==read.rawVersion
        val image=read.asset?.takeIf {!read.failedPreview && read.rawVersion!=null && read.renderedVersion!=null}
        val provenEmpty=read.status==SourceStatus.EMPTY && !read.failedPreview && read.rawVersion!=null && read.renderedVersion!=null
        val accepted=image!=null && (mode==MapSourcePreviewMode.REFRESH || source.sourceVersion==read.rawVersion) ||
            mode==MapSourcePreviewMode.REFRESH && provenEmpty
        val preview=image?.let {MapLastPreview(it.key,ticket.target,requireNotNull(read.renderedVersion),System.currentTimeMillis())}
        val emptyProof=provenEmpty && (mode==MapSourcePreviewMode.REFRESH || matches) ||
            source.status==SourceStatus.EMPTY && matches && source.observedVersion==read.renderedVersion
        val status=when {
            read.status !in setOf(SourceStatus.CURRENT,SourceStatus.EMPTY)->read.status
            image!=null->if(accepted) read.status else SourceStatus.STALE
            emptyProof->SourceStatus.EMPTY
            !matches || source.lastPreview?.target?.let {it!=source.target}==true->SourceStatus.STALE
            else->read.status
        }
        return MapSourceDerivedPatch(ticket.nodeId,ticket.sourceId,ticket.target,status,read.fileName,observed,read.lastKnownPage,
            preview,image,accepted,read.rawVersion,read.renderedVersion)
    }
    private fun retire(task:Work) {
        task.retired.set(true);task.job.cancel();latest.keys.removeAll {latest[it]===task};publishBusy()
    }
    fun cancel(owner:Any) {
        queued.remove(owner)?.job?.cancel();work.filter {it.owner===owner}.toList().forEach(::retire);publishBusy()
    }
    fun documentChanged() {
        queued.values.toList().forEach {batch->
            batch.tickets.keys.removeAll {id->!batch.ownerCurrent() || session.currentSourcePreview(batch.tickets.getValue(id))==null}
            if(batch.tickets.isEmpty()) {queued.remove(batch.owner);batch.job.cancel()}
        }
        latest.keys.removeAll {id->
            val task=latest.getValue(id)
            val ticket=task.tickets.firstOrNull {it.sourceId==id}
            !task.ownerCurrent() || ticket==null || session.currentSourcePreview(ticket)==null
        }
        work.filter {it.tickets.none {ticket->valid(it,ticket)}}.toList().forEach(::retire);publishBusy()
    }
    fun retireAll() {queued.values.forEach {it.job.cancel()};queued.clear();work.toList().forEach(::retire);failures=emptyMap();publishBusy()}
}
