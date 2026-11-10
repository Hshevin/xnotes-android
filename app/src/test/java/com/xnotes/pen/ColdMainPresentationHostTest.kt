package com.xnotes.pen

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.MotionEvent
import com.xnotes.presentOriginalPdfLibrary
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.canvas.InitialView
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.Tool
import com.xnotes.mindmap.*
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.ui.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** The ordinary producer and Main's bootstrap share actual Editors, actors and private files. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class ColdMainPresentationHostTest {
    private class Host(val f:EditorPageCopyFixture) {
        val authority="cold-main-${UUID.randomUUID()}"
        val uri=DocumentsContract.buildDocumentUri(authority,"root/map.xmap")
        val file=File(f.root,"map.xmap")
        val document=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="root"),MapNode(id="n",parent="root",text="Map A")))
        var denyWrite=false
        var frame by mutableStateOf(ColdPresentationCoordinator.Frame())
        lateinit var scope:CoroutineScope
        lateinit var maps:MapWorkspace
        lateinit var coordinator:ColdPresentationCoordinator
        lateinit var bootstrap:ColdPresentationBootstrap
        private var observer:Job?=null
        private val store=LastPresentationStore(f.context.filesDir)
        init {
            val provider=object:ContentProvider() {
                override fun onCreate()=true
                override fun getType(uri:Uri)="application/octet-stream"
                override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?):Cursor =
                    MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {addRow(arrayOf(file.name,file.length()))}
                override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
                    if('w' in mode && denyWrite) throw IOException("Keep Map B in its actual local journal")
                    return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode))
                }
                override fun insert(uri:Uri,v:ContentValues?):Uri?=null
                override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
                override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int=error("Cold restore must not delete source files")
            }
            provider.attachInfo(f.context,ProviderInfo().apply {this.authority=this@Host.authority})
            ShadowContentResolver.registerProviderInternal(authority,provider)
            file.writeBytes(MapCodec.encode(document))
        }
        suspend fun waitFor(check:()->Boolean)=withTimeout(10000L) {
            while(!withContext(Dispatchers.Main) {check()}) {tick();delay(1)}
        }
        fun tick()=org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(20))
        suspend fun attach(cold:Boolean=false):Boolean=withContext(Dispatchers.Main) {
            scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
            maps=MapWorkspace(f.context,scope);maps.bindHost(Any())
            bootstrap=ColdPresentationBootstrap()
            coordinator=ColdPresentationCoordinator(f.editor,maps,bootstrap)
            coordinator.bindFrame {frame}
            val restored=coordinator.bootstrap()
            frame=ColdPresentationCoordinator.Frame(homePanes=(bootstrap.presentation as? LastPresentation.Native)?.slots
                ?.filterIsInstance<ColdNativeSlot.Home>()?.map {it.pane}?.toSet().orEmpty())
            f.editor.onPresentationIntent={coordinator.retireIntent()}
            f.editor.preparePresentationDeparture={current->coordinator.prepareDeparture(current)}
            maps.onPresentationIntent={coordinator.retireIntent()}
            maps.prepareHostDeparture={current->coordinator.prepareDeparture(current)}
            observer=coordinator.observe(scope)
            if(!cold) assertTrue(restored)
            restored
        }
        suspend fun pair(nativePane:Pane=Pane.PRIMARY):MapWorkspace.Entry {
            withContext(Dispatchers.IO) {f.seed()}
            attach()
            val opened=CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                assertTrue(maps.openPair(uri,"map.xmap",mapIsFirst=false,prepareNative={valid->
                    f.editor.preparePagedPairOpen(f.sourceUri,"source.xnote",preferredNativePane=nativePane,stillCurrent=valid)
                },onPresented={_,cap->f.editor.mixedPresentationActive=true;f.editor.focusMixedNativePane(cap.nativePane)},
                    onFinished={opened.complete(it)}))
            }
            assertTrue(opened.await())
            return withContext(Dispatchers.Main) {requireNotNull(maps.current)}
        }
        suspend fun mixed():ColdMixedPresentation {
            var result:ColdMixedPresentation?=null
            withTimeout(10000) {while(result==null) {
                val value=withContext(Dispatchers.IO) {(store.observe().read as? LastPresentationRead.Ready)?.value}
                result=(value as? LastPresentation.Mixed)?.value
                if(result==null) {tick();delay(2)}
            }}
            return requireNotNull(result)
        }
        private fun editorField(editor:Editor,name:String)=Editor::class.java.getDeclaredField(name).apply {isAccessible=true}.get(editor)
        suspend fun dropRuntime() {
            withContext(Dispatchers.Main) {coordinator.close();observer?.cancelAndJoin();observer=null}
            val panes=withContext(Dispatchers.Main) {listOfNotNull(f.editor,f.editor.secondary)}
            withContext(Dispatchers.Main) {for(pane in panes) {
                pane.onPresentationIntent=null;pane.preparePresentationDeparture=null
                val jobs=editorField(pane,"autosaveScope") as CoroutineScope
                jobs.cancel();jobs.coroutineContext[Job]?.join()
            }}
            for(pane in panes) (editorField(pane,"sessionOwner") as LegacySessionQueue.Owner).awaitAccepted()
            withContext(Dispatchers.Main) {maps.dispose()}.join()
            scope.cancel()
        }
        suspend fun cold():Boolean {
            dropRuntime()
            withContext(Dispatchers.Main) {f.editor=Editor(f.context)}
            return attach(cold=true)
        }
        suspend fun finish() {if(::coordinator.isInitialized) {coordinator.close();observer?.cancel();maps.dispose().join();scope.cancel()}}
    }
    private fun scenario(block:suspend (Host)->Unit)=runParkingScenario {f->
        val host=Host(f)
        try {block(host)} finally {withContext(NonCancellable+Dispatchers.Main) {host.finish()}}
    }

    @Test fun changingOnlyViewOverridesPublishesTheNewColdRestoreView()=scenario {h->
        h.pair()
        val before=h.mixed()
        val updated=com.xnotes.canvas.ViewOverrides(contrast=137,scrollbar=false)
        withContext(Dispatchers.Main) {h.f.editor.updateViewOverrides(updated)}
        val store=LastPresentationStore(h.f.context.filesDir)
        withTimeout(5000L) {
            while(true) {
                val saved=withContext(Dispatchers.IO) {
                    ((store.observe().read as? LastPresentationRead.Ready)?.value as? LastPresentation.Mixed)?.value
                }
                if(saved?.nativeView?.position?.overrides==updated) break
                h.tick();delay(5)
            }
        }
        assertNotEquals(updated,before.nativeView?.position?.overrides)
        assertTrue(h.cold())
        withContext(Dispatchers.Main) {assertEquals(updated,h.f.editor.viewOverrides)}
        assertEquals(0,h.f.writes.get())
    }

    @Test fun ordinaryPairWithoutSaveSplitPublishesActualEditedBodiesAndColdRestoresBothWithNullNotePath()=scenario {h->
        val entry=h.pair()
        h.mixed()
        withContext(Dispatchers.Main) {
            h.f.editor.newNote()
        }
        h.waitFor {h.f.editor.noteOpen && h.f.editor.visibleDocumentUri==null && !h.f.editor.opening}
        val oldNote=withContext(Dispatchers.Main) {
            val editor=h.f.editor
            editor.view.layout(0,0,1000,800)
            editor.state.pendingInitialView=InitialView.Restore(1.25,12.0,34.0,true)
            editor.selectTool(Tool.PEN)
            assertEquals(Tool.PEN,editor.tool)
            parkingPen(editor,MotionEvent.ACTION_DOWN,Pt(100.0,100.0))
            parkingPen(editor,MotionEvent.ACTION_MOVE,Pt(130.0,120.0))
            parkingPen(editor,MotionEvent.ACTION_UP,Pt(160.0,150.0))
            assertEquals("The fixture must draw a real stroke before testing its cache",1,
                editor.state.document.pages.first().items.filterIsInstance<Stroke>().size)
            h.f.stopDebounce()
            assertTrue(entry.session.operate {it.update("n") {node->node.copy(text="Map B from ordinary editing")}})
            entry.session.cancelPendingAutosave();h.denyWrite=true
            assertTrue(h.maps.updateSplitLayout {it.copy(ratio=.36f,sideBySide=false,reversed=true,focusedPane=it.nativePane)})
            editor.state.document
        }
        var saved=h.mixed()
        withTimeout(10000) {while(saved.nativeHint.documentId!=oldNote.identity.documentId || saved.nativeHint.path!=null || saved.layout.ratio!=.36f) {
            h.tick();delay(2);saved=h.mixed()
        }}
        assertNull(saved.nativeHint.path)
        val cache=File(h.f.context.filesDir,if(saved.layout.nativePane==Pane.PRIMARY) "session/document.xnote" else "session-b/document.xnote")
        val cached=withContext(Dispatchers.IO) {cache.inputStream().use {h.f.codec.read(it)}}
        assertEquals("The automatic producer must publish the actual stroke before cold adoption",1,
            cached.pages.first().items.filterIsInstance<Stroke>().size)
        assertTrue("No explicit arrangement is created by automatic checkpoint",File(h.f.context.filesDir,"splits").listFiles().orEmpty().none {it.extension=="xsplit"})
        assertTrue(h.cold())
        withContext(Dispatchers.Main) {
            assertNotSame(oldNote,h.f.editor.state.document)
            assertEquals(oldNote.identity.documentId,h.f.editor.visibleDocumentId)
            assertNull(h.f.editor.visibleDocumentUri);assertTrue(h.f.editor.requiresIndependentSave)
            assertEquals(1,h.f.editor.state.document.pages.first().items.filterIsInstance<Stroke>().size)
            assertEquals("Map B from ordinary editing",h.maps.current!!.session.document!!.node("n").text)
            assertEquals(saved.layout,h.maps.splitPresentation.layout)
        }
        assertTrue(h.cold())
        withContext(Dispatchers.Main) {assertTrue(h.f.editor.requiresIndependentSave);assertNull(h.f.editor.visibleDocumentUri)}
    }

    @Test fun mapBesideHomeAutomaticallyColdRestoresItsSlotsWithoutReadingTheParkedNoteCache()=scenario {h->
        val entry=h.pair();h.mixed()
        withContext(Dispatchers.Main) {
            assertTrue(h.maps.updateSplitLayout {it.copy(nativeHome=true,ratio=.29f,focusedPane=it.nativePane)})
            h.frame=ColdPresentationCoordinator.Frame(homePanes=setOf(requireNotNull(h.maps.splitPresentation.layout).nativePane))
        }
        withTimeout(10000) {while(true) {
            val read=withContext(Dispatchers.IO) {LastPresentationStore(h.f.context.filesDir).observe().read}
            if((read as? LastPresentationRead.Ready)?.value is LastPresentation.MapOnly) break
            h.tick();delay(2)
        }}
        val mapId=entry.session.document!!.id
        assertTrue(h.cold())
        withContext(Dispatchers.Main) {
            assertEquals(mapId,h.maps.current!!.session.document!!.id)
            assertTrue(h.maps.visible);assertTrue(h.maps.splitPresentation.layout!!.nativeHome)
            assertEquals(.29f,h.maps.splitPresentation.layout!!.ratio,0f)
            assertTrue(h.f.editor.openPanes.none {it.noteOpen})
        }
    }

    @Test fun closingMapKeepsTheActualHomeSlotNativeIdentityAndMixedLayoutOnColdOpen()=scenario {h->
        val entry=h.pair(Pane.SECONDARY);h.mixed()
        val layout=withContext(Dispatchers.Main) {
            assertTrue(h.maps.updateSplitLayout {it.copy(ratio=.32f,sideBySide=false,reversed=true,focusedPane=it.mapPane)})
            requireNotNull(h.maps.splitPresentation.layout)
        }
        val nativeId=withContext(Dispatchers.Main) {h.f.editor.openPanes.single {it.noteOpen}.visibleDocumentId}
        withContext(Dispatchers.Main) {
            val gate=requireNotNull(h.coordinator.prepareDeparture {h.maps.current===entry && h.maps.splitPresentation.layout==layout})
            try {assertNotNull(h.maps.closeToHome(entry,beforeAdopt={gate.commit {h.maps.current===entry}}))}
            finally {gate.close()}
        }
        var value:LastPresentation.Native?=null
        withTimeout(10000) {while(value==null) {
            value=withContext(Dispatchers.IO) {
                ((LastPresentationStore(h.f.context.filesDir).observe().read as? LastPresentationRead.Ready)?.value as? LastPresentation.Native)
            }
            if(value==null) {h.tick();delay(2)}
        }}
        assertTrue(requireNotNull(value).slots.any {it is ColdNativeSlot.Home && it.pane==layout.mapPane})
        assertEquals(ColdNativeLayout(layout.focusedPane,layout.ratio,layout.sideBySide,layout.reversed),requireNotNull(value).layout)
        assertTrue(h.cold())
        withContext(Dispatchers.Main) {
            assertNull(h.maps.current);assertNull(h.maps.splitPresentation.layout)
            val native=h.f.editor.openPanes.single {it.noteOpen}
            assertEquals(nativeId,native.visibleDocumentId);assertEquals(layout.nativePane,native.pane)
            assertEquals(layout.ratio,h.f.editor.splitRatio,0f)
            assertEquals(layout.sideBySide,h.f.editor.splitSideBySide)
            assertEquals(layout.reversed,h.f.editor.splitReversed);assertEquals(layout.focusedPane,h.f.editor.focusedPane)
        }
    }

    @Test fun originalLibraryHomeGateFailurePreservesTheActualPairAndExistingMarker()=scenario {h->
        val entry=h.pair();h.mixed()
        val marker=File(h.f.context.filesDir,"last-presentation.json")
        val before=withContext(Dispatchers.IO) {marker.readBytes()}
        val model=withContext(Dispatchers.Main) {h.f.editor.state.document}
        val layout=withContext(Dispatchers.Main) {h.maps.splitPresentation.layout}
        var replacements=0
        val broken=LastPresentationStore(h.f.context.filesDir,object:LastPresentationStore.FileOps {
            override fun openRead(path:java.nio.file.Path)=java.nio.file.Files.newInputStream(path)
            override fun replace(stage:java.nio.file.Path,target:java.nio.file.Path) {replacements++;throw IOException("Marker replacement denied")}
            override fun deleteIfExists(path:java.nio.file.Path) {java.nio.file.Files.deleteIfExists(path)}
        })
        val library=OriginalPdfLibraryState(h.f.context,h.scope)
        try {
            withContext(Dispatchers.Main) {
                h.f.editor.preparePresentationDeparture={PreparedHomeGate.prepare(broken,broken.reserve())}
                assertFalse(presentOriginalPdfLibrary(h.f.editor,library,prepareParking={valid->
                    h.coordinator.checkpointOutgoing(valid,localOnly=true)?.let {proof->({proof.current()})}
                }) {h.maps.current===entry})
                assertFalse(library.visible);assertSame(entry,h.maps.current);assertTrue(h.maps.visible)
                assertSame(model,h.f.editor.state.document);assertEquals(layout,h.maps.splitPresentation.layout)
            }
            assertEquals(1,replacements)
            assertArrayEquals(before,withContext(Dispatchers.IO) {marker.readBytes()})
        } finally {withContext(Dispatchers.Main) {library.dispose()}}
    }
}
