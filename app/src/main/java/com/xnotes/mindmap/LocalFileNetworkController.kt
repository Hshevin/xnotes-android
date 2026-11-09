package com.xnotes.mindmap

import android.content.Context
import com.xnotes.storage.DocumentRegistry
import com.xnotes.storage.NotebookStorage
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext

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
    private var registered=DocumentRegistry.FileObservations(emptyMap(),false)
    private var registryProblem:BacklinkProblem?=null
    private var projectionGeneration=0L
    private var filterWork:Job?=null
    var filter by mutableStateOf(NetworkFilter());private set
    var expandedFiles by mutableStateOf<Set<String>>(emptySet());private set
    var browseMode by mutableStateOf(if(query.hasLocalFocus) NetworkBrowseMode.LOCAL else NetworkBrowseMode.LIBRARY);private set
    var centreFiles by mutableStateOf<Set<String>>(if(query.hasLocalFocus) setOf(query.focusId) else emptySet());private set
    var fileChoices by mutableStateOf<List<LocalNetworkFile>>(emptyList());private set
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
        val live=MapDocuments.observations(app);val expanded=expandedFiles;val mode=browseMode;val centres=centreFiles
        val reader=MapBacklinkLibrary(app,query.roots,budget.read)
        scanWork=workScope.launch {
            try {
                val result=when(mode) {
                    NetworkBrowseMode.LOCAL->reader.scanExpandedNetwork(query.focusId,expanded,live,budget)
                    NetworkBrowseMode.FILES->reader.scanSelectedNetwork(centres,expanded,live,budget)
                    NetworkBrowseMode.LIBRARY->reader.scanLibraryNetwork(live,budget)
                }
                var registryFailure:BacklinkProblem?=null
                val known=if(mode==NetworkBrowseMode.LIBRARY) try {withContext(Dispatchers.IO) {
                    val coroutine=currentCoroutineContext()
                    NotebookStorage.get(app).registry.knownAvailableFiles(checkActive={coroutine.ensureActive()})
                }} catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
                catch(_:Exception) {registryFailure=BacklinkProblem.UNAVAILABLE;DocumentRegistry.FileObservations(emptyMap(),false)} else null
                if(!current() || request!=scanGeneration) return@launch
                if(MapDocuments.observationVersion()!=version) {startScan();return@launch}
                stored=result;library=reader;problem=null
                if(known!=null) {registered=known;registryProblem=registryFailure}
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
        val mode=browseMode
        var limited=scan.limited || mode==NetworkBrowseMode.LIBRARY && registered.limited
        if(mode==NetworkBrowseMode.LIBRARY) registryProblem?.let {issues+=BacklinkIssue(it)}
        var direct=0;var deeper=0
        fun append(items:List<NetworkConceptProjection>) {
            for(concept in items) {
                val isDirect=when(mode) {
                    NetworkBrowseMode.LOCAL->concept.members.any {it.target.documentId==query.focusId}
                    NetworkBrowseMode.FILES->concept.key.graphId in centreFiles || concept.members.any {it.target.documentId in centreFiles}
                    NetworkBrowseMode.LIBRARY->true
                }
                val used=if(isDirect) direct else deeper
                if(concept.members.size>budget.evidenceLimit-used) {limited=true;continue}
                projections+=concept
                if(isDirect) direct+=concept.members.size else deeper+=concept.members.size
            }
        }
        for(graph in scan.graphs) if(graph.candidate.key !in actors) {
            graph.graphId?.let {identities[graph.candidate.key]=it}
            val blocked=graph.graphId?.let(scan.reservedIds::get)
            append(if(blocked==null) graph.concepts else graph.concepts.map {concept->
                concept.copy(members=concept.members.map {it.copy(blocked=blocked)})
            })
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
        for((observation,blocked) in ready) {
            owner.ensureActive()
            val doc=observation.document ?: continue
            val candidate=candidates[observation.key]
            val location=BacklinkLocation(observation.uri,candidate?.aliases ?: setOf(observation.uri.toString()),
                observation.key,doc.id,candidate?.name ?: observation.uri.lastPathSegment.orEmpty().take(BacklinkBudget.LABEL_CHARS))
            val member:(String,String,MapSourceTarget)->MapBacklinkMembership?={node,id,target->observation.captureMembership(node,id,target)}
            val projected=when(mode) {
                NetworkBrowseMode.LOCAL->reader.projectExpandedNetwork(doc,query.focusId,expandedFiles,location,BacklinkOrigin.LIVE,
                    observation.session.conflict,blocked,member,budget) {owner.ensureActive()}
                NetworkBrowseMode.FILES->reader.projectSelectedNetwork(doc,centreFiles,expandedFiles,location,BacklinkOrigin.LIVE,
                    observation.session.conflict,blocked,member,budget) {owner.ensureActive()}
                NetworkBrowseMode.LIBRARY->reader.projectLibraryNetwork(doc,location,BacklinkOrigin.LIVE,
                    observation.session.conflict,blocked,member,budget) {owner.ensureActive()}
            }
            append(projected.concepts.map {concept->concept.copy(members=concept.members.map {row->
                if(row.membership==null && row.blocked==null) row.copy(blocked=BacklinkProblem.CHANGED) else row
            })})
            if(projected.limited) limited=true
        }
        val duplicates=identities.entries.groupBy {it.value}.filterValues {it.size>1}.keys
        val projected=projections.map {concept->if(concept.location.graphId in duplicates)
            concept.copy(members=concept.members.map {it.copy(blocked=BacklinkProblem.DUPLICATE)}) else concept}
        duplicates.forEach {issues+=BacklinkIssue(BacklinkProblem.DUPLICATE)}
        if(limited) issues+=BacklinkIssue(BacklinkProblem.LIMIT)
        val expansion=when(mode) {
            NetworkBrowseMode.LOCAL->NetworkExpansion.select(query.focusId,expandedFiles,projected)
            NetworkBrowseMode.FILES->NetworkExpansion.selectRoots(centreFiles,expandedFiles,projected)
            NetworkBrowseMode.LIBRARY->{
                val valid=projected.filter {canonical(it.key.graphId)}
                if(valid.size!=projected.size) issues+=BacklinkIssue(BacklinkProblem.IDENTITY)
                NetworkExpansionResult(valid,valid.associate {it.key to NetworkConceptOrigin(it.key.graphId,NetworkOriginKind.OWNER,1)})
            }
        }
        val reachable=expansion.concepts
        val names=linkedMapOf<String,LinkedHashSet<String>>()
        for(concept in reachable) for(row in concept.members) {
            val values=names.getOrPut(row.target.documentId) {linkedSetOf()}
            if(values.size<8 && row.sourceFileName.isNotBlank()) values+=row.sourceFileName.take(BacklinkBudget.LABEL_CHARS)
        }
        catalog=names.map {LocalNetworkFile(it.key,it.value.toList())}
        val observedMaps=scan.graphs.mapNotNull {graph->graph.graphId?.let {id->
            BacklinkLocation(graph.candidate.uri,graph.candidate.aliases.toSet(),graph.candidate.key,id,graph.candidate.name)
        }}+ready.mapNotNull {(observation,_)->observation.document?.let {doc->
            val candidate=candidates[observation.key]
            BacklinkLocation(observation.uri,candidate?.aliases?.toSet() ?: observation.knownAliases,
                observation.key,doc.id,candidate?.name ?: observation.uri.lastPathSegment.orEmpty())
        }}
        val observedById=observedMaps.groupBy {it.graphId}
        val observedIdsAt=observedMaps.groupBy {it.key}.mapValues {(_,locations)->locations.mapTo(hashSetOf()) {it.graphId}}
        val extras=linkedMapOf<String,LinkedHashSet<String>>()
        val blockedOwners=projected.filter {it.members.any {row->row.blocked!=null}}.mapTo(hashSetOf()) {it.key.graphId}
        scan.graphs.filter {it.problem!=null}.mapNotNullTo(blockedOwners) {it.graphId}
        ready.filter {it.second!=null}.mapNotNullTo(blockedOwners) {it.first.document?.id}
        val unavailableChoices=blockedOwners.toMutableSet()
        if(mode==NetworkBrowseMode.LIBRARY) {
            for(location in observedMaps) {
                val scope=candidates[location.key]?.place
                if(canonical(location.graphId) && location.graphId !in blockedOwners && location.graphId !in duplicates &&
                    scope==BacklinkPlace.NORMAL && scan.liveBlocked[location.key]==null && location.graphId !in scan.reservedIds) {
                    extras.getOrPut(location.graphId) {linkedSetOf()}.add(location.title)
                }
            }
            if(!registered.limited) for((id,file) in registered.files) {
                owner.ensureActive()
                if(!canonical(id)) {issues+=BacklinkIssue(BacklinkProblem.IDENTITY,file.name);continue}
                val locations=observedById[id].orEmpty()
                val reusedPosition=file.locations.any {position->position.available &&
                    observedIdsAt[MapSaves.key(android.net.Uri.parse(position.uri))]?.any {it!=id}==true}
                val mismatch=locations.isNotEmpty() && file.kind!="xmap" || reusedPosition
                val failure=if(mismatch || id in blockedOwners) BacklinkProblem.IDENTITY else reader.registeredFileProblem(file)
                if(failure!=null) {
                    issues+=BacklinkIssue(failure,file.name);extras.remove(id)
                    if(failure in setOf(BacklinkProblem.IDENTITY,BacklinkProblem.DUPLICATE,BacklinkProblem.LIMIT)) unavailableChoices+=id
                    continue
                }
                if(id !in scan.reservedIds) extras.getOrPut(id) {linkedSetOf()}.apply {file.name?.takeIf {it.isNotBlank()}?.let {add(it.take(BacklinkBudget.LABEL_CHARS))}}
            }
            val choices=linkedMapOf<String,LinkedHashSet<String>>()
            for((id,values) in names.toList()+extras.toList()) if(canonical(id) && id !in unavailableChoices) {
                val old=choices.getOrPut(id) {linkedSetOf()}
                old.addAll(values.take((8-old.size).coerceAtLeast(0)))
            }
            if(choices.size>NetworkFilter.MAX_FILES) {limited=true;issues+=BacklinkIssue(BacklinkProblem.LIMIT)}
            fileChoices=choices.entries.take(NetworkFilter.MAX_FILES).map {LocalNetworkFile(it.key,it.value.toList())}
        }
        val extraFiles=extras.map {LocalNetworkFile(it.key,it.value.take(8))}
        retireFilterWork()
        val token=projectionGeneration;val controlVersion=actionGeneration;val selection=filter
        val observationVersion=MapDocuments.observationVersion()
        fun accepted()=current() && querying && projectionGeneration==token && actionGeneration==controlVersion &&
            MapDocuments.observationVersion()==observationVersion && live.all {it.current()}
        fun commit(facts:NetworkFileFacts) {
            if(!accepted()) return
            val selected=NetworkFilterSelector.select(reachable,selection,facts)
            val extraSelection=NetworkFilterSelector.selectFiles(extras.keys,selection,facts)
            val unknown=selected.unknown+extraSelection.second
            val roots=when(mode) {
                NetworkBrowseMode.LOCAL->null
                NetworkBrowseMode.FILES->centreFiles.map {id->fileChoices.firstOrNull {it.documentId==id} ?: LocalNetworkFile(id,emptyList())}
                NetworkBrowseMode.LIBRARY->emptyList()
            }
            val matches=selected.concepts.mapTo(hashSetOf()) {it.key}
            val context=selected.concepts.flatMap {expansion.origins[it.key]?.path.orEmpty()}.mapTo(linkedSetOf()) {
                NetworkConceptKey(it.location.key,it.location.graphId,it.nodeId)
            }.apply {removeAll(matches)}
            val retained=reachable.filter {it.key in matches || it.key in context}
            val assembled=LocalNetworkAssembler.assemble(query,retained,(issues+facts.issues).distinct(),identities.size,
                complete=!limited && issues.isEmpty() && facts.issues.isEmpty() && scan.omittedIssues==0 && unknown.isEmpty(),
                omittedIssues=scan.omittedIssues,excludedMaps=scan.excludedMaps,limited=limited,budget=budget,
                issuer=issuer,generation=controlVersion,origins=expansion.origins,rootFiles=roots,
                extraFiles=extraFiles.filter {it.documentId in extraSelection.first})
            report=assembled.copy(filterActive=selection.active,
                matchedFiles=if(selection.active) selected.matched+extraSelection.first else emptySet(),unknownFilterFiles=unknown,
                filteredConcepts=selected.filteredConcepts,
                contextConcepts=assembled.concepts.mapTo(hashSetOf()) {it.key}.intersect(context),browseMode=mode,centreFiles=centreFiles)
        }
        val allIds=(names.keys+extras.keys).filterTo(linkedSetOf()) {selection.fileIds?.contains(it)!=false}
        if(allIds.size>NetworkFilter.MAX_FILES) {limited=true;issues+=BacklinkIssue(BacklinkProblem.LIMIT)}
        val ids=allIds.take(NetworkFilter.MAX_FILES).toSet()
        if(selection.kinds==null && selection.folder==null || ids.isEmpty() || selection.kinds?.isEmpty()==true) {
            commit(NetworkFileFacts(emptyMap()));return
        }
        // Only immutable rows and scalar discovery facts cross IO. No native source or PDF is opened.
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
    /** Controls are runtime query state. They neither mutate graphs nor share content history. */
    fun setExpanded(fileId:String,value:Boolean,expectedRevision:Long=filterRevision):Boolean {
        if(!current() || !querying || loading || filtering || stored==null || actionGeneration!=expectedRevision ||
            browseMode==NetworkBrowseMode.LIBRARY || fileId in centreFiles) return false
        if(value && report?.files?.none {it.documentId==fileId}!=false) return false
        if((fileId in expandedFiles)==value) return true
        if(value && expandedFiles.size>=250) {problem=BacklinkProblem.LIMIT;return false}
        val next=expandedFiles.toMutableSet().apply {if(value) add(fileId) else remove(fileId)}
        expandedFiles=java.util.Collections.unmodifiableSet(next)
        actionGeneration++;problem=null;startScan();return true
    }
    fun collapseAll(expectedRevision:Long=filterRevision):Boolean {
        if(!current() || !querying || loading || filtering || stored==null || actionGeneration!=expectedRevision ||
            browseMode==NetworkBrowseMode.LIBRARY) return false
        if(expandedFiles.isEmpty()) return true
        expandedFiles=emptySet();actionGeneration++;problem=null;startScan();return true
    }
    fun showLibrary(expectedRevision:Long=filterRevision)=changeScope(NetworkBrowseMode.LIBRARY,emptySet(),expectedRevision)
    fun showCollection(ids:Set<String>,expectedRevision:Long=filterRevision):Boolean {
        if(ids.isEmpty() || ids.size>250 || ids.any {id->fileChoices.none {it.documentId==id}}) return false
        return changeScope(NetworkBrowseMode.FILES,ids,expectedRevision)
    }
    fun showLocal(expectedRevision:Long=filterRevision):Boolean {
        if(!query.hasLocalFocus) return false
        return changeScope(NetworkBrowseMode.LOCAL,setOf(query.focusId),expectedRevision)
    }
    private fun changeScope(mode:NetworkBrowseMode,centres:Set<String>,expectedRevision:Long):Boolean {
        if(!current() || !querying || loading || filtering || stored==null || actionGeneration!=expectedRevision) return false
        if(browseMode==mode && centreFiles==centres) return true
        browseMode=mode;centreFiles=java.util.Collections.unmodifiableSet(LinkedHashSet(centres))
        expandedFiles=emptySet();actionGeneration++;problem=null;startScan();return true
    }
    private fun canonical(id:String)=runCatching {java.util.UUID.fromString(id).toString()==id}.getOrDefault(false)
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
        if(!current() || !querying || loading || filtering || stored==null || !evidenceCurrent(target)) {
            problem=BacklinkProblem.CHANGED;return null
        }
        val generation=actionGeneration
        // Once accepted, this stored request may create its own actor. Its live overlay is not a new query.
        val concept=report?.concepts?.singleOrNull {it.key==target.conceptKey} ?: return null
        val origin=concept.origin ?: return null
        val localAnchor=if(origin.kind==NetworkOriginKind.OWNER) target else anchor
        if(!evidenceCurrent(localAnchor)) {problem=BacklinkProblem.CHANGED;return null}
        return MapNetworkOpenRequest.captureExpanded(query,origin,localAnchor.row,target.row,issuer) {
            current() && generation==actionGeneration &&
                listOf(localAnchor.row,target.row).all {row->row.membership?.let {
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
