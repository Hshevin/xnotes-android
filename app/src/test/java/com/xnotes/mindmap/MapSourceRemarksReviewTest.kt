package com.xnotes.mindmap

import com.xnotes.R
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class MapSourceRemarksReviewTest {
    private val source=MapSource(id="source",documentId="00000000-0000-0000-0000-000000000001",fileName="Source",status=SourceStatus.UNAVAILABLE)
    private fun document()=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Keep history",sources=listOf(source))))
    @Test fun typingASourceRemarkStaysLocalAndDoneCreatesExactlyOneUndoStep() {
        MapComposeReviewFixture(document()).use {f->
            val initial=f.session.document!!;val editor=SourceListEditor(f.session,f.session.sourceEditSnapshot("root")!!)
            var closed=false
            f.content {GraphSourcesDialog(f.session.sourceEditSnapshot("root")!!,editor,add={}) {closed=true}}
            f.enter("a");f.enter("ab");f.enter("A complete source remark")
            assertEquals("Per-key text must stay in the row draft",initial,f.session.document)
            assertFalse(f.session.canUndo)
            f.click(R.string.map_done);assertTrue(closed)
            assertEquals("A complete source remark",f.session.document!!.root.sources.single().note)
            f.session.undo();assertEquals(initial,f.session.document);assertFalse(f.session.canUndo)
            f.session.redo();assertEquals("A complete source remark",f.session.document!!.root.sources.single().note)
        }
    }
    @Test fun confirmingARemarkAgainstAChangedSourceBaselineKeepsTheDraftAndReportsConflict() {
        MapComposeReviewFixture(document()).use {f->
            val editor=SourceListEditor(f.session,f.session.sourceEditSnapshot("root")!!);var closed=false
            f.content {GraphSourcesDialog(f.session.sourceEditSnapshot("root")!!,editor,add={}) {closed=true}}
            f.enter("My pending remark")
            f.session.operate {it.update("root") {n->n.copy(sources=n.sources.map {s->s.copy(note="Another accepted remark")})}}
            f.settle();f.click(R.string.map_done)
            assertFalse("A conflicting draft must remain reviewable",closed)
            assertTrue(editor.conflict);assertEquals("Another accepted remark",f.session.document!!.root.sources.single().note)
            assertTrue(f.visible(R.string.graph_sources_changed))
        }
    }
    @Test fun anOlderTextFieldCallbackCannotAdoptASyncedNewRemarkAsItsOwnBaseline() {
        MapComposeReviewFixture(document()).use {f->
            val editor=SourceListEditor(f.session,f.session.sourceEditSnapshot("root")!!);var closed=false
            f.content {GraphSourcesDialog(f.session.sourceEditSnapshot("root")!!,editor,add={}) {closed=true}}
            val oldText=f.textAction()
            f.session.operate {it.update("root") {n->n.copy(sources=n.sources.map {s->s.copy(note="Accepted while old field remained")})}}
            f.settle();assertTrue(oldText(AnnotatedString("Text from the retired field")));f.settle()
            f.click(R.string.map_done)
            assertFalse("A retired field must keep its own baseline instead of silently adopting the new note",closed)
            assertTrue(editor.conflict)
            assertEquals("Accepted while old field remained",f.session.document!!.root.sources.single().note)
        }
    }
    @Test fun aFieldRenderedBeforeSourceRemovalCannotStartANewDraftAfterUndoRevivesTheSameValues() {
        MapComposeReviewFixture(document()).use {f->
            val editor=SourceListEditor(f.session,f.session.sourceEditSnapshot("root")!!);var closed=false
            f.content {GraphSourcesDialog(f.session.sourceEditSnapshot("root")!!,editor,add={}) {closed=true}}
            val oldText=f.textAction()
            f.session.operate {it.update("root") {n->n.copy(sources=emptyList())}}
            f.session.undo();val restored=f.session.document!!;assertTrue(f.session.canRedo)
            // No composition refreshes the old row before its retained actual field callback runs.
            assertTrue(oldText(AnnotatedString("Keep this rejected draft")));f.settle();f.click(R.string.map_done)
            assertFalse("The old field cannot claim the restored member's new lifetime",closed)
            assertTrue(editor.conflict);assertEquals("Keep this rejected draft",editor.noteDraft(source.id))
            assertSame(restored,f.session.document);assertTrue(f.session.canRedo)
        }
    }
}
