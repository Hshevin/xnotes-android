package com.xnotes.mindmap

import java.util.UUID
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkFilterControllerTest {
    private fun id()=UUID.randomUUID().toString()
    private data class Case(val source:MapSource,val target:MapSource,val third:MapSource,val document:MindMapDocument)
    private fun data():Case {
        val s=MapSource(documentId=id(),fileName="Center.xnote")
        val t=MapSource(documentId=id(),fileName="Misleading.pdf")
        val u=MapSource(documentId=id(),fileName="Third.xnote")
        return Case(s,t,u,MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),
            MapNode(id="c",parent="r",text="Complete body",sources=listOf(s,t,t.copy(id=id(),note="Another location"),u)))))
    }
    private suspend fun await(check:()->Boolean)=withContext(Dispatchers.Default) {
        withTimeout(15000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
    }
    private fun scenario(block:suspend(MapBacklinkLibraryTest.Fixture,LocalFileNetworkController,Case,java.io.File)->Unit)=
        MapBacklinkLibraryTest().scenario {f->
            val d=data();val file=f.graph("filter-controller.xmap",d.document)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            val c=withContext(Dispatchers.Main) {LocalFileNetworkController(f.context,scope,NetworkQuery(d.source.documentId,"Center",listOf(f.tree.toString())))}
            try {
                withContext(Dispatchers.Main) {assertTrue(c.refresh())}
                await {!c.loading && c.report!=null}
                block(f,c,d,file)
            } finally {withContext(Dispatchers.Main) {c.close()};scope.cancel()}
        }
    @Test fun collectionChangesKeepWholeSourcesAndUseTheUnfilteredCatalogWithoutRereadingTheLibrary()=scenario {f,c,d,file->
        val bytes=file.readBytes();val journals=f.journalSnapshot();val reads=f.reads.get();val trees=f.treeQueries.get()
        withContext(Dispatchers.Main) {
            assertEquals(setOf(d.source.documentId,d.target.documentId,d.third.documentId),c.catalog.map {it.documentId}.toSet())
            assertFalse(c.catalog.any {it.documentId==d.document.id}) // Owner is context, not a source-filter option.
            assertTrue(c.applyFilter(NetworkFilter(fileIds=setOf(d.target.documentId))))
            assertFalse(c.filtering);assertEquals(1,c.report!!.concepts.size)
            assertEquals(d.document.node("c").sources.map {it.id},c.report!!.concepts.single().members.map {it.row.sourceId})
            assertEquals(setOf(d.target.documentId),c.report!!.matchedFiles);assertTrue(c.report!!.filterActive)
            assertEquals(setOf(d.source.documentId,d.target.documentId,d.third.documentId),c.catalog.map {it.documentId}.toSet())
            val members=c.report!!.concepts.single().members
            assertNotNull(c.captureFocus(members.first(),members[2]))
        }
        assertEquals(reads,f.reads.get());assertEquals(trees,f.treeQueries.get())
        assertArrayEquals(bytes,file.readBytes());assertEquals(journals,f.journalSnapshot());assertEquals(0,f.writes.get())
    }
    @Test fun filterAtoBtoARetiresOldMembersAndAnOldDraftRevisionCannotOverwriteTheNewSelection()=scenario {_,c,d,_->
        withContext(Dispatchers.Main) {
            val original=c.report!!.concepts.single().members;val request=c.captureFocus(original[0],original[1])!!
            val draftRevision=c.filterRevision
            assertTrue(c.applyFilter(NetworkFilter(fileIds=setOf(d.target.documentId)),draftRevision))
            assertFalse(request.current());assertTrue(c.applyFilter(NetworkFilter()))
            assertFalse(request.current());assertNull(c.captureFocus(original[0],original[1]))
            assertFalse(c.applyFilter(NetworkFilter(fileIds=emptySet()),draftRevision))
            assertFalse(c.filter.active);assertEquals(1,c.report!!.concepts.size)
            val latest=c.report!!.concepts.single().members
            assertNotNull(c.captureFocus(latest[0],latest[1]))
        }
    }
    @Test fun emptySelectionIsAnExplicitFilterResultAndClearingItDoesNotLoseTheCatalog()=scenario {_,c,d,_->
        withContext(Dispatchers.Main) {
            val revision=c.filterRevision
            assertTrue(c.applyFilter(NetworkFilter(),revision));assertEquals(revision,c.filterRevision) // No-op.
            assertTrue(c.applyFilter(NetworkFilter(fileIds=emptySet())))
            assertTrue(c.report!!.complete);assertTrue(c.report!!.filterActive);assertEquals(1,c.report!!.filteredConcepts)
            assertTrue(c.report!!.concepts.isEmpty());assertEquals(listOf(d.source.documentId),c.report!!.files.map {it.documentId})
            assertEquals(3,c.catalog.size)
            assertTrue(c.applyFilter(NetworkFilter()));assertFalse(c.report!!.filterActive);assertEquals(1,c.report!!.concepts.size)
        }
    }
    @Test fun typedMetadataUsesTheRegistryAndCancelOrCloseCannotAdoptALateFilterResult()=scenario {f,c,d,file->
        withContext(Dispatchers.IO) {
            f.storage.registry.register(d.source.documentId,"xnote","Center.xnote",f.uri(file).toString()+"-source")
            f.storage.registry.register(d.target.documentId,"xcanvas","Misleading.pdf",f.uri(file).toString()+"-target")
            f.storage.registry.register(d.third.documentId,"xnote","Third.xnote",f.uri(file).toString()+"-third")
        }
        val bytes=file.readBytes();val reads=f.reads.get()
        withContext(Dispatchers.Main) {assertTrue(c.applyFilter(NetworkFilter(kinds=setOf(NetworkFileKind.CANVAS))))}
        await {!c.filtering}
        withContext(Dispatchers.Main) {
            assertEquals(1,c.report!!.concepts.size);assertEquals(setOf(d.target.documentId),c.report!!.matchedFiles)
            assertTrue(c.report!!.unknownFilterFiles.isEmpty())
            val retained=c.report
            assertTrue(c.applyFilter(NetworkFilter(kinds=setOf(NetworkFileKind.NOTE))))
            assertTrue(c.filtering);c.cancel();assertFalse(c.filtering)
            assertSame(retained,c.report)
            assertFalse(c.applyFilter(NetworkFilter(),c.filterRevision-1))
        }
        await {!c.filtering}
        withContext(Dispatchers.Main) {
            assertTrue(c.refresh())
        }
        await {!c.loading && !c.filtering}
        withContext(Dispatchers.Main) {
            val latest=c.report
            assertTrue(c.applyFilter(NetworkFilter(kinds=setOf(NetworkFileKind.CANVAS))))
            assertTrue(c.filtering);c.close();assertFalse(c.filtering);assertSame(latest,c.report)
            assertFalse(c.applyFilter(NetworkFilter()))
        }
        assertArrayEquals(bytes,file.readBytes());assertTrue(f.reads.get()>=reads);assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun aLiveSourceAddedWhileTheOldFolderQueryIsBlockedWinsOverItsLateResult()=scenario {f,c,d,file->
        val provider=NetworkFileFactsReaderTest.Provider(f.context,File(f.root,"controller-metadata").apply {mkdirs()})
        val added=MapSource(documentId=id(),fileName="A newly added source.xnote")
        val ids=listOf(d.source.documentId,d.target.documentId,d.third.documentId,added.documentId)
        withContext(Dispatchers.IO) {ids.forEachIndexed {index,uuid->
            provider.add("item-$index","source-$index.xnote",inFolder=index==1 || index==3)
            f.storage.registry.register(uuid,"xnote","Source $index",provider.uri("item-$index").toString())
        }}
        val lease=withContext(Dispatchers.Main) {MapDocuments.acquire(f.context,f.uri(file)).also(f.leases::add)}
        await {!lease.session.loading && !c.loading &&
            c.report?.concepts?.singleOrNull()?.members?.all {it.row.origin==BacklinkOrigin.LIVE}==true}
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val queries=AtomicInteger()
        var oldSignal:android.os.CancellationSignal?=null
        provider.beforeQuery={signal->if(queries.incrementAndGet()==1) {
            oldSignal=signal;entered.countDown();check(release.await(10,TimeUnit.SECONDS))
        }}
        val original=file.readBytes()
        try {
            withContext(Dispatchers.Main) {assertTrue(c.applyFilter(NetworkFilter(folder=provider.folder())))}
            assertTrue(withContext(Dispatchers.IO) {entered.await(10,TimeUnit.SECONDS)})
            withContext(Dispatchers.Main) {
                assertTrue(c.filtering)
                assertTrue(lease.session.operate {it.update("c") {node->node.copy(sources=node.sources+added)}})
                lease.session.cancelPendingAutosave()
            }
            await {c.catalog.any {it.documentId==added.documentId} && !c.filtering && c.report!!.concepts.single().members.any {it.row.sourceId==added.id}}
            withContext(Dispatchers.Main) {
                assertTrue("Retiring the old facts task cancels its actual metadata query",oldSignal?.isCanceled==true)
                assertEquals(setOf(d.target.documentId,added.documentId),c.report!!.matchedFiles)
                assertEquals(d.document.node("c").sources.map {it.id}+added.id,c.report!!.concepts.single().members.map {it.row.sourceId})
            }
            release.countDown()
            withContext(Dispatchers.Default) {delay(30)}
            withContext(Dispatchers.Main) {
                assertTrue(c.report!!.concepts.single().members.any {it.row.sourceId==added.id})
                assertEquals(setOf(d.target.documentId,added.documentId),c.report!!.matchedFiles)
            }
            assertArrayEquals(original,file.readBytes());assertEquals(0,provider.reads.get());assertEquals(0,provider.writes.get())
            assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
        } finally {release.countDown();provider.beforeQuery=null}
    }
    @Test fun aLastKnownFormatStillMatchesWithoutAnAvailableLocationButAFolderRequiresCurrentLocationFacts()=scenario {f,c,d,file->
        val provider=NetworkFileFactsReaderTest.Provider(f.context,File(f.root,"unavailable-kind").apply {mkdirs()})
        val target=provider.add("target","A last observed canvas.xnote")
        withContext(Dispatchers.IO) {
            f.storage.registry.register(d.target.documentId,"xcanvas",target.name,provider.uri("target").toString())
            f.storage.registry.markUnavailable(provider.uri("target").toString())
        }
        val bytes=file.readBytes();val targetBytes=target.readBytes()
        val kind=NetworkFilter(fileIds=setOf(d.target.documentId),kinds=setOf(NetworkFileKind.CANVAS))
        withContext(Dispatchers.Main) {assertTrue(c.applyFilter(kind))}
        await {!c.filtering}
        withContext(Dispatchers.Main) {
            assertEquals(1,c.report!!.concepts.size);assertEquals(setOf(d.target.documentId),c.report!!.matchedFiles)
            assertEquals(d.target.status,c.report!!.concepts.single().members.first {it.fileId==d.target.documentId}.row.status)
        }
        assertEquals(0,provider.queries.get())
        withContext(Dispatchers.IO) {
            f.storage.registry.writableDatabase.execSQL("DELETE FROM locations WHERE document_id=?",arrayOf(d.target.documentId))
        }
        withContext(Dispatchers.Main) {assertTrue(c.refresh())}
        await {!c.loading && !c.filtering}
        withContext(Dispatchers.Main) {
            assertEquals(1,c.report!!.concepts.size);assertEquals(setOf(d.target.documentId),c.report!!.matchedFiles)
            assertTrue(c.applyFilter(NetworkFilter(kind.fileIds,kind.kinds,provider.folder())))
        }
        await {!c.filtering}
        withContext(Dispatchers.Main) {
            assertFalse(c.report!!.complete);assertTrue(c.report!!.concepts.isEmpty())
            assertEquals(setOf(d.target.documentId),c.report!!.unknownFilterFiles)
            assertTrue(c.report!!.issues.any {it.problem==BacklinkProblem.UNAVAILABLE})
        }
        assertArrayEquals(bytes,file.readBytes());assertArrayEquals(targetBytes,target.readBytes())
        assertEquals(0,provider.reads.get());assertEquals(0,provider.writes.get());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
}
