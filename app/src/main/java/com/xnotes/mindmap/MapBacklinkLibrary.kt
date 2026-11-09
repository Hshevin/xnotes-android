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
    val keys:Set<String> = emptySet(),val problem:BacklinkProblem?=null)
internal data class BacklinkLibraryScan(val candidates:List<BacklinkCandidate>,val graphs:List<BacklinkStoredGraph>,
    val issues:List<BacklinkIssue>,val omittedIssues:Int,val excludedMaps:Int,val reservedIds:Map<String,BacklinkProblem>,val limited:Boolean,
    val liveBlocked:Map<String,BacklinkProblem> = emptyMap())

/** Owned, cancellable read observation. No graph lease, writer baseline or native/PDF decode. */
internal class MapBacklinkLibrary(private val app:Context,private val roots:List<String>,private val budget:BacklinkBudget=BacklinkBudget()) {
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
    suspend fun scan(documentId:String,live:List<MapDocumentObservation> = emptyList()):BacklinkLibraryScan = withContext(Dispatchers.IO) {
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
            if(scope!=null) liveBlocked[snapshot.key]=scope
            else if(candidate!=null && !snapshot.loading && !snapshot.creating && !snapshot.failed && snapshot.knownAliases.isNotEmpty()) MapSaves.lockFor(candidate.uri).withLock {
                coroutine.ensureActive()
                if(MapFiles.store(app,candidate.uri,checkActive={coroutine.ensureActive()},knownAliases=candidate.aliases).recoveryOutside(snapshot.knownAliases))
                    liveBlocked[snapshot.key]=BacklinkProblem.RECOVERY_CONFLICT
            }
        }
        val liveKeys=live.mapTo(hashSetOf()) {it.key}
        val graphs=mutableListOf<BacklinkStoredGraph>()
        for(candidate in candidates.values) {
            coroutine.ensureActive()
            if(candidate.key in liveKeys || candidate.place in setOf(BacklinkPlace.RESERVED,BacklinkPlace.TRASH)) continue
            if(exhausted) break
            try {graphs+=read(candidate,documentId,active)
                if(projectionLimited) {issue(BacklinkProblem.LIMIT);break}
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:BacklinkLimit) {limited=true;issue(BacklinkProblem.LIMIT);break}
            catch(error:Exception) {
                val problem=if(error is MapRecoveryObservationException) BacklinkProblem.RECOVERY_CONFLICT else BacklinkProblem.CORRUPT
                issue(problem,candidate.name);graphs+=BacklinkStoredGraph(candidate,null,emptyList(),problem=problem)
            }
        }
        BacklinkLibraryScan(candidates.values.toList(),graphs,issues.toList(),omitted,excluded,reservedIds.toMap(),limited,liveBlocked)
    }
    private suspend fun read(candidate:BacklinkCandidate,documentId:String,active:()->Unit):BacklinkStoredGraph = MapSaves.lockFor(candidate.uri).withLock {
        active();var failure:Exception?=null
        for(alias in candidate.aliases) {
            active();val uri=Uri.parse(alias)
            val target=readTarget(uri,active,::account)
            try {
                val store=MapFiles.store(app,uri,target,active,candidate.aliases)
                val observed=store.observe(active,::account);active()
                val location=BacklinkLocation(uri,candidate.aliases,candidate.key,observed.document.id,candidate.name)
                val found=project(observed.document,documentId,location,if(observed.recovered) BacklinkOrigin.RECOVERY else BacklinkOrigin.STORED,
                    observed.conflict,if(candidate.place==BacklinkPlace.UNKNOWN) BacklinkProblem.UNKNOWN_SCOPE else null)
                if(!observed.remoteAvailable) issue(BacklinkProblem.UNAVAILABLE,candidate.name)
                return@withLock BacklinkStoredGraph(candidate,observed.document.id,found,store.recoveryKeys)
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
        loop@for(node in doc.nodes) for(source in node.sources) if(source.documentId==documentId) {
            if(rows>=budget.maxRows) {limited=true;projectionLimited=true;break@loop}
            rows++
            val preview=source.lastPreview?.let {BacklinkPreview(it.assetKey,it.target,it.renderedVersion,it.generatedAt)}
            val title=node.text.ifBlank {if(node.kind==NodeKind.REFERENCE) node.sources.firstOrNull()?.fileName.orEmpty() else ""}
            out+=MapBacklinkRow(location,node.id,source.id,source.target,title.take(BacklinkBudget.LABEL_CHARS),node.kind,
                source.fileName.take(BacklinkBudget.LABEL_CHARS),source.note.take(BacklinkBudget.NOTE_CHARS),source.lastKnownPage,
                source.status,preview,origin,conflict,blocked,membership?.invoke(node.id,source.id,source.target))
        }
        return out
    }
    internal fun resetProjectionBudget() {rows=0;projectionLimited=false}

    /** Fresh action-time metadata: a previous live URI cannot undo a later move into Trash. */
    internal suspend fun actionProblem(row:MapBacklinkRow):BacklinkProblem? = withContext(Dispatchers.IO) {
        val coroutine=currentCoroutineContext();val active={coroutine.ensureActive()}
        val registered=NotebookStorage.get(app).registry.knownMapLocations(4097,row.location.graphId).filter {it.available}
        if(registered.size>4096) return@withContext BacklinkProblem.LIMIT
        for(location in registered) {
            active()
            when(pathPlace(Uri.parse(location.uri))) {
                BacklinkPlace.TRASH->return@withContext BacklinkProblem.TRASHED
                BacklinkPlace.RESERVED->return@withContext BacklinkProblem.UNKNOWN_SCOPE
                else->Unit
            }
        }
        if(registered.any {key(Uri.parse(it.uri))!=row.location.key}) {
            return@withContext if(registered.any {key(Uri.parse(it.uri))==row.location.key}) BacklinkProblem.DUPLICATE else BacklinkProblem.CHANGED
        }
        val aliases=linkedSetOf(row.location.uri.toString()).apply {addAll(row.location.aliases);registered.forEach {add(it.uri)}}
        val candidate=BacklinkCandidate(row.location.key,aliases,row.location.title)
        val actualPath=aliases.mapNotNull {pathPlace(Uri.parse(it))}
        if(BacklinkPlace.TRASH in actualPath) return@withContext BacklinkProblem.TRASHED
        if(BacklinkPlace.RESERVED in actualPath) return@withContext BacklinkProblem.UNKNOWN_SCOPE
        if(BacklinkPlace.NORMAL in actualPath) {
            val uri=row.location.uri
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
