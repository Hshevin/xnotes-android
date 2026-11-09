package com.xnotes.pen

import android.net.Uri
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import com.xnotes.platform.AppStorageDocumentsProvider
import android.view.MotionEvent
import com.xnotes.core.geometry.Pt
import com.xnotes.mindmap.*
import com.xnotes.ui.Editor
import com.xnotes.format.BundleIdentity
import com.xnotes.core.tools.Tool
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real JSON files, registry and broker actors behind the existing source-navigation API.
 * This host test does not claim Compose, Surface or target-device execution. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapGraphSourceNavigationHostTest {
    private class Fixture(val native:EditorPageCopyFixture,val nativeCollision:Boolean=false) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val maps=MapWorkspace(native.context,scope)
        val binding=Any()
        val target=MindMapDocument(nodes=listOf(MapNode(id="br",parent=null,text="Target B"),
            MapNode(id="bn",parent="br",text="Saved target body")))
        val source=MapSource(documentId=target.id,fileName="B.xmap",scope=SourceScope.FILE)
        val origin=MindMapDocument(nodes=listOf(MapNode(id="ar",parent=null,text="Origin A"),
            MapNode(id="an",parent="ar",text="Saved origin body",sources=listOf(source),primarySource=source.id)))
        val notes=AppStorageDocumentsProvider.rootDir(native.context)
        val tree=AppStorageDocumentsProvider.treeUri(native.context)
        val provider=AppStorageDocumentsProvider()
        val folder=File(notes,"map-source-${UUID.randomUUID()}").apply {mkdirs()}
        val a=File(folder,"A.xmap");val b=File(folder,"B.xmap")
        fun uri(file:File)=DocumentsContract.buildDocumentUriUsingTree(tree,"root/"+file.relativeTo(notes).invariantSeparatorsPath)
        lateinit var entry:MapWorkspace.Entry
        lateinit var navigator:MapSourceNavigator
        val panes=mutableListOf<Editor>()
        private fun stopDebounce(editor:Editor) {
            for(name in listOf("noteDebounceJob","canvasDebounceJob"))
                (Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(editor) as? Job)?.cancel()
        }
        suspend fun start() {
            provider.attachInfo(native.context,ProviderInfo().apply {
                authority=tree.authority;exported=true;grantUriPermissions=true
                readPermission="android.permission.MANAGE_DOCUMENTS";writePermission=readPermission
            })
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(requireNotNull(tree.authority),provider)
            val other=if(nativeCollision) null else openParkingPanes(native)
            withContext(Dispatchers.Main) {
                if(nativeCollision) {
                    native.editor.newNote()
                    native.editor.state.document.identity=BundleIdentity(target.id)
                    assertNull(native.editor.state.document.path)
                    native.editor.view.layout(0,0,1000,1000);native.editor.selectTool(Tool.PEN)
                }
                panes+=native.editor;other?.let {panes+=it}
                for(pane in panes) {
                    parkingPen(pane,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
                    parkingPen(pane,MotionEvent.ACTION_MOVE,Pt(150.0,140.0))
                    parkingPen(pane,MotionEvent.ACTION_UP,Pt(180.0,160.0))
                    stopDebounce(pane)
                    assertTrue(pane.state.document.dirty);assertTrue(pane.history.canUndo)
                }
                if(panes.size==2) assertNotSame(panes[0].documentOwner,panes[1].documentOwner)
            }
            withContext(Dispatchers.IO) {
                a.writeBytes(MapCodec.encode(origin));b.writeBytes(MapCodec.encode(target))
                native.storage.registry.registerMapObserved(target.id,"B.xmap",uri(b).toString())
                native.storage.registry.registerMapObserved(origin.id,"A.xmap",uri(a).toString())
            }
            withContext(Dispatchers.Main) {
                maps.bindHost(binding)
                assertTrue(maps.open(uri(a),"A",preparePresentation={
                    native.editor.awaitHostParking()?.let {proof->({proof.current()})}
                }))
                navigator=MapSourceNavigator(native.editor,maps,binding,scope,
                    guard={_,_->MapSourceGuardResult.Ready(emptySet())})
            }
            await {maps.visible && !maps.opening}
            withContext(Dispatchers.Main) {
                entry=maps.current!!;entry.presentation.synchronize(entry.session)
                assertTrue(entry.session.operate {doc->doc.update("an") {it.copy(text="Unsaved origin body")}})
                assertTrue(entry.session.operate {doc->doc.update("an") {it.copy(text="Undo this second edit")}})
                entry.session.undo();entry.session.selected="an";entry.session.cancelPendingAutosave()
                assertTrue(entry.session.dirty);assertTrue(entry.session.canUndo);assertTrue(entry.session.canRedo)
            }
        }
        suspend fun await(check:()->Boolean)=withTimeout(5000L) {
            while(!withContext(Dispatchers.Main) {check()}) delay(1)
        }
        fun openSource():Boolean {
            val ticket=requireNotNull(entry.session.captureSourceNavigation("an",source.id,source.target))
            val bookmark=requireNotNull(entry.session.captureSourceReturn(ticket))
            return navigator.open(entry,ticket,bookmark)
        }
        suspend fun finish()=withContext(NonCancellable) {
            withContext(Dispatchers.Main) {
                if(this@Fixture::navigator.isInitialized) navigator.dispose()
                maps.dispose()
            }.join()
            scope.cancel()
            withContext(Dispatchers.IO) {folder.deleteRecursively()}
        }
    }
    private fun scenario(nativeCollision:Boolean=false,block:suspend(Fixture)->Unit)=runParkingScenario {native->
        val f=Fixture(native,nativeCollision)
        try {f.start();block(f)} finally {f.finish()}
    }
    @Test fun aRegisteredMapFileSourceOpensTheActualDestinationWhileKeepingTheDirtyOriginAndBothNativeOwners()=scenario {f->
        val aBytes=f.a.readBytes();val bBytes=f.b.readBytes()
        val nativeBytes=listOf(f.native.source.readBytes(),f.native.target.readBytes())
        val models=withContext(Dispatchers.Main) {f.panes.map {it.state.document}}
        val histories=withContext(Dispatchers.Main) {f.panes.map {it.history}}
        val undos=withContext(Dispatchers.Main) {f.panes.map {it.history.nextUndo}}
        val owners=withContext(Dispatchers.Main) {f.panes.map {it.documentOwner}}
        val originModel=withContext(Dispatchers.Main) {f.entry.session.document!!}
        val revision=withContext(Dispatchers.Main) {f.entry.session.contentRevision}
        withContext(Dispatchers.Main) {assertTrue(f.openSource())}
        f.await {!f.navigator.busy}
        // Preserve evidence before checking the missing destination behavior: the existing
        // implementation completes with UNSUPPORTED and remains on A, rather than timing out.
        assertArrayEquals(aBytes,f.a.readBytes());assertArrayEquals(bBytes,f.b.readBytes())
        assertArrayEquals(nativeBytes[0],f.native.source.readBytes());assertArrayEquals(nativeBytes[1],f.native.target.readBytes())
        assertEquals(0,f.native.writes.get());assertEquals(0,f.native.writeOpens.get())
        withContext(Dispatchers.Main) {
            assertTrue(f.maps.visible)
            assertSame(originModel,f.entry.session.document);assertEquals(revision,f.entry.session.contentRevision)
            assertTrue(f.entry.session.dirty);assertTrue(f.entry.session.canUndo);assertTrue(f.entry.session.canRedo)
            assertTrue(f.entry.lease.alive)
            for(i in f.panes.indices) {
                assertSame(models[i],f.panes[i].state.document);assertSame(histories[i],f.panes[i].history)
                assertSame(undos[i],f.panes[i].history.nextUndo);assertSame(owners[i],f.panes[i].documentOwner)
                assertTrue(f.panes[i].state.document.dirty);assertTrue(f.panes[i].noteOpen)
            }
            assertEquals("Map FILE source navigation completed; failure=${f.navigator.failure}",
                f.target.id,f.maps.current?.session?.document?.id)
            assertEquals("Saved target body",f.maps.current!!.session.document!!.node("bn").text)
            assertEquals(MapSaves.key(f.uri(f.b)),MapSaves.key(f.maps.current!!.uri))
        }
    }
    @Test fun anEditedDestinationSavesAndColdDecodesThenReturnsToTheSameDirtyOriginCameraAndSpecificSource()=scenario {f->
        val aBytes=f.a.readBytes()
        val before=withContext(Dispatchers.Main) {
            val view=MindMapView(f.native.context).apply {
                document=f.entry.session.document!!;documentEpoch=f.entry.session.interactionEpoch
                layout(0,0,1000,700);fit();zoomBy(1.35f);panText(77f,-44f)
            }
            assertTrue(f.entry.presentation.showSources(f.entry.session,view,"an"))
            Triple(view.viewport(),f.entry.session.document!!,f.entry.session.contentRevision)
        }
        withContext(Dispatchers.Main) {assertTrue(f.openSource())}
        f.await {!f.navigator.busy}
        val child=withContext(Dispatchers.Main) {
            val current=requireNotNull(f.maps.current)
            assertEquals("Map FILE open failed: ${f.navigator.failure}",f.target.id,current.session.document!!.id)
            assertTrue(f.maps.visible);assertNotSame(f.entry,current)
            assertTrue(current.session.operate {doc->doc.update("bn") {it.copy(text="Edited target body")}})
            assertTrue(current.session.operate {doc->doc.update("bn") {it.copy(text="Undo this target edit")}})
            current.session.undo();current.session.cancelPendingAutosave()
            assertEquals("Edited target body",current.session.document!!.node("bn").text)
            assertTrue(current.session.canUndo);assertTrue(current.session.canRedo)
            current
        }
        val saved=withContext(Dispatchers.Main) {child.session.saveAttempt()}.await()
        assertTrue("Actual target save failed: $saved",saved is MapSaveOutcome.Written)
        val cold=withContext(Dispatchers.IO) {MapCodec.decode(f.b.readBytes())}
        assertEquals(f.target.id,cold.id);assertEquals("Edited target body",cold.node("bn").text)
        assertArrayEquals(aBytes,f.a.readBytes())
        withContext(Dispatchers.Main) {assertTrue(f.navigator.returnToSourceMap())}
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertSame(f.entry,f.maps.current);assertTrue(f.maps.visible);assertSame(before.second,f.entry.session.document)
            assertEquals(before.third,f.entry.session.contentRevision);assertTrue(f.entry.session.dirty)
            assertTrue(f.entry.session.canUndo);assertTrue(f.entry.session.canRedo)
            assertEquals("an",f.entry.session.selected);assertEquals("an",f.entry.presentation.sourcesFor)
            assertEquals(f.source.id,f.entry.presentation.sourceFocus?.sourceId)
            val returned=MindMapView(f.native.context).apply {
                document=f.entry.session.document!!;documentEpoch=f.entry.session.interactionEpoch
                layout(0,0,1000,700);fit()
            }
            assertTrue(f.entry.presentation.restore(f.entry.session,returned))
            assertEquals(before.first.zoom,returned.viewport().zoom,1e-5f)
            assertEquals(before.first.panX,returned.viewport().panX,1e-4f)
            assertEquals(before.first.panY,returned.viewport().panY,1e-4f)
            assertTrue(child.lease.alive);assertFalse(child.session.closing)
        }
        assertArrayEquals(aBytes,f.a.readBytes());assertEquals(0,f.native.writes.get());assertEquals(0,f.native.writeOpens.get())
    }
    @Test fun anUnsavedUnregisteredNativeModelWithTheMapUuidCannotBeAdoptedAsAMapSource()=scenario(nativeCollision=true) {f->
        val aBytes=f.a.readBytes();val bBytes=f.b.readBytes()
        val model=f.native.editor.state.document;val history=f.native.editor.history;val undo=history.nextUndo
        assertEquals(f.target.id,model.identity.documentId);assertNull(model.path)
        // Only the actual JSON target is registered. No native contract is forged for this UUID.
        assertEquals("xmap",f.native.storage.registry.kindForId(f.target.id))
        withContext(Dispatchers.Main) {assertTrue(f.openSource())}
        f.await {!f.navigator.busy}
        withContext(Dispatchers.Main) {
            assertSame(f.entry,f.maps.current);assertTrue(f.maps.visible);assertNotNull(f.navigator.failure)
            assertSame(model,f.native.editor.state.document);assertSame(history,f.native.editor.history);assertSame(undo,history.nextUndo)
            assertTrue(model.dirty);assertTrue(f.native.editor.noteOpen)
        }
        assertArrayEquals(aBytes,f.a.readBytes());assertArrayEquals(bBytes,f.b.readBytes())
        assertEquals(0,f.native.writes.get());assertEquals(0,f.native.writeOpens.get())
    }

}
