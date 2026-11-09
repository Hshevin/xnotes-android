package com.xnotes.mindmap

import android.content.ContentProvider
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.view.ContextThemeWrapper
import com.xnotes.pen.initializeParkingEglHandles
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.BrowseEntry
import com.xnotes.ui.Editor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual DocumentsProvider inventory and native file moves; no writer or PDF/model re-encoding. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapBacklinkLibraryTest {
    private class Fixture(base:Context,val root:File,val opaqueRoot:Boolean=false) {
        val context=object:ContextThemeWrapper(base,android.R.style.Theme_Material_Light_NoActionBar) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getExternalFilesDir(type:String?)=File(root,"external").apply {mkdirs()}
        }
        val notes=AppStorageDocumentsProvider.rootDir(context)
        private val actualTree=AppStorageDocumentsProvider.treeUri(context)
        val authority=if(opaqueRoot) "backlink-opaque-${UUID.randomUUID()}" else requireNotNull(actualTree.authority)
        val tree=if(opaqueRoot) DocumentsContract.buildTreeDocumentUri(authority,"opaque-root") else actualTree
        val storage=NotebookStorage(context)
        val original=AppStorageDocumentsProvider()
        val reads=AtomicInteger();val writes=AtomicInteger()
        var beforeChildren:((CancellationSignal?)->Unit)?=null
        val bridge=object:ContentProvider() {
            override fun onCreate()=true
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?)=query(uri,projection,selection,args,sort,null)
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?,signal:CancellationSignal?):Cursor? {
                @Suppress("UNCHECKED_CAST") val columns=projection as? Array<String>
                val id=DocumentsContract.getDocumentId(uri)
                return if(uri.pathSegments.lastOrNull()=="children") {
                    beforeChildren?.invoke(signal);signal?.throwIfCanceled()
                    original.queryChildDocuments(if(opaqueRoot && id=="opaque-root") "root/.xnote" else id,columns,sort)
                } else if(opaqueRoot && id=="opaque-root") null else original.queryDocument(id,columns)
            }
            override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
                if('w' in mode) writes.incrementAndGet() else reads.incrementAndGet()
                return original.openDocument(DocumentsContract.getDocumentId(uri),mode,null)
            }
            override fun getType(uri:Uri)=original.getDocumentType(DocumentsContract.getDocumentId(uri))
            override fun insert(uri:Uri,values:android.content.ContentValues?):Uri?=null
            override fun update(uri:Uri,values:android.content.ContentValues?,selection:String?,args:Array<out String>?)=0
            override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int {original.deleteDocument(DocumentsContract.getDocumentId(uri));return 1}
            override fun call(method:String,arg:String?,extras:android.os.Bundle?)=original.call(method,arg,extras)
        }
        val leases=mutableListOf<MapDocumentLease>()
        val editors=mutableListOf<Editor>()
        init {
            original.attachInfo(context,ProviderInfo().apply {authority=this@Fixture.authority;exported=true;grantUriPermissions=true
                readPermission="android.permission.MANAGE_DOCUMENTS";writePermission=readPermission})
            bridge.attachInfo(context,ProviderInfo().apply {authority=this@Fixture.authority})
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority,bridge)
            storage.registry.writableDatabase
        }
        fun uri(file:File,asTree:Boolean=true):Uri {
            val id="root/"+file.relativeTo(notes).invariantSeparatorsPath
            return if(asTree) DocumentsContract.buildDocumentUriUsingTree(tree,id) else DocumentsContract.buildDocumentUri(authority,id)
        }
        fun graph(name:String,doc:MindMapDocument)=File(notes,name).apply {parentFile!!.mkdirs();writeBytes(MapCodec.encode(doc))}
        suspend fun acquire(uri:Uri):MapDocumentLease {
            val lease=withContext(Dispatchers.Main) {MapDocuments.acquire(context,uri).also(leases::add)}
            withTimeout(10000L) {while(withContext(Dispatchers.Main) {lease.session.loading}) delay(1L)}
            return lease
        }
        fun journalSnapshot():Map<String,List<Byte>> = File(context.noBackupFilesDir,"mind-map-recovery").let {dir->
            if(!dir.exists()) emptyMap() else dir.walkTopDown().filter {it.isFile}.associate {it.relativeTo(dir).path to it.readBytes().toList()}
        }
    }
    private fun scenario(opaqueRoot:Boolean=false,block:suspend (Fixture)->Unit) {
        initializeParkingEglHandles()
        val app=RuntimeEnvironment.getApplication();val root=File(app.cacheDir,"backlink-library-${UUID.randomUUID()}").apply {mkdirs()}
        val f=Fixture(app,root,opaqueRoot);val field=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true};val previous=field.get(null)
        field.set(null,f.storage)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var failure:Throwable?=null
        val job=scope.launch {
            try {withTimeout(20000L) {block(f)}} catch(error:Throwable) {failure=error}
            finally {withContext(NonCancellable) {
                f.beforeChildren=null
                val released=withContext(Dispatchers.Main) {f.leases.map {it.close()}}
                released.forEach {it.join()}
                for(editor in f.editors) {
                    val auto=Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(editor) as CoroutineScope
                    withContext(Dispatchers.Main) {auto.cancel()};auto.coroutineContext[Job]?.join()
                }
                f.storage.registry.close();field.set(null,previous)
            }}
        }
        try {
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!job.isCompleted && System.nanoTime()<until) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications();shadowOf(Looper.getMainLooper()).idle();Thread.yield()
            }
            assertTrue("Actual library observation did not settle",job.isCompleted);failure?.let {throw it}
        } finally {f.beforeChildren=null;scope.cancel();if(job.isCompleted) root.deleteRecursively();field.set(null,previous)}
    }
    private val source=UUID.randomUUID().toString()
    private fun document(count:Int=1,note:String=""):MindMapDocument=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Concept",
        sources=(0 until count).map {MapSource(documentId=source,note=note)})))

    @Test fun actualUnregisteredTreeGraphsAreReadWithoutRegisteringOrWritingAndTheirRowsStayLightweight()=scenario {f->
        val doc=document(3,"n".repeat(2000));val file=f.graph("never-opened.xmap",doc);val before=file.readBytes()
        assertTrue(f.storage.registry.knownMapLocations().isEmpty())
        val scan=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scan(source)
        assertFalse(scan.limited);assertTrue(scan.issues.isEmpty());assertEquals(1,scan.graphs.size)
        val rows=scan.graphs.single().rows
        assertEquals(3,rows.size);assertEquals(doc.id,rows.first().location.graphId)
        assertTrue(rows.all {it.sourceNote.length==512 && it.blocked==null && it.membership==null})
        assertEquals(BacklinkPlace.NORMAL,scan.candidates.single().place)
        assertArrayEquals(before,file.readBytes());assertEquals(0,f.writes.get());assertTrue(f.journalSnapshot().isEmpty())
        assertTrue("A read index cannot register its observations as writer discovery",f.storage.registry.knownMapLocations().isEmpty())
    }

    @Test fun opaqueRootWithMissingMetadataCannotAuthorizeItsReadableReservedChildrenAsAnActiveLibrary()=scenario(opaqueRoot=true) {f->
        val file=f.graph(".xnote/hidden.xmap",document());val original=file.readBytes()
        val scan=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scan(source)
        assertTrue("A null root metadata cursor is not proof of a normal opaque library root",scan.issues.any {it.problem==BacklinkProblem.UNKNOWN_SCOPE})
        assertTrue(scan.graphs.flatMap {it.rows}.isEmpty());assertEquals("The uncertain reserved child must not be read",0,f.reads.get())
        assertArrayEquals(original,file.readBytes());assertEquals(0,f.writes.get());assertTrue(f.journalSnapshot().isEmpty())
    }

    @Test fun actualEditorTrashMoveBlocksTheOldLiveUriAndAnOldRowAtActionTime()=scenario {f->
        val doc=document();val file=f.graph("move-me.xmap",doc);val bytes=file.readBytes();val uri=f.uri(file)
        val lease=f.acquire(uri)
        val live=withContext(Dispatchers.Main) {MapDocuments.observations(f.context)}
        val before=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scan(source)
        val row=before.graphs.single().rows.single()
        assertNull(backlinkLocationProblem(f.context,listOf(f.tree.toString()),row))
        val editor=withContext(Dispatchers.Main) {Editor(f.context).also(f.editors::add)}
        val moved=withContext(Dispatchers.IO) {editor.trashEntries(f.tree.toString(),listOf(BrowseEntry(file.name,uri.toString(),false,parentDocId="root")))}
        assertTrue("Actual editor/provider must move, not fake a Trash URI",moved.second.isEmpty());assertEquals(1,moved.first.size);assertFalse(file.exists())
        val registered=f.storage.registry.knownMapLocations(documentId=doc.id).single()
        assertTrue(registered.available);assertNotEquals(MapSaves.key(uri),MapSaves.key(Uri.parse(registered.uri)))
        val writes=f.writes.get();val journals=f.journalSnapshot()
        val scan=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scan(source,live)
        assertEquals(BacklinkProblem.TRASHED,scan.reservedIds[doc.id]);assertEquals(BacklinkProblem.TRASHED,scan.liveBlocked[MapSaves.key(uri)])
        assertEquals(BacklinkProblem.TRASHED,backlinkLocationProblem(f.context,listOf(f.tree.toString()),row))
        assertSame(live.single().document,withContext(Dispatchers.Main) {lease.session.document})
        val movedId=DocumentsContract.getDocumentId(Uri.parse(registered.uri)).removePrefix("root/")
        assertArrayEquals(bytes,File(f.notes,movedId).readBytes());assertEquals(writes,f.writes.get());assertEquals(journals,f.journalSnapshot())
    }

    @Test fun cumulativeByteAndRowBudgetsBoundSeveralIndividuallyValidGraphsWithoutDroppingTheAcceptedRowPrefix()=scenario {f->
        val a=f.graph("a.xmap",document(3,"a".repeat(4000)));val b=f.graph("b.xmap",document(3,"b".repeat(4000)))
        val originals=listOf(a.readBytes(),b.readBytes())
        val bytes=MapBacklinkLibrary(f.context,listOf(f.tree.toString()),BacklinkBudget(maxBytes=a.length()+b.length()/2)).scan(source)
        assertTrue(bytes.limited);assertTrue(bytes.issues.any {it.problem==BacklinkProblem.LIMIT})
        val rows=MapBacklinkLibrary(f.context,listOf(f.tree.toString()),BacklinkBudget(maxRows=2)).scan(source)
        assertTrue(rows.limited);assertEquals(2,rows.graphs.flatMap {it.rows}.size);assertTrue(rows.issues.any {it.problem==BacklinkProblem.LIMIT})
        assertArrayEquals(originals[0],a.readBytes());assertArrayEquals(originals[1],b.readBytes());assertEquals(0,f.writes.get())
    }

    @Test fun aVisibleSameGraphPreviewBatchReadsOnceAndRejectsChangedDescriptors()=scenario {f->
        val asset=MapPreviewAsset.fromPng(png());val a=MapSource(documentId=source)
        val bound=a.copy(previewKey=asset.key,lastPreview=MapLastPreview(asset.key,a.target,"native-sha256:old",1))
        val second=bound.copy(id=UUID.randomUUID().toString())
        val doc=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,sources=listOf(bound,second))),previewAssets=mapOf(asset.key to asset))
        val file=f.graph("previews.xmap",doc);val reader=MapBacklinkLibrary(f.context,listOf(f.tree.toString()))
        val rows=reader.scan(source).graphs.single().rows;val reads=f.reads.get();val bytes=file.readBytes()
        val images=reader.preview(rows)
        assertEquals(1,f.reads.get()-reads);assertEquals(rows.map {it.key}.toSet(),images.keys);assertTrue(images.values.all {it==asset})
        file.writeBytes(MapCodec.encode(doc.update("r") {it.copy(sources=it.sources.map {s->s.copy(lastPreview=s.lastPreview!!.copy(generatedAt=2))})}))
        assertTrue(reader.preview(rows).isEmpty());assertEquals(0,f.writes.get());assertNotEquals(bytes.toList(),file.readBytes().toList())
    }

    @Test fun actualKnownAliasRecoveryWinsReadOnlyAndExtraAliasRecoveryBlocksAnAlreadyOpenActor()=scenario {f->
        val a=document();val b=a.update("r") {it.copy(sources=it.sources.map {s->s.copy(note="Retained exact B")})}
        val file=f.graph("alias.xmap",a);val bare=f.uri(file,false);val tree=f.uri(file,true)
        val lease=f.acquire(bare)
        val actual=MapFiles.target(f.context,bare)
        val failing=object:MapTarget {override fun read()=actual.read();override fun write(bytes:ByteArray):Unit=throw IOException("before write")}
        val old=MapStore(File(f.context.noBackupFilesDir,"mind-map-recovery"),tree.toString(),failing)
        old.open();assertThrows(IOException::class.java) {old.save(b)}
        val original=file.readBytes();val journals=f.journalSnapshot()
        val cold=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scan(source)
        assertEquals(BacklinkOrigin.RECOVERY,cold.graphs.single().rows.single().origin)
        assertEquals("Retained exact B",cold.graphs.single().rows.single().sourceNote)
        val live=withContext(Dispatchers.Main) {MapDocuments.observations(f.context)}
        val liveScan=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scan(source,live)
        assertTrue(liveScan.graphs.isEmpty());assertEquals(BacklinkProblem.RECOVERY_CONFLICT,liveScan.liveBlocked[MapSaves.key(bare)])
        assertEquals(a,withContext(Dispatchers.Main) {lease.session.document});assertArrayEquals(original,file.readBytes())
        assertEquals(journals,f.journalSnapshot());assertEquals(0,f.writes.get())
    }

    @Test fun cancellationStopsARealProviderQueryAndWaitingForTheCanonicalWriterWithoutOpeningPartialJson()=scenario {f->
        val file=f.graph("locked.xmap",document());val uri=f.uri(file);val original=file.readBytes()
        coroutineScope {
            val queried=CompletableDeferred<Unit>();var signal:CancellationSignal?=null
            f.beforeChildren={current->signal=current;queried.complete(Unit);while(current?.isCanceled==false) Thread.yield();current?.throwIfCanceled()}
            val read=async {MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scan(source)}
            queried.await();read.cancelAndJoin();assertTrue(requireNotNull(signal).isCanceled);f.beforeChildren=null
            val lock=MapSaves.lockFor(uri);lock.lock()
            try {
                file.writeText("{");val reads=f.reads.get()
                val waiting=async {MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scan(source)}
                delay(30);assertFalse(waiting.isCompleted);assertEquals(reads,f.reads.get())
                waiting.cancelAndJoin();assertEquals(reads,f.reads.get());file.writeBytes(original)
            } finally {lock.unlock()}
        }
        assertArrayEquals(original,file.readBytes());assertEquals(0,f.writes.get());assertTrue(f.journalSnapshot().isEmpty())
    }
    private fun png():ByteArray {
        val out=ByteArrayOutputStream();val data=java.io.DataOutputStream(out)
        data.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(name:String,body:ByteArray) {val type=name.toByteArray();val crc=CRC32().apply {update(type);update(body)}
            data.writeInt(body.size);data.write(type);data.write(body);data.writeInt(crc.value.toInt())}
        val header=ByteArrayOutputStream();java.io.DataOutputStream(header).use {it.writeInt(2);it.writeInt(2);it.write(byteArrayOf(8,6,0,0,0))}
        chunk("IHDR",header.toByteArray());val body=ByteArrayOutputStream()
        DeflaterOutputStream(body).use {repeat(2) {_ ->it.write(0);it.write(ByteArray(8) {255.toByte()})}}
        chunk("IDAT",body.toByteArray());chunk("IEND",byteArrayOf());return out.toByteArray()
    }
}
