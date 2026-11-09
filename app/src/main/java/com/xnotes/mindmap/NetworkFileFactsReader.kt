package com.xnotes.mindmap

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import com.xnotes.core.util.DocKeys
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.storage.NotebookStorage
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Last-registered identity/type plus fresh directory facts. Never opens a source body or registers it. */
internal class NetworkFileFactsReader(context:Context) {
    private val app=context.applicationContext
    private val resolver=app.contentResolver
    private val authority=app.packageName+".documents"
    private class Failure(val problem:BacklinkProblem):Exception()
    private data class Node(val uri:Uri,val id:String?,val name:String,val directory:Boolean,val file:File?=null)
    private data class Location(val uri:Uri,val available:Boolean)

    private inner class Read(val check:()->Unit) {
        var items=0
        private val metadata=mutableMapOf<String,Result<Node>>()
        fun item() {check();if(++items>MAX_ITEMS) throw Failure(BacklinkProblem.LIMIT)}
        private fun docId(uri:Uri):String = try {DocumentsContract.getDocumentId(uri)} catch(_:IllegalArgumentException) {
            DocumentsContract.getTreeDocumentId(uri)
        }
        private fun document(uri:Uri):Uri = if(DocumentsContract.isTreeUri(uri))
            DocumentsContract.buildDocumentUriUsingTree(uri,docId(uri)) else uri
        private fun local(uri:Uri):Node? {
            val file:File
            val root:File
            if(uri.scheme=="content" && uri.authority==authority) {
                root=AppStorageDocumentsProvider.recoveryRoot(app)?.canonicalFile ?: throw Failure(BacklinkProblem.UNAVAILABLE)
                if(!root.isDirectory || !root.canRead()) throw Failure(BacklinkProblem.UNAVAILABLE)
                val id=docId(uri)
                if(!DocKeys.within(id,"root") || id.split('/').any {it=="." || it==".."}) throw Failure(BacklinkProblem.UNKNOWN_SCOPE)
                file=File(root,id.removePrefix("root").trimStart('/')).canonicalFile
            } else if(uri.scheme=="file") {
                file=File(requireNotNull(uri.path)).canonicalFile
                root=listOfNotNull(AppStorageDocumentsProvider.recoveryRoot(app),File(app.filesDir,"Notes"))
                    .map {it.canonicalFile}.firstOrNull {DocKeys.within(file.path,it.path)} ?: throw Failure(BacklinkProblem.UNKNOWN_SCOPE)
            } else return null
            if(!DocKeys.within(file.path,root.path)) throw Failure(BacklinkProblem.UNKNOWN_SCOPE)
            val relative=file.path.removePrefix(root.path).trimStart('/').split('/')
            if(".xnote" in relative) throw Failure(if("trash" in relative) BacklinkProblem.TRASHED else BacklinkProblem.UNKNOWN_SCOPE)
            if(!root.isDirectory || !file.exists() || !file.canRead()) throw Failure(BacklinkProblem.UNAVAILABLE)
            return Node(uri,if(uri.scheme=="content") docId(uri) else null,file.name,file.isDirectory,file)
        }
        suspend fun query(uri:Uri,columns:Array<String>):Cursor? {
            item()
            return suspendCancellableCoroutine {waiting->
                val cancellation=CancellationSignal()
                waiting.invokeOnCancellation {cancellation.cancel()}
                try {
                    val cursor=resolver.query(uri,columns,null,null,null,cancellation)
                    waiting.resume(cursor) {_,value,_->value?.close()}
                } catch(error:Exception) {waiting.resumeWith(Result.failure(error))}
            }
        }
        suspend fun node(raw:Uri):Node {
            check()
            val uri=document(raw);val key=MapSaves.key(uri)
            metadata[key]?.let {return it.getOrThrow()}
            try {
                val value=local(uri) ?: run {
                    if(uri.scheme!="content") throw Failure(BacklinkProblem.UNKNOWN_SCOPE)
                    val id=docId(uri)
                    val cursor=query(uri,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE))
                        ?: throw Failure(BacklinkProblem.UNAVAILABLE)
                    cursor.use {c->
                        check()
                        if(!c.moveToFirst() || c.isNull(0) || c.isNull(1) || c.isNull(2) || c.getString(0)!=id)
                            throw Failure(BacklinkProblem.UNAVAILABLE)
                        val name=c.getString(1);val mime=c.getString(2)
                        if(name.length>8192 || mime.length>8192) throw Failure(BacklinkProblem.LIMIT)
                        if(name.isBlank() || mime.isBlank()) throw Failure(BacklinkProblem.UNKNOWN_SCOPE)
                        if(name==".xnote") throw Failure(BacklinkProblem.UNKNOWN_SCOPE)
                        Node(uri,id,name,mime==DocumentsContract.Document.MIME_TYPE_DIR)
                    }
                }
                check();metadata[key]=Result.success(value);return value
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(error:Exception) {
                check()
                val failure=if(error is Failure) error else Failure(BacklinkProblem.UNAVAILABLE)
                metadata[key]=Result.failure(failure);throw failure
            }
        }
        suspend fun folder(folder:NetworkFolder):Node = node(Uri.parse(folder.uri)).also {
            if(!it.directory) throw Failure(BacklinkProblem.UNKNOWN_SCOPE)
        }
        suspend fun inside(parent:Node,uri:Uri):NetworkFolderMatch {
            val child=node(uri)
            if(child.directory) throw Failure(BacklinkProblem.UNKNOWN_SCOPE)
            check()
            if(parent.file!=null && child.file!=null) return if(DocKeys.within(child.file.path,parent.file.path))
                NetworkFolderMatch.INSIDE else NetworkFolderMatch.OUTSIDE
            if(parent.file!=null || child.file!=null || parent.uri.authority!=child.uri.authority)
                throw Failure(BacklinkProblem.UNKNOWN_SCOPE)
            val result=DocumentsContract.isChildDocument(resolver,parent.uri,child.uri)
            check()
            return if(result) NetworkFolderMatch.INSIDE else NetworkFolderMatch.OUTSIDE
        }
        fun childUri(parent:NetworkFolder,id:String):Uri {
            val uri=Uri.parse(parent.uri)
            return if(DocumentsContract.isTreeUri(uri)) DocumentsContract.buildDocumentUriUsingTree(uri,id)
                else DocumentsContract.buildDocumentUri(requireNotNull(uri.authority),id)
        }
    }
    suspend fun read(ids:Set<String>,folder:NetworkFolder?,observedMaps:List<BacklinkLocation> = emptyList()):NetworkFileFacts = withContext(Dispatchers.IO) {
        require(ids.size<=NetworkFilter.MAX_FILES && ids.all {runCatching {UUID.fromString(it).toString()==it}.getOrDefault(false)})
        if(ids.isEmpty()) return@withContext NetworkFileFacts(emptyMap())
        val coroutine=currentCoroutineContext();val read=Read {coroutine.ensureActive()}
        val issues=linkedSetOf<BacklinkIssue>()
        fun issue(problem:BacklinkProblem,name:String?=null) {
            if(issues.size<MAX_ISSUES) issues+=BacklinkIssue(problem,name?.take(BacklinkBudget.LABEL_CHARS))
        }
        val observed=observedMaps.filter {it.graphId in ids}.groupBy {it.graphId}
        val registry=try {NotebookStorage.get(app).registry.fileObservations(ids,read.check)}
            catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(_:Exception) {
                read.check();return@withContext NetworkFileFacts(ids.associateWith {NetworkFileFact(null,problem=BacklinkProblem.UNAVAILABLE)},
                    listOf(BacklinkIssue(BacklinkProblem.UNAVAILABLE)))
            }
        if(registry.limited) return@withContext NetworkFileFacts(ids.associateWith {NetworkFileFact(null,problem=BacklinkProblem.LIMIT)},
            listOf(BacklinkIssue(BacklinkProblem.LIMIT)))
        var folderProblem:BacklinkProblem?=null
        val parent=if(folder==null) null else try {read.folder(folder)}
            catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(error:Exception) {read.check();folderProblem=(error as? Failure)?.problem ?: BacklinkProblem.UNAVAILABLE;null}
        val facts=linkedMapOf<String,NetworkFileFact>()
        for(id in ids) {
            read.check()
            val old=registry.files[id];val maps=observed[id].orEmpty()
            val known=when(old?.kind) {"xnote"->NetworkFileKind.NOTE;"xcanvas"->NetworkFileKind.CANVAS;"xmap"->NetworkFileKind.MAP;else->null}
            val kind=if(maps.isNotEmpty() && old?.kind==null) NetworkFileKind.MAP else known
            val locations=linkedMapOf<String,Location>()
            var problem:BacklinkProblem?=null
            try {
                for(location in old?.locations.orEmpty()) {
                    read.check();putLocation(locations,location.uri,location.available)
                }
                for(map in maps) {
                    read.check()
                    if(MapSaves.key(map.uri)!=map.key) throw Failure(BacklinkProblem.IDENTITY)
                    putLocation(locations,map.uri.toString(),true)
                }
                problem=when {
                    locations.size>MAX_LOCATIONS->BacklinkProblem.LIMIT
                    maps.isNotEmpty() && old?.kind!=null && old.kind!="xmap"->BacklinkProblem.IDENTITY
                    locations.values.count {it.available}>1->BacklinkProblem.DUPLICATE
                    folder!=null && locations.values.none {it.available}->BacklinkProblem.UNAVAILABLE
                    kind==null->BacklinkProblem.IDENTITY
                    folderProblem!=null->folderProblem
                    else->null
                }
                if(problem==null && parent!=null) {
                    val location=locations.values.single {it.available}
                    facts[id]=NetworkFileFact(kind,read.inside(parent,location.uri))
                }
            } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
            catch(error:Exception) {read.check();problem=(error as? Failure)?.problem ?: BacklinkProblem.UNAVAILABLE}
            if(id !in facts) facts[id]=NetworkFileFact(if(problem==BacklinkProblem.IDENTITY) null else kind,problem=problem)
            problem?.let {issue(it,old?.name ?: maps.firstOrNull()?.title)}
        }
        read.check();NetworkFileFacts(facts,issues.toList())
    }
    private fun putLocation(found:MutableMap<String,Location>,raw:String,available:Boolean) {
        if(raw.length>8192) throw Failure(BacklinkProblem.LIMIT)
        val uri=Uri.parse(raw);val key=MapSaves.key(uri)
        val old=found[key]
        if(old==null || available && !old.available) found[key]=Location(uri,available || old?.available==true)
    }

