package com.xnotes.mindmap

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** One pure query's read-only library and live overlay. It owns no native reading context or graph lease. */
internal class LocalFileNetworkController(
    context:Context,scope:CoroutineScope,val query:NetworkQuery,
    private val budget:LocalNetworkBudget=LocalNetworkBudget(),
) {
    private val app=context.applicationContext
    private val owner=SupervisorJob(scope.coroutineContext[Job])
    private val workScope=CoroutineScope(scope.coroutineContext+owner+Dispatchers.Main.immediate)
    private val issuer=Any()
    internal val requestOwner:Any get()=issuer
    private var active=true
    private var actionGeneration by mutableStateOf(0L)
    private var scanGeneration=0L
    private var inventory=-1L
    private var querying=false
    private var scanWork:Job?=null
    private var observing:Job?=null
    private var library:MapBacklinkLibrary?=null
    private var stored:NetworkLibraryScan?=null
    private var projectionGeneration=0L
    private var filterWork:Job?=null
    var filter by mutableStateOf(NetworkFilter());private set
    val filterRevision get()=actionGeneration
    var catalog by mutableStateOf<List<LocalNetworkFile>>(emptyList());private set
    var filtering by mutableStateOf(false);private set
    var report by mutableStateOf<LocalFileNetworkReport?>(null);private set
    var loading by mutableStateOf(false);private set
    var problem by mutableStateOf<BacklinkProblem?>(null);private set

    private fun current()=active && owner.isActive
    fun refresh():Boolean {
        if(!current()) return false
        actionGeneration++;querying=true;problem=null
        inventory=MapDocuments.observationVersion()
        if(observing==null) observing=workScope.launch(start=CoroutineStart.UNDISPATCHED) {
            snapshotFlow {MapDocuments.observationVersion() to MapDocuments.observations(app)}.collect {(version,live)->
                if(!current()) return@collect
                if(version!=inventory) {
                    // A retired actor can never reveal the old disk projection captured before that actor opened.
                    if(querying) startScan() else {stored=null;library=null}
                } else if(stored!=null) publish(live)
            }
        }
        startScan()
        return true
    }
    private fun startScan() {
        if(!current() || !querying) return
        retireFilterWork()
        val request=++scanGeneration
        scanWork?.cancel();loading=true;stored=null;library=null
        val version=MapDocuments.observationVersion();inventory=version
        val live=MapDocuments.observations(app)
        val reader=MapBacklinkLibrary(app,query.roots,budget.read)
        scanWork=workScope.launch {
            try {
                val result=reader.scanNetwork(query.focusId,live,budget)
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
        if(!current() || !querying) return
        val scan=stored ?: return
        val reader=library ?: return
        val candidates=scan.candidates.associateBy {it.key}
        val actors=live.associateBy {it.key}
        val identities=linkedMapOf<String,String>()
        val projections=mutableListOf<NetworkConceptProjection>()
        val issues=scan.issues.toMutableList()
        var limited=scan.limited
        for(graph in scan.graphs) if(graph.candidate.key !in actors) {
            graph.graphId?.let {identities[graph.candidate.key]=it}
            val blocked=graph.graphId?.let(scan.reservedIds::get)
            projections+=if(blocked==null) graph.concepts else graph.concepts.map {concept->
                concept.copy(members=concept.members.map {it.copy(blocked=blocked)})
            }
        }
        val ready=mutableListOf<Pair<MapDocumentObservation,BacklinkProblem?>>()
        for(observation in live) {
            val candidate=candidates[observation.key]
            val doc=observation.document
            if(doc==null || observation.loading || observation.creating || observation.failed) {
                issues+=BacklinkIssue(if(observation.failed) BacklinkProblem.UNAVAILABLE else BacklinkProblem.LOADING,candidate?.name)
                continue
            }
            identities[observation.key]=doc.id
            val scopeProblem=scan.reservedIds[doc.id] ?: when(candidate?.place) {
                BacklinkPlace.NORMAL->null;BacklinkPlace.TRASH->BacklinkProblem.TRASHED;else->BacklinkProblem.UNKNOWN_SCOPE
            }
            val blocked=scopeProblem ?: scan.liveBlocked[observation.key] ?:
                if(observation.closing || observation.replacing) BacklinkProblem.BUSY else null
            if(blocked!=null) issues+=BacklinkIssue(blocked,candidate?.name)
            if(observation.discoveryFailed) issues+=BacklinkIssue(BacklinkProblem.UNAVAILABLE,candidate?.name)
            ready+=observation to blocked
        }
        var evidence=projections.sumOf {concept->concept.members.size}
        for((observation,blocked) in ready) {
            owner.ensureActive()
            val doc=observation.document ?: continue
            val candidate=candidates[observation.key]
            val location=BacklinkLocation(observation.uri,candidate?.aliases ?: setOf(observation.uri.toString()),
                observation.key,doc.id,candidate?.name ?: observation.uri.lastPathSegment.orEmpty().take(BacklinkBudget.LABEL_CHARS))
            val projected=reader.projectNetwork(doc,query.focusId,location,BacklinkOrigin.LIVE,observation.session.conflict,blocked,
                membership={node,id,target->observation.captureMembership(node,id,target)},networkBudget=budget,checkActive={owner.ensureActive()})
            projections+=projected.concepts.map {concept->concept.copy(members=concept.members.map {row->
                if(row.membership==null && row.blocked==null) row.copy(blocked=BacklinkProblem.CHANGED) else row
            })}
            evidence+=projected.concepts.sumOf {it.members.size}
            if(projected.limited || evidence>budget.evidenceLimit) {limited=true;break}
        }
        val duplicates=identities.entries.groupBy {it.value}.filterValues {it.size>1}.keys
        val projected=projections.map {concept->if(concept.location.graphId in duplicates)
            concept.copy(members=concept.members.map {it.copy(blocked=BacklinkProblem.DUPLICATE)}) else concept}
        duplicates.forEach {issues+=BacklinkIssue(BacklinkProblem.DUPLICATE)}
        if(limited) issues+=BacklinkIssue(BacklinkProblem.LIMIT)
        val names=linkedMapOf<String,LinkedHashSet<String>>()
        for(concept in projected) for(row in concept.members) {
            val values=names.getOrPut(row.target.documentId) {linkedSetOf()}
            if(values.size<8 && row.sourceFileName.isNotBlank()) values+=row.sourceFileName.take(BacklinkBudget.LABEL_CHARS)
        }
        catalog=names.map {LocalNetworkFile(it.key,it.value.toList())}
        retireFilterWork()
        val token=projectionGeneration;val controlVersion=actionGeneration;val selection=filter
        val observationVersion=MapDocuments.observationVersion()
        fun accepted()=current() && querying && projectionGeneration==token && actionGeneration==controlVersion &&
            MapDocuments.observationVersion()==observationVersion && live.all {it.current()}
        fun commit(facts:NetworkFileFacts) {
            if(!accepted()) return
            val selected=NetworkFilterSelector.select(projected,selection,facts)
            report=LocalNetworkAssembler.assemble(query,selected.concepts,(issues+facts.issues).distinct(),identities.size,
                complete=!limited && issues.isEmpty() && facts.issues.isEmpty() && scan.omittedIssues==0 && selected.unknown.isEmpty(),
                omittedIssues=scan.omittedIssues,excludedMaps=scan.excludedMaps,limited=limited,budget=budget,
                issuer=issuer,generation=controlVersion).copy(filterActive=selection.active,
                matchedFiles=if(selection.active) selected.matched else emptySet(),unknownFilterFiles=selected.unknown,
                filteredConcepts=selected.filteredConcepts)
        }
        val ids=names.keys.filterTo(linkedSetOf()) {selection.fileIds?.contains(it)!=false}
        if(selection.kinds==null && selection.folder==null || ids.isEmpty() || selection.kinds?.isEmpty()==true) {
            commit(NetworkFileFacts(emptyMap()));return
        }
        // Only immutable rows and scalar discovery facts cross IO. No native source or PDF is opened.
        val observedMaps=scan.graphs.mapNotNull {graph->graph.graphId?.let {id->
            BacklinkLocation(graph.candidate.uri,graph.candidate.aliases.toSet(),graph.candidate.key,id,graph.candidate.name)
        }}+ready.mapNotNull {(observation,_)->observation.document?.let {doc->
            val candidate=candidates[observation.key]
            BacklinkLocation(observation.uri,candidate?.aliases?.toSet() ?: observation.knownAliases,
                observation.key,doc.id,candidate?.name ?: observation.uri.lastPathSegment.orEmpty())
        }}
        filtering=true
        val pending=workScope.launch(start=CoroutineStart.LAZY) {
            try {
                val facts=NetworkFileFactsReader(app).read(ids,selection.folder,observedMaps)
                commit(facts)
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:Exception) {
                if(accepted()) commit(NetworkFileFacts(ids.associateWith {NetworkFileFact(null,problem=BacklinkProblem.UNAVAILABLE)},
                    listOf(BacklinkIssue(BacklinkProblem.UNAVAILABLE))))
            } finally {if(projectionGeneration==token) {filtering=false;filterWork=null}}
        }
        filterWork=pending;pending.start()
    }
    private fun retireFilterWork() {projectionGeneration++;filterWork?.cancel();filterWork=null;filtering=false}
    fun applyFilter(next:NetworkFilter,expectedRevision:Long=filterRevision):Boolean {
        if(!current() || actionGeneration!=expectedRevision) return false
        if(filter==next) return true
        filter=next;actionGeneration++;problem=null
        if(!querying || stored==null || library==null) {querying=true;startScan()}
        else publish(MapDocuments.observations(app))
        return true
    }
    private fun sameMembership(a:MapBacklinkMembership?,b:MapBacklinkMembership?):Boolean = when {
        a==null || b==null->a==null && b==null
        else->a.issuer===b.issuer && a.documentId==b.documentId && a.source==b.source
    }
    private fun evidenceCurrent(evidence:LocalNetworkEvidence):Boolean {
        if(!evidence.issuedBy(issuer,actionGeneration)) return false
        val row=evidence.row
        return report?.concepts?.any {concept->concept.members.any {latest->latest.row.let {
            it.key==row.key && it.target==row.target && it.blocked==null && sameMembership(it.membership,row.membership)
        }}}==true
    }
    fun captureFocus(anchor:LocalNetworkEvidence,target:LocalNetworkEvidence):MapNetworkOpenRequest? {
        if(!current() || !querying || loading || filtering || stored==null || !evidenceCurrent(anchor) || !evidenceCurrent(target)) {
            problem=BacklinkProblem.CHANGED;return null
        }
        val generation=actionGeneration
        // Once accepted, this stored request may create its own actor. Its live overlay is not a new query.
        return MapNetworkOpenRequest.capture(query,anchor.row,target.row,issuer) {
            current() && generation==actionGeneration &&
                listOf(anchor.row,target.row).all {row->row.membership?.let {
                    it.current(it.issuer,row.location.graphId,row.nodeId,row.sourceId,row.target)
                }!=false}
        }
    }
    fun cancel() {
        actionGeneration++;querying=false;scanGeneration++
        retireFilterWork()
        scanWork?.cancel();scanWork=null;loading=false;problem=BacklinkProblem.CANCELLED
    }
    fun close() {
        if(!active) return
        active=false;actionGeneration++;querying=false;scanGeneration++
        retireFilterWork()
        owner.cancel();scanWork=null;observing=null;stored=null;library=null
    }
}
