package com.xnotes.mindmap

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.viewModelScope
import com.xnotes.core.geometry.Pt
import java.io.File
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real session/history and native MapView camera geometry; no substitute viewport engine. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapPresentationStateTest {
    private fun await(condition:()->Boolean) {
        repeat(500) {shadowOf(Looper.getMainLooper()).idle();if(condition()) return;Thread.sleep(5)}
        fail("Actual map presentation IO did not settle")
    }
    private fun scenario(block:(MapSession,Context)->Unit) {
        val context=RuntimeEnvironment.getApplication()
        val doc=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),MapNode(id="n",parent="r",text="Before")))
        val file=File.createTempFile("map-presentation-",".xmap",context.cacheDir).apply {writeBytes(MapCodec.encode(doc))}
        val session=MapSession()
        try {
            session.open(context,Uri.fromFile(file));await {!session.loading};assertFalse(session.loadFailed)
            block(session,context)
        } finally {
            session.textDraft?.let {session.cancelTextDraft(it.token)}
            session.textRestore?.let {session.completeTextRestore(it.token)}
            session.save();await {!session.saving};session.viewModelScope.cancel();file.delete()
        }
    }
    private fun graph(session:MapSession,context:Context,width:Int=1000,height:Int=700)=MindMapView(context).apply {
        document=session.document!!;documentEpoch=session.interactionEpoch
        layout(0,0,width,height);fit();setSelectedNodes(session.selectedNodes)
    }
    private fun density(context:Context,dpi:Int)=context.createConfigurationContext(Configuration(context.resources.configuration).apply {densityDpi=dpi})
    private fun center(view:MindMapView):Pt {
        val v=view.viewport();val scale=view.contentScale()
        return Pt((view.width/2.0-v.panX)/scale,(view.height/2.0-v.panY)/scale)
    }
    @Test fun aNewViewRestoresTheSameWorldCenterAcrossDensityAndSizeWithoutOverwritingSelectionOrHistory()=scenario {session,context->
        session.operate {it.update("n") {node->node.copy(text="First edit")}}
        session.operate {it.update("n") {node->node.copy(text="Second edit")}};session.undo()
        assertTrue(session.canRedo)
        val state=MapPresentationState();val first=graph(session,context)
        first.zoomBy(1.4f);first.panText(93f,-67f)
        state.synchronize(session);state.sourcesFor="n"
        assertTrue(state.capture(session,first))
        val world=center(first);val zoom=first.viewport().zoom
        val model=session.document;val revision=session.contentRevision;val undo=session.canUndo;val redo=session.canRedo
        session.selected="n" // A new native→graph import may choose a node after the old camera was parked.
        val second=graph(session,density(context,320),800,600)
        assertTrue(state.restore(session,second))
        assertEquals(world.x,center(second).x,.001);assertEquals(world.y,center(second).y,.001)
        assertEquals(zoom,second.viewport().zoom,1e-6f)
        assertEquals("n",session.selected);assertEquals("n",state.sourcesFor)
        assertSame(model,session.document);assertEquals(revision,session.contentRevision)
        assertEquals(undo,session.canUndo);assertEquals(redo,session.canRedo)
        second.panText(21f,-13f);val moved=second.viewport()
        assertFalse(state.restore(session,second));assertEquals(moved,second.viewport())
    }
    @Test fun savedArrangementExportsOnlyTheNormalCameraAndRestoresItWithoutOldSourceFocusOrHistory()=scenario {session,context->
        val state=MapPresentationState();val view=graph(session,context)
        view.zoomBy(1.3f);view.panText(83f,-27f);state.synchronize(session)
        assertTrue(state.capture(session,view))
        val saved=requireNotNull(state.arrangementCamera(session))
        assertEquals(view.viewport(),saved.viewport)
        state.sourcesFor="n"
        session.operate {it.update("n") {node->node.copy(text="B")}}
        val document=session.document;val revision=session.contentRevision;val selected=session.selected
        val undo=session.canUndo;val redo=session.canRedo
        val next=MapPresentationState();next.synchronize(session);next.sourcesFor="n"
        assertTrue(next.applyArrangementCamera(session,saved));assertNull(next.sourcesFor);assertNull(next.sourceFocus)
        val replacement=graph(session,context,750,500)
        assertTrue(next.restore(session,replacement))
        assertEquals(center(view).x,center(replacement).x,.001);assertEquals(center(view).y,center(replacement).y,.001)
        assertSame(document,session.document);assertEquals(revision,session.contentRevision);assertEquals(selected,session.selected)
        assertEquals(undo,session.canUndo);assertEquals(redo,session.canRedo)
    }
    @Test fun anImeOrUnstableTextCameraCannotReplaceTheNormalCameraAndImeDoesNotConsumeItsRestore()=scenario {session,context->
        val state=MapPresentationState();val first=graph(session,context)
        first.zoomBy(1.3f);first.panText(61f,42f)
        state.synchronize(session);assertTrue(state.capture(session,first))
        val normal=center(first);val zoom=first.viewport().zoom
        assertTrue(session.beginText("n",first.viewport(),first.width,first.height,first.resources.displayMetrics.density))
        val token=session.textDraft!!.token
        assertTrue(session.updateTextDraft(token,TextFieldValue("Unconfirmed typing")))
        first.prepareTextEditing("n");first.zoomBy(1.8f)
        assertFalse(state.capture(session,first))
        session.cancelTextDraft(token);assertNotNull(session.textRestore)
        assertFalse(state.capture(session,first))
        session.completeTextRestore(token)
        assertFalse(state.capture(session,first,imeBottom=180))
        val second=graph(session,context,750,500);val initial=second.viewport()
        assertFalse(state.restore(session,second,imeBottom=180));assertEquals(initial,second.viewport())
        assertTrue(state.restore(session,second))
        assertEquals(normal.x,center(second).x,.001);assertEquals(normal.y,center(second).y,.001)
        assertEquals(zoom,second.viewport().zoom,1e-6f);assertFalse(session.canUndo)
    }
    @Test fun aSameIdFileReloadRetiresTheOldCameraAndSourcePanelEvenWhenTheOldViewDisposesLater()=scenario {session,context->
        val state=MapPresentationState();val first=graph(session,context)
        first.panText(115f,82f);state.synchronize(session);state.sourcesFor="n"
        assertTrue(state.capture(session,first))
        session.useFileVersion();await {!session.replacingDocument}
        state.synchronize(session)
        assertNull(state.sourcesFor)
        assertFalse("The outgoing old-epoch view must not reinsert its camera",state.capture(session,first))
        val second=graph(session,context)
        val fit=second.viewport();assertFalse(state.restore(session,second));assertEquals(fit,second.viewport())
        assertFalse(session.canUndo);assertFalse(session.canRedo)
    }
    @Test fun normalBodyChangesKeepTheCameraButANewDocumentIdentityDropsIt()=scenario {session,context->
        val state=MapPresentationState();val first=graph(session,context)
        first.panText(-88f,99f);state.synchronize(session);state.sourcesFor="n";assertTrue(state.capture(session,first))
        val world=center(first)
        session.operate {it.update("n") {node->node.copy(text="Body changed while native was visible")}}
        state.synchronize(session)
        val second=graph(session,context);assertTrue(state.restore(session,second));assertEquals(world.x,center(second).x,.001);assertEquals(world.y,center(second).y,.001)
        val nextId=java.util.UUID.randomUUID().toString()
        session.operate {it.copy(id=nextId)};state.synchronize(session)
        assertNull(state.sourcesFor);assertFalse(state.capture(session,first))
        val third=graph(session,context);assertFalse(state.restore(session,third))
    }
}
