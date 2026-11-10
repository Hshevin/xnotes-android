package com.xnotes.mindmap

import com.xnotes.ui.BrowseEntry
import com.xnotes.ui.Editor
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual locked graph reads return separate file-edge evidence without touching source bodies. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapFileRelationLibraryTest {
    private fun id()=UUID.randomUUID().toString()
    private fun card(name:String,file:String)=MapNode(id=name,parent=null,free=true,kind=NodeKind.REFERENCE,
        text=name,sources=listOf(MapSource(documentId=file,fileName="$name.xnote")),fileCardId=file)
    private fun document(vararg cards:MapNode,relations:List<MapRelation>,concepts:List<MapNode> = emptyList())=
        MindMapDocument(nodes=cards.toList()+concepts,relations=relations,organization=MapOrganization.RELATION_CANVAS)
            .also {it.validate()}
    private fun edge(from:String,to:String,kind:RelationKind=RelationKind.RELATED,label:String="")=
        MapRelation(id=id(),from=from,to=to,kind=kind,label=label)

    @Test fun oneActualReadKeepsExplicitFileEdgesSeparateFromContentAndNeverInfersAnOrdinaryReferenceEdge()=
        MapBacklinkLibraryTest().scenario {f->
            val a=id();val b=id();val c=id()
            val ordinary=MapNode(id="reference",parent=null,free=true,kind=NodeKind.REFERENCE,
                sources=listOf(MapSource(documentId=c,fileName="ordinary.xnote")))
            val concept=MapNode(id="concept",parent=null,free=true,text="Multi-source concept",
                sources=listOf(MapSource(documentId=a),MapSource(documentId=b)))
            val explicit=edge("A","B",RelationKind.DEPENDS_ON,"A requires B")
            val doc=document(card("A",a),card("B",b),relations=listOf(explicit,edge("A","concept"),edge("reference","B")),
                concepts=listOf(ordinary,concept))
            val file=f.graph("independent-evidence.xmap",doc);val bytes=file.readBytes();val journal=f.journalSnapshot()
            val scan=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scanNetwork(a)
            assertEquals(1,f.reads.get());assertEquals(1,scan.graphs.size)
            val graph=scan.graphs.single();assertEquals(listOf("concept"),graph.concepts.map {it.key.nodeId})
            assertEquals(setOf(a,b),graph.concepts.single().members.map {it.target.documentId}.toSet())
            val relation=graph.fileRelations.single()
            assertEquals(explicit,relation.relation);assertTrue(relation.relation.directed)
            assertEquals(a,relation.fromFileId);assertEquals(b,relation.toFileId)
            assertEquals("A",relation.fromTitle);assertEquals("B",relation.toTitle)
            assertEquals(FileRelationKey(graph.candidate.key,doc.id,explicit.id),relation.key)
            assertNull(relation.membership);assertNull(relation.blocked)
            assertArrayEquals(bytes,file.readBytes());assertEquals(journal,f.journalSnapshot())
            assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get());assertTrue(f.storage.registry.knownMapLocations().isEmpty())
        }

    @Test fun localSelectedExpandedAndLibraryScopesUseRealEndpointsWithoutInventingOwnershipEdges()=MapBacklinkLibraryTest().scenario {f->
        val a=id();val b=id();val c=id();val d=id()
        val ab=edge("A","B");val bc=edge("B","C");val cd=edge("C","D")
        val doc=document(card("A",a),card("B",b),card("C",c),card("D",d),relations=listOf(ab,bc,cd))
        f.graph("scopes.xmap",doc)
        fun NetworkLibraryScan.relations()=graphs.flatMap {it.fileRelations}.map {it.relation.id}.toSet()
        fun reader()=MapBacklinkLibrary(f.context,listOf(f.tree.toString()))
        assertEquals(setOf(ab.id),reader().scanNetwork(a).relations())
        assertEquals(setOf(ab.id,bc.id),reader().scanExpandedNetwork(a,setOf(b)).relations())
        assertEquals(setOf(ab.id),reader().scanExpandedNetwork(a,setOf(doc.id)).relations())
        assertEquals(setOf(ab.id,bc.id),reader().scanSelectedNetwork(setOf(b),emptySet()).relations())
        assertEquals(setOf(ab.id),reader().scanSelectedNetwork(setOf(a),emptySet()).relations())
        assertTrue(reader().scanSelectedNetwork(setOf(doc.id),emptySet()).relations().isEmpty())
        assertEquals(setOf(ab.id,bc.id,cd.id),reader().scanLibraryNetwork().relations())
        val source=reader().scan(b)
        assertTrue(source.graphs.single().rows.isEmpty())
        assertEquals(setOf(ab.id,bc.id),source.graphs.single().fileRelations.map {it.relation.id}.toSet())
        assertEquals(8,f.reads.get());assertEquals(0,f.writes.get())
    }

    @Test fun anUnrelatedEdgeCannotConsumeASourceQueryBudgetBeforeItsMatchingEndpoint()=MapBacklinkLibraryTest().scenario {f->
        val a=id();val b=id();val c=id();val d=id()
        val other=edge("C","D");val wanted=edge("A","B")
        val doc=document(card("A",a),card("B",b),card("C",c),card("D",d),relations=listOf(other,wanted))
        val file=f.graph("filter-before-budget.xmap",doc)
        val reader=MapBacklinkLibrary(f.context,listOf(f.tree.toString()),BacklinkBudget(maxRows=1))
        val scan=reader.scan(a)
        assertFalse(scan.limited);assertEquals(wanted,scan.graphs.single().fileRelations.single().relation)
        val location=scan.graphs.single().fileRelations.single().location
        val captured=mutableListOf<String>()
        val projected=reader.projectFileRelations(doc,location,BacklinkOrigin.LIVE,false,
            membership={captured+=it;null},maxEvidence=1,fileIds=setOf(a))
        assertFalse(projected.limited);assertEquals(wanted,projected.rows.single().relation);assertEquals(listOf(wanted.id),captured)
        assertEquals(1,f.reads.get());assertArrayEquals(MapCodec.encode(doc),file.readBytes());assertEquals(0,f.writes.get())
    }

    @Test fun contentAndUserEdgesShareTheSameEvidenceAllowanceAndReportOmittedRelations()=MapBacklinkLibraryTest().scenario {f->
        val a=id();val b=id();val c=id()
        val concept=MapNode(id="concept",parent=null,free=true,text="Complete concept",
            sources=listOf(MapSource(documentId=a),MapSource(documentId=b)))
        val ab=edge("A","B");val ac=edge("A","C")
        val doc=document(card("A",a),card("B",b),card("C",c),relations=listOf(ab,ac),concepts=listOf(concept))
        f.graph("shared-quota.xmap",doc)
        val scan=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scanNetwork(a,networkBudget=LocalNetworkBudget(maxEvidence=3))
        val graph=scan.graphs.single()
        assertEquals(2,graph.concepts.single().members.size);assertEquals(ab,graph.fileRelations.single().relation)
        assertTrue(scan.limited);assertTrue(scan.issues.any {it.problem==BacklinkProblem.LIMIT})
        val source=MapBacklinkLibrary(f.context,listOf(f.tree.toString()),BacklinkBudget(maxRows=2)).scan(a)
        assertEquals(1,source.graphs.single().rows.size);assertEquals(1,source.graphs.single().fileRelations.size)
        assertTrue(source.limited);assertEquals(0,f.writes.get())
    }

    @Test fun anOwnedButUnconnectedRelationCannotStarveALaterRealEndpoint()=MapBacklinkLibraryTest().scenario {f->
        val g=id();val a=id();val b=id();val c=id()
        val unrelated=document(card("A",a),card("B",b),relations=listOf(edge("A","B"))).copy(id=g)
        val wanted=edge("G","C")
        val connected=document(card("G",g),card("C",c),relations=listOf(wanted))
        f.graph("a-owned-unconnected.xmap",unrelated);f.graph("z-real-endpoint.xmap",connected)
        val scan=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scanSelectedNetwork(setOf(g),emptySet(),
            networkBudget=LocalNetworkBudget(maxEvidence=1))
        assertEquals(listOf(wanted),scan.graphs.flatMap {it.fileRelations}.map {it.relation})
        assertFalse(scan.limited);assertEquals(2,f.reads.get());assertEquals(0,f.writes.get())
    }

    @Test fun earlyDeeperRelationsCannotExhaustALaterGraphsDirectEvidence()=MapBacklinkLibraryTest().scenario {f->
        val a=id();val b=id();val c=id()
        val deep=document(card("B",b),card("C",c),relations=RelationKind.entries.map {edge("B","C",it)})
        val directEdge=edge("A","B",RelationKind.REFERENCED_FROM)
        val direct=document(card("A",a),card("B",b),relations=listOf(directEdge))
        f.graph("a-deeper.xmap",deep);f.graph("z-direct.xmap",direct)
        val scan=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scanExpandedNetwork(a,setOf(b),
            networkBudget=LocalNetworkBudget(maxEvidence=2))
        val rows=scan.graphs.flatMap {it.fileRelations}
        assertEquals(2,rows.count {a !in it.fileIds});assertEquals(directEdge,rows.single {a in it.fileIds}.relation)
        assertTrue(scan.limited);assertEquals(2,f.reads.get());assertEquals(0,f.writes.get())
    }

    @Test fun recoveryWinnerCarriesItsExactRelationAndOriginWithoutWritingEitherGraphOrJournal()=MapBacklinkLibraryTest().scenario {f->
        val a=id();val b=id();val initial=edge("A","B",RelationKind.BELONGS_TO,"old")
        val doc=document(card("A",a),card("B",b),relations=listOf(initial));val file=f.graph("recovery.xmap",doc)
        val actual=MapFiles.target(f.context,f.uri(file))
        val failed=object:MapTarget {override fun read()=actual.read();override fun write(bytes:ByteArray):Unit=throw IOException("before write")}
        val store=MapStore(File(f.context.noBackupFilesDir,"mind-map-recovery"),f.uri(file).toString(),failed)
        store.open();val updated=doc.copy(relations=listOf(initial.copy(label="Retained winner")))
        assertThrows(IOException::class.java) {store.save(updated)}
        val bytes=file.readBytes();val journal=f.journalSnapshot()
        val scan=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scan(a)
        val relation=scan.graphs.single().fileRelations.single()
        assertEquals(BacklinkOrigin.RECOVERY,relation.origin);assertEquals(updated.relations.single(),relation.relation)
        assertArrayEquals(bytes,file.readBytes());assertEquals(journal,f.journalSnapshot());assertEquals(0,f.writes.get())
    }

    @Test fun actionTimeLocationCheckRejectsARelationsRealGraphAfterItWasMovedToTrash()=MapBacklinkLibraryTest().scenario {f->
        val a=id();val b=id();val doc=document(card("A",a),card("B",b),relations=listOf(edge("A","B")))
        val file=f.graph("trash-relation.xmap",doc);val uri=f.uri(file)
        val row=MapBacklinkLibrary(f.context,listOf(f.tree.toString())).scan(a).graphs.single().fileRelations.single()
        assertNull(backlinkLocationProblem(f.context,listOf(f.tree.toString()),row.location))
        // A previously observed graph has a real registry identity for the move to update.
        f.storage.registry.registerMapObserved(doc.id,file.name,uri.toString())
        // The editor's actual provider operation also updates the real location registry.
        val editor=withContext(Dispatchers.Main) {Editor(f.context).also(f.editors::add)}
        val moved=withContext(Dispatchers.IO) {editor.trashEntries(f.tree.toString(),listOf(BrowseEntry(file.name,uri.toString(),false,parentDocId="root")))}
        assertTrue(moved.second.isEmpty());assertEquals(1,moved.first.size)
        assertFalse(file.exists())
        assertEquals(1,f.storage.registry.knownMapLocations(documentId=doc.id).size)
        assertEquals(BacklinkProblem.TRASHED,backlinkLocationProblem(f.context,listOf(f.tree.toString()),row.location))
    }

    @Test fun stableRelationKeysDoNotCollideWhenLocationOrGraphIdsContainSeparators() {
        assertNotEquals(FileRelationKey("a","b:c","d").stableKey,FileRelationKey("a:b","c","d").stableKey)
        assertNotEquals(FileRelationKey("ab","c","d").stableKey,FileRelationKey("a","bc","d").stableKey)
    }
}
