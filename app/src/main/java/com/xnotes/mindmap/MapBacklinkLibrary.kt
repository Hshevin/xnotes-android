package com.xnotes.mindmap

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.xnotes.core.util.DocKeys
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.DocumentRegistry
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal class BacklinkLimit:IOException()
internal enum class BacklinkPlace { NORMAL,RESERVED,TRASH,UNKNOWN }
internal class BacklinkCandidate(val key:String,val aliases:LinkedHashSet<String>,var name:String,
    var inVisibleTree:Boolean=false,val registeredIds:MutableSet<String> = linkedSetOf()) {
    var place=BacklinkPlace.UNKNOWN
    val uri:Uri get()=Uri.parse(aliases.first())
}
internal data class BacklinkStoredGraph(val candidate:BacklinkCandidate,val graphId:String?,val rows:List<MapBacklinkRow>,
    val keys:Set<String> = emptySet(),val problem:BacklinkProblem?=null,val fileRelations:List<MapFileRelationRow> = emptyList())
internal data class BacklinkLibraryScan(val candidates:List<BacklinkCandidate>,val graphs:List<BacklinkStoredGraph>,
    val issues:List<BacklinkIssue>,val omittedIssues:Int,val excludedMaps:Int,val reservedIds:Map<String,BacklinkProblem>,val limited:Boolean,
    val liveBlocked:Map<String,BacklinkProblem> = emptyMap())

