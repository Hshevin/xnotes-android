package com.xnotes.mindmap

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.xnotes.ui.NativeBacklinkContext
import java.util.IdentityHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Read-only library projection plus explicit, source-scoped operations on the existing map actors. */
internal class MapBacklinkController(
    context:Context,scope:CoroutineScope,private val source:NativeBacklinkContext,
    private val budget:BacklinkBudget=BacklinkBudget(),
) {
    private val app=context.applicationContext
    private val owner=SupervisorJob(scope.coroutineContext[Job])
    private val workScope=CoroutineScope(scope.coroutineContext+owner+Dispatchers.Main.immediate)
    private var active=true
    private var actionGeneration=0L
    private var scanGeneration=0L
    private var inventory=-1L
    private var querying=false
    private var scanWork:Job?=null
    private var observing:Job?=null
    private var library:MapBacklinkLibrary?=null
    private var stored:BacklinkLibraryScan?=null
    var report by mutableStateOf<BacklinkReport?>(null);private set
    var loading by mutableStateOf(false);private set
    var problem by mutableStateOf<BacklinkProblem?>(null);private set

    private class Removal(val row:MapBacklinkRow,val generation:Long,val token:Any=Any()) {
        var lease:MapDocumentLease?=null
        var ticket:MapBacklinkRemovalTicket?=null
        var applied=false
        var released=false
        var preparing:Job?=null
        var saving=false
        var preserveDirty=false
    }
    private var operation:Removal?=null
    private var removalState by mutableStateOf<BacklinkRemovalState?>(null)
    val removal:BacklinkRemovalState? get() {
        val state=removalState ?: return null
        val work=operation ?: return state
        val review=work.applied && work.lease?.takeIf {it.alive}?.session?.let {session->
            work.ticket?.let {session.captureRemovedBacklinkFocus(it)!=null}
        }==true
        return when(state) {
            is BacklinkRemovalState.Removed->state.copy(canReview=review)
            is BacklinkRemovalState.Failed->state.copy(canReview=review,canRetrySave=state.canRetrySave && retryCurrent(work))
            else->state
        }
    }
    private fun current()=active && source.current()
    private fun current(work:Removal)=current() && operation===work && (work.applied || work.generation==actionGeneration)
    private fun retryCurrent(work:Removal):Boolean {
        val lease=work.lease ?: return false
        val ticket=work.ticket ?: return false
        val session=lease.session
        return current(work) && lease.alive && work.applied && !work.saving && !session.closing && !session.replacingDocument &&
            session.interactionEpoch==ticket.source.epoch && session.document?.id==ticket.documentId
    }
    fun refresh():Boolean {
        if(!current()) return false
        actionGeneration++
        operation?.takeIf {!it.applied}?.let {retireRemoval(it)}
        querying=true;problem=null
        inventory=MapDocuments.observationVersion()
        if(observing==null) observing=workScope.launch(start=CoroutineStart.UNDISPATCHED) {
            snapshotFlow {MapDocuments.observationVersion() to MapDocuments.observations(app)}.collect {(version,live)->
                if(!current()) {scanWork?.cancel();loading=false;return@collect}
                if(version!=inventory) {
                    if(querying) startScan() else {
                        // Never resurrect a disk projection captured before the last live actor retired.
                        stored=null
                        report=report?.let {old->old.copy(rows=old.rows.map {it.copy(blocked=BacklinkProblem.CHANGED)},complete=false)}
                    }
                } else if(stored!=null) publish(live)
            }
        }
        startScan()
        return true
    }
    private fun startScan() {
        if(!current() || !querying) return
        val request=++scanGeneration
        scanWork?.cancel();loading=true;stored=null;library=null
        val version=MapDocuments.observationVersion();inventory=version
        val live=MapDocuments.observations(app)
        val reader=MapBacklinkLibrary(app,source.libraryRoots,budget)
        scanWork=workScope.launch {
            try {
                val result=reader.scan(source.documentId,live)
                if(!current() || request!=scanGeneration) return@launch
                if(MapDocuments.observationVersion()!=version) {startScan();return@launch}
                stored=result;library=reader;problem=null
                publish(MapDocuments.observations(app))
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:Exception) {if(current() && request==scanGeneration) problem=BacklinkProblem.UNAVAILABLE}
            finally {if(request==scanGeneration) loading=false}
        }
    }
    private fun publish(live:List<MapDocumentObservation>) {
        if(!current()) return
        val scan=stored ?: return
        val reader=library ?: return
        val candidates=scan.candidates.associateBy {it.key}
        val actors=live.associateBy {it.key}
        val rows=mutableListOf<MapBacklinkRow>()
        val issues=scan.issues.toMutableList()
        val identities=linkedMapOf<String,String>()
        var limited=scan.limited
        var checked=0
        reader.resetProjectionBudget()
        for(graph in scan.graphs) if(graph.candidate.key !in actors) {
            graph.graphId?.let {identities[graph.candidate.key]=it;checked++}
            rows+=graph.rows
        }
        for(observation in live) {
            val candidate=candidates[observation.key]
            val doc=observation.document
            if(doc==null || observation.loading || observation.creating || observation.failed) {
                issues+=BacklinkIssue(if(observation.failed) BacklinkProblem.UNAVAILABLE else BacklinkProblem.LOADING,candidate?.name)
                continue
            }
            identities[observation.key]=doc.id;checked++
            val scopeProblem=scan.reservedIds[doc.id] ?: when(candidate?.place) {
                BacklinkPlace.TRASH->BacklinkProblem.TRASHED
                BacklinkPlace.RESERVED,BacklinkPlace.UNKNOWN,null->BacklinkProblem.UNKNOWN_SCOPE
                else->null
            }
            val blocked=scopeProblem ?: scan.liveBlocked[observation.key] ?:
                if(observation.closing || observation.replacing) BacklinkProblem.BUSY else null
            if(blocked!=null) issues+=BacklinkIssue(blocked,candidate?.name)
            if(observation.discoveryFailed) issues+=BacklinkIssue(BacklinkProblem.UNAVAILABLE,candidate?.name)
            val location=BacklinkLocation(observation.uri,candidate?.aliases ?: setOf(observation.uri.toString()),
                observation.key,doc.id,candidate?.name ?: observation.uri.lastPathSegment.orEmpty().take(BacklinkBudget.LABEL_CHARS))
            rows+=reader.project(doc,source.documentId,location,BacklinkOrigin.LIVE,observation.session.conflict,blocked) {node,id,target->
                observation.captureMembership(node,id,target)
            }.map {row->if(row.membership==null && row.blocked==null) row.copy(blocked=BacklinkProblem.CHANGED) else row}
        }
        val duplicates=identities.entries.groupBy {it.value}.filterValues {it.size>1}.keys
        val projected=rows.map {row->if(row.location.graphId in duplicates) row.copy(blocked=BacklinkProblem.DUPLICATE) else row}
        duplicates.forEach {issues+=BacklinkIssue(BacklinkProblem.DUPLICATE)}
        if(projected.size>budget.maxRows || reader.projectionLimited) {limited=true;issues+=BacklinkIssue(BacklinkProblem.LIMIT)}
        val visible=projected.take(budget.maxRows)
        val finalIssues=issues.distinct()
        report=BacklinkReport(visible,finalIssues.take(budget.maxIssues),checked,
            !limited && finalIssues.isEmpty() && scan.omittedIssues==0,
            scan.omittedIssues+(finalIssues.size-budget.maxIssues).coerceAtLeast(0),scan.excludedMaps)
        updatePreviews()
    }
    fun cancel() {
        actionGeneration++;querying=false;scanGeneration++;scanWork?.cancel();scanWork=null;loading=false
        operation?.takeIf {!it.applied}?.let {retireRemoval(it)}
        problem=BacklinkProblem.CANCELLED
    }
    private fun sameMembership(a:MapBacklinkMembership?,b:MapBacklinkMembership?):Boolean = when {
        a==null || b==null->a==null && b==null
        else->a.issuer===b.issuer && a.documentId==b.documentId && a.source==b.source
    }
    private fun rowCurrent(row:MapBacklinkRow)=report?.rows?.any {latest->latest.key==row.key && latest.target==row.target &&
        latest.blocked==null && sameMembership(latest.membership,row.membership)}==true
    fun captureOpen(row:MapBacklinkRow):MapBacklinkOpenRequest? {
        if(!current() || loading || row.blocked!=null || !rowCurrent(row)) {problem=BacklinkProblem.CHANGED;return null}
        val generation=actionGeneration
        // An accepted stored action may itself create the actor and cause a live overlay; that is not a new request.
        return MapBacklinkOpenRequest.capture(source,row,{current() && actionGeneration==generation &&
            (row.membership?.let {it.current(it.issuer,row.location.graphId,row.nodeId,row.sourceId,row.target)}!=false)})
    }
    fun prepareRemove(row:MapBacklinkRow) {
        if(!current() || loading || row.blocked!=null || !rowCurrent(row)) {problem=BacklinkProblem.CHANGED;return}
        operation?.let {if(it.saving) {problem=BacklinkProblem.BUSY;return} else retireRemoval(it)}
        val work=Removal(row,actionGeneration);operation=work
        removalState=BacklinkRemovalState.Preparing(row,work.token)
        work.preparing=workScope.launch {
          try {
            val access=acquireBacklink(app,row,valid={current(work)},roots=source.libraryRoots)
            if(access is BacklinkAccessResult.Failed) {
                if(current(work)) removalState=BacklinkRemovalState.Failed(row,work.token,access.problem)
                return@launch
            }
            val ready=access as BacklinkAccessResult.Ready
            if(!current(work)) {ready.lease.close();return@launch}
            work.lease=ready.lease
            val session=ready.lease.session
            val ticket=session.captureBacklinkRemoval(row.location.graphId,row.nodeId,row.sourceId,row.target,ready.membership)
            if(ticket==null) {removalState=BacklinkRemovalState.Failed(row,work.token,BacklinkProblem.CHANGED);return@launch}
            work.ticket=ticket
            val node=session.document!!.node(row.nodeId)
            val actual=node.sources.first {it.id==row.sourceId}
            val labels=row.copy(nodeTitle=node.text.take(BacklinkBudget.LABEL_CHARS),sourceFileName=actual.fileName.take(BacklinkBudget.LABEL_CHARS),
                sourceNote=actual.note.take(BacklinkBudget.NOTE_CHARS),lastKnownPage=actual.lastKnownPage,status=actual.status)
            removalState=BacklinkRemovalState.Confirm(labels,work.token)
          } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
          catch(_:Exception) {if(current(work)) removalState=BacklinkRemovalState.Failed(row,work.token,BacklinkProblem.UNAVAILABLE)}
        }
    }
    fun confirmRemove(token:Any) {
        val work=operation?.takeIf {it.token===token} ?: return
        if(!current(work) || removalState !is BacklinkRemovalState.Confirm) return
        val lease=work.lease ?: return
        val ticket=work.ticket ?: return
        removalState=BacklinkRemovalState.Preparing(work.row,token)
        work.preparing=workScope.launch {
          try {
            val blocked=backlinkLocationProblem(app,source.libraryRoots,work.row) ?:
                if(backlinkRecoveryOutside(app,lease.uri,work.row.location.aliases,lease.session.recoveryKeys)) BacklinkProblem.RECOVERY_CONFLICT else null
            if(!current(work)) return@launch
            if(blocked!=null) {preserveScopeFailure(work);removalState=BacklinkRemovalState.Failed(work.row,token,blocked);return@launch}
            when(lease.session.removeBacklinkSource(ticket)) {
                MapBacklinkRemovalResult.Applied->{work.applied=true;saveRemoval(work)}
                MapBacklinkRemovalResult.Busy->removalState=BacklinkRemovalState.Failed(work.row,token,BacklinkProblem.BUSY)
                MapBacklinkRemovalResult.Changed->removalState=BacklinkRemovalState.Failed(work.row,token,BacklinkProblem.CHANGED)
                MapBacklinkRemovalResult.Invalid->removalState=BacklinkRemovalState.Failed(work.row,token,BacklinkProblem.CORRUPT)
            }
          } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
          catch(_:Exception) {if(current(work)) removalState=BacklinkRemovalState.Failed(work.row,token,BacklinkProblem.UNAVAILABLE)}
        }
    }
    private fun saveRemoval(work:Removal) {
        val lease=work.lease ?: return
        work.preserveDirty=false
        work.saving=true;removalState=BacklinkRemovalState.Saving(work.row,work.token)
        val accepted=lease.session.saveAttempt()
        // The accepted save belongs to the actor, not to the lifetime of the source tab.
        MapSaves.scope.launch {
            try {
                val result=accepted.await()
                work.saving=false
                if(current(work)) removalState=when(result) {
                    is MapSaveOutcome.Written->if(work.ticket?.let {lease.session.backlinkRemovalAppliedCurrent(it)}==true)
                        BacklinkRemovalState.Removed(work.row,work.token,!result.latest)
                        else BacklinkRemovalState.SavedChanged(work.row,work.token,!result.latest)
                    is MapSaveOutcome.Conflict->BacklinkRemovalState.Failed(work.row,work.token,BacklinkProblem.RECOVERY_CONFLICT,canRetrySave=true)
                    else->BacklinkRemovalState.Failed(work.row,work.token,BacklinkProblem.SAVE_FAILED,canRetrySave=true)
                }
            } catch(_:Exception) {
                work.saving=false
                if(current(work)) removalState=BacklinkRemovalState.Failed(work.row,work.token,BacklinkProblem.SAVE_FAILED,canRetrySave=true)
            } finally {if(!current(work)) release(work)}
        }
    }
    fun retryRemoveSave(token:Any) {
        val work=operation?.takeIf {it.token===token} ?: return
        if(removalState !is BacklinkRemovalState.Failed || !retryCurrent(work)) return
        val lease=work.lease ?: return
        removalState=BacklinkRemovalState.Preparing(work.row,token)
        work.preparing=workScope.launch {
          try {
            val blocked=backlinkLocationProblem(app,source.libraryRoots,work.row) ?:
                if(backlinkRecoveryOutside(app,lease.uri,work.row.location.aliases,lease.session.recoveryKeys)) BacklinkProblem.RECOVERY_CONFLICT else null
            if(!retryCurrent(work)) return@launch
            if(blocked!=null) {preserveScopeFailure(work);removalState=BacklinkRemovalState.Failed(work.row,token,blocked,canRetrySave=true)}
            else saveRemoval(work)
          } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
          catch(_:Exception) {if(current(work)) removalState=BacklinkRemovalState.Failed(work.row,token,BacklinkProblem.UNAVAILABLE,canRetrySave=true)}
        }
    }
    fun captureRemovedOpen(token:Any):MapBacklinkOpenRequest? {
        val work=operation?.takeIf {it.token===token && it.applied} ?: return null
        if(!current(work) || work.saving) return null
        val ticket=work.ticket ?: return null
        if(work.lease?.session?.captureRemovedBacklinkFocus(ticket)==null) return null
        val generation=actionGeneration
        return MapBacklinkOpenRequest.capture(source,work.row,{current(work) && generation==actionGeneration},ticket)
    }
    fun dismissRemove(token:Any) {operation?.takeIf {it.token===token && !it.saving}?.let {retireRemoval(it)}}
    private fun retireRemoval(work:Removal) {
        work.preparing?.cancel()
        if(!work.saving) release(work)
        if(operation===work) {operation=null;removalState=null}
    }
    private fun release(work:Removal) {
        if(!work.released) {work.released=true;work.lease?.close(preserveDirty=work.preserveDirty);work.lease=null}
    }
    private fun preserveScopeFailure(work:Removal) {work.preserveDirty=true;work.lease?.session?.cancelPendingAutosave()}

    private val visiblePreviews=IdentityHashMap<MapBacklinkRow,Boolean>()
    private var previewGeneration=0L
    private var previewWork:Job?=null
    var previewAssets by mutableStateOf<Map<String,MapPreviewAsset>>(emptyMap());private set
    var previewBusy by mutableStateOf<Set<String>>(emptySet());private set
    var previewFailed by mutableStateOf<Set<String>>(emptySet());private set
    fun showPreview(row:MapBacklinkRow,visible:Boolean) {
        if(visible) {if(!current()) return;visiblePreviews[row]=true} else visiblePreviews.remove(row)
        updatePreviews()
    }
    private fun updatePreviews() {
        val request=++previewGeneration;previewWork?.cancel()
        if(!current()) {previewAssets=emptyMap();previewBusy=emptySet();previewFailed=emptySet();return}
        val latest=report?.rows.orEmpty().associateBy {it.key}
        val visible=visiblePreviews.keys.mapNotNull {old->latest[old.key]?.takeIf {it.target==old.target && it.preview==old.preview}}.distinctBy {it.key}
        val keys=visible.mapTo(hashSetOf()) {it.key}
        previewAssets=previewAssets.filter {(key,asset)->key in keys && latest[key]?.preview?.assetKey==asset.key}
        previewBusy=emptySet();previewFailed=previewFailed.intersect(keys)
        previewWork=workScope.launch {
            yield() // Merge the visible rows launched by this composition into one bounded, per-graph batch.
            if(!current() || request!=previewGeneration) return@launch
            val wanted=visible.filter {it.preview!=null && it.key !in previewAssets}.take(BacklinkBudget.PREVIEW_ITEMS)
            if(wanted.isEmpty()) return@launch
            previewBusy=wanted.mapTo(hashSetOf()) {it.key}
            previewFailed=previewFailed+visible.filter {it.preview!=null && it.key !in previewAssets && it.key !in previewBusy}.map {it.key}
            try {
                val result=linkedMapOf<String,MapPreviewAsset>()
                val disk=mutableListOf<MapBacklinkRow>()
                for(row in wanted) {
                    val member=row.membership
                    if(member==null) disk+=row else if(member.current(member.issuer,row.location.graphId,row.nodeId,row.sourceId,row.target)) {
                        val doc=member.issuer.document
                        val actual=doc?.nodes?.firstOrNull {it.id==row.nodeId}?.sources?.firstOrNull {it.id==row.sourceId && it.target==row.target}
                        if(actual?.lastPreview?.let {BacklinkPreview(it.assetKey,it.target,it.renderedVersion,it.generatedAt)}==row.preview)
                            doc?.previewAssets?.get(row.preview!!.assetKey)?.let {result[row.key]=it}
                    }
                }
                if(disk.isNotEmpty()) result+=MapBacklinkLibrary(app,source.libraryRoots,budget).preview(disk)
                if(!current() || request!=previewGeneration) return@launch
                var bytes=previewAssets.values.sumOf {it.byteSize.toLong()};var pixels=previewAssets.values.sumOf {it.width.toLong()*it.height}
                val accepted=previewAssets.toMutableMap();val failed=previewFailed.toMutableSet()
                for(row in wanted) {
                    val asset=result[row.key]
                    if(asset!=null && rowCurrent(row) && report?.rows?.firstOrNull {it.key==row.key}?.preview==row.preview &&
                        bytes+asset.byteSize<=BacklinkBudget.PREVIEW_BYTES && pixels+asset.width.toLong()*asset.height<=BacklinkBudget.PREVIEW_PIXELS) {
                        accepted[row.key]=asset;bytes+=asset.byteSize;pixels+=asset.width.toLong()*asset.height;failed.remove(row.key)
                    } else failed+=row.key
                }
                previewAssets=accepted;previewFailed=failed
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:Exception) {if(current() && request==previewGeneration) previewFailed=previewFailed+previewBusy}
            finally {if(request==previewGeneration) previewBusy=emptySet()}
        }
    }
    fun close() {
        if(!active) return
        active=false;actionGeneration++;querying=false
        operation?.let {retireRemoval(it)}
        owner.cancel();visiblePreviews.clear();previewAssets=emptyMap();previewBusy=emptySet();previewFailed=emptySet()
    }
}
