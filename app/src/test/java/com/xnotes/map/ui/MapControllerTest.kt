package com.xnotes.map.ui

import com.xnotes.core.geometry.Pt
import com.xnotes.core.tools.Tool
import com.xnotes.map.LayoutKind
import com.xnotes.map.NodeId
import com.xnotes.map.Vec
import com.xnotes.map.pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapControllerTest {

    private val x = ControllerFixture()
    private val c = x.c
    private val e = x.editor

    private fun root(title: String = "root", at: Pt = Pt(0.0, 0.0)): NodeId = x.f.root(title, at)

    // ---- who takes a press ----

    @Test fun aFingerTapSelectsANodeUnderAnyTool() {
        val r = root()
        for (tool in listOf(Tool.PAN, Tool.PEN, Tool.ERASER, Tool.SHAPE, Tool.MAP)) {
            c.clearSelection()
            assertTrue("$tool", x.tap(x.center(r), tool = tool))
            assertEquals(r, c.selected)
        }
        assertEquals("selecting is not an edit", 1, x.f.undoCount())
    }

    @Test fun aPenTakesNodesOnlyUnderTheMapSelectAndPanTools() {
        val r = root()
        for (tool in listOf(Tool.MAP, Tool.SELECT, Tool.PAN)) assertTrue("$tool", x.tap(x.center(r), finger = false, tool = tool))
        for (tool in listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.SHAPE, Tool.TEXT)) {
            assertFalse("$tool leaves the node to the canvas", x.tap(x.center(r), finger = false, tool = tool))
        }
        assertEquals(r, c.inkTarget(x.center(r)))
    }

    @Test fun withFingerDrawingOnAFingerTakesNodesOnlyUnderTheMapAndSelectTools() {
        val r = root()
        assertFalse(x.gesture(x.center(r), tool = Tool.PEN, fingerDraws = true))
        assertFalse(x.gesture(x.center(r), tool = Tool.PAN, fingerDraws = true))
        assertTrue(x.gesture(x.center(r), tool = Tool.MAP, fingerDraws = true))
        assertTrue(x.gesture(x.center(r), tool = Tool.SELECT, fingerDraws = true))
    }

    @Test fun theEraserNeverTakesTheMap() {
        val r = root()
        assertFalse(c.press(x.center(r), 1.0, 48.0, finger = false, eraser = true, tool = Tool.MAP, connector = false, fingerDraws = false))
    }

    @Test fun emptyCanvasIsLeftToTheCanvas() {
        root()
        assertFalse(x.tap(Pt(2000.0, 2000.0), tool = Tool.MAP))
    }

    @Test fun treesOnLayersThatMayNotBeEditedAreOutOfReach() {
        val r = root()
        x.host.editable = { false }
        c.refresh()
        assertNull(c.nodeAt(x.center(r)))
        assertFalse(x.tap(x.center(r)))
        assertNull(c.inkTarget(x.center(r)))
    }

    // ---- taps ----

    @Test fun tappingTheSelectedNodeOpensItsTitle() {
        val r = root("old")
        x.tap(x.center(r))
        x.tap(x.center(r))
        assertTrue(c.isEditingTitle)
        assertEquals(r, c.titleField!!.node)
        assertEquals("old", c.titleField!!.text)
        assertFalse(c.titleField!!.isDraft)
    }

    @Test fun typingResizesTheShownNodeButNotTheModelUntilCommitted() {
        val r = root("a")
        val steps = x.f.undoCount()
        c.editTitle(r)
        val before = x.rect(r).w
        c.typing("a far longer title than before")
        assertTrue(x.rect(r).w > before)
        assertEquals("a", x.state.nodes.getValue(r).title)
        c.commitTitle()
        assertEquals("a far longer title than before", x.state.nodes.getValue(r).title)
        assertEquals(steps + 1, x.f.undoCount())
        assertFalse(c.isEditingTitle)
    }

    @Test fun cancellingAnEditKeepsTheOldTitle() {
        val r = root("keep")
        c.editTitle(r)
        c.typing("changed")
        c.cancelTitle()
        assertEquals("keep", x.state.nodes.getValue(r).title)
        assertEquals(1, x.f.undoCount())
    }

    @Test fun tappingEmptyCanvasWithTheMapToolStartsACentralTopic() {
        x.host.active = "layer2"
        assertTrue(c.tapBlank(Pt(500.0, 300.0), mapTool = true))
        assertNotNull(e.draft)
        val draft = e.draft!!.node
        assertTrue(c.isEditingTitle)
        assertEquals(draft, c.selected)
        assertEquals("layer2", x.state.layers[draft])
        val box = x.rect(draft)
        assertTrue("the topic sits where the tap was", box.contains(Pt(500.0, 300.0)))
        c.typing("Idea")
        c.commitTitle()
        assertEquals(listOf(draft), x.state.roots)
        assertEquals("Idea", x.state.nodes.getValue(draft).title)
        assertEquals(1, x.f.undoCount())
    }

    @Test fun aBlankNewTopicIsNotMade() {
        c.tapBlank(Pt(0.0, 0.0), mapTool = true)
        c.commitTitle()
        assertTrue(x.state.isEmpty)
        assertNull(c.selected)
        assertEquals(0, x.f.undoCount())
    }

    @Test fun tappingEmptyCanvasPutsTheSelectionAwayFirst() {
        val r = root()
        x.tap(x.center(r))
        assertTrue(c.tapBlank(Pt(2000.0, 0.0), mapTool = true))
        assertNull(c.selected)
        assertEquals("no new topic while something was selected", 1, x.state.nodes.size)
        assertFalse("without the map tool and nothing selected the tap is the canvas's", c.tapBlank(Pt(2000.0, 0.0), mapTool = false))
    }

    @Test fun tappingEmptyCanvasEndsAnEditKeepingTheWords() {
        val r = root("a")
        c.editTitle(r)
        c.typing("b")
        c.tapBlank(Pt(2000.0, 0.0), mapTool = false)
        assertEquals("b", x.state.nodes.getValue(r).title)
        assertFalse(c.isEditingTitle)
    }

    // ---- handles ----

    @Test fun theChildHandleSitsOnTheGrowthSideAndTheSiblingHandleAcrossIt() {
        val r = root()
        val a = x.f.child(r, "a")
        c.select(a)
        val box = x.rect(a)
        val handles = c.chrome(1.0, 48.0)!!.handles
        val child = handles.single { it.kind == MapController.HandleKind.CHILD }
        val sibling = handles.single { it.kind == MapController.HandleKind.SIBLING }
        assertTrue(child.center.x > box.right)
        assertEquals(box.centerY, child.center.y, 1e-9)
        assertTrue(sibling.center.y > box.bottom)
        assertEquals(24.0, child.radius, 1e-9)

        c.select(r)
        assertEquals("a root has no sibling handle", listOf(MapController.HandleKind.CHILD), c.chrome(1.0, 48.0)!!.handles.map { it.kind })

        e.setLayout(r, LayoutKind.ORG)
        c.select(a)
        val org = c.chrome(1.0, 48.0)!!.handles
        val down = org.single { it.kind == MapController.HandleKind.CHILD }
        assertTrue(down.center.y > x.rect(a).bottom)
        assertTrue(org.single { it.kind == MapController.HandleKind.SIBLING }.center.x > x.rect(a).right)
    }

    @Test fun handlesKeepTheirScreenSizeAtAnyZoom() {
        val r = root()
        c.select(r)
        assertEquals(12.0, c.chrome(2.0, 48.0)!!.handles.single().radius, 1e-9)
    }

    @Test fun theChildHandleStartsASubtopicReadyToType() {
        val r = root()
        c.select(r)
        val handle = c.chrome(1.0, 48.0)!!.handles.single()
        assertTrue(x.gesture(handle.center, finger = false, tool = Tool.PEN))
        assertNotNull(e.draft)
        val draft = e.draft!!.node
        assertEquals(r, x.state.parent[draft])
        assertTrue(c.isEditingTitle)
        assertTrue(c.titleField!!.isDraft)
        c.typing("Sub")
        c.commitTitle()
        assertEquals("Sub", x.state.nodes.getValue(draft).title)
        assertEquals(draft, c.selected)
        assertEquals(2, x.f.undoCount())
    }

    @Test fun tabAndEnterAddChildrenAndSiblings() {
        val r = root()
        c.select(r)
        c.commitAndAdd(MapController.HandleKind.SIBLING)
        val first = e.draft!!.node
        assertEquals("a root's sibling is its child", r, x.state.parent[first])
        c.typing("one")
        c.commitAndAdd(MapController.HandleKind.SIBLING)
        val second = e.draft!!.node
        assertEquals(listOf(first, second), x.state.childrenOf(r))
        c.typing("two")
        c.commitAndAdd(MapController.HandleKind.CHILD)
        assertEquals(second, x.state.parent[e.draft!!.node])
    }

    // ---- drags ----

    @Test fun draggingANodeToEmptySpaceNudgesItAsOneStep() {
        val r = root()
        val a = x.f.child(r, "a")
        val start = x.center(a)
        val steps = x.f.undoCount()
        x.gesture(start, Pt(start.x + 40.0, start.y + 100.0), Pt(start.x + 50.0, start.y + 120.0))
        assertEquals(Vec(50.0, 120.0), x.state.offsets[a])
        assertEquals(a, c.selected) // before undoCount(), whose undo of a's creation clears it
        assertEquals(steps + 1, x.f.undoCount())
    }

    @Test fun aDragPreviewsWithoutTouchingTheModel() {
        val r = root()
        val a = x.f.child(r, "a")
        val start = x.center(a)
        val before = x.state
        val was = x.rect(a)
        c.press(start, 1.0, 48.0, finger = true, eraser = false, tool = Tool.PAN, connector = false, fingerDraws = false)
        c.move(Pt(start.x, start.y + 200.0), 1.0)
        assertEquals(before, x.state)
        assertEquals(was.y + 200.0, x.rect(a).y, 1e-9)
        assertTrue(c.chrome(1.0, 48.0)!!.handles.isEmpty())
        c.cancel()
        assertEquals(was, x.rect(a))
        assertEquals(before, x.state)
    }

    @Test fun aShortWiggleIsATapNotADrag() {
        val r = root()
        val start = x.center(r)
        x.gesture(start, Pt(start.x + 5.0, start.y + 5.0))
        assertEquals(r, c.selected)
        assertEquals(1, x.f.undoCount())
    }

    @Test fun droppingOntoAnotherNodeMakesItsChild() {
        val r = root()
        val a = x.f.child(r, "a")
        val b = x.f.child(r, "b")
        x.gesture(x.center(a), Pt(x.center(a).x + 30.0, x.center(a).y), x.center(b))
        assertEquals(b, x.state.parent[a])
    }

    @Test fun theDropTargetIsOutlinedWhileDragging() {
        val r = root()
        val a = x.f.child(r, "a")
        val b = x.f.child(r, "b")
        c.press(x.center(a), 1.0, 48.0, finger = true, eraser = false, tool = Tool.PAN, connector = false, fingerDraws = false)
        c.move(Pt(x.center(a).x + 30.0, x.center(a).y), 1.0)
        c.move(x.center(b), 1.0)
        assertEquals(x.rect(b), c.chrome(1.0, 48.0)!!.dropTarget)
        c.cancel()
    }

    // ---- relations ----

    @Test fun theConnectorToolDrawsARelationBetweenTwoNodes() {
        val a = root("a")
        val b = root("b", at = Pt(600.0, 0.0))
        assertTrue(x.gesture(x.center(a), Pt(300.0, 20.0), x.center(b), finger = false, tool = Tool.SHAPE, connector = true))
        val rel = x.state.relations.values.single()
        assertEquals(a, rel.from)
        assertEquals(b, rel.to)
        assertEquals(rel.id, c.selectedRelation)
        assertNull(c.selected)
    }

    @Test fun aRelationLineFollowsThePointerUntilItReachesANode() {
        val a = root("a")
        val b = root("b", at = Pt(600.0, 0.0))
        c.press(x.center(a), 1.0, 48.0, finger = false, eraser = false, tool = Tool.SHAPE, connector = true, fingerDraws = false)
        c.move(Pt(300.0, 300.0), 1.0)
        assertEquals(Pt(300.0, 300.0), c.chrome(1.0, 48.0)!!.relationLine!!.second)
        c.move(x.center(b), 1.0)
        assertEquals(x.rect(b), c.chrome(1.0, 48.0)!!.dropTarget)
        c.release(Pt(300.0, 300.0))
        assertTrue("released off any node: nothing", x.state.relations.isEmpty())
    }

    @Test fun tappingARelationSelectsIt() {
        val a = root("a")
        val b = root("b", at = Pt(600.0, 0.0))
        val id = e.addRelation(a, b)
        c.refresh()
        val (p, q) = MapProjectionTestHelpers.ends(x, a, b)
        val mid = Pt((p.x + q.x) / 2, (p.y + q.y) / 2)
        assertTrue(x.tap(mid))
        assertEquals(id, c.selectedRelation)
        assertNotNull(c.selectedRelationRect)
        c.deleteSelected()
        assertTrue(x.state.relations.isEmpty())
    }

    // ---- ink ----

    @Test fun writingIntoANodeBeingCreatedKeepsItWithoutWords() {
        val r = root()
        c.select(r)
        c.commitAndAdd(MapController.HandleKind.CHILD)
        val draft = e.draft!!.node
        val box = x.rect(draft)
        assertTrue(c.writeInk(draft, pen(box.left + 20.0 to box.bottom + 10.0, box.left + 60.0 to box.bottom + 20.0)))
        assertNull(e.draft)
        assertEquals("", x.state.nodes.getValue(draft).title)
        assertEquals(1, x.state.nodes.getValue(draft).ink.strokes.size)
        assertFalse(c.isEditingTitle)
    }

    // ---- selection and history ----

    @Test fun aSelectedNodeThatGoesAwayIsNoLongerSelected() {
        val r = root()
        val a = x.f.child(r, "a")
        c.select(a)
        x.f.history.undo()
        assertNull(c.selected)
    }

    @Test fun historyFinishesTheEditFirst() {
        val r = root("a")
        c.editTitle(r)
        c.typing("b")
        c.settleForHistory()
        assertFalse(c.isEditingTitle)
        assertEquals("b", x.state.nodes.getValue(r).title)
    }

    @Test fun menuActionsActOnTheSelectedNode() {
        val r = root()
        val a = x.f.child(r, "a")
        x.f.child(a, "a1")
        c.select(a)
        assertTrue(c.selectedHasChildren)
        c.toggleCollapsed()
        assertTrue(c.selectedCollapsed)
        c.setLayout(LayoutKind.INDENT)
        assertEquals(LayoutKind.INDENT, x.state.layout[r])
        c.detachSelected()
        assertTrue(a in x.state.roots)
        c.deleteSelected()
        assertFalse(a in x.state.nodes)
        assertNull(c.selected)
    }

    @Test fun copiedSubtreesPasteAsNewTreesOnTheActiveLayer() {
        val r = root()
        val a = x.f.child(r, "a")
        x.f.child(a, "a1")
        c.select(a)
        val fragment = c.copySelected()!!
        x.host.active = "top"
        assertTrue(c.paste(fragment, Pt(1000.0, 1000.0)))
        val pasted = x.state.roots.last()
        assertEquals("a", x.state.nodes.getValue(pasted).title)
        assertEquals("top", x.state.layers[pasted])
        assertEquals(pasted, c.selected)
        assertEquals(Pt(1000.0, 1000.0), x.state.rootOrigin.getValue(pasted))
    }

    @Test fun everyChangeTellsTheHost() {
        val before = x.host.changes
        root()
        assertTrue(x.host.changes > before)
    }
}

/** The ends of the relation line between [a] and [b] as shown. */
object MapProjectionTestHelpers {
    fun ends(x: ControllerFixture, a: NodeId, b: NodeId): Pair<Pt, Pt> =
        MapProjection.relationEnds(x.rect(a), x.rect(b))!!
}
