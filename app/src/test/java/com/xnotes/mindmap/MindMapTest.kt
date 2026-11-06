package com.xnotes.mindmap

import org.junit.Assert.*
import org.junit.Test

class MindMapTest {
    private fun tree(): MindMapDocument {
        val root = MapNode(id = "r", parent = null, text = "Root")
        return MindMapDocument(id = "doc", nodes = listOf(root,
            MapNode(id = "a", parent = "r"), MapNode(id = "b", parent = "r"),
            MapNode(id = "c", parent = "a")))
    }

    @Test fun deletingSubtreeAndUndoRestoresInkAndOrder() {
        val doc = tree().update("c") { it.copy(kind = NodeKind.INK, ink = listOf(MapInk(listOf(InkPoint(5.0, 8.0, .7, 12.0))))) }
        val history = MapHistory(doc)
        history.commit(doc.delete("a"))
        assertEquals(listOf("r", "b"), history.document.nodes.map { it.id })
        history.undo()
        assertEquals(doc, history.document)
        history.redo()
        assertEquals(listOf("r", "b"), history.document.nodes.map { it.id })
    }

    @Test fun rejectsCyclesAndRootReparentingWithoutChangingTree() {
        val doc = tree()
        assertEquals(doc, doc.reparent("a", "c"))
        assertEquals(doc, doc.reparent("r", "b"))
        assertEquals(doc, doc.delete("r"))
        val moved = doc.reparent("c", "b")
        assertEquals("b", moved.node("c").parent)
        moved.validate()
    }

    @Test fun collapsedBranchesDisappearButContentSurvives() {
        val doc = tree().update("a") { it.copy(collapsed = true) }
        assertEquals(listOf("r", "a", "b"), doc.visible().map { it.id })
        assertEquals(4, doc.nodes.size)
    }

    @Test fun layoutAvoidsOverlapWithUnequalNodeSizesOnBothSides() {
        var doc = tree().update("a") { it.copy(height = 300.0, width = 420.0) }
        doc = doc.add("a", NodeKind.TEXT, "Tall sibling").first
        val laid = MapLayout.arrange(doc)
        val nodes = laid.visible()
        for (a in nodes) for (b in nodes) if (a.id != b.id) {
            assertFalse("${a.id} overlaps ${b.id}", a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height)
        }
        assertTrue(laid.node("a").x > laid.node("r").x)
        assertTrue(laid.node("b").x < laid.node("r").x)
    }

    @Test fun subtreeTranslationPreservesLocalInkAndDescendantOffsets() {
        val doc = tree()
        val moved = doc.translate("a", 36.0, -17.0)
        assertEquals(36.0, moved.node("c").x - doc.node("c").x, .001)
        assertEquals(-17.0, moved.node("a").y - doc.node("a").y, .001)
        assertEquals(doc.node("b"), moved.node("b"))
    }

    @Test fun selectionAndNoopDoNotConsumeHistoryAndNewEditDropsRedo() {
        val history = MapHistory(tree())
        history.commit(history.document)
        assertFalse(history.canUndo)
        history.commit(history.document.delete("c"))
        history.undo()
        history.commit(history.document.delete("b"))
        assertFalse(history.canRedo)
    }

    @Test fun malformedTreeIsRejected() {
        for (nodes in listOf(
            listOf(MapNode(id = "r", parent = null), MapNode(id = "r", parent = "r")),
            listOf(MapNode(id = "r", parent = null), MapNode(id = "a", parent = "missing")),
            listOf(MapNode(id = "r", parent = null), MapNode(id = "a", parent = "b"), MapNode(id = "b", parent = "a")),
            listOf(MapNode(id = "r", parent = null, x = Double.NaN))
        )) assertThrows(IllegalArgumentException::class.java) { MindMapDocument(nodes = nodes).validate() }
    }
    @Test fun fitPaperPreservesWorldInkAndUndoRestoresOriginalCoordinates() {
        val original=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,kind=NodeKind.INK,x=200.0,y=100.0,
            ink=listOf(MapInk(listOf(InkPoint(-30.0,-20.0,.75,123.0),InkPoint(400.0,270.0,.9,150.0)))))))
        original.validate()
        val fitted=original.update("r") {it.fitInkContent()}
        val old=original.root;val next=fitted.root
        assertTrue(next.width>old.width);assertTrue(next.height>old.height)
        for((a,b) in old.ink.single().points.zip(next.ink.single().points)) {
            assertEquals(a.x+old.x,b.x+next.x,.001);assertEquals(a.y+old.y,b.y+next.y,.001)
            assertEquals(a.pressure,b.pressure,0.0);assertEquals(a.time,b.time,0.0)
        }
        val h=MapHistory(original);h.commit(fitted);h.undo();assertEquals(original,h.document)
        h.redo();assertEquals(fitted,MapCodec.decode(MapCodec.encode(h.document)))
    }

}
