package com.xnotes.mindmap

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual xmap session, source-list CAS, derived-history merge and native View camera consumers. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapSourceNavigationSessionTest {
    private fun png():ByteArray {
        val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes)
        out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(name:String,body:ByteArray) {
            val type=name.toByteArray(Charsets.US_ASCII);val crc=CRC32().apply {update(type);update(body)}
            out.writeInt(body.size);out.write(type);out.write(body);out.writeInt(crc.value.toInt())
        }
        val header=ByteArrayOutputStream();DataOutputStream(header).use {it.writeInt(2);it.writeInt(2);it.write(byteArrayOf(8,6,0,0,0))}
        chunk("IHDR",header.toByteArray())
        val compressed=ByteArrayOutputStream();DeflaterOutputStream(compressed).use {z->repeat(2) {z.write(0);z.write(ByteArray(8) {-1})}}
        chunk("IDAT",compressed.toByteArray());chunk("IEND",byteArrayOf());return bytes.toByteArray()
    }
    private inner class Fixture(base:Context) {
        val root=File(base.cacheDir,"map-source-navigation-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val asset=MapPreviewAsset.fromPng(png())
        val initialSource=MapSource(documentId=UUID.randomUUID().toString(),fileName="Source.xnote",scope=SourceScope.PAGE,
            pageId=UUID.randomUUID().toString(),lastKnownPage=2,note="Original note",sourceVersion="a".repeat(64))
        val one=initialSource.copy(previewKey=asset.key,lastPreview=MapLastPreview(asset.key,initialSource.target,"native-sha256:${"a".repeat(64)}",1L))
        val two=MapSource(documentId=one.documentId,fileName=one.fileName,scope=SourceScope.PAGE,pageId=UUID.randomUUID().toString(),lastKnownPage=1)
        val original=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Root"),
            MapNode(id="n",parent="r",text="Independent body",sources=listOf(one,two),primarySource=one.id),
            MapNode(id="f",parent=null,free=true,text="Free",x=900.0)),relations=listOf(MapRelation(id="edge",from="r",to="n")),
            previewAssets=mapOf(asset.key to asset))
        val file=File(root,"graph.xmap").apply {writeBytes(MapCodec.encode(original))}
        val session=MapSession()
        lateinit var panel:SourceListEditor
        suspend fun start() {
            session.open(context,Uri.fromFile(file));await {!session.loading};assertFalse(session.loadFailed)
            panel=SourceListEditor(session,session.sourceEditSnapshot("n")!!)
        }
        fun view(width:Int=1000,height:Int=700)=MindMapView(context).apply {
            document=session.document!!;documentEpoch=session.interactionEpoch;layout(0,0,width,height);fit()
        }
        suspend fun await(condition:()->Boolean)=withContext(Dispatchers.Default) {
            withTimeout(10000) {while(!withContext(Dispatchers.Main) {condition()}) delay(1)}
        }
        suspend fun close() {
            if(::panel.isInitialized) panel.close()
            session.textDraft?.let {session.cancelTextDraft(it.token)};session.textRestore?.let {session.completeTextRestore(it.token)}
            session.saveAttempt().await();session.viewModelScope.cancel()
        }
    }
    private fun scenario(block:suspend (Fixture)->Unit) {
        val fixture=Fixture(RuntimeEnvironment.getApplication());var failure:Throwable?=null
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val job=scope.launch {
            try {withContext(Dispatchers.Main) {fixture.start();withTimeout(20000) {block(fixture)}}}
            catch(error:Throwable) {failure=error}
            finally {withContext(NonCancellable+Dispatchers.Main) {fixture.close()}}
        }
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!job.isCompleted && System.nanoTime()<deadline) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications();shadowOf(Looper.getMainLooper()).idle();Thread.sleep(1)
            }
            assertTrue("Real source navigation consumer did not settle",job.isCompleted);failure?.let {throw it}
        } finally {scope.cancel();if(job.isCompleted) fixture.root.deleteRecursively()}
    }
    @Test fun sourceTicketsSurviveSemanticAndDerivedC1AndThePanelClosingWithoutAddingNavigationHistory()=scenario {f->
        val s=f.session
        val ticket=f.panel.captureNavigation(f.one.id,f.one.target)!!
        val start=s.contentRevision;val bytes=MapCodec.encode(s.document!!)
        val bookmark=s.captureSourceReturn(ticket)!!
        assertEquals(start,s.contentRevision);assertArrayEquals(bytes,MapCodec.encode(s.document!!));assertFalse(s.canUndo)
        assertTrue(f.panel.note(f.one.id,"Latest source note"));assertTrue(f.panel.move(f.one.id,1));assertTrue(f.panel.primary(f.two.id))
        val preview=s.captureSourcePreview("n",f.one.id)!!
        assertTrue(s.mergeSourceDerived(listOf(MapSourceDerivedPatch("n",f.one.id,f.one.target,SourceStatus.STALE,"Renamed.xnote",
            "native-sha256:${"b".repeat(64)}",3))) {s.currentSourcePreview(preview)!=null})
        val before=s.contentRevision;val undo=s.canUndo;val redo=s.canRedo
        val current=s.currentSourceNavigation(ticket)!!
        assertEquals("Latest source note",current.note);assertEquals("Renamed.xnote",current.fileName);assertEquals(3,current.lastKnownPage)
        assertEquals(SourceStatus.STALE,current.status);assertEquals(f.one.target,current.target)
        assertArrayEquals(f.asset.pngBytes(),s.document!!.previewAssets.getValue(current.lastPreview!!.assetKey).pngBytes())
        f.panel.close();assertNull(f.panel.captureNavigation(f.one.id,f.one.target));assertNotNull(s.currentSourceNavigation(ticket))
        assertTrue(s.restoreSourceReturn(bookmark));assertEquals(before,s.contentRevision)
        assertEquals(undo,s.canUndo);assertEquals(redo,s.canRedo)
        assertEquals("Independent body",s.document!!.node("n").text)
    }
    @Test fun targetChangesAndSourceDeleteUndoOrFileReloadNeverReviveAnOldNavigationTicket()=scenario {f->
        val s=f.session;val old=s.captureSourceNavigation("n",f.one.id,f.one.target)!!
        assertTrue(s.operate {it.update("n") {node->node.copy(sources=node.sources.map {source->if(source.id==f.one.id) source.copy(region=Rect(1.0,2.0,30.0,40.0)) else source})}})
        assertNull(s.currentSourceNavigation(old));s.undo();assertNull(s.currentSourceNavigation(old))
        val deleted=s.captureSourceNavigation("n",f.one.id)!!
        assertTrue(s.operate {it.update("n") {node->node.copy(sources=node.sources.filterNot {it.id==f.one.id},primarySource=null)}})
        s.undo();assertNull(s.currentSourceNavigation(deleted));s.redo();assertNull(s.currentSourceNavigation(deleted));s.undo()
        val beforeReload=s.captureSourceNavigation("n",f.one.id)!!;val bookmark=s.captureSourceReturn(beforeReload)!!
        s.useFileVersion();f.await {!s.replacingDocument}
        s.selected="f"
        assertNull(s.currentSourceNavigation(beforeReload));assertFalse(s.restoreSourceReturn(bookmark));assertEquals("f",s.selected)
        assertArrayEquals(f.asset.pngBytes(),s.document!!.previewAssets.getValue(f.one.previewKey!!).pngBytes())
    }
    @Test fun onlyAnExplicitReturnRestoresSelectionAndTheExactSourceRowWithoutTouchingContentRedo()=scenario {f->
        val s=f.session;val ticket=s.captureSourceNavigation("n",f.one.id)!!
        s.operate {it.update("n") {node->node.copy(text="Temporary")}};s.undo();assertTrue(s.canRedo)
        assertTrue(s.selectRelation("edge"));val bookmark=s.captureSourceReturn(ticket)!!
        val presentation=MapPresentationState();presentation.synchronize(s)
        val first=f.view();first.panText(80f,-40f);assertTrue(presentation.capture(s,first))
        s.selected="f"
        assertTrue(presentation.restore(s,f.view(800,600)));assertEquals("f",s.selected)
        val bytes=MapCodec.encode(s.document!!);val revision=s.contentRevision
        assertTrue(presentation.applySourceReturn(s,bookmark))
        assertEquals("edge",s.selectedRelation);assertTrue(s.selectedNodes.isEmpty());assertEquals("n",presentation.sourcesFor)
        val focus=presentation.sourceFocus!!;assertEquals(f.one.id,focus.sourceId);assertEquals("n",focus.nodeId)
        presentation.consumeSourceFocus(Any());assertSame(focus,presentation.sourceFocus)
        presentation.consumeSourceFocus(focus.token);assertNull(presentation.sourceFocus)
        assertArrayEquals(bytes,MapCodec.encode(s.document!!));assertEquals(revision,s.contentRevision);assertTrue(s.canRedo)
        assertTrue(s.setNodeSelectionMode(true));assertTrue(s.selectNodes(setOf("r","n")))
        val multi=s.captureSourceReturn(ticket)!!;s.setNodeSelectionMode(false);s.selected="f"
        assertTrue(presentation.applySourceReturn(s,multi));assertTrue(s.nodeSelectionMode);assertEquals(setOf("r","n"),s.selectedNodes)
        assertTrue(s.canRedo);assertEquals(revision,s.contentRevision)
    }
    private fun center(view:MindMapView):Pt {
        val camera=view.viewport();val scale=view.contentScale()
        return Pt((view.width/2.0-camera.panX)/scale,(view.height/2.0-camera.panY)/scale)
    }
    @Test fun sourceNoteImeAndPanelClosingCannotReplaceThePannedNormalCameraBeforeOpenAndReturn()=scenario {f->
        val s=f.session;val state=MapPresentationState();state.synchronize(s)
        val first=f.view();first.zoomBy(1.4f);first.panText(97f,-63f)
        val world=center(first);val zoom=first.viewport().zoom;val content=MapCodec.encode(s.document!!)
        assertFalse(state.showSources(s,first,"n",cameraReady=false));assertNull(state.sourcesFor)
        assertTrue(state.showSources(s,first,"n"));assertEquals("n",state.sourcesFor)
        first.layout(0,0,1000,350)
        assertNotEquals(world,center(first))
        assertFalse(state.capture(s,first,imeBottom=180))
        assertFalse("A transitional resize callback with the previous IME inset must not overwrite the normal camera",state.capture(s,first))
        val ticket=s.captureSourceNavigation("n",f.one.id)!!;val bookmark=s.captureSourceReturn(ticket)!!
        state.parkSourcePanel();assertNull(state.sourcesFor);assertFalse(state.capture(s,first))
        assertTrue(state.applySourceReturn(s,bookmark))
        val next=f.view(800,600)
        assertFalse(state.restore(s,next,imeBottom=180));assertTrue(state.restore(s,next))
        assertEquals(world.x,center(next).x,.001);assertEquals(world.y,center(next).y,.001);assertEquals(zoom,next.viewport().zoom,1e-6f)
        assertEquals(f.one.id,state.sourceFocus!!.sourceId)
        assertArrayEquals(content,MapCodec.encode(s.document!!));assertFalse(s.canUndo);assertFalse(s.canRedo)
    }    @Test fun failedOpenReopensOnlyThePanelWithoutStealingANewTextSelectionOrCancellingItsDraft()=scenario {f->
        val s=f.session;val state=MapPresentationState();state.synchronize(s)
        val ticket=s.captureSourceNavigation("n",f.one.id)!!;val bookmark=s.captureSourceReturn(ticket)!!
        assertTrue(s.beginText("n",MindMapView.Viewport(1f,0f,0f),1000,700))
        val token=s.textDraft!!.token
        assertTrue(s.updateTextDraft(token,androidx.compose.ui.text.input.TextFieldValue("C1 survives failed navigation")))
        assertTrue(s.canRestoreSourceReturn(bookmark,requireReady=false));assertFalse(s.canRestoreSourceReturn(bookmark))
        assertFalse(s.restoreSourceReturn(bookmark));assertNull(s.captureSourceNavigation("n",f.one.id))
        val content=MapCodec.encode(s.document!!);val revision=s.contentRevision
        assertTrue(state.reopenSourcePanel(s,ticket))
        assertEquals("n",s.selected);assertEquals(token,s.textDraft!!.token)
        assertEquals("C1 survives failed navigation",s.textDraft!!.value.text)
        assertEquals(f.one.id,state.sourceFocus!!.sourceId)
        assertEquals(revision,s.contentRevision);assertArrayEquals(content,MapCodec.encode(s.document!!));assertFalse(s.canUndo)
        s.cancelTextDraft(token)
        assertFalse(s.canRestoreSourceReturn(bookmark));assertTrue(s.canRestoreSourceReturn(bookmark,requireReady=false))
        s.completeTextRestore(token);assertTrue(s.canRestoreSourceReturn(bookmark))
    }
    @Test fun aDeletedOrUndoRevivedSourceCanReturnToItsGraphWithoutFocusingAnotherMembership()=scenario {f->
        val s=f.session;s.selected="n"
        val ticket=s.captureSourceNavigation("n",f.one.id)!!;val bookmark=s.captureSourceReturn(ticket)!!
        val state=MapPresentationState();state.synchronize(s)
        assertTrue(s.operate {it.update("n") {node->node.copy(sources=node.sources.filterNot {it.id==f.one.id},primarySource=null)}})
        assertTrue(s.canRestoreSourceReturn(bookmark));assertNull(s.currentSourceNavigation(ticket))
        s.selected="f";val revision=s.contentRevision
        assertTrue(state.applySourceReturn(s,bookmark));assertEquals("n",s.selected)
        assertNull(state.sourcesFor);assertNull(state.sourceFocus);assertEquals(revision,s.contentRevision)
        s.undo();assertTrue(s.canRedo);assertNull(s.currentSourceNavigation(ticket))
        assertTrue(state.applySourceReturn(s,bookmark));assertNull(state.sourceFocus);assertNull(state.sourcesFor);assertTrue(s.canRedo)
        assertArrayEquals(f.asset.pngBytes(),s.document!!.previewAssets.getValue(f.one.previewKey!!).pngBytes())
    }
    @Test fun losingTheSourceWhileItsPanelIsParkedUnfreezesTheOrdinaryCameraAfterFailedReopen()=scenario {f->
        val s=f.session;val state=MapPresentationState();state.synchronize(s)
        val first=f.view();first.panText(81f,-62f)
        assertTrue(state.showSources(s,first,"n"))
        val ticket=s.captureSourceNavigation("n",f.one.id)!!
        state.parkSourcePanel();assertNull(state.sourcesFor)
        assertTrue(s.operate {it.delete("n")});state.synchronize(s)
        assertFalse(state.reopenSourcePanel(s,ticket));assertNull(state.sourceFocus);assertNull(state.sourcesFor)
        assertTrue(state.restore(s,first))
        first.panText(55f,43f);val moved=center(first)
        assertTrue("Failed source reopening must not leave the ordinary camera permanently protected",state.capture(s,first))
        val next=f.view(800,600);assertTrue(state.restore(s,next))
        assertEquals(moved.x,center(next).x,.001);assertEquals(moved.y,center(next).y,.001)
    }

}
