package com.xnotes.mindmap

import android.net.Uri
import android.os.Looper
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapTextTransactionTest {
    private fun session():MapSession {
        val context=RuntimeEnvironment.getApplication()
        val file=File.createTempFile("text-draft-",".xmap",context.cacheDir).apply {
            writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="原文",x=123.0,y=234.0)))))
        }
        return MapSession().also { s ->
            s.open(context,Uri.fromFile(file))
            repeat(300) {shadowOf(Looper.getMainLooper()).idle();if(!s.loading)return@also;Thread.sleep(10)}
            fail("Timed out loading graph")
        }
    }
    private val camera=MindMapView.Viewport(1.5f,100f,200f)
    @Test fun composingDraftAndCancellationDoNotMutateDocumentOrHistory() {
        val s=session();val original=s.document
        assertTrue(s.beginText("r",camera,800,600))
        val id=s.textDraft!!.token
        val value=TextFieldValue("中文 🖊\n草稿",TextRange(2),TextRange(0,2))
        assertTrue(s.updateTextDraft(id,value))
        assertEquals(value,s.textDraft!!.value);assertEquals(original,s.document);assertFalse(s.canUndo)
        s.cancelTextDraft(id)
        assertNull(s.textDraft);assertEquals(original,s.document);assertFalse(s.canUndo)
        assertEquals(camera,s.textRestore!!.viewport)
        assertFalse(s.updateTextDraft(id,TextFieldValue("late")))
    }
    @Test fun doneMergesSourcesAndCommitsOneUndoStep() {
        val s=session();assertTrue(s.beginText("r",camera,800,600))
        val id=s.textDraft!!.token
        val source=MapSource(documentId="00000000-0000-0000-0000-000000000001")
        s.operate {it.update("r") {n->n.copy(sources=listOf(source),width=400.0)}}
        val before=s.document
        assertTrue(s.updateTextDraft(id,TextFieldValue("完成 🖊\n第二行")))
        assertTrue(s.finishTextDraft(id));assertNull(s.textDraft)
        assertEquals("完成 🖊\n第二行",s.document!!.root.text)
        assertEquals(listOf(source),s.document!!.root.sources);assertEquals(400.0,s.document!!.root.width,0.0)
        s.undo();assertEquals(before,s.document);s.redo();assertEquals("完成 🖊\n第二行",s.document!!.root.text)
    }
    @Test fun unchangedDoneDoesNotRelayoutOrAddHistory() {
        val s=session();val original=s.document
        s.beginText("r",camera,800,600);assertTrue(s.finishTextDraft(s.textDraft!!.token))
        assertEquals(original,s.document);assertFalse(s.canUndo)
    }
    @Test fun conflictingTextRejectsDoneAndPreservesDraft() {
        val s=session();s.beginText("r",camera,800,600);val id=s.textDraft!!.token
        s.updateTextDraft(id,TextFieldValue("my draft"))
        s.operate {it.update("r") {n->n.copy(text="external")}}
        assertFalse(s.finishTextDraft(id));assertEquals("my draft",s.textDraft!!.value.text)
        assertEquals("external",s.document!!.root.text)
    }
    @Test fun overLimitIsRejectedWithoutSeparatingVisibleValueFromDraft() {
        val s=session();s.beginText("r",camera,800,600);val id=s.textDraft!!.token
        val before=s.textDraft!!.value
        assertFalse(s.updateTextDraft(id,TextFieldValue("x".repeat(20001))))
        assertEquals(before,s.textDraft!!.value);assertTrue(s.limitError)
    }
    @Test fun previousSelectionIsRestoredOnlyAfterHostRestorationAndBlocksNewEditUntilThen() {
        val s=session();s.selected=null
        assertFalse(s.beginText("r",camera,0,600))
        assertTrue(s.beginText("r",camera,800,600,2f));val token=s.textDraft!!.token
        s.cancelTextDraft(token)
        assertNotNull(s.textRestore);assertEquals(2f,s.textRestore!!.density,0f)
        assertFalse(s.beginText("r",camera,800,600))
        s.completeTextRestore("late");assertNotNull(s.textRestore)
        s.completeTextRestore(token);assertNull(s.textRestore);assertEquals("r",s.selected)
        assertTrue(s.beginText("r",camera,800,600))
    }
    @Test fun emptyTextCanCommitAndOldHostCannotOverwriteLaterDraft() {
        val s=session();s.beginText("r",camera,800,600);val old=s.textDraft!!.token
        s.updateTextDraft(old,TextFieldValue(""));assertTrue(s.finishTextDraft(old))
        assertEquals("",s.document!!.root.text);s.completeTextRestore(old)
        s.beginText("r",camera,800,600);val current=s.textDraft!!.token
        assertFalse(s.updateTextDraft(old,TextFieldValue("late")));assertFalse(s.finishTextDraft(old))
        assertEquals(current,s.textDraft!!.token);assertEquals("",s.textDraft!!.value.text)
    }
    @Test fun nodePropertiesStillConvertTextRootToInkInOneStepAndRetainSources() {
        val s=session();val root=s.document!!.root
        val source=MapSource(documentId="00000000-0000-0000-0000-000000000001")
        s.operate {it.update("r") {n->n.copy(sources=listOf(source))}};val before=s.document
        assertTrue(s.editNodeProperties("r",root.copy(kind=NodeKind.INK)))
        assertEquals(NodeKind.INK,s.document!!.root.kind);assertEquals(listOf(source),s.document!!.root.sources)
        s.undo();assertEquals(before,s.document)
    }
}