/** Owned, cancellable read observation. No graph lease, writer baseline or native/PDF decode. */
internal class MapBacklinkLibrary(private val app:Context,private val roots:List<String>,private val budget:BacklinkBudget=BacklinkBudget()) {
    private data class Projection<T>(val value:T,val limited:Boolean=false)
    private data class BacklinkProjection(val rows:List<MapBacklinkRow>,val fileRelations:List<MapFileRelationRow>)
    private data class ReadGraph<T>(val candidate:BacklinkCandidate,val graphId:String?,val value:T?,
        val keys:Set<String> = emptySet(),val limited:Boolean=false,val problem:BacklinkProblem?=null)
    private data class LibraryScan<T>(val candidates:List<BacklinkCandidate>,val graphs:List<ReadGraph<T>>,
        val issues:List<BacklinkIssue>,val omittedIssues:Int,val excludedMaps:Int,
        val reservedIds:Map<String,BacklinkProblem>,val limited:Boolean,val liveBlocked:Map<String,BacklinkProblem>)
    private val resolver=app.contentResolver
    private val candidates=linkedMapOf<String,BacklinkCandidate>()
    private val issues=mutableListOf<BacklinkIssue>()
    private var omitted=0
    private var directories=0
    private var entries=0L
    private var bytes=0L
    private var rows=0
    private var limited=false
    private var exhausted=false
    internal var projectionLimited=false;private set
    private val reservedRoots=linkedMapOf<String,Uri>()
    private val reservedIds=linkedMapOf<String,BacklinkProblem>()
    private val appAuthority=app.packageName+".documents"
    private var observedRoots:List<String> = roots
    private fun issue(problem:BacklinkProblem,name:String?=null) {
        if(problem==BacklinkProblem.LIMIT) limited=true
        if(issues.size<budget.maxIssues) issues+=BacklinkIssue(problem,name?.take(BacklinkBudget.LABEL_CHARS)) else omitted++
    }
    private fun key(uri:Uri)=MapSaves.key(uri)
    private fun check() {if(exhausted) throw BacklinkLimit()}
    private fun account(count:Int) {
        if(count>0) bytes+=count
        if(bytes>budget.maxBytes) {limited=true;exhausted=true;throw BacklinkLimit()}
    }
    private fun add(uri:Uri,name:String,visible:Boolean=false,id:String?=null) {
        if(uri.toString().length>8192 || name.length>8192) {issue(BacklinkProblem.LIMIT);return}
        val canonical=key(uri)
        val found=candidates[canonical] ?: run {
            if(candidates.size>=budget.maxMaps) {limited=true;throw BacklinkLimit()}
            BacklinkCandidate(canonical,linkedSetOf(uri.toString()),name.take(BacklinkBudget.LABEL_CHARS)).also {candidates[canonical]=it}
        }
        if(found.aliases.size<32) found.aliases+=uri.toString() else issue(BacklinkProblem.LIMIT,name)
        if(visible && name.isNotBlank()) found.name=name.take(BacklinkBudget.LABEL_CHARS)
        found.inVisibleTree=found.inVisibleTree || visible
        id?.let {found.registeredIds+=it}
    }
    private suspend fun query(uri:Uri,columns:Array<String>):Cursor? = suspendCancellableCoroutine {waiter->
        val signal=CancellationSignal()
        waiter.invokeOnCancellation {signal.cancel()}
        try {
            val cursor=resolver.query(uri,columns,null,null,null,signal)
            waiter.resume(cursor) {_,value,_->value?.close()}
        } catch(error:Exception) {waiter.resumeWith(Result.failure(error))}
    }
    private suspend fun documentName(uri:Uri):String? = query(uri,arrayOf(OpenableColumns.DISPLAY_NAME))?.use {
        if(it.moveToFirst()) it.getString(0) else null
    }
    private fun reservedName(name:String)=name==".xnote"
    private fun relativePlace(path:String):BacklinkPlace {
        val parts=path.split('/')
        val reserved=parts.indexOfFirst(::reservedName)
        return if(reserved<0) BacklinkPlace.NORMAL else if(parts.drop(reserved+1).contains("trash")) BacklinkPlace.TRASH else BacklinkPlace.RESERVED
    }
    /** Only providers whose IDs are paths, or actual app-owned paths, supply this proof. */
    private fun pathPlace(uri:Uri):BacklinkPlace? {
        if(uri.scheme=="file") {
            val path=File(requireNotNull(uri.path)).canonicalPath
            val external=AppStorageDocumentsProvider.recoveryRoot(app)?.canonicalPath
            val internal=File(app.filesDir,"Notes").canonicalPath
            val base=listOfNotNull(external,internal).firstOrNull {DocKeys.within(path,it)} ?: return null
            return relativePlace(path.removePrefix(base).trimStart('/'))
        }
        val id=runCatching {DocumentsContract.getDocumentId(uri)}.getOrNull() ?: return null
        if(id.split('/').any {it=="." || it==".."}) return null
        if(uri.authority==appAuthority && DocKeys.within(id,"root")) return relativePlace(id.removePrefix("root").trimStart('/'))
        if(uri.authority=="com.android.externalstorage.documents") {
            relativePlace(id).takeIf {it!=BacklinkPlace.NORMAL}?.let {return it}
            for(root in observedRoots) {
                val parsed=Uri.parse(root)
                if(parsed.authority!=uri.authority || !DocumentsContract.isTreeUri(parsed)) continue
                val top=runCatching {DocumentsContract.getTreeDocumentId(parsed)}.getOrNull() ?: continue
                if(DocKeys.within(id,top)) return relativePlace(id)
            }
        }
        return null
    }
    private fun classify(candidate:BacklinkCandidate,active:()->Unit={}):BacklinkPlace {
        candidate.aliases.mapNotNull {runCatching {pathPlace(Uri.parse(it))}.getOrNull()}.firstOrNull {
            it==BacklinkPlace.RESERVED || it==BacklinkPlace.TRASH
        }?.let {return it}
        var unknown=false
        for(alias in candidate.aliases) {
            active();val uri=Uri.parse(alias)
            for(parent in reservedRoots.values.filter {it.authority==uri.authority}) {
                active()
                try {
                    if(key(parent)==key(uri) || DocumentsContract.isChildDocument(resolver,parent,uri)) return BacklinkPlace.RESERVED
                } catch(_:Exception) {unknown=true}
            }
        }
        if(unknown) return BacklinkPlace.UNKNOWN
        if(candidate.inVisibleTree || candidate.aliases.any {runCatching {pathPlace(Uri.parse(it))==BacklinkPlace.NORMAL}.getOrDefault(false)})
            return BacklinkPlace.NORMAL
        return BacklinkPlace.UNKNOWN
    }
    private suspend fun walk(root:Uri,active:()->Unit,seen:MutableSet<String>) {
        val todo=ArrayDeque<String>().apply {add(DocumentsContract.getTreeDocumentId(root))}
        val queued=hashSetOf(key(DocumentsContract.buildDocumentUriUsingTree(root,todo.first())))
        while(todo.isNotEmpty()) {
            active();val id=todo.removeFirst()
            val parent=DocumentsContract.buildDocumentUriUsingTree(root,id)
            if(!seen.add(key(parent))) continue
            if(++directories>budget.maxDirectories) {limited=true;throw BacklinkLimit()}
            val isRoot=id==DocumentsContract.getTreeDocumentId(root)
            val rootName=if(isRoot) documentName(parent) else null
            val place=pathPlace(parent)
            if(rootName?.let(::reservedName)==true || place in setOf(BacklinkPlace.RESERVED,BacklinkPlace.TRASH)) {
                reservedRoots[key(parent)]=parent;continue
            }
            if(isRoot && rootName.isNullOrBlank() && place!=BacklinkPlace.NORMAL) {
                issue(BacklinkProblem.UNKNOWN_SCOPE);continue
            }
            val children=DocumentsContract.buildChildDocumentsUriUsingTree(root,id)
            val cursor=query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE))
                ?: throw IOException("Unreadable directory")
            cursor.use {c->while(c.moveToNext()) {
                active()
                // Non-map files and cyclic duplicate listings cannot consume unbounded CPU either.
                if(++entries>(budget.maxDirectories.toLong()+budget.maxMaps)*4) {limited=true;throw BacklinkLimit()}
                val childId=c.getString(0);val name=c.getString(1);val mime=c.getString(2)
                if(childId==null || name==null || mime==null) {issue(BacklinkProblem.CORRUPT);continue}
                if(childId.length>8192 || name.length>8192) {issue(BacklinkProblem.LIMIT);continue}
                val uri=DocumentsContract.buildDocumentUriUsingTree(root,childId)
                if(mime==DocumentsContract.Document.MIME_TYPE_DIR) {
                    if(reservedName(name)) reservedRoots[key(uri)]=uri else if(key(uri) !in seen && queued.add(key(uri))) {
                        if(todo.size+directories>=budget.maxDirectories) {limited=true;throw BacklinkLimit()}
                        todo.add(childId)
                    }
                } else if(name.endsWith(".xmap",ignoreCase=true)) add(uri,name,true)
            }}
        }
    }
    suspend fun scan(documentId:String,live:List<MapDocumentObservation> = emptyList()):BacklinkLibraryScan {
        val result=scanProjected(live) {doc,location,origin,conflict,blocked,active->
            val found=project(doc,documentId,location,origin,conflict,blocked)
            val relations=projectFileRelationsWithin(doc,location,origin,conflict,blocked,null,
                (budget.maxRows-rows).coerceAtLeast(0),active) {documentId in it.fileIds}
            rows+=relations.rows.size
            Projection(BacklinkProjection(found,relations.rows),projectionLimited || relations.limited)
        }
        return BacklinkLibraryScan(result.candidates,result.graphs.map {graph->
            BacklinkStoredGraph(graph.candidate,graph.graphId,graph.value?.rows.orEmpty(),graph.keys,graph.problem,
                graph.value?.fileRelations.orEmpty())
        },result.issues,result.omittedIssues,result.excludedMaps,result.reservedIds,result.limited,result.liveBlocked)
    }
    /** Content memberships and explicit file edges come from the same locked document observation. */
    suspend fun scanNetwork(focusId:String,live:List<MapDocumentObservation> = emptyList(),
        networkBudget:LocalNetworkBudget=LocalNetworkBudget(read=budget)):NetworkLibraryScan {
        var evidence=0
        val result=scanProjected(live) {doc,location,origin,conflict,blocked,active->
            val found=projectNetworkWithin(doc,focusId,location,origin,conflict,blocked,null,
                (networkBudget.evidenceLimit-evidence).coerceAtLeast(0),null,active)
            evidence+=found.concepts.sumOf {it.members.size}+found.fileRelations.size
            Projection(found,found.limited)
        }
        return networkScan(result)
    }
    /** Direct and deeper candidates share evidence by kind, but retain separate discovery allowances. */
    suspend fun scanExpandedNetwork(focusId:String,expanded:Set<String>,live:List<MapDocumentObservation> = emptyList(),
        networkBudget:LocalNetworkBudget=LocalNetworkBudget(read=budget)):NetworkLibraryScan {
        val files=expanded.toSet();var direct=0;var deeper=0
        val result=scanProjected(live,continueAfterProjectionLimit=true) {doc,location,origin,conflict,blocked,active->
            val found=projectExpandedNetworkWithin(doc,focusId,files,location,origin,conflict,blocked,null,
                (networkBudget.evidenceLimit-direct).coerceAtLeast(0),(networkBudget.evidenceLimit-deeper).coerceAtLeast(0),null,active)
            for(concept in found.concepts) {
                if(concept.members.any {it.target.documentId==focusId}) direct+=concept.members.size else deeper+=concept.members.size
            }
            for(relation in found.fileRelations) if(focusId in relation.fileIds) direct++ else deeper++
            Projection(found,found.limited)
        }
        return networkScan(result)
    }
    /** Selected real roots are matched before either evidence kind is charged. */
    suspend fun scanSelectedNetwork(roots:Set<String>,expanded:Set<String>,live:List<MapDocumentObservation> = emptyList(),
        networkBudget:LocalNetworkBudget=LocalNetworkBudget(read=budget)):NetworkLibraryScan {
        val selected=roots.toSet();val files=expanded.toSet();var direct=0;var deeper=0
        val result=scanProjected(live,continueAfterProjectionLimit=true,checkGraphIdentity=true) {doc,location,origin,conflict,blocked,active->
            val found=projectSelectedNetworkWithin(doc,selected,files,location,origin,conflict,blocked,null,
                (networkBudget.evidenceLimit-direct).coerceAtLeast(0),(networkBudget.evidenceLimit-deeper).coerceAtLeast(0),null,active)
            for(concept in found.concepts) {
                if(concept.location.graphId in selected || concept.members.any {it.target.documentId in selected}) direct+=concept.members.size
                else deeper+=concept.members.size
            }
            for(relation in found.fileRelations) {
                if(relation.fileIds.any {it in selected}) direct++ else deeper++
            }
            Projection(found,found.limited)
        }
        return networkScan(result)
    }
    suspend fun scanLibraryNetwork(live:List<MapDocumentObservation> = emptyList(),
        networkBudget:LocalNetworkBudget=LocalNetworkBudget(read=budget)):NetworkLibraryScan {
        var evidence=0
        val result=scanProjected(live,checkGraphIdentity=true) {doc,location,origin,conflict,blocked,active->
            val found=projectLibraryNetworkWithin(doc,location,origin,conflict,blocked,null,
                (networkBudget.evidenceLimit-evidence).coerceAtLeast(0),null,active)
            evidence+=found.concepts.sumOf {it.members.size}+found.fileRelations.size
            Projection(found,found.limited)
        }
        return networkScan(result)
    }
    private fun networkScan(result:LibraryScan<NetworkProjection>)=NetworkLibraryScan(result.candidates,
        result.graphs.map {NetworkStoredGraph(it.candidate,it.graphId,it.value?.concepts.orEmpty(),it.keys,it.problem,
            it.value?.fileRelations.orEmpty())},result.issues,result.omittedIssues,result.excludedMaps,
        result.reservedIds,result.limited,result.liveBlocked)
    private fun graphIdentityProblem(id:String,uri:Uri):BacklinkProblem? {
        val registry=NotebookStorage.get(app).registry;val kind=registry.kindForId(id)
        val here=registry.expectationAt(uri.toString())
        if(kind!=null && kind!="xmap" || registry.expectationForId(id).requiresPages ||
            here!=null && (here.documentId!=id || here.requiresPages)) return BacklinkProblem.IDENTITY
        val known=registry.knownMapLocations(33,id).filter {it.available}.map {key(Uri.parse(it.uri))}.distinct()
        return when {known.size>32->BacklinkProblem.LIMIT;known.size>1->BacklinkProblem.DUPLICATE;else->null}
    }
    /** Registered orphan locations are last-known hints; reserved/ambiguous positions are not usable orphans. */
    internal fun registeredFileProblem(file:DocumentRegistry.ObservedFile):BacklinkProblem? {
        val available=file.locations.filter {it.available}.distinctBy {key(Uri.parse(it.uri))}
        if(available.size>32) return BacklinkProblem.LIMIT
        if(available.size>1) return BacklinkProblem.DUPLICATE
        if(available.isEmpty()) return BacklinkProblem.UNAVAILABLE
        return when(runCatching {pathPlace(Uri.parse(available.single().uri))}.getOrNull()) {
            BacklinkPlace.NORMAL->null;BacklinkPlace.TRASH->BacklinkProblem.TRASHED;else->BacklinkProblem.UNKNOWN_SCOPE
        }
    }
    private suspend fun <T> scanProjected(live:List<MapDocumentObservation>,continueAfterProjectionLimit:Boolean=false,
        checkGraphIdentity:Boolean=false,
        projector:(MindMapDocument,BacklinkLocation,BacklinkOrigin,Boolean,BacklinkProblem?,()->Unit)->Projection<T>):LibraryScan<T> = withContext(Dispatchers.IO) {
        candidates.clear();issues.clear();reservedRoots.clear();reservedIds.clear()
        omitted=0;directories=0;entries=0;bytes=0;rows=0;limited=false;exhausted=false;projectionLimited=false
        val coroutine=currentCoroutineContext();val active={coroutine.ensureActive();check()}
        val allRoots=linkedSetOf<String>().apply {addAll(roots)}
        try {
            resolver.persistedUriPermissions.filter {it.isReadPermission}.forEach {permission->
                active();val uri=permission.uri
                if(DocumentsContract.isTreeUri(uri)) allRoots+=uri.toString()
                else try {documentName(uri)?.takeIf {it.endsWith(".xmap",true)}?.let {add(uri,it)}}
                    catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
                    catch(limit:BacklinkLimit) {throw limit}
                    catch(_:Exception) {issue(BacklinkProblem.UNAVAILABLE)}
            }
            try {NotebookStorage.get(app).registry.knownMapLocations((budget.maxMaps.toLong()+1).coerceAtMost(4097).toInt()).forEach {location->
                active();add(Uri.parse(location.uri),location.name,id=location.id)
            }} catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(limit:BacklinkLimit) {throw limit}
            catch(_:Exception) {issue(BacklinkProblem.UNAVAILABLE)}
            live.forEach {snapshot->
                active();add(snapshot.uri,snapshot.uri.lastPathSegment.orEmpty())
                snapshot.knownAliases.forEach {alias->val uri=Uri.parse(alias)
                    if(uri.scheme in setOf("file","content") && key(uri)==snapshot.key) add(uri,snapshot.uri.lastPathSegment.orEmpty())}
            }
            observedRoots=allRoots.toList()
            val seen=hashSetOf<String>()
            for(root in allRoots) {
                active()
                try {walk(Uri.parse(root),active,seen)}
                catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
                catch(limit:BacklinkLimit) {throw limit}
                catch(_:Exception) {issue(BacklinkProblem.UNAVAILABLE)}
            }
        } catch(_:BacklinkLimit) {limited=true;issue(BacklinkProblem.LIMIT)}
        var excluded=0
        candidates.values.forEach {candidate->
            coroutine.ensureActive();candidate.place=classify(candidate) {coroutine.ensureActive()}
            if(candidate.place!=BacklinkPlace.NORMAL) {
                val problem=if(candidate.place==BacklinkPlace.TRASH) BacklinkProblem.TRASHED else BacklinkProblem.UNKNOWN_SCOPE
                candidate.registeredIds.forEach {reservedIds[it]=problem}
                if(candidate.place in setOf(BacklinkPlace.RESERVED,BacklinkPlace.TRASH)) excluded++
                else issue(BacklinkProblem.UNKNOWN_SCOPE,candidate.name)
            }
        }
        val liveBlocked=linkedMapOf<String,BacklinkProblem>()
        for(snapshot in live) {
            coroutine.ensureActive()
            val candidate=candidates[snapshot.key]
            val scope=snapshot.document?.id?.let(reservedIds::get) ?: when(candidate?.place) {
                BacklinkPlace.NORMAL->null;BacklinkPlace.TRASH->BacklinkProblem.TRASHED;else->BacklinkProblem.UNKNOWN_SCOPE
            }
            val identity=if(checkGraphIdentity) snapshot.document?.id?.let {graphIdentityProblem(it,snapshot.uri)} else null
            if(scope!=null) liveBlocked[snapshot.key]=scope
            else if(identity!=null) liveBlocked[snapshot.key]=identity
            else if(candidate!=null && !snapshot.loading && !snapshot.creating && !snapshot.failed && snapshot.knownAliases.isNotEmpty()) MapSaves.lockFor(candidate.uri).withLock {
                coroutine.ensureActive()
                if(MapFiles.store(app,candidate.uri,checkActive={coroutine.ensureActive()},knownAliases=candidate.aliases).recoveryOutside(snapshot.knownAliases))
                    liveBlocked[snapshot.key]=BacklinkProblem.RECOVERY_CONFLICT
            }
        }
        val liveKeys=live.mapTo(hashSetOf()) {it.key}
        val graphs=mutableListOf<ReadGraph<T>>()
        for(candidate in candidates.values) {
            coroutine.ensureActive()
            if(candidate.key in liveKeys || candidate.place in setOf(BacklinkPlace.RESERVED,BacklinkPlace.TRASH)) continue
            if(exhausted) break
            try {
                var identity:BacklinkProblem?=null
                val graph=read(candidate,active) {doc,location,origin,conflict,blocked,check->
                    identity=if(checkGraphIdentity) graphIdentityProblem(doc.id,location.uri) else null
                    projector(doc,location,origin,conflict,blocked ?: identity,check)
                }
                graphs+=if(identity==null) graph else graph.copy(problem=identity)
                identity?.let {issue(it,candidate.name)}
                if(graph.limited) {issue(BacklinkProblem.LIMIT);if(!continueAfterProjectionLimit) break}
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:BacklinkLimit) {limited=true;issue(BacklinkProblem.LIMIT);break}
            catch(error:Exception) {
                val problem=if(error is MapRecoveryObservationException) BacklinkProblem.RECOVERY_CONFLICT else BacklinkProblem.CORRUPT
                issue(problem,candidate.name);graphs+=ReadGraph<T>(candidate,null,null,problem=problem)
            }
        }
        LibraryScan(candidates.values.toList(),graphs,issues.toList(),omitted,excluded,reservedIds.toMap(),limited,liveBlocked)
    }
    private suspend fun <T> read(candidate:BacklinkCandidate,active:()->Unit,
        projector:(MindMapDocument,BacklinkLocation,BacklinkOrigin,Boolean,BacklinkProblem?,()->Unit)->Projection<T>):ReadGraph<T> = MapSaves.lockFor(candidate.uri).withLock {
        active();var failure:Exception?=null
        for(alias in candidate.aliases) {
            active();val uri=Uri.parse(alias)
            val target=readTarget(uri,active,::account)
            try {
                val store=MapFiles.store(app,uri,target,active,candidate.aliases)
                val observed=store.observe(active,::account);active()
                val location=BacklinkLocation(uri,candidate.aliases,candidate.key,observed.document.id,candidate.name)
                val found=projector(observed.document,location,if(observed.recovered) BacklinkOrigin.RECOVERY else BacklinkOrigin.STORED,
                    observed.conflict,if(candidate.place==BacklinkPlace.UNKNOWN) BacklinkProblem.UNKNOWN_SCOPE else null,active)
                if(!observed.remoteAvailable) issue(BacklinkProblem.UNAVAILABLE,candidate.name)
                return@withLock ReadGraph(candidate,observed.document.id,found.value,store.recoveryKeys,found.limited)
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(limit:BacklinkLimit) {throw limit}
            catch(error:MapRecoveryObservationException) {throw error}
            catch(error:Exception) {failure=error}
        }
        throw failure ?: IOException("Unreadable graph")
    }
    private fun readTarget(uri:Uri,active:()->Unit,account:(Int)->Unit):MapTarget=object:MapTarget {
        override fun read():ByteArray {
            active()
            return (resolver.openInputStream(uri) ?: throw IOException("Unreadable graph")).use {input->
                MapCodec.readBytes(object:FilterInputStream(input) {
                    override fun read():Int {active();return super.read().also {if(it>=0) account(1)}}
                    override fun read(bytes:ByteArray,offset:Int,length:Int):Int {active();return `in`.read(bytes,offset,length).also {if(it>0) account(it)}}
                })
            }
        }
        override fun write(bytes:ByteArray):Unit=error("A backlink observation cannot write a graph")
    }
    internal fun project(doc:MindMapDocument,documentId:String,location:BacklinkLocation,origin:BacklinkOrigin,
        conflict:Boolean,blocked:BacklinkProblem?=null,membership:((String,String,MapSourceTarget)->MapBacklinkMembership?)?=null):List<MapBacklinkRow> {
        val out=mutableListOf<MapBacklinkRow>()
        loop@for(node in doc.nodes) if(node.fileCardId==null) for(source in node.sources) if(source.documentId==documentId) {
            if(rows>=budget.maxRows) {limited=true;projectionLimited=true;break@loop}
            rows++
            out+=projectMember(node,source,location,origin,conflict,blocked,membership)
        }
        return out
    }
    internal fun resetProjectionBudget() {rows=0;projectionLimited=false}

    internal fun projectFileRelations(doc:MindMapDocument,location:BacklinkLocation,origin:BacklinkOrigin,
        conflict:Boolean,blocked:BacklinkProblem?=null,membership:((String)->MapFileRelationMembership?)?=null,
        maxEvidence:Int=budget.maxRows,fileIds:Set<String>?=null,checkActive:()->Unit={}):FileRelationProjection =
        projectFileRelationsWithin(doc,location,origin,conflict,blocked,membership,maxEvidence,checkActive) {row->
            fileIds==null || row.fileIds.any {it in fileIds}
        }

    private fun projectFileRelationsWithin(doc:MindMapDocument,location:BacklinkLocation,origin:BacklinkOrigin,
        conflict:Boolean,blocked:BacklinkProblem?,membership:((String)->MapFileRelationMembership?)?,maxEvidence:Int,
        checkActive:()->Unit,include:(MapFileRelationRow)->Boolean):FileRelationProjection {
        val out=mutableListOf<MapFileRelationRow>();var hitLimit=false
        visitFileRelations(doc,checkActive) {relation,from,to->
            val row=fileRelationRow(relation,from,to,location,origin,conflict,blocked)
            if(include(row)) {
                if(out.size>=maxEvidence) hitLimit=true
                else out+=row.copy(membership=membership?.invoke(relation.id))
            }
        }
        return FileRelationProjection(out,hitLimit)
    }

    /** Only explicit fileCardId nodes can be endpoints. An ordinary REFERENCE is still a concept. */
    private fun visitFileRelations(doc:MindMapDocument,checkActive:()->Unit,
        visit:(MapRelation,MapNode,MapNode)->Unit) {
        val cards=linkedMapOf<String,MapNode>()
        for(node in doc.nodes) {
            checkActive()
            if(node.canonicalFileCardSource()!=null) cards[node.id]=node
        }
        for(relation in doc.relations) {
            checkActive()
            val from=cards[relation.from] ?: continue;val to=cards[relation.to] ?: continue
            visit(relation,from,to)
        }
    }
    private fun fileRelationRow(relation:MapRelation,from:MapNode,to:MapNode,location:BacklinkLocation,
        origin:BacklinkOrigin,conflict:Boolean,blocked:BacklinkProblem?)=MapFileRelationRow(location,relation,
        requireNotNull(from.fileCardId),requireNotNull(to.fileCardId),
        from.text.ifBlank {from.sources.single().fileName}.take(BacklinkBudget.LABEL_CHARS),
        to.text.ifBlank {to.sources.single().fileName}.take(BacklinkBudget.LABEL_CHARS),origin,conflict,blocked)

    internal fun projectNetwork(doc:MindMapDocument,focusId:String,location:BacklinkLocation,origin:BacklinkOrigin,
        conflict:Boolean,blocked:BacklinkProblem?=null,membership:((String,String,MapSourceTarget)->MapBacklinkMembership?)?=null,
        networkBudget:LocalNetworkBudget=LocalNetworkBudget(),fileMembership:((String)->MapFileRelationMembership?)?=null,
        checkActive:()->Unit={}):NetworkProjection =
        projectNetworkWithin(doc,focusId,location,origin,conflict,blocked,membership,networkBudget.evidenceLimit,fileMembership,checkActive)

    private fun projectNetworkWithin(doc:MindMapDocument,focusId:String,location:BacklinkLocation,origin:BacklinkOrigin,
        conflict:Boolean,blocked:BacklinkProblem?,membership:((String,String,MapSourceTarget)->MapBacklinkMembership?)?,
        maxEvidence:Int,fileMembership:((String)->MapFileRelationMembership?)?,checkActive:()->Unit):NetworkProjection =
        projectNetworkDocument(doc,location,origin,conflict,blocked,membership,fileMembership,maxEvidence,0,checkActive,
            conceptDepth={node->if(node.sources.any {checkActive();it.documentId==focusId}) 1 else 0},
            relationDepth={row->if(focusId in row.fileIds) 1 else 0})

    internal fun projectExpandedNetwork(doc:MindMapDocument,focusId:String,expanded:Set<String>,location:BacklinkLocation,
        origin:BacklinkOrigin,conflict:Boolean,blocked:BacklinkProblem?=null,
        membership:((String,String,MapSourceTarget)->MapBacklinkMembership?)?=null,
        networkBudget:LocalNetworkBudget=LocalNetworkBudget(),fileMembership:((String)->MapFileRelationMembership?)?=null,
        checkActive:()->Unit={}):NetworkProjection =
        projectExpandedNetworkWithin(doc,focusId,expanded,location,origin,conflict,blocked,membership,
            networkBudget.evidenceLimit,networkBudget.evidenceLimit,fileMembership,checkActive)

    private fun projectExpandedNetworkWithin(doc:MindMapDocument,focusId:String,expanded:Set<String>,location:BacklinkLocation,
        origin:BacklinkOrigin,conflict:Boolean,blocked:BacklinkProblem?,
        membership:((String,String,MapSourceTarget)->MapBacklinkMembership?)?,maxDirect:Int,maxDeeper:Int,
        fileMembership:((String)->MapFileRelationMembership?)?,checkActive:()->Unit):NetworkProjection =
        projectNetworkDocument(doc,location,origin,conflict,blocked,membership,fileMembership,maxDirect,maxDeeper,checkActive,
            conceptDepth={node->when {
                node.sources.any {checkActive();it.documentId==focusId}->1
                doc.id in expanded || node.sources.any {checkActive();it.documentId in expanded}->2
                else->0
            }},relationDepth={row->when {
                focusId in row.fileIds->1
                row.fileIds.any {it in expanded}->2
                else->0
            }})

    internal fun projectSelectedNetwork(doc:MindMapDocument,roots:Set<String>,expanded:Set<String>,location:BacklinkLocation,
        origin:BacklinkOrigin,conflict:Boolean,blocked:BacklinkProblem?=null,
        membership:((String,String,MapSourceTarget)->MapBacklinkMembership?)?=null,
        networkBudget:LocalNetworkBudget=LocalNetworkBudget(),fileMembership:((String)->MapFileRelationMembership?)?=null,
        checkActive:()->Unit={}):NetworkProjection =
        projectSelectedNetworkWithin(doc,roots,expanded,location,origin,conflict,blocked,membership,
            networkBudget.evidenceLimit,networkBudget.evidenceLimit,fileMembership,checkActive)
    private fun projectSelectedNetworkWithin(doc:MindMapDocument,roots:Set<String>,expanded:Set<String>,location:BacklinkLocation,
        origin:BacklinkOrigin,conflict:Boolean,blocked:BacklinkProblem?,membership:((String,String,MapSourceTarget)->MapBacklinkMembership?)?,
        maxDirect:Int,maxDeeper:Int,fileMembership:((String)->MapFileRelationMembership?)?,checkActive:()->Unit):NetworkProjection =
        projectNetworkDocument(doc,location,origin,conflict,blocked,membership,fileMembership,maxDirect,maxDeeper,checkActive,
            conceptDepth={node->when {
                doc.id in roots || node.sources.any {checkActive();it.documentId in roots}->1
                doc.id in expanded || node.sources.any {checkActive();it.documentId in expanded}->2
                else->0
            }},relationDepth={row->when {
                row.fileIds.any {it in roots}->1
                row.fileIds.any {it in expanded}->2
                else->0
            }})

    internal fun projectLibraryNetwork(doc:MindMapDocument,location:BacklinkLocation,origin:BacklinkOrigin,
        conflict:Boolean,blocked:BacklinkProblem?=null,membership:((String,String,MapSourceTarget)->MapBacklinkMembership?)?=null,
        networkBudget:LocalNetworkBudget=LocalNetworkBudget(),fileMembership:((String)->MapFileRelationMembership?)?=null,
        checkActive:()->Unit={}):NetworkProjection =
        projectLibraryNetworkWithin(doc,location,origin,conflict,blocked,membership,networkBudget.evidenceLimit,fileMembership,checkActive)
    private fun projectLibraryNetworkWithin(doc:MindMapDocument,location:BacklinkLocation,origin:BacklinkOrigin,
        conflict:Boolean,blocked:BacklinkProblem?,membership:((String,String,MapSourceTarget)->MapBacklinkMembership?)?,
        maxEvidence:Int,fileMembership:((String)->MapFileRelationMembership?)?,checkActive:()->Unit):NetworkProjection =
        projectNetworkDocument(doc,location,origin,conflict,blocked,membership,fileMembership,maxEvidence,0,checkActive,{1},{1})

    /** Both evidence types consume the same allowance in their layer; deeper saturation cannot stop direct discovery. */
    private fun projectNetworkDocument(doc:MindMapDocument,location:BacklinkLocation,origin:BacklinkOrigin,
        conflict:Boolean,blocked:BacklinkProblem?,membership:((String,String,MapSourceTarget)->MapBacklinkMembership?)?,
        fileMembership:((String)->MapFileRelationMembership?)?,maxDirect:Int,maxDeeper:Int,checkActive:()->Unit,
        conceptDepth:(MapNode)->Int,relationDepth:(MapFileRelationRow)->Int):NetworkProjection {
        val concepts=mutableListOf<NetworkConceptProjection>();val relations=mutableListOf<MapFileRelationRow>()
        var direct=0;var deeper=0;var hitLimit=false
        fun accept(depth:Int,size:Int):Boolean {
            if(depth==0) return false
            val left=if(depth==1) maxDirect-direct else maxDeeper-deeper
            if(size>left) {hitLimit=true;return false}
            if(depth==1) direct+=size else deeper+=size
            return true
        }
        for(node in doc.nodes) {
            checkActive();if(node.fileCardId!=null || node.sources.isEmpty()) continue
            if(!accept(conceptDepth(node),node.sources.size)) continue
            val members=node.sources.map {source->checkActive();projectMember(node,source,location,origin,conflict,blocked,membership)}
            concepts+=NetworkConceptProjection(NetworkConceptKey(location.key,location.graphId,node.id),location,
                members.first().nodeTitle,node.kind,members)
        }
        visitFileRelations(doc,checkActive) {relation,from,to->
            val row=fileRelationRow(relation,from,to,location,origin,conflict,blocked)
            if(accept(relationDepth(row),1)) relations+=row.copy(membership=fileMembership?.invoke(relation.id))
        }
        return NetworkProjection(concepts,hitLimit,relations)
    }
    private fun projectMember(node:MapNode,source:MapSource,location:BacklinkLocation,origin:BacklinkOrigin,
        conflict:Boolean,blocked:BacklinkProblem?,membership:((String,String,MapSourceTarget)->MapBacklinkMembership?)?):MapBacklinkRow {
        val preview=source.lastPreview?.let {BacklinkPreview(it.assetKey,it.target,it.renderedVersion,it.generatedAt)}
        val title=node.text.ifBlank {if(node.kind==NodeKind.REFERENCE) node.sources.firstOrNull()?.fileName.orEmpty() else ""}
        return MapBacklinkRow(location,node.id,source.id,source.target,title.take(BacklinkBudget.LABEL_CHARS),node.kind,
            source.fileName.take(BacklinkBudget.LABEL_CHARS),source.note.take(BacklinkBudget.NOTE_CHARS),source.lastKnownPage,
            source.status,preview,origin,conflict,blocked,membership?.invoke(node.id,source.id,source.target),
            source.observedVersion,source.sourceVersion,source.acceptedPreviewVersion)
    }

    /** Fresh action-time metadata: a previous live URI cannot undo a later move into Trash. */
    internal suspend fun actionProblem(row:MapBacklinkRow):BacklinkProblem? = actionProblem(row.location)
    internal suspend fun actionProblem(location:BacklinkLocation):BacklinkProblem? = withContext(Dispatchers.IO) {
        val coroutine=currentCoroutineContext();val active={coroutine.ensureActive()}
        val registered=NotebookStorage.get(app).registry.knownMapLocations(4097,location.graphId).filter {it.available}
        if(registered.size>4096) return@withContext BacklinkProblem.LIMIT
        for(registeredLocation in registered) {
            active()
            when(pathPlace(Uri.parse(registeredLocation.uri))) {
                BacklinkPlace.TRASH->return@withContext BacklinkProblem.TRASHED
                BacklinkPlace.RESERVED->return@withContext BacklinkProblem.UNKNOWN_SCOPE
                else->Unit
            }
        }
        if(registered.any {key(Uri.parse(it.uri))!=location.key}) {
            return@withContext if(registered.any {key(Uri.parse(it.uri))==location.key}) BacklinkProblem.DUPLICATE else BacklinkProblem.CHANGED
        }
        val aliases=linkedSetOf(location.uri.toString()).apply {addAll(location.aliases);registered.forEach {add(it.uri)}}
        val candidate=BacklinkCandidate(location.key,aliases,location.title)
        val actualPath=aliases.mapNotNull {pathPlace(Uri.parse(it))}
        if(BacklinkPlace.TRASH in actualPath) return@withContext BacklinkProblem.TRASHED
        if(BacklinkPlace.RESERVED in actualPath) return@withContext BacklinkProblem.UNKNOWN_SCOPE
        if(BacklinkPlace.NORMAL in actualPath) {
            val uri=location.uri
            val readable=try {
                if(uri.scheme=="file") File(requireNotNull(uri.path)).let {it.isFile && it.canRead()}
                else resolver.openInputStream(uri)?.use {true} ?: false
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled} catch(_:Exception) {false}
            return@withContext if(readable) null else BacklinkProblem.UNAVAILABLE
        }
        val allRoots=linkedSetOf<String>().apply {
            addAll(roots)
            resolver.persistedUriPermissions.filter {it.isReadPermission && DocumentsContract.isTreeUri(it.uri)}.forEach {add(it.uri.toString())}
        }
        var visited=0;var count=0L
        for(raw in allRoots) {
            active();if(++visited>budget.maxDirectories) return@withContext BacklinkProblem.LIMIT
            val root=Uri.parse(raw)
            try {
                val top=DocumentsContract.getTreeDocumentId(root)
                val parent=DocumentsContract.buildDocumentUriUsingTree(root,top)
                val cursor=query(DocumentsContract.buildChildDocumentsUriUsingTree(root,top),arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE)) ?: continue
                cursor.use {c->while(c.moveToNext()) {
                    active();if(++count>(budget.maxDirectories.toLong()+budget.maxMaps)*4) throw BacklinkLimit()
                    if(c.getString(2)==DocumentsContract.Document.MIME_TYPE_DIR && reservedName(c.getString(1).orEmpty()))
                        c.getString(0)?.let {id->val child=DocumentsContract.buildDocumentUriUsingTree(root,id);reservedRoots[key(child)]=child}
                }}
                if(aliases.any {alias->val uri=Uri.parse(alias)
                    uri.authority==root.authority && (runCatching {DocumentsContract.getTreeDocumentId(uri)==top}.getOrDefault(false) ||
                        key(parent)==key(uri) || DocumentsContract.isChildDocument(resolver,parent,uri))}) candidate.inVisibleTree=true
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(limit:BacklinkLimit) {return@withContext BacklinkProblem.LIMIT}
            catch(_:Exception) { } // This root cannot prove an opaque standalone location is active.
        }
        when(classify(candidate,active)) {
            BacklinkPlace.NORMAL->null;BacklinkPlace.TRASH->BacklinkProblem.TRASHED;else->BacklinkProblem.UNKNOWN_SCOPE
        }
    }

    /** Each visible batch decodes one actual graph per canonical location, with no native source reads. */
    suspend fun preview(requests:List<MapBacklinkRow>):Map<String,MapPreviewAsset> = withContext(Dispatchers.IO) {
        val wanted=requests.distinctBy {it.key}
        if(wanted.size>BacklinkBudget.PREVIEW_ITEMS) throw BacklinkLimit()
        val coroutine=currentCoroutineContext();var readBytes=0L;var encoded=0L;var pixels=0L
        val active={coroutine.ensureActive();if(readBytes>budget.maxBytes) throw BacklinkLimit()}
        val charge={count:Int->if(count>0) readBytes+=count;if(readBytes>budget.maxBytes) throw BacklinkLimit()}
        val result=linkedMapOf<String,MapPreviewAsset>()
        for(group in wanted.groupBy {it.location.key}.values) {
            active();val first=group.first()
            require(group.all {key(it.location.uri)==first.location.key})
            val aliases=group.flatMapTo(linkedSetOf()) {it.location.aliases}
            MapSaves.lockFor(first.location.uri).withLock {
                val observed=MapFiles.store(app,first.location.uri,readTarget(first.location.uri,active,charge),active,aliases).observe(active,charge)
                val doc=observed.document
                for(row in group) {
                    active();val descriptor=row.preview ?: continue
                    if(doc.id!=row.location.graphId) continue
                    val source=doc.nodes.firstOrNull {it.id==row.nodeId}?.sources?.firstOrNull {it.id==row.sourceId && it.target==row.target} ?: continue
                    if(source.lastPreview?.let {BacklinkPreview(it.assetKey,it.target,it.renderedVersion,it.generatedAt)}!=descriptor) continue
                    val asset=doc.previewAssets[descriptor.assetKey] ?: continue
                    encoded+=asset.byteSize;pixels+=asset.width.toLong()*asset.height
                    if(encoded>BacklinkBudget.PREVIEW_BYTES || pixels>BacklinkBudget.PREVIEW_PIXELS) throw BacklinkLimit()
                    result[row.key]=asset
                }
            }
        }
        result
    }
    suspend fun preview(row:MapBacklinkRow):MapPreviewAsset?=preview(listOf(row))[row.key]
}

/** Shared action-time location check for both exact Open and Remove. No graph/body is read. */
internal suspend fun backlinkLocationProblem(app:Context,roots:List<String>,row:MapBacklinkRow):BacklinkProblem? =
    MapBacklinkLibrary(app,roots).actionProblem(row)

/** Relation navigation checks the same actual location without fabricating a source membership. */
internal suspend fun backlinkLocationProblem(app:Context,roots:List<String>,location:BacklinkLocation):BacklinkProblem? =
    MapBacklinkLibrary(app,roots).actionProblem(location)
