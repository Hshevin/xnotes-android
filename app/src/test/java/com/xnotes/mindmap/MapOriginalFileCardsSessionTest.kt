package com.xnotes.mindmap

import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapOriginalFileCardsSessionTest {
    private fun original():MapFileCardSeed {
        val id=UUID.randomUUID().toString()
        return MapFileCardSeed(id,"Original",400.0,200.0,MapSourceTarget(id,SourceScope.PDF_ORIGINAL,
            UUID.randomUUID().toString(),originalPdf=PdfOriginalProjection("a".repeat(64),0)))
    }
    @Test fun aMixedBatchIsOneUndoAndOnlyItsExplicitRelationBecomesNetworkEvidence()=MapBacklinkLibraryTest().scenario {f->
        val original=original();val native=MapFileCardSeed(UUID.randomUUID().toString(),"Note",800.0,200.0)
        val base=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Keep body")))
        val file=f.graph("original-cards.xmap",base);val lease=f.acquire(f.uri(file))
        withContext(Dispatchers.Main) {
            val session=lease.session;val receipt=session.captureFileCards(listOf(original,native))!!
            val result=session.importFileCards(receipt) as MapFileCardsImportResult.Applied
            session.cancelPendingAutosave();val imported=session.document!!
            assertEquals(2,result.nodeIds.size);assertTrue(session.fileCardsCurrent(receipt))
            session.undo();session.cancelPendingAutosave();assertEquals(base,session.document)
            session.redo();session.cancelPendingAutosave();assertEquals(imported,session.document)
            assertFalse(session.fileCardsCurrent(receipt))
            val cards=imported.nodes.filter {it.fileCardId!=null}
            assertTrue(session.operate {it.connect(cards[0].id,cards[1].id,RelationKind.DEPENDS_ON).first})
            session.cancelPendingAutosave();assertTrue(session.saveAttempt().await() is MapSaveOutcome.Written)
        }
        val stored=MapCodec.decode(file.readBytes());assertEquals(6,MapCodec.toJson(stored).getInt("version"))
        val reader=MapBacklinkLibrary(f.context,listOf(f.tree.toString()))
        assertTrue(reader.scan(original.documentId).graphs.flatMap {it.rows}.isEmpty())
        val network=reader.scanLibraryNetwork()
        assertTrue(network.graphs.flatMap {it.concepts}.isEmpty())
        val row=network.graphs.flatMap {it.fileRelations}.single()
        assertEquals(setOf(original.documentId,native.documentId),row.fileIds);assertEquals(RelationKind.DEPENDS_ON,row.relation.kind)
        assertEquals(original.target,stored.nodes.single {it.fileCardId==original.documentId}.sources.single().target)
    }
    @Test fun derivedStatusKeepsCardAndRelationReceiptsButSourceIdOrExactTargetReplacementRetiresThem()=MapBacklinkLibraryTest().scenario {f->
        for(replaceId in listOf(true,false)) {
            val original=original();val native=MapFileCardSeed(UUID.randomUUID().toString(),"Note",800.0,200.0)
            val added=MindMapDocument().addFileCards(listOf(original,native))
            val cards=added.document.nodes.filter {it.fileCardId!=null}
            val initial=added.document.connect(cards[0].id,cards[1].id,RelationKind.RELATED).first
            val lease=f.acquire(f.uri(f.graph("card-life-$replaceId.xmap",initial)))
            withContext(Dispatchers.Main) {
                val session=lease.session;val receipt=session.captureFileCards(listOf(original,native))!!
                assertTrue((session.importFileCards(receipt) as MapFileCardsImportResult.Applied).alreadyPresent)
                val relation=session.captureFileRelationMembership(initial.relations.single().id) {true}!!
                val card=session.document!!.nodes.single {it.fileCardId==original.documentId};val source=card.sources.single()
                assertTrue(session.mergeSourceDerived(listOf(MapSourceDerivedPatch(card.id,source.id,source.target,
                    SourceStatus.TRASHED,"Last observed title",null,1))) {true})
                session.cancelPendingAutosave();assertTrue(session.fileCardsCurrent(receipt));assertTrue(relation.current())
                assertFalse(session.canUndo)
                val changed=session.document!!.update(card.id) {node->
                    val replacement=node.sources.single().let {
                        if(replaceId) it.copy(id=UUID.randomUUID().toString()) else it.copy(pageId=UUID.randomUUID().toString())
                    }
                    node.copy(sources=listOf(replacement),primarySource=replacement.id)
                }
                // A source replacement must also move the node's primary pointer; an orphaned
                // primarySource is an invalid model and must never enter content history.
                changed.validate();assertNotEquals(session.document,changed)
                assertTrue(session.operate {changed});session.cancelPendingAutosave()
                assertFalse(session.fileCardsCurrent(receipt));assertFalse(relation.current())
                session.undo();session.cancelPendingAutosave()
                assertFalse(session.fileCardsCurrent(receipt));assertFalse(relation.current())
                assertEquals(MapFileCardsImportResult.Changed,session.importFileCards(receipt))
            }
        }
    }
}
