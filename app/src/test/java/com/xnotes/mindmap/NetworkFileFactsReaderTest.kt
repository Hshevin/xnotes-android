package com.xnotes.mindmap

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Real SQLite rows and opaque DocumentsProvider ancestry; no native reader/decoder is substituted. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkFileFactsReaderTest {
    private fun id()=UUID.randomUUID().toString()
    private fun scenario(block:suspend (MapBacklinkLibraryTest.Fixture)->Unit)=MapBacklinkLibraryTest().scenario(block=block)
    internal class Provider(val context:Context,val root:File) {
        val authority="network-filter-${UUID.randomUUID()}"
        val folderId="opaque-folder"
        val entries=linkedMapOf(folderId to File(root,"Actual chosen directory").apply {mkdirs()})
        val inside=linkedSetOf<String>()
        val missingIds=linkedSetOf<String>()
        val reads=AtomicInteger();val writes=AtomicInteger();val ancestry=AtomicInteger();val queries=AtomicInteger()
        var missingRoot=false;var denyQuery=false;var beforeQuery:((CancellationSignal?)->Unit)?=null
        var alwaysChild=false;var listedRows=0;var listDirectories=true
        val original=object:DocumentsProvider() {
            override fun onCreate()=true
            override fun queryRoots(p:Array<out String>?)=MatrixCursor(p ?: emptyArray())
            override fun queryDocument(documentId:String,p:Array<out String>?):Cursor {
                val virtual=documentId.startsWith("virtual-child-")
                val file=entries[documentId] ?: entries.getValue(folderId).takeIf {virtual} ?: error("Missing document: $documentId")
                val columns=p ?: arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE)
                return MatrixCursor(columns).apply {addRow(columns.map {when(it) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID->documentId
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME->if(virtual) "Child ${documentId.removePrefix("virtual-child-")}" else file.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE->if(if(virtual) listDirectories else file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                    DocumentsContract.Document.COLUMN_SIZE->file.length()
                    else->null
                }}.toTypedArray())}
            }
            override fun queryChildDocuments(parentDocumentId:String,p:Array<out String>?,sortOrder:String?):Cursor {
                val columns=p ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE)
                return MatrixCursor(columns).apply {repeat(listedRows) {at->addRow(columns.map {when(it) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID->"virtual-child-$at"
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME->"Child $at"
                    DocumentsContract.Document.COLUMN_MIME_TYPE->if(listDirectories) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                    else->null
                }}.toTypedArray())}}
            }
            override fun isChildDocument(parentDocumentId:String,documentId:String):Boolean {
                ancestry.incrementAndGet();return alwaysChild || parentDocumentId==folderId && documentId in inside
            }
            override fun openDocument(documentId:String,mode:String,signal:CancellationSignal?):ParcelFileDescriptor {
                if('w' in mode) writes.incrementAndGet() else reads.incrementAndGet()
                return ParcelFileDescriptor.open(entries.getValue(documentId),ParcelFileDescriptor.parseMode(mode))
            }
        }
        private val bridge=object:ContentProvider() {
            override fun onCreate()=true
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?)=query(uri,p,s,a,sort,null)
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?,signal:CancellationSignal?):Cursor? {
                queries.incrementAndGet();beforeQuery?.invoke(signal);signal?.throwIfCanceled()
                if(denyQuery) throw SecurityException("Provider read permission is gone")
                val doc=DocumentsContract.getDocumentId(uri)
                if(doc in missingIds || doc==folderId && missingRoot) return null
                return if(uri.lastPathSegment=="children") original.queryChildDocuments(doc,p,sort) else original.queryDocument(doc,p)
            }
            override fun openFile(uri:Uri,mode:String)=original.openDocument(DocumentsContract.getDocumentId(uri),mode,null)
            override fun call(method:String,arg:String?,extras:Bundle?)=original.call(method,arg,extras)
            override fun getType(uri:Uri)=if(entries.getValue(DocumentsContract.getDocumentId(uri)).isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
            override fun insert(uri:Uri,v:ContentValues?):Uri?=error("A filter cannot insert")
            override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=error("A filter cannot update")
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=error("A filter cannot delete")
        }
        init {
            original.attachInfo(context,ProviderInfo().apply {this.authority=this@Provider.authority;exported=true;grantUriPermissions=true
                readPermission="android.permission.MANAGE_DOCUMENTS";writePermission=readPermission})
            bridge.attachInfo(context,ProviderInfo().apply {this.authority=this@Provider.authority})
            ShadowContentResolver.registerProviderInternal(authority,bridge)
        }
        fun uri(documentId:String)=DocumentsContract.buildDocumentUri(authority,documentId)
        fun folder()=NetworkFolder(uri(folderId).toString(),"Chosen folder")
        fun add(documentId:String,name:String,inFolder:Boolean=true,bytes:ByteArray="Untouched source payload".toByteArray()):File {
            val file=File(root,name).apply {parentFile!!.mkdirs();writeBytes(bytes)};entries[documentId]=file
            if(inFolder) inside+=documentId
            return file
        }
    }
    private fun native(file:File):String {
        val doc=Document(mutableListOf(Page(500.0,500.0))).apply {enableReferenceAnchors()}
        file.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(doc.fixedWriteSnapshot(),it)}
        return doc.identity.documentId
    }
    @Test fun typedLastObservedKindsDoNotFollowMisleadingNamesAndFactsNeverReadOrRegisterSourceBodies()=scenario {f->
        val p=Provider(f.context,File(f.root,"facts-provider").apply {mkdirs()})
        val note=p.add("n","actually-note.xmap");val noteId=native(note)
        val canvas=p.add("c","named-pdf.pdf");val canvasId=id();val absent=id()
        f.storage.registry.register(noteId,"xnote",note.name,p.uri("n").toString(),pageReferences=true)
        f.storage.registry.register(canvasId,"xcanvas",canvas.name,p.uri("c").toString())
        val graph=MindMapDocument();val graphFile=p.add("g","unknown.extension",bytes=MapCodec.encode(graph))
        val actual=MapCodec.decode(graphFile.readBytes())
        val observed=BacklinkLocation(p.uri("g"),setOf(p.uri("g").toString()),MapSaves.key(p.uri("g")),actual.id,graphFile.name)
        val before=listOf(note,canvas,graphFile).map {it.readBytes().toList()};val locations=f.storage.registry.knownLocations()
        val facts=NetworkFileFactsReader(f.context).read(setOf(noteId,canvasId,actual.id,absent),null,listOf(observed))
        assertEquals(NetworkFileKind.NOTE,facts.files.getValue(noteId).kind)
        assertEquals(NetworkFileKind.CANVAS,facts.files.getValue(canvasId).kind)
        assertEquals(NetworkFileKind.MAP,facts.files.getValue(actual.id).kind);assertNull(facts.files.getValue(absent).kind)
        assertEquals(locations,f.storage.registry.knownLocations());assertNull(f.storage.registry.idAt(observed.uri.toString()))
        assertEquals("Typed registry facts need no provider query when no folder is selected",0,p.queries.get())
        assertEquals(0,p.ancestry.get());assertEquals(0,p.reads.get());assertEquals(0,p.writes.get())
        assertEquals(before,listOf(note,canvas,graphFile).map {it.readBytes().toList()});assertTrue(f.journalSnapshot().isEmpty())
        f.storage.registry.markUnavailable(p.uri("n").toString())
        f.storage.registry.writableDatabase.delete("locations","document_id=?",arrayOf(canvasId))
        val unavailableLocations=f.storage.registry.knownLocations()
        val reader=NetworkFileFactsReader(f.context)
        val kindsOnly=reader.read(setOf(noteId,canvasId),null)
        assertEquals(NetworkFileKind.NOTE,kindsOnly.files.getValue(noteId).kind)
        assertEquals(NetworkFileKind.CANVAS,kindsOnly.files.getValue(canvasId).kind)
        assertNull(kindsOnly.files.getValue(noteId).problem);assertNull(kindsOnly.files.getValue(canvasId).problem)
        assertTrue(kindsOnly.issues.isEmpty());assertEquals(0,p.queries.get())
        val withFolder=reader.read(setOf(noteId,canvasId),p.folder())
        assertTrue(withFolder.files.values.all {it.folder==NetworkFolderMatch.UNKNOWN && it.problem!=null})
        assertFalse(withFolder.issues.isEmpty())
        assertEquals("xnote",f.storage.registry.kindForId(noteId));assertEquals("xcanvas",f.storage.registry.kindForId(canvasId))
        assertEquals(unavailableLocations,f.storage.registry.knownLocations())
        assertEquals(0,p.reads.get());assertEquals(0,p.writes.get())
        assertEquals(before,listOf(note,canvas,graphFile).map {it.readBytes().toList()});assertTrue(f.journalSnapshot().isEmpty())
    }
    @Test fun opaqueFolderMembershipUsesActualAncestryAndFreshDirectoryMetadataRatherThanIdPrefixes()=scenario {f->
        val p=Provider(f.context,File(f.root,"opaque-provider").apply {mkdirs()})
        val a=p.add("unrelated-looking-child","inside.xnote",true);val b=p.add("opaque-folder-looks-inside","outside.xnote",false)
        val inside=id();val outside=id()
        f.storage.registry.register(inside,"xnote",a.name,p.uri("unrelated-looking-child").toString())
        f.storage.registry.register(outside,"xnote",b.name,p.uri("opaque-folder-looks-inside").toString())
        val reader=NetworkFileFactsReader(f.context);val facts=reader.read(setOf(inside,outside),p.folder())
        assertEquals(NetworkFolderMatch.INSIDE,facts.files.getValue(inside).folder)
        assertEquals(NetworkFolderMatch.OUTSIDE,facts.files.getValue(outside).folder)
        assertTrue(p.queries.get()>0);assertTrue(p.ancestry.get()>0)
        p.missingRoot=true
        val unknown=reader.read(setOf(inside,outside),p.folder())
        assertTrue(unknown.files.values.all {it.folder==NetworkFolderMatch.UNKNOWN});assertTrue(unknown.issues.isNotEmpty())
        p.missingRoot=false
        val notADirectory=reader.read(setOf(inside),NetworkFolder(p.uri("unrelated-looking-child").toString(),"Not a directory"))
        assertEquals(NetworkFolderMatch.UNKNOWN,notADirectory.files.getValue(inside).folder)
        assertEquals(0,p.reads.get());assertEquals(0,p.writes.get());assertTrue(f.journalSnapshot().isEmpty())
    }
    @Test fun unavailableDuplicatePermissionAndActualMapKindConflictsRemainExplicitWithoutChoosingALocation()=scenario {f->
        val p=Provider(f.context,File(f.root,"unknown-provider").apply {mkdirs()})
        val a=p.add("a","inside.xnote",true);val b=p.add("b","outside.xnote",false)
        val duplicate=id();val unavailable=id();val conflict=id()
        f.storage.registry.register(duplicate,"xnote",a.name,p.uri("a").toString())
        f.storage.registry.register(duplicate,"xnote",b.name,p.uri("b").toString())
        val gone=p.add("gone","unavailable.xnote")
        f.storage.registry.register(unavailable,"xnote",gone.name,p.uri("gone").toString());f.storage.registry.markUnavailable(p.uri("gone").toString())
        val conflicted=p.add("conflict","renamed.xmap",bytes=MapCodec.encode(MindMapDocument(id=conflict)))
        f.storage.registry.register(conflict,"xnote",conflicted.name,p.uri("conflict").toString())
        val actualMapId=MapCodec.decode(conflicted.readBytes()).id
        val mapObservation=BacklinkLocation(p.uri("conflict"),setOf(p.uri("conflict").toString()),MapSaves.key(p.uri("conflict")),actualMapId,conflicted.name)
        val reader=NetworkFileFactsReader(f.context)
        val before=listOf(a,b,gone,conflicted).map {it.readBytes().toList()};val locations=f.storage.registry.knownLocations()
        val facts=reader.read(setOf(duplicate,unavailable,conflict),p.folder(),listOf(mapObservation))
        assertEquals(BacklinkProblem.DUPLICATE,facts.files.getValue(duplicate).problem)
        assertEquals(NetworkFolderMatch.UNKNOWN,facts.files.getValue(duplicate).folder)
        assertEquals(NetworkFolderMatch.UNKNOWN,facts.files.getValue(unavailable).folder)
        assertEquals(BacklinkProblem.IDENTITY,facts.files.getValue(conflict).problem);assertNull(facts.files.getValue(conflict).kind)
        p.denyQuery=true
        val denied=reader.read(setOf(duplicate,unavailable),p.folder())
        assertTrue(denied.files.values.all {it.folder==NetworkFolderMatch.UNKNOWN});assertTrue(denied.issues.isNotEmpty())
        assertEquals("xnote",f.storage.registry.kindForId(conflict));assertEquals(locations,f.storage.registry.knownLocations())
        assertEquals(before,listOf(a,b,gone,conflicted).map {it.readBytes().toList()})
        assertEquals(0,p.reads.get());assertEquals(0,p.writes.get());assertTrue(f.journalSnapshot().isEmpty())
    }
    @Test fun cancellationCancelsTheActualDirectoryQueryAndNeverFallsBackToReadingTheSourceBody()=scenario {f->
        val p=Provider(f.context,File(f.root,"cancel-provider").apply {mkdirs()});val file=p.add("a","source.xnote");val source=id()
        f.storage.registry.register(source,"xnote",file.name,p.uri("a").toString())
        val bytes=file.readBytes();val entered=CompletableDeferred<Unit>();var cancellation:CancellationSignal?=null
        p.beforeQuery={signal->cancellation=signal;entered.complete(Unit);while(signal?.isCanceled==false) Thread.yield();signal?.throwIfCanceled()}
        try {coroutineScope {
            val task=async {NetworkFileFactsReader(f.context).read(setOf(source),p.folder())}
            entered.await();task.cancelAndJoin();assertTrue("Reader must forward coroutine cancellation to provider metadata IO",cancellation?.isCanceled==true)
        }} finally {p.beforeQuery=null}
        assertArrayEquals(bytes,file.readBytes());assertEquals(0,p.reads.get());assertEquals(0,p.writes.get());assertTrue(f.journalSnapshot().isEmpty())
    }
    @Test fun internalDirectoryBrowsingMissingLocationsAndAllMetadataBudgetsStayReadOnlyAndExplicit()=scenario {f->
        val reader=NetworkFileFactsReader(f.context)
        val child=File(f.notes,"Visible folder").apply {mkdirs()}
        File(f.notes,".xnote/hidden").mkdirs();File(f.notes,"not-a-directory.xnote").writeText("Original bytes")
        val internal=NetworkFolder(f.tree.toString(),"Internal storage")
        val listed=reader.listFolders(internal)
        assertNull(listed.problem);assertFalse(listed.limited)
        assertEquals(listOf("Visible folder"),listed.folders.map {it.title})
        assertEquals(MapSaves.key(f.uri(child)),MapSaves.key(Uri.parse(listed.folders.single().uri)))
        assertTrue(child.delete());val goneFolder=reader.listFolders(listed.folders.single())
        assertTrue(goneFolder.folders.isEmpty());assertNotNull(goneFolder.problem);assertFalse(child.exists())
        val deleted=File(f.notes,"deleted.xnote");val deletedId=native(deleted);val deletedUri=f.uri(deleted)
        f.storage.registry.register(deletedId,"xnote",deleted.name,deletedUri.toString(),pageReferences=true)
        assertTrue(deleted.delete())
        val absent=reader.read(setOf(deletedId),internal)
        assertEquals(NetworkFolderMatch.UNKNOWN,absent.files.getValue(deletedId).folder)
        assertNotNull(absent.files.getValue(deletedId).problem);assertFalse(deleted.exists())
        assertTrue(f.storage.registry.expectationForId(deletedId).requiresPages)

        val p=Provider(f.context,File(f.root,"limits-provider").apply {mkdirs()})
        val missing=p.add("missing","provider-deleted.xnote");val missingId=id()
        f.storage.registry.register(missingId,"xnote",missing.name,p.uri("missing").toString())
        assertTrue(missing.delete());p.missingIds+="missing";p.alwaysChild=true
        val lied=reader.read(setOf(missingId),p.folder())
        assertEquals("A provider saying isChild=true does not replace a missing metadata observation",NetworkFolderMatch.UNKNOWN,lied.files.getValue(missingId).folder)
        assertNotNull(lied.files.getValue(missingId).problem);assertFalse(missing.exists())
        val crowded=id()
        repeat(33) {at->val file=p.add("many-$at","many-$at.xnote")
            f.storage.registry.register(crowded,"xnote",file.name,p.uri("many-$at").toString())}
        val tooMany=reader.read(setOf(crowded),p.folder())
        assertEquals(BacklinkProblem.LIMIT,tooMany.files.getValue(crowded).problem)
        assertEquals(NetworkFolderMatch.UNKNOWN,tooMany.files.getValue(crowded).folder)
        p.listedRows=4097;p.listDirectories=true
        val directories=reader.listFolders(p.folder())
        assertTrue(directories.limited);assertEquals(BacklinkProblem.LIMIT,directories.problem);assertTrue(directories.folders.size<=4096)
        p.listedRows=16385;p.listDirectories=false
        val entries=reader.listFolders(p.folder())
        assertTrue(entries.limited);assertEquals(BacklinkProblem.LIMIT,entries.problem);assertTrue(entries.folders.isEmpty())

        // 313×32 actual SQLite location rows exceed the global cap without exceeding the per-ID cap.
        val ids=List(313) {UUID.nameUUIDFromBytes("network-limit-$it".toByteArray()).toString()}.toSet()
        val db=f.storage.registry.writableDatabase;db.beginTransaction()
        try {
            val document=db.compileStatement("INSERT INTO documents(id,kind,name) VALUES(?,'xnote','Last observed')")
            val location=db.compileStatement("INSERT INTO locations(uri,location_key,document_id,available) VALUES(?,?,?,1)")
            try {for(uuid in ids) {
                document.bindString(1,uuid);document.executeInsert();document.clearBindings()
                repeat(32) {at->val uri=p.uri("global-$uuid-$at").toString()
                    location.bindString(1,uri);location.bindString(2,f.storage.registry.locationKey(uri));location.bindString(3,uuid)
                    location.executeInsert();location.clearBindings()}
            }} finally {document.close();location.close()}
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
        val before=f.storage.registry.knownLocations()
        val bounded=reader.read(ids,null)
        assertTrue(bounded.issues.any {it.problem==BacklinkProblem.LIMIT});assertTrue(bounded.issues.size<=128)
        assertTrue(bounded.files.values.any {it.problem==BacklinkProblem.LIMIT})
        assertEquals(before,f.storage.registry.knownLocations())
        assertEquals(0,p.reads.get());assertEquals(0,p.writes.get());assertEquals(0,f.reads.get());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        assertTrue(f.notes.deleteRecursively());assertFalse(f.notes.exists())
        val missingRoot=reader.listFolders(internal)
        assertEquals(BacklinkProblem.UNAVAILABLE,missingRoot.problem);assertTrue(missingRoot.folders.isEmpty())
        assertFalse("A readonly internal-root probe must never mkdir or fall back to a different root",f.notes.exists())
        assertTrue(f.journalSnapshot().isEmpty())
    }
}
