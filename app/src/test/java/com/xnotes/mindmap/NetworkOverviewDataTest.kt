package com.xnotes.mindmap

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkOverviewDataTest {
    private fun id()=UUID.randomUUID().toString()
    private fun source(uuid:String)=MapSource(documentId=uuid,fileName="$uuid.xnote")
    private fun graph(vararg nodes:MapNode)=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null))+nodes)
    private fun concept(name:String,vararg sources:MapSource)=MapNode(id=name,parent="r",text=name,sources=sources.toList())
    private suspend fun await(check:()->Boolean)=withContext(Dispatchers.Default) {
        withTimeout(15000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
    }
    private suspend fun controller(f:MapBacklinkLibraryTest.Fixture,query:NetworkQuery,block:suspend(LocalFileNetworkController)->Unit)=withContext(Dispatchers.Main) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val c=LocalFileNetworkController(f.context,scope,query)
        try {assertTrue(c.refresh());await {!c.loading && !c.filtering && c.report!=null};block(c)}
        finally {c.close();scope.cancel()}
    }
    @Test fun libraryProjectsEveryRealSourceConceptInOneReadPerGraphWithoutInventingBodyReferences()=MapBacklinkLibraryTest().scenario {f->
        val s=source(id());val t=source(id());val u=source(id())
        val a=f.graph("one.xmap",graph(concept("st",s,t),concept("no-sources")))
        val b=f.graph("two.xmap",graph(concept("u",u)))
        val bytes=listOf(a,b).map {it.readBytes()};val journal=f.journalSnapshot()
        val scan=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scanLibraryNetwork()
        assertEquals(setOf("st","u"),scan.graphs.flatMap {it.concepts}.map {it.key.nodeId}.toSet())
        assertEquals(3,scan.graphs.flatMap {it.concepts}.sumOf {it.members.size});assertEquals(2,f.reads.get())
        assertTrue(scan.issues.isEmpty());assertFalse(scan.limited)
        listOf(a,b).forEachIndexed {i,file->assertArrayEquals(bytes[i],file.readBytes())}
        assertEquals(journal,f.journalSnapshot());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun libraryUsesActualOwnersAndAvailableRegisteredOrphansButNeverAnInternalFocusKey()=MapBacklinkLibraryTest().scenario {f->
        val s=source(id());val t=source(id());val doc=graph(concept("st",s,t));val map=f.graph("one.xmap",doc)
        val native=File(f.notes,"isolated.xnote").apply {writeBytes(byteArrayOf(1,4,9))};val nativeId=id()
        val unavailable=id();val unavailableUri=f.uri(File(f.notes,"unavailable.xcanvas")).toString()
        withContext(Dispatchers.IO) {
            f.storage.registry.register(nativeId,"xnote","Last identified orphan",f.uri(native).toString())
            f.storage.registry.register(unavailable,"xcanvas","Unavailable",unavailableUri)
            f.storage.registry.markUnavailable(unavailableUri)
        }
        val bytes=native.readBytes();val query=NetworkQuery.library("Library",listOf(f.tree.toString()))
        controller(f,query) {c->
            assertEquals(NetworkBrowseMode.LIBRARY,c.browseMode);assertTrue(c.centreFiles.isEmpty())
            assertEquals(setOf(s.documentId,t.documentId,doc.id,nativeId),c.report!!.files.map {it.documentId}.toSet())
            assertFalse(c.report!!.files.any {it.documentId==query.focusId || it.documentId==unavailable})
            assertEquals(NetworkOriginKind.OWNER,c.report!!.concepts.single().origin!!.kind)
            assertTrue(c.fileChoices.any {it.documentId==nativeId && "Last identified orphan" in it.observedNames})
            assertFalse(c.showLocal());assertFalse(c.setExpanded(doc.id,true))
            val member=c.report!!.concepts.single().members.last();val request=c.captureFocus(member,member)!!
            assertSame(request.anchor,request.target);assertEquals(t.target,request.target.target)
        }
        assertEquals(1,f.reads.get());assertArrayEquals(bytes,native.readBytes());assertTrue(map.exists())
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun modeChangesUseTheLibraryChoicesKeepFiltersAndPermanentlyRetireOldActionsAndDrafts()=MapBacklinkLibraryTest().scenario {f->
        val s=source(id());val t=source(id());val u=source(id())
        val a=graph(concept("st",s,t));val b=graph(concept("u",u));f.graph("a.xmap",a);f.graph("b.xmap",b)
        controller(f,NetworkQuery(s.documentId,"S",listOf(f.tree.toString()))) {c->
            assertEquals(NetworkBrowseMode.LOCAL,c.browseMode);assertTrue(c.fileChoices.isEmpty())
            assertFalse(c.showCollection(setOf(u.documentId)))
            assertTrue(c.showLibrary());await {!c.loading && !c.filtering && c.browseMode==NetworkBrowseMode.LIBRARY && c.report!!.concepts.size==2}
            val old=c.report!!.concepts.first().members.first();val request=c.captureFocus(old,old)!!
            val draftRevision=c.filterRevision
            assertTrue(c.applyFilter(NetworkFilter(fileIds=setOf(u.documentId))))
            assertTrue(c.showCollection(setOf(u.documentId)));await {!c.loading && !c.filtering && c.browseMode==NetworkBrowseMode.FILES}
            assertEquals(setOf(u.documentId),c.centreFiles);assertEquals(setOf("u"),c.report!!.concepts.map {it.key.nodeId}.toSet())
            assertTrue(c.filter.active);assertFalse(request.current());assertFalse(c.showLibrary(draftRevision))
            assertTrue(c.showLocal());await {!c.loading && !c.filtering && c.browseMode==NetworkBrowseMode.LOCAL}
            assertTrue(c.filter.active);assertTrue(c.report!!.concepts.isEmpty());assertFalse(request.current())
            assertTrue(c.showLibrary());await {!c.loading && !c.filtering && c.browseMode==NetworkBrowseMode.LIBRARY}
            assertFalse(request.current());assertTrue(c.fileChoices.any {it.documentId==b.id})
            assertFalse(c.showCollection(emptySet()));assertFalse(c.showCollection(setOf(id())))
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun selectedRootsAreMatchedBeforeChargingEvidenceAndUnrelatedGraphsCannotUseTheirQuota()=MapBacklinkLibraryTest().scenario {f->
        val s=source(id());val t=source(id());val unrelated=source(id())
        f.graph("a-unrelated.xmap",graph(*Array(5) {concept("other$it",unrelated.copy(id=id()))}))
        f.graph("z-selected.xmap",graph(concept("selected",s,t)))
        val scan=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scanSelectedNetwork(setOf(s.documentId),emptySet(),
            networkBudget=LocalNetworkBudget(maxEvidence=2))
        assertEquals(listOf("selected"),scan.graphs.flatMap {it.concepts}.map {it.key.nodeId})
        assertEquals(2,scan.graphs.flatMap {it.concepts}.sumOf {it.members.size})
        assertEquals(2,f.reads.get());assertFalse(scan.limited);assertTrue(scan.issues.isEmpty())
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun aRegisteredNativeAtAReusedLocationCannotReappearAsAnOrphanOfTheOldUuid()=MapBacklinkLibraryTest().scenario {f->
        val oldId=id();val target=source(id());val actual=graph(concept("actual",target))
        val file=f.graph("reused.xmap",actual);val uri=f.uri(file).toString();val bytes=file.readBytes()
        withContext(Dispatchers.IO) {f.storage.registry.register(oldId,"xnote","Old native identity",uri)}
        val journal=f.journalSnapshot()
        controller(f,NetworkQuery.library("Library",listOf(f.tree.toString()))) {c->
            assertFalse(c.report!!.complete);assertTrue(c.report!!.issues.any {it.problem==BacklinkProblem.IDENTITY})
            assertFalse(c.fileChoices.any {it.documentId==oldId || it.documentId==actual.id})
            assertFalse(c.report!!.files.any {it.documentId==oldId})
            assertFalse(c.showCollection(setOf(oldId)));assertFalse(c.showCollection(setOf(actual.id)))
            val row=c.report!!.concepts.single().members.single()
            assertEquals(BacklinkProblem.IDENTITY,row.row.blocked);assertNull(c.captureFocus(row,row))
        }
        assertEquals(oldId,f.storage.registry.idAt(uri));assertEquals("xnote",f.storage.registry.kindForId(oldId))
        assertNull(f.storage.registry.kindForId(actual.id));assertArrayEquals(bytes,file.readBytes())
        assertEquals(journal,f.journalSnapshot());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun availableIdentityInventoryHasAnExplicitOverflowAndCancelsWithoutReadingAnySourceBody()=MapBacklinkLibraryTest().scenario {f->
        withContext(Dispatchers.IO) {
            val db=f.storage.registry.writableDatabase
            val document=db.compileStatement("INSERT INTO documents(id,kind,name) VALUES(?,'xnote',?)")
            val location=db.compileStatement("INSERT INTO locations(uri,location_key,document_id,available) VALUES(?,?,?,1)")
            db.beginTransaction()
            try {
                repeat(10001) {n->
                    val uuid=UUID(0,n.toLong()+1).toString();val uri="content://inventory/document/$n"
                    document.bindString(1,uuid);document.bindString(2,"Last observed $n");document.executeInsert()
                    location.bindString(1,uri);location.bindString(2,uri);location.bindString(3,uuid);location.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally {db.endTransaction();document.close();location.close()}
            val read=f.storage.registry.knownAvailableFiles()
            assertTrue(read.limited);assertEquals(10000,read.files.size)
            assertTrue(read.files.values.all {it.kind=="xnote" && it.locations.single().available})
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                f.storage.registry.knownAvailableFiles(checkActive={throw java.util.concurrent.CancellationException()})
            }
            assertEquals(10001,db.rawQuery("SELECT COUNT(*) FROM documents",null).use {it.moveToFirst();it.getInt(0)})
        }
        assertEquals(0,f.reads.get());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun actualMapWithAnAlreadyNativeUuidCannotWashTheRegistryOrBecomeAnAvailableOrphan()=MapBacklinkLibraryTest().scenario {f->
        val s=source(id());val doc=graph(concept("s",s));val file=f.graph("conflict.xmap",doc)
        withContext(Dispatchers.IO) {f.storage.registry.register(doc.id,"xnote","A known paginated note",f.uri(file).toString(),pageReferences=true)}
        val bytes=file.readBytes()
        controller(f,NetworkQuery.library("Library",listOf(f.tree.toString()))) {c->
            assertTrue(c.report!!.issues.any {it.problem==BacklinkProblem.IDENTITY});assertFalse(c.report!!.complete)
            assertTrue(c.report!!.concepts.flatMap {it.members}.all {it.row.blocked==BacklinkProblem.IDENTITY})
            assertFalse(c.fileChoices.any {it.documentId==doc.id})
            val member=c.report!!.concepts.single().members.single();assertNull(c.captureFocus(member,member))
        }
        assertEquals("xnote",f.storage.registry.kindForId(doc.id));assertTrue(f.storage.registry.expectationForId(doc.id).requiresPages)
        withContext(Dispatchers.IO) {
            f.storage.registry.writableDatabase.execSQL("DELETE FROM locations WHERE document_id=?",arrayOf(doc.id))
            f.storage.registry.writableDatabase.execSQL("DELETE FROM documents WHERE id=?",arrayOf(doc.id))
        }
        assertTrue(f.storage.registry.expectationForId(doc.id).requiresPages)
        controller(f,NetworkQuery.library("Tombstone",listOf(f.tree.toString()))) {c->
            assertTrue(c.report!!.issues.any {it.problem==BacklinkProblem.IDENTITY})
            assertTrue(c.report!!.concepts.flatMap {it.members}.all {it.row.blocked==BacklinkProblem.IDENTITY})
            assertFalse(c.fileChoices.any {it.documentId==doc.id})
        }
        assertNull(f.storage.registry.kindForId(doc.id))
        assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
}
