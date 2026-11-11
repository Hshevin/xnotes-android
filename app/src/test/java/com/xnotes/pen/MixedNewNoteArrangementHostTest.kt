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
import com.xnotes.core.model.PageStyle
import com.xnotes.mindmap.*
import com.xnotes.ui.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowSystemClock

/**
 * The emulator path: a Map beside a NOTE newly created from the pane home, with the real cold
 * presentation checkpoint running. Its first working-session snapshot binds the unanchored
 * NOTE's root reference scope; that must not look like a provenance change to the arrangement save.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MixedNewNoteArrangementHostTest {
    @Test fun aNewUnanchoredNoteBesideAMapSavesAfterTheColdWorkingSessionCheckpoint()=runParkingScenario {f->
        val authority="mixed-new-note-${UUID.randomUUID()}"
        val mapUri=DocumentsContract.buildDocumentUri(authority,"root/new.xmap")
        val mapFile=File(f.root,"new.xmap").apply {writeBytes(byteArrayOf())}
        val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun getType(uri:Uri)="application/octet-stream"
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?):Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {addRow(arrayOf(mapFile.name,mapFile.length()))}
            override fun insert(uri:Uri,values:ContentValues?):Uri?=null
            override fun update(uri:Uri,values:ContentValues?,s:String?,a:Array<out String>?)=0
            override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int=error("An arrangement must never delete a source")
            override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor=ParcelFileDescriptor.open(mapFile,ParcelFileDescriptor.parseMode(mode))
        }
        provider.attachInfo(f.context,ProviderInfo().apply {this.authority=authority})
        ShadowContentResolver.registerProviderInternal(authority,provider)
        withContext(Dispatchers.Main) {com.xnotes.platform.FontCatalog.init(f.context);com.xnotes.platform.TemplateLibrary.init(f.context)}
        val map=MindMapDocument(nodes=listOf(MapNode(parent=null,text="Topic")))
        MapFiles.create(f.context,mapUri,map)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val maps=withContext(Dispatchers.Main) {MapWorkspace(f.context,scope)}
        val host=Any()
        val presentations=LastPresentationStore(f.context.filesDir)
        suspend fun await(check:()->Boolean)=withTimeout(10000L) {
            while(!withContext(Dispatchers.Main) {check()}) {ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(20));delay(1L)}
        }
        try {
            withContext(Dispatchers.Main) {
                maps.bindHost(host)
                val coordinator=ColdPresentationCoordinator(f.editor,maps,ColdPresentationBootstrap(),presentations)
                coordinator.bindFrame {ColdPresentationCoordinator.Frame()}
                assertTrue(coordinator.bootstrap())
                f.editor.onPresentationIntent={coordinator.retireIntent()}
                f.editor.preparePresentationDeparture={current->coordinator.prepareDeparture(current)}
                maps.onPresentationIntent={coordinator.retireIntent()}
                maps.prepareHostDeparture={current->coordinator.prepareDeparture(current)}
                coordinator.observe(scope)
                assertTrue(maps.open(mapUri,"new.xmap"))
            }
            await {!maps.opening && maps.visible}
            val entry=withContext(Dispatchers.Main) {requireNotNull(maps.current)}
            // Open a note beside this map: the Map takes the second slot and the native slot shows its home.
            withContext(Dispatchers.Main) {
                val detach=requireNotNull(f.editor.prepareDetachMixedPane(f.editor.secondaryPane()))
                val slot=detach.result.retiredPane
                assertTrue(maps.showSplit(entry,MapSplitLayout(slot,slot,.5f,true,slot==Pane.PRIMARY,nativeHome=true),
                    beforeAdopt={detach.commit()!=null}))
                f.editor.mixedPresentationActive=true
            }
            // New note (template None) created and opened from that pane home, then the split turned top/bottom.
            val tree=DocumentsContract.buildTreeDocumentUri(f.authority,"root").toString()
            val made=requireNotNull(withContext(Dispatchers.IO) {
                f.editor.createNoteFile(tree,"root","new note",Editor.NewNoteSetup(148.0,210.0,PageStyle(template="none"),null))
            })
            val nativePane=withContext(Dispatchers.Main) {
                f.editor.openAsync(made,"new note.xnote");f.editor.view.layout(0,0,1000,1000)
                val native=requireNotNull(maps.splitPresentation.layout).nativePane
                assertTrue(maps.updateSplitLayout {it.copy(nativeHome=false,focusedPane=native)})
                assertTrue(f.editor.focusMixedNativePane(native))
                assertTrue(maps.updateSplitLayout {it.copy(sideBySide=false)})
                assertFalse("A new note opens without page identities",f.editor.state.document.hasReferenceAnchors)
                native
            }
            // The idle cold checkpoint publishes this Mixed presentation through the NOTE working session.
            await {(presentations.observe().read as? LastPresentationRead.Ready)?.value is LastPresentation.Mixed}
            val noteId=withContext(Dispatchers.Main) {f.editor.state.document.identity.documentId}
            val capture=requireNotNull(withContext(Dispatchers.Main) {
                maps.prepareMixedArrangementSave(prepareNative={pane,valid->f.editor.prepareMixedNoteArrangementSave(pane,valid)},
                    stillCurrent={maps.isHostCurrent(host)})
            }) {"The checkpointed new NOTE must still yield its actual source proof"}
            assertEquals(nativePane,capture.request.layout.nativePane);assertEquals(false,capture.request.layout.sideBySide)
            assertEquals(map.id,capture.request.map.documentId);assertEquals(noteId,capture.request.native.documentId)
            assertEquals(made,capture.request.native.uri)
            val attempt=SplitPublicationAttempt(SplitArrangementStore(f.context.filesDir))
            try {
                assertTrue(attempt.prepare(capture.request,capture.name))
                val published=requireNotNull(withContext(Dispatchers.Main) {capture.publish {attempt.commit()}})
                val read=withContext(Dispatchers.IO) {SplitArrangementStore(f.context.filesDir).readManaged(published.toURI().toString())}
                assertEquals(capture.request,((read as SplitArrangementRead.Ready).request as SplitOpenRequest.Mixed).request)
            } finally {attempt.close()}
        } finally {
            withContext(NonCancellable+Dispatchers.Main) {maps.dispose()}.join()
            scope.cancel()
        }
    }
}