    suspend fun listFolders(parent:NetworkFolder):NetworkFolderListing = withContext(Dispatchers.IO) {
        val coroutine=currentCoroutineContext();val read=Read {coroutine.ensureActive()}
        val folders=linkedMapOf<String,NetworkFolder>()
        var problem:BacklinkProblem?=null
        fun add(uri:Uri,name:String) {
            if(name==".xnote") return
            if(name.isBlank()) {problem=BacklinkProblem.UNKNOWN_SCOPE;return}
            if(name.length>8192 || uri.toString().length>8192) throw Failure(BacklinkProblem.LIMIT)
            val key=MapSaves.key(uri)
            if(key !in folders && folders.size>=MAX_FOLDERS) throw Failure(BacklinkProblem.LIMIT)
            folders[key]=NetworkFolder(uri.toString(),name)
        }
        try {
            val node=read.folder(parent)
            if(node.file!=null) Files.newDirectoryStream(node.file.toPath()).use {children->for(path in children) {
                read.item();val child=path.toFile().canonicalFile
                if(child.name==".xnote") continue
                if(!DocKeys.within(child.path,node.file.path)) {problem=BacklinkProblem.UNKNOWN_SCOPE;continue}
                if(child.isDirectory && child.canRead()) {
                    val id=if(node.id=="root") "root/${child.name}" else "${node.id}/${child.name}"
                    val uri=if(node.uri.scheme=="file") Uri.fromFile(child) else read.childUri(parent,id)
                    add(uri,child.name)
                }
            }} else {
                val uri=Uri.parse(parent.uri)
                val children=if(DocumentsContract.isTreeUri(uri)) DocumentsContract.buildChildDocumentsUriUsingTree(uri,requireNotNull(node.id))
                    else DocumentsContract.buildChildDocumentsUri(requireNotNull(uri.authority),requireNotNull(node.id))
                val cursor=read.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE)) ?: throw Failure(BacklinkProblem.UNAVAILABLE)
                cursor.use {c->while(c.moveToNext()) {
                    read.item()
                    if(c.isNull(0) || c.isNull(1) || c.isNull(2)) {problem=BacklinkProblem.UNKNOWN_SCOPE;continue}
                    if(c.getString(2)==DocumentsContract.Document.MIME_TYPE_DIR) add(read.childUri(parent,c.getString(0)),c.getString(1))
                }}
            }
        } catch(cancelled:java.util.concurrent.CancellationException) {throw cancelled}
        catch(error:Exception) {read.check();problem=(error as? Failure)?.problem ?: BacklinkProblem.UNAVAILABLE}
        read.check()
        NetworkFolderListing(folders.values.sortedBy {it.title},problem,problem==BacklinkProblem.LIMIT)
    }
    private companion object {
        const val MAX_LOCATIONS=32
        const val MAX_ISSUES=128
        const val MAX_FOLDERS=4096
        const val MAX_ITEMS=16384
    }
}
