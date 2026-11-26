package com.xnotes.map.ui

import com.xnotes.core.geometry.Pt
import com.xnotes.core.tools.Tool
import com.xnotes.map.LayoutKind
import com.xnotes.map.NodeId
import com.xnotes.map.Vec
import com.xnotes.map.pen
import com.xnotes.core.testkit.assertEquals
import com.xnotes.core.testkit.assertFalse
import com.xnotes.core.testkit.assertNotNull
import com.xnotes.core.testkit.assertNull
import com.xnotes.core.testkit.assertTrue
import com.xnotes.core.testkit.assertSame
import kotlin.test.Test

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
        assertEquals("a root has no sibling handle", listOf(MapController.HandleKind.RESIZE, MapController.HandleKind.CHILD), c.chrome(1.0, 48.0)!!.handles.map { it.kind })

        e.setLayout(r, LayoutKind.ORG)
        c.select(a)
        val org = c.chrome(1.0, 48.0)!!.handles
        val down = org.single { it.kind == MapController.HandleKind.CHILD }
        assertTrue(down.center.y > x.rect(a).bottom)
        assertTrue(org.single { it.kind == MapController.HandleKind.SIBLING }.center.x > x.rect(a).right)
    }

    @Test fun zoomedOutHandlesSitOnTheLineNotOverTheNeighbours() {
        // Below the root, so both children grow to the same side whatever the root's balance.
        val p = x.f.child(root(), "p")
        val a = x.f.child(p, "a")
        val b = x.f.child(p, "b")
        // At zoom 0.25 a 48 px target has a 96 canvas-unit radius, far wider than the layout's gaps.
        c.select(p)
        val child = c.chrome(0.25, 48.0)!!.handles.single { it.kind == MapController.HandleKind.CHILD }
        assertTrue(child.center.x > x.rect(p).right)
        assertTrue("the child handle stops short of the children", child.center.x < x.rect(a).left)
        c.select(a)
        val sibling = c.chrome(0.25, 48.0)!!.handles.single { it.kind == MapController.HandleKind.SIBLING }
        assertTrue(sibling.center.y > x.rect(a).bottom)
        assertTrue("the sibling handle stops short of the next sibling", sibling.center.y < x.rect(b).top)
    }

    @Test fun aNodeUnderThePressBeatsAHandleSpillingOverIt() {
        val r = root()
        val a = x.f.child(r, "a")
        c.select(r)
        val handle = c.chrome(0.25, 48.0)!!.handles.single { it.kind == MapController.HandleKind.CHILD }
        val onChild = Pt(x.rect(a).left + 2.0, x.rect(a).centerY)
        assertTrue("the target reaches the child", handle.center.distanceTo(onChild) <= handle.radius)
        c.press(onChild, 0.25, 48.0, finger = true, eraser = false, tool = Tool.PAN, connector = false, fingerDraws = false)
        c.release(onChild)
        assertEquals("the tap selected the child, not a new subtopic", a, c.selected)
        assertNull(e.draft)
    }

    @Test fun aCollapsedNodesChildHandleClearsItsCountBadge() {
        val r = root()
        x.f.child(r, "a")
        x.f.model.setCollapsed(r, true)
        c.refresh()
        c.select(r)
        val handle = c.chrome(1.0, 48.0)!!.handles.single { it.kind == MapController.HandleKind.CHILD }
        val box = x.rect(r)
        assertTrue(handle.center.x - handle.radius >= box.right + MapProjection.BADGE)
    }

    @Test fun handlesKeepTheirScreenSizeAtAnyZoom() {
        val r = root()
        c.select(r)
        assertEquals(12.0, c.chrome(2.0, 48.0)!!.handles.single { it.kind == MapController.HandleKind.CHILD }.radius, 1e-9)
    }

    // ---- resizing ----

    private fun grip() = c.chrome(1.0, 48.0)!!.handles.single { it.kind == MapController.HandleKind.RESIZE }

    @Test fun theResizeGripSitsAtTheSelectedNodesBottomRightCorner() {
        val r = root()
        c.select(r)
        val box = x.rect(r)
        val g = grip()
        assertTrue(g.center.x > box.right && g.center.y > box.bottom)
        assertTrue(g.center.distanceTo(Pt(box.right, box.bottom)) < g.radius)
    }

    @Test fun draggingTheGripPreviewsThenResizesInOneStep() {
        val r = root()
        // counted first: counting undoes everything, which clears the selection
        val undo = x.f.undoCount()
        c.select(r)
        val start = x.rect(r)
        val g = grip().center
        // pressed just inside the node's own corner: the grip wins over the node it belongs to
        val at = Pt(start.right - 2.0, start.bottom - 2.0)
        val before = x.state
        assertTrue(c.press(at, 1.0, 48.0, finger = false, eraser = false, tool = Tool.PEN, connector = false, fingerDraws = false))
        c.move(Pt(at.x + 150.0, at.y + 120.0), 1.0)
        assertEquals("the preview is shown", start.w + 150.0, x.rect(r).w, 1e-6)
        assertEquals(start.h + 120.0, x.rect(r).h, 1e-6)
        assertEquals("only the grip shows while resizing", listOf(MapController.HandleKind.RESIZE), c.chrome(1.0, 48.0)!!.handles.map { it.kind })
        assertSame("nothing is committed yet", before, x.state)
        c.release(Pt(at.x + 150.0, at.y + 120.0))
        assertEquals(r, c.selected)
        assertTrue(g != grip().center)
        assertEquals(start.w + 150.0, x.rect(r).w, 1e-6)
        assertEquals(start.h + 120.0, x.rect(r).h, 1e-6)
        assertEquals(undo + 1, x.f.undoCount())
    }

    @Test fun aCancelledResizeLeavesTheNodeAsItWas() {
        val r = root()
        c.select(r)
        val start = x.rect(r)
        val g = grip().center
        val before = x.state
        c.press(g, 1.0, 48.0, finger = true, eraser = false, tool = Tool.PAN, connector = false, fingerDraws = false)
        c.move(Pt(g.x + 200.0, g.y + 200.0), 1.0)
        c.cancel()
        assertEquals(before, x.state)
        assertEquals(start, x.rect(r))
    }

    @Test fun aPenStrokeInANodeIsCutAtItsInkArea() {
        val r = root()
        assertTrue(c.inkCuts(x.center(r)).isEmpty())
        e.resize(r, com.xnotes.map.Size(300.0, 220.0))
        c.refresh()
        val box = x.rect(r)
        val cuts = c.inkCuts(Pt(box.centerX, box.bottom - 20.0))
        assertEquals(4, cuts.size)
        val area = x.f.metrics.frame(x.state.nodes.getValue(r), true, box.topLeft).ink!!
        assertTrue(com.xnotes.core.model.CutEdges.keeps(cuts, area.centerX, area.centerY))
        assertFalse(com.xnotes.core.model.CutEdges.keeps(cuts, area.right + 1.0, area.centerY))
        assertFalse(com.xnotes.core.model.CutEdges.keeps(cuts, area.centerX, area.top - 1.0))
        assertTrue(c.inkCuts(Pt(5000.0, 5000.0)).isEmpty())
    }

    @Test fun theChildHandleStartsASubtopicReadyToType() {
        val r = root()
        c.select(r)
        val handle = c.chrome(1.0, 48.0)!!.handles.single { it.kind == MapController.HandleKind.CHILD }
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

    @Test fun aNodeBeingTypedIntoHasNoOutlineOrHandles() {
        val r = root()
        c.select(r)
        assertNotNull(c.chrome(1.0, 48.0)!!.outline)
        c.editTitle(r)
        assertNull("the node's own box marks it while typing", c.chrome(1.0, 48.0))
        c.commitTitle()
        assertEquals(x.rect(r), c.chrome(1.0, 48.0)!!.outline)
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
