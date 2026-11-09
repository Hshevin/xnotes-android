package com.xnotes.pen

import android.net.Uri
import com.xnotes.mindmap.*
import com.xnotes.platform.AppStorageDocumentsProvider
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual native parking, graph actor, persisted relations and both exploration return routes. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class FileRelationWorkspaceHostTest {
    private class Fixture(val native:EditorPageCopyFixture) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val maps=MapWorkspace(native.context,scope)
        val networks=FileNetworkWorkspace(native.context,scope)
        val binding=Any()
        lateinit var navigator:MapFileRelationNavigator
        lateinit var coordinator:LocalNetworkCoordinator
        lateinit var file:File
        lateinit var graph:MindMapDocument
        lateinit var relation:MapRelation
        lateinit var ids:List<String>
        val roots get()=listOf(AppStorageDocumentsProvider.treeUri(native.context).toString())
        suspend fun start() {
            openParkingPanes(native)
            withContext(Dispatchers.Main) {
                maps.bindHost(binding);networks.bindHost(binding)
                navigator=MapFileRelationNavigator(native.editor,maps,binding,scope)
                coordinator=LocalNetworkCoordinator(native.editor,maps,networks,binding,scope)
                ids=listOf(native.editor.state.document.identity.documentId,
                    native.editor.secondary!!.state.document.identity.documentId)
                val cards=MindMapDocument(organization=MapOrganization.RELATION_CANVAS,nodes=emptyList())
                    .addFileCards(ids.mapIndexed {i,id->MapFileCardSeed(id,"File $i.xnote",i*400.0,0.0)}).document
                relation=MapRelation(from=cards.nodes[0].id,to=cards.nodes[1].id,kind=RelationKind.DEPENDS_ON,label="Read B first")
                graph=cards.copy(relations=listOf(relation)).also {it.validate()}
            }
            file=File(AppStorageDocumentsProvider.rootDir(native.context),"manual-association.xmap")
            withContext(Dispatchers.IO) {
                file.writeBytes(MapCodec.encode(graph))
                native.storage.registry.registerMapObserved(graph.id,file.name,Uri.fromFile(file).toString())
            }
        }
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
        suspend fun finish()=withContext(NonCancellable) {
            withContext(Dispatchers.Main) {navigator.dispose();coordinator.dispose();networks.dispose();maps.dispose()}.join()
            scope.cancel();if(::file.isInitialized) file.delete()
        }
    }
    private fun scenario(block:suspend(Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native)
        try {f.start();block(f)} finally {f.finish()}
    }
    @Test fun sourceSideManualGroupOpensOnlyTheRealLineAndReturnsWithFreshSavedEvidence()=scenario {f->
        val original=f.native.source.readBytes()
        val context=withContext(Dispatchers.Main) {requireNotNull(f.native.editor.captureBacklinkContext())}
        val controller=MapBacklinkController(f.native.context,f.scope,context)
        try {
            withContext(Dispatchers.Main) {assertTrue(controller.refresh())}
            f.await {!controller.loading && controller.report?.fileRelations?.size==1}
            withContext(Dispatchers.Main) {
                assertTrue(controller.report!!.rows.isEmpty()) // Identity sources are not content references.
                val row=controller.report!!.fileRelations.single()
                assertEquals(f.relation,row.relation)
                assertTrue(f.navigator.open(requireNotNull(controller.captureOpenRelation(row))))
            }
            f.await {!f.navigator.busy && f.maps.visible}
            val edited=f.relation.copy(label="Updated in the original canvas")
            withContext(Dispatchers.Main) {
                val entry=f.maps.current!!;val session=entry.session
                assertEquals(f.relation.id,session.selectedRelation)
                assertTrue(session.changeRelation(f.relation,edited) is RelationEditResult.Applied)
                session.cancelPendingAutosave();assertTrue(session.saveAttempt().await() is MapSaveOutcome.Written)
                assertNotNull(f.maps.close(entry))
            }
            f.await {!f.maps.visible && MapDocuments.observations(f.native.context).isEmpty() &&
                controller.report?.fileRelations?.singleOrNull()?.let {it.origin!=BacklinkOrigin.LIVE && it.relation==edited}==true}
            withContext(Dispatchers.Main) {
                assertTrue(f.native.editor.noteOpen);assertEquals(context.documentId,f.native.editor.state.document.identity.documentId)
                assertTrue(f.navigator.open(requireNotNull(controller.captureOpenRelation(controller.report!!.fileRelations.single()))))
            }
            f.await {!f.navigator.busy && f.maps.visible}
            withContext(Dispatchers.Main) {
                val session=f.maps.current!!.session
                assertEquals(edited,session.document!!.relations.single())
                assertTrue(session.changeRelation(edited,null) is RelationEditResult.Applied)
                session.cancelPendingAutosave();assertTrue(session.saveAttempt().await() is MapSaveOutcome.Written)
                f.maps.close(f.maps.current!!)
            }
            f.await {!controller.loading && controller.report?.fileRelations?.isEmpty()==true && MapDocuments.observations(f.native.context).isEmpty()}
            assertTrue(MapCodec.decode(f.file.readBytes()).relations.isEmpty())
            assertArrayEquals(original,f.native.source.readBytes());assertEquals(0,f.native.writes.get())
        } finally {withContext(Dispatchers.Main) {controller.close()}}
    }
    @Test fun sourceAssociationReportsARealSaveConflictAndRejectsItsOpenRequest()=scenario {f->
        val context=withContext(Dispatchers.Main) {requireNotNull(f.native.editor.captureBacklinkContext())}
        val controller=MapBacklinkController(f.native.context,f.scope,context)
        var lease:MapDocumentLease?=null
        val remote=f.graph.copy(relations=listOf(f.relation.copy(label="External version")))
        var oldRequest:FileRelationOpenRequest?=null
        try {
            withContext(Dispatchers.Main) {controller.refresh();lease=MapDocuments.acquire(f.native.context,Uri.fromFile(f.file))}
            f.await {lease?.session?.loading==false}
            withContext(Dispatchers.Main) {
                assertTrue(lease!!.session.changeRelation(f.relation,f.relation.copy(label="Local pending")) is RelationEditResult.Applied)
                lease!!.session.cancelPendingAutosave()
            }
            f.await {controller.report?.fileRelations?.singleOrNull()?.let {it.origin==BacklinkOrigin.LIVE && !it.conflict}==true}
            withContext(Dispatchers.Main) {oldRequest=controller.captureOpenRelation(controller.report!!.fileRelations.single());assertNotNull(oldRequest)}
            withContext(Dispatchers.IO) {f.file.writeBytes(MapCodec.encode(remote))}
            withContext(Dispatchers.Main) {assertTrue(lease!!.session.saveAttempt().await() is MapSaveOutcome.Conflict)}
            f.await {controller.report?.fileRelations?.singleOrNull()?.conflict==true}
            withContext(Dispatchers.Main) {
                assertNull(controller.captureOpenRelation(controller.report!!.fileRelations.single()))
                assertEquals(BacklinkProblem.RECOVERY_CONFLICT,controller.problem)
                assertFalse(oldRequest!!.current())
                assertFalse(f.maps.visible);assertFalse(f.navigator.busy)
            }
            assertEquals(remote,MapCodec.decode(f.file.readBytes()))
        } finally {withContext(Dispatchers.Main) {controller.close();lease?.close()?.join()}}
    }

    @Test fun networkEdgeEditsAndDeletesReturnToTheSameCameraScopeAndFreshReport()=scenario {f->
        val original=f.native.source.readBytes()
        val entry=withContext(Dispatchers.Main) {f.networks.admit(f.binding,NetworkQuery(f.ids.first(),"Source",f.roots))!!}
        f.await {!entry.controller.loading && entry.controller.report?.fileRelations?.size==1}
        val viewport=MindMapView.Viewport(.75f,91f,-39f)
        val selected=withContext(Dispatchers.Main) {
            assertTrue(entry.controller.report!!.concepts.isEmpty())
            val scene=entry.builder.build(entry.controller.report!!,LocalNetworkLabels("File","Concept","Ink","Reference","References %d","Belongs",userRelation="User association"))
            val p=entry.presentation;p.reconcile(scene)
            val attached=p.attach();val size=LocalNetworkGeometry(1000,700,1f)
            assertTrue(p.finishCamera(p.prepareCamera(attached,size)!!,LocalNetworkCamera(viewport,1000,700,1f)))
            val edge=scene.edges.values.single() as NetworkSceneEdgeKey.UserRelation
            assertTrue(p.selectEdge(edge))
            val input=p.captureInput()!!
            val request=entry.controller.captureRelation(entry.controller.report!!.fileRelations.single())!!.withPresentationGuard {input.current() && f.networks.visible}!!
            assertTrue(f.navigator.open(request) {map->f.networks.parkForMap(f.binding,entry,map)})
            edge
        }
        f.await {!f.navigator.busy && f.maps.visible && !f.networks.visible}
        val edited=f.relation.copy(label="Edited from network")
        withContext(Dispatchers.Main) {
            val map=f.maps.current!!;val session=map.session
            assertEquals(f.relation.id,session.selectedRelation)
            assertTrue(session.changeRelation(f.relation,edited) is RelationEditResult.Applied)
            session.cancelPendingAutosave();assertTrue(session.saveAttempt().await() is MapSaveOutcome.Written)
            assertTrue(f.coordinator.resume(entry,map))
        }
        f.await {!f.coordinator.busy && f.networks.visible && entry.controller.report?.fileRelations?.singleOrNull()?.relation==edited}
        withContext(Dispatchers.Main) {
            assertSame(entry,f.networks.current);assertEquals(NetworkBrowseMode.LOCAL,entry.controller.browseMode)
            assertEquals(viewport,entry.presentation.camera!!.viewport);assertEquals(selected,entry.presentation.selectedEdge)
            val row=entry.controller.report!!.fileRelations.single()
            assertTrue(f.navigator.open(entry.controller.captureRelation(row)!!) {map->f.networks.parkForMap(f.binding,entry,map)})
        }
        f.await {!f.navigator.busy && f.maps.visible}
        withContext(Dispatchers.Main) {
            val map=f.maps.current!!;val session=map.session
            assertTrue(session.changeRelation(edited,null) is RelationEditResult.Applied)
            session.cancelPendingAutosave();assertTrue(session.saveAttempt().await() is MapSaveOutcome.Written)
            assertTrue(f.coordinator.resume(entry,map,closeMap=true))
        }
        f.await {!f.coordinator.busy && f.networks.visible && !entry.controller.loading &&
            entry.controller.report?.fileRelations?.isEmpty()==true && MapDocuments.observations(f.native.context).isEmpty()}
        withContext(Dispatchers.Main) {
            entry.presentation.reconcile(entry.builder.build(entry.controller.report!!,LocalNetworkLabels("File","Concept","Ink","Reference","References %d","Belongs")))
            assertNull(entry.presentation.selectedEdge);assertEquals(viewport,entry.presentation.camera!!.viewport)
        }
        assertArrayEquals(original,f.native.source.readBytes());assertEquals(0,f.native.writes.get())
    }
}
