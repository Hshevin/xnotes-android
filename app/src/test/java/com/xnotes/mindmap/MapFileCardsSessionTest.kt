package com.xnotes.mindmap

import java.util.UUID
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapFileCardsSessionTest {
    private fun seed(name:String)=MapFileCardSeed(UUID.randomUUID().toString(),name,400.0,200.0)
    @Test fun aRealActorImportsOneBatchOntoTheLatestBodyWithOneUndoAndSavesTheSameCardIdentities()=MapBacklinkLibraryTest().scenario {f->
        val original=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Before")))
        val file=f.graph("batch.xmap",original);val lease=f.acquire(f.uri(file))
        withContext(Dispatchers.Main) {
            val s=lease.session;val input=mutableListOf(seed("A"),seed("B"));val ticket=s.captureFileCards(input)!!
            input.clear()
            val body=original.update("r") {it.copy(text="Concurrent body",x=321.0)}
            assertTrue(s.operate {body});s.cancelPendingAutosave()
            assertTrue(s.fileCardsCurrent(ticket));val applied=s.importFileCards(ticket) as MapFileCardsImportResult.Applied
            s.cancelPendingAutosave();assertFalse(applied.alreadyPresent);assertEquals(2,applied.nodeIds.size)
            assertEquals(body.node("r"),s.document!!.node("r"));val accepted=s.document!!;val revision=s.contentRevision
            val repeated=s.importFileCards(ticket) as MapFileCardsImportResult.Applied
            assertTrue(repeated.alreadyPresent);assertEquals(applied.nodeIds,repeated.nodeIds);assertEquals(revision,s.contentRevision)
            s.undo();s.cancelPendingAutosave();assertEquals(body,s.document);assertFalse(s.fileCardsCurrent(ticket))
            s.redo();s.cancelPendingAutosave();assertEquals(accepted,s.document)
            assertEquals(MapFileCardsImportResult.Changed,s.importFileCards(ticket))
            assertTrue(s.saveAttempt().await() is MapSaveOutcome.Written)
            assertEquals(accepted,MapCodec.decode(file.readBytes()))
        }
        assertEquals(1,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun textInkAndConnectedDraftsAreBusyAndNeverGetAutomaticallyConfirmed()=MapBacklinkLibraryTest().scenario {f->
        val doc=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Text")))
        val file=f.graph("busy.xmap",doc);val lease=f.acquire(f.uri(file));val bytes=file.readBytes()
        withContext(Dispatchers.Main) {
            val s=lease.session;val before=s.document;val ticket=s.captureFileCards(listOf(seed("A")))!!
            s.inkContact=true;assertEquals(MapFileCardsImportResult.Busy,s.importFileCards(ticket));assertNull(s.captureFileCards(listOf(seed("B"))))
            s.inkContact=false
            assertTrue(s.beginText("r",MindMapView.Viewport(1f,0f,0f),1000,700));val text=s.textDraft!!.token
            assertTrue(s.updateTextDraft(text,TextFieldValue("Still a draft")))
            assertEquals(MapFileCardsImportResult.Busy,s.importFileCards(ticket));assertEquals("Still a draft",s.textDraft!!.value.text)
            assertSame(before,s.document);assertFalse(s.canUndo);s.cancelTextDraft(text)
            s.textRestore?.let {s.completeTextRestore(it.token)}
            assertTrue(s.beginConnectedNode("r",com.xnotes.core.geometry.Pt(600.0,200.0)))
            assertEquals(MapFileCardsImportResult.Busy,s.importFileCards(ticket));assertNotNull(s.connectedDraft)
            s.cancelConnectedNode(s.connectedDraft!!.token)
            assertTrue(s.importFileCards(ticket) is MapFileCardsImportResult.Applied);s.cancelPendingAutosave()
            assertEquals("Text",s.document!!.node("r").text)
        }
        assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun swappingTwoValidCardRolesCannotReuseTheAlreadyAppliedNodeBindings()=MapBacklinkLibraryTest().scenario {f->
        val seeds=listOf(seed("A"),seed("B"));val doc=MindMapDocument().addFileCards(seeds).document
        val file=f.graph("swapped.xmap",doc);val bytes=file.readBytes();val lease=f.acquire(f.uri(file))
        withContext(Dispatchers.Main) {
            val s=lease.session;val ticket=s.captureFileCards(seeds)!!
            assertTrue((s.importFileCards(ticket) as MapFileCardsImportResult.Applied).alreadyPresent)
            assertTrue(s.fileCardsCurrent(ticket))
            val a=s.document!!.nodes.single {it.fileCardId==seeds[0].documentId}
            val b=s.document!!.nodes.single {it.fileCardId==seeds[1].documentId}
            val exchanged=s.document!!.copy(nodes=s.document!!.nodes.map {node->
                val newId=when(node.id) {a.id->seeds[1].documentId;b.id->seeds[0].documentId;else->null}
                if(newId==null) node else node.copy(fileCardId=newId,sources=node.sources.map {it.copy(documentId=newId)})
            })
            exchanged.validate();assertTrue(s.operate {exchanged});s.cancelPendingAutosave()
            assertFalse(s.fileCardsCurrent(ticket));assertEquals(MapFileCardsImportResult.Changed,s.importFileCards(ticket))
            assertEquals(exchanged,s.document)
            assertEquals(a.sources.single().id,s.document!!.node(a.id).sources.single().id)
            assertEquals(b.sources.single().id,s.document!!.node(b.id).sources.single().id)
        }
        assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun adoptingTheFileVersionOfTheSameUuidInTheSameActorDoesNotReviveAnEarlierBatchTicket()=MapBacklinkLibraryTest().scenario {f->
        val doc=MindMapDocument();val file=f.graph("reopened.xmap",doc);val lease=f.acquire(f.uri(file));val bytes=file.readBytes()
        val ticket=withContext(Dispatchers.Main) {
            val captured=lease.session.captureFileCards(listOf(seed("A")))!!
            lease.session.useFileVersion();captured
        }
        withTimeout(10000) {while(withContext(Dispatchers.Main) {lease.session.replacingDocument || lease.session.saving}) delay(1)}
        withContext(Dispatchers.Main) {
            assertEquals(doc.id,lease.session.document!!.id);assertFalse(lease.session.fileCardsCurrent(ticket))
            assertEquals(MapFileCardsImportResult.Changed,lease.session.importFileCards(ticket))
            assertFalse(lease.session.dirty);assertFalse(lease.session.canUndo)
        }
        assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun explicitFileCardsAreNotContentBacklinksOrNetworkConceptMemberships()=MapBacklinkLibraryTest().scenario {f->
        val item=seed("A");val concept=MapNode(id="r",parent=null,sources=listOf(MapSource(documentId=item.documentId)))
        val doc=MindMapDocument(nodes=listOf(concept)).addFileCards(listOf(item)).document
        val file=f.graph("roles.xmap",doc);val reader=MapBacklinkLibrary(f.context,listOf(f.tree.toString()))
        assertEquals(listOf("r"),reader.scan(item.documentId).graphs.single().rows.map {it.nodeId})
        assertEquals(listOf("r"),reader.scanNetwork(item.documentId).graphs.single().concepts.map {it.key.nodeId})
        assertEquals(listOf("r"),reader.scanExpandedNetwork(item.documentId,setOf(doc.id)).graphs.single().concepts.map {it.key.nodeId})
        assertEquals(listOf("r"),reader.scanSelectedNetwork(setOf(doc.id),emptySet()).graphs.single().concepts.map {it.key.nodeId})
        assertEquals(listOf("r"),reader.scanLibraryNetwork().graphs.single().concepts.map {it.key.nodeId})
        assertEquals(doc,MapCodec.decode(file.readBytes()));assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
}
