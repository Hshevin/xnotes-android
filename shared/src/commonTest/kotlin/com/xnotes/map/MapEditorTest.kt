package com.xnotes.map

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.testkit.assertEquals
import com.xnotes.core.testkit.assertFalse
import com.xnotes.core.testkit.assertNotEquals
import com.xnotes.core.testkit.assertNotNull
import com.xnotes.core.testkit.assertNull
import com.xnotes.core.testkit.assertSame
import kotlin.test.assertFailsWith
import com.xnotes.core.testkit.assertTrue
import kotlin.test.Test

class MapEditorTest {

    private val f = EditorFixture()
    private val e get() = f.editor

    // ---- drafts ----

    @Test fun draftRootIsVisibleButNotInHistory() {
        val d = e.beginRoot(Pt(10.0, 20.0))
        assertSame(d, e.draft)
        assertTrue(d.node in f.state.nodes)
        assertEquals(listOf(d.node), f.state.roots)
        assertNotNull(e.layout().rectOf(d.node))
        assertFalse(f.history.canUndo)
    }

    @Test fun committingARootMakesOneUndoStep() {
        e.beginRoot(Pt(10.0, 20.0), layer = "L1")
        val r = e.commitDraft("  Topic  ")!!
        val n = f.state.nodes.getValue(r)
        assertEquals("Topic", n.title)
        assertEquals(NodeStyle.ROOT, n.style)
        assertEquals(Pt(10.0, 20.0), f.state.rootOrigin[r])
        assertEquals(LayoutKind.BALANCED, f.state.layout[r])
        assertEquals("L1", f.state.layers[r])
        assertNull(e.draft)
        assertValid(f.state)
        f.history.undo()
        assertEquals(MapState.EMPTY, f.state)
        f.history.redo()
        assertEquals("Topic", f.state.nodes.getValue(r).title)
        assertEquals(1, f.undoCount())
    }

    @Test fun rootOnDefaultLayerHasNoLayerEntry() {
        val r = f.root()
        assertFalse(r in f.state.layers)
    }

    @Test fun blankCommitCancels() {
        val r = f.root()
        val before = f.state
        e.beginChild(r)
        assertNull(e.commitDraft("   "))
        assertEquals(before, f.state)
        assertNull(e.draft)
        assertEquals(1, f.undoCount())
    }

    @Test fun keepIfBlankCommitsAnEmptyTitle() {
        val r = f.root()
        e.beginChild(r)
        val c = e.commitDraft("", keepIfBlank = true)!!
        assertEquals("", f.state.nodes.getValue(c).title)
        assertEquals(2, f.undoCount())
    }

    @Test fun cancelRestoresWithoutHistory() {
        val r = f.root()
        val before = f.state
        e.beginChild(r)
        e.cancelDraft()
        assertEquals(before, f.state)
        assertEquals(1, f.undoCount())
    }

    @Test fun otherEditsAreRefusedWhileADraftIsOpen() {
        val r = f.root()
        e.beginChild(r)
        assertFailsWith<IllegalStateException> { e.setTitle(r, "x") }
        assertFailsWith<IllegalStateException> { e.beginRoot(Pt(0.0, 0.0)) }
        assertFailsWith<IllegalStateException> { e.delete(listOf(r)) }
    }

    @Test fun commitWithoutDraftThrows() {
        assertFailsWith<IllegalStateException> { e.commitDraft("x") }
    }

    @Test fun childIsAppendedLastWithTopicStyle() {
        val r = f.root()
        val a = f.child(r, "a")
        val b = f.child(r, "b")
        assertEquals(listOf(a, b), f.state.childrenOf(r))
        assertEquals(NodeStyle.TOPIC, f.state.nodes.getValue(b).style)
        assertEquals(r, f.state.parent[b])
        assertValid(f.state)
    }

    @Test fun siblingGoesRightAfterItsNode() {
        val r = f.root()
        val a = f.child(r, "a")
        val c = f.child(r, "c")
        val b = f.sibling(a, "b")
        assertEquals(listOf(a, b, c), f.state.childrenOf(r))
    }

    @Test fun rootsHaveNoSiblings() {
        val r = f.root()
        assertNull(e.beginSibling(r))
        assertNull(e.draft)
    }

    @Test fun draftingUnderACollapsedParentExpandsIt() {
        val r = f.root()
        f.child(r, "a")
        assertTrue(f.model.setCollapsed(r, true))
        e.beginChild(r)
        assertFalse(r in f.model.collapsed)
    }

    @Test fun unknownParentGivesNoDraft() {
        assertNull(e.beginChild(id("ghost")))
    }

    // ---- content ----

    @Test fun unchangedTitleRecordsNothing() {
        val r = f.root("same")
        assertFalse(e.setTitle(r, "same"))
        assertFalse(e.setTitle(r, " same "))
        assertTrue(e.setTitle(r, "other"))
        assertEquals(2, f.undoCount())
    }

    @Test fun restylingSeveralNodesIsOneStep() {
        val r = f.root()
        val a = f.child(r, "a")
        val red = Rgba(255, 0, 0)
        assertTrue(e.updateStyle(listOf(r, a)) { it.copy(border = red) })
        assertEquals(red, f.state.nodes.getValue(r).style.border)
        assertEquals(red, f.state.nodes.getValue(a).style.border)
        assertEquals(3, f.undoCount())
        assertFalse(e.updateStyle(listOf(r, a)) { it.copy(border = red) })
    }

    // ---- ink ----

    /** Root "root" at the origin: title box (16, 10, 40, 24), so a new ink area starts at (16, 42). */
    private fun rootForInk() = f.root("root")

    @Test fun firstStrokeOpensAnInkAreaBelowTheTitle() {
        val r = rootForInk()
        val s = pen(30.0 to 60.0, 80.0 to 90.0)
        assertTrue(e.addInk(r, s))
        val ink = f.state.nodes.getValue(r).ink
        val stored = ink.strokes.single()
        assertFalse("the input stroke is copied", s === stored)
        assertEquals(listOf(30.0, 80.0), s.samples.map { it.x })
        assertEquals(listOf(14.0, 64.0), stored.samples.map { it.x })
        assertEquals(listOf(18.0, 48.0), stored.samples.map { it.y })
        assertEquals(s.samples.map { it.pressure }, stored.samples.map { it.pressure })
        assertEquals(s.samples.map { it.t }, stored.samples.map { it.t })
        assertEquals(MapEditor.INK_MIN_W, ink.width, 1e-9)
        assertEquals(MapEditor.INK_MIN_H, ink.height, 1e-9)
        val frame = f.metrics.frame(f.state.nodes.getValue(r), true, Pt(0.0, 0.0))
        assertRect(com.xnotes.core.geometry.Rect(16.0, 42.0, 160.0, 80.0), frame.ink)
        assertEquals(2, f.undoCount())
    }

    @Test fun firstStrokeSizesTheAreaToFitWithAMargin() {
        val r = rootForInk()
        e.addInk(r, pen(30.0 to 60.0, 400.0 to 300.0))
        val ink = f.state.nodes.getValue(r).ink
        val stored = ink.strokes.single()
        val b = stored.bounds()
        assertEquals(b.right + MapEditor.INK_MARGIN, ink.width, 1e-9)
        assertEquals(b.bottom + MapEditor.INK_MARGIN, ink.height, 1e-9)
        assertEquals("the first stroke shows whole", 2, stored.samples.size)
        assertEquals(stored.samples.size, pen(30.0 to 60.0, 400.0 to 300.0).samples.size)
    }

    @Test fun firstStrokeLeftOfOrAboveTheAreaShiftsIntoIt() {
        val r = rootForInk()
        e.addInk(r, pen(0.0 to 30.0, 20.0 to 70.0))
        val stored = f.state.nodes.getValue(r).ink.strokes.single()
        assertEquals(0.0, stored.bounds().left, 1e-6)
        assertEquals(0.0, stored.bounds().top, 1e-6)
    }

    @Test fun laterStrokesAreCutAtTheAreaAndNeverGrowOrShiftIt() {
        val r = rootForInk()
        e.addInk(r, pen(30.0 to 60.0, 80.0 to 90.0))
        val before = f.state.nodes.getValue(r).ink
        val first = before.strokes.single()
        val input = pen(0.0 to 60.0, 60.0 to 80.0, 400.0 to 300.0)
        assertTrue(e.addInk(r, input))
        val ink = f.state.nodes.getValue(r).ink
        assertEquals(before.width, ink.width, 1e-9)
        assertEquals(before.height, ink.height, 1e-9)
        assertSame("earlier ink is untouched", first, ink.strokes[0])
        val cut = ink.strokes[1]
        assertEquals("no sample is dropped", listOf(-16.0, 44.0, 384.0), cut.samples.map { it.x })
        assertEquals(listOf(18.0, 38.0, 258.0), cut.samples.map { it.y })
        assertEquals(input.samples.map { it.pressure }, cut.samples.map { it.pressure })
        assertTrue(cut.cuts.isNotEmpty())
        val b = cut.bounds()
        val eps = 1e-6
        assertTrue("cut at the left edge: $b", b.left >= -eps)
        assertTrue("cut at the top edge: $b", b.top >= -eps)
        assertTrue("cut at the right edge: $b", b.right <= ink.width + eps)
        assertTrue("cut at the bottom edge: $b", b.bottom <= ink.height + eps)
        assertTrue("the input stroke is not cut", input.cuts.isEmpty())
    }

    @Test fun aStrokeThatShowsNothingInsideTheAreaIsNotWritten() {
        val r = rootForInk()
        e.addInk(r, pen(30.0 to 60.0, 80.0 to 90.0))
        val before = f.state
        val undo = f.undoCount()
        // on the title line, above the area's top edge at y = 42
        assertFalse(e.addInk(r, pen(20.0 to 14.0, 40.0 to 20.0)))
        assertSame(before, f.state)
        assertEquals(undo, f.undoCount())
    }

    @Test fun anAreaWithNoStrokesLeftStillCutsInsteadOfGrowing() {
        val r = rootForInk()
        e.addInk(r, pen(30.0 to 60.0, 80.0 to 90.0))
        val area = f.state.nodes.getValue(r).ink
        e.replaceInk(r, area.strokes, emptyList())
        assertTrue(e.addInk(r, pen(30.0 to 60.0, 500.0 to 500.0)))
        val ink = f.state.nodes.getValue(r).ink
        assertEquals(area.width, ink.width, 1e-9)
        assertEquals(area.height, ink.height, 1e-9)
        assertTrue(ink.strokes.single().bounds().right <= ink.width + 1e-6)
    }

    @Test fun shorteningATitleNeverNarrowsTheAreaUnderInkWrittenBesideIt() {
        val r = f.root("x".repeat(30))
        e.addInk(r, pen(30.0 to 60.0, 50.0 to 70.0))
        // written out where only the long title made room
        assertTrue(e.addInk(r, pen(250.0 to 60.0, 300.0 to 70.0)))
        e.setTitle(r, "Hi")
        val node = f.state.nodes.getValue(r)
        val area = f.metrics.frame(node, true, Pt(0.0, 0.0)).ink!!
        for (s in node.ink.strokes) assertTrue("${s.bounds()} inside ${area.w}", s.bounds().right <= area.w + 1e-6)
        assertEquals(300.0, area.w, 1e-9)
    }

    @Test fun firstStrokeInAResizedNodeKeepsItsWidthAndIsCutSideways() {
        val r = rootForInk()
        assertTrue(e.resize(r, Size(200.0, 10.0)))
        assertEquals(NodeInk.NONE, f.state.nodes.getValue(r).ink)
        e.addInk(r, pen(0.0 to 60.0, 400.0 to 140.0))
        val node = f.state.nodes.getValue(r)
        val frame = f.metrics.frame(node, true, Pt(0.0, 0.0))
        assertEquals(200.0, frame.rect.w, 1e-9)
        assertEquals(168.0, frame.ink!!.w, 1e-9)
        val stroke = node.ink.strokes.single()
        assertEquals("nothing shifts sideways", -16.0, stroke.samples.first().x, 1e-9)
        val b = stroke.bounds()
        assertTrue(b.left >= -1e-6 && b.right <= 168.0 + 1e-6)
        assertEquals("the height still fits the stroke", b.bottom + MapEditor.INK_MARGIN, node.ink.height, 1e-6)
    }

    @Test fun inkWritesUndoAsOneStepEach() {
        val r = rootForInk()
        e.addInk(r, pen(30.0 to 60.0, 80.0 to 90.0))
        e.addInk(r, pen(30.0 to 70.0, 80.0 to 95.0))
        f.history.undo()
        assertEquals(1, f.state.nodes.getValue(r).ink.strokes.size)
        f.history.undo()
        assertEquals(NodeInk.NONE, f.state.nodes.getValue(r).ink)
    }

    @Test fun replaceInkSwapsStrokesAndKeepsTheAreaSize() {
        val r = rootForInk()
        e.addInk(r, pen(30.0 to 60.0, 80.0 to 90.0))
        val before = f.state.nodes.getValue(r).ink
        val frag = pen(1.0 to 1.0, 2.0 to 2.0)
        assertTrue(e.replaceInk(r, before.strokes, listOf(frag)))
        val after = f.state.nodes.getValue(r).ink
        assertSame(frag, after.strokes.single())
        assertEquals(before.width, after.width, 1e-9)
        assertFalse(e.replaceInk(r, listOf(pen(0.0 to 0.0, 1.0 to 1.0)), emptyList()))
    }

    @Test fun removingTheInkAreaDropsEverything() {
        val r = rootForInk()
        e.addInk(r, pen(30.0 to 60.0, 80.0 to 90.0))
        assertTrue(e.removeInkArea(r))
        assertEquals(NodeInk.NONE, f.state.nodes.getValue(r).ink)
        assertFalse(e.removeInkArea(r))
    }

    @Test fun inkIntoUnknownNodeIsRefused() {
        assertFalse(e.addInk(id("ghost"), pen(0.0 to 0.0, 1.0 to 1.0)))
    }

    // ---- resizing ----

    @Test fun resizingIsOneUndoStep() {
        val r = f.root("root")
        val before = f.state
        assertTrue(e.resize(r, Size(300.0, 200.0)))
        val rect = e.layout().rectOf(r)!!
        assertEquals(300.0, rect.w, 1e-9)
        assertEquals(200.0, rect.h, 1e-9)
        assertEquals(f.metrics.resized(before.nodes.getValue(r), true, Size(300.0, 200.0)), f.state.nodes.getValue(r))
        assertValid(f.state)
        f.history.undo()
        assertEquals(before, f.state)
        f.history.redo()
        assertEquals(300.0, f.state.nodes.getValue(r).width!!, 1e-9)
    }

    @Test fun resizingToTheSameSizeChangesNothing() {
        val r = f.root("root")
        e.resize(r, Size(300.0, 200.0))
        val n = f.undoCount()
        assertFalse(e.resize(r, Size(300.0, 200.0)))
        assertEquals(n, f.undoCount())
    }

    @Test fun resizingAnUnknownNodeIsRefused() {
        assertFalse(e.resize(id("ghost"), Size(300.0, 200.0)))
    }

    @Test fun resizingAChildReflowsItsTree() {
        val r = f.root("root")
        e.setLayout(r, LayoutKind.RIGHT)
        val a = f.child(r, "a")
        val b = f.child(r, "b")
        val bTop = e.layout().rectOf(b)!!.top
        e.resize(a, Size(200.0, 300.0))
        assertTrue(e.layout().rectOf(b)!!.top > bTop)
    }

    @Test fun aResizedNodeKeepsItsSizeThroughCopyAndPaste() {
        val r = f.root("root")
        e.resize(r, Size(300.0, 200.0))
        val frag = MapClipboard.copy(f.state, e.layout(), listOf(r))
        val pasted = e.paste(frag, Pt(1000.0, 0.0), null).single()
        assertEquals(Size(300.0, 200.0), e.layout().rectOf(pasted)!!.let { Size(it.w, it.h) })
    }

    // ---- sources ----

    private fun src(id: String, page: String?, kind: SourceKind = SourceKind.PDF_PAGE) =
        SourceRef(id, kind, "doc-1", page, "MiTA · $page")

    @Test fun sourcesAddSkippingDuplicatesRemoveAndReorder() {
        val r = f.root()
        assertTrue(e.addSources(r, listOf(src("a", "p1"), src("b", "p2"))))
        assertFalse(e.addSources(r, listOf(src("c", "p1"))))
        assertTrue(e.addSources(r, listOf(src("d", "p1", SourceKind.NOTE_PAGE))))
        assertEquals(listOf("a", "b", "d"), f.state.nodes.getValue(r).sources.map { it.id })
        assertTrue(e.moveSource(r, "d", 0))
        assertEquals(listOf("d", "a", "b"), f.state.nodes.getValue(r).sources.map { it.id })
        assertFalse(e.moveSource(r, "d", -5))
        assertTrue(e.removeSource(r, "a"))
        assertFalse(e.removeSource(r, "a"))
        assertEquals(listOf("d", "b"), f.state.nodes.getValue(r).sources.map { it.id })
    }

    // ---- structure ----

    @Test fun deleteTakesSubtreesAndTheirRelations() {
        val r = f.root()
        val a = f.child(r, "a")
        val a1 = f.child(a, "a1")
        val b = f.child(r, "b")
        val free = f.root("free", Pt(500.0, 0.0))
        val rel1 = e.addRelation(a1, free)!!
        val rel2 = e.addRelation(b, free)!!
        val before = f.state
        assertTrue(e.delete(listOf(a, a1)))
        assertFalse(a in f.state.nodes)
        assertFalse(a1 in f.state.nodes)
        assertEquals(listOf(b), f.state.childrenOf(r))
        assertFalse(rel1 in f.state.relations)
        assertTrue(rel2 in f.state.relations)
        assertValid(f.state)
        f.history.undo()
        assertEquals(before, f.state)
    }

    @Test fun deletingARootRemovesItsWholeTreeAndEntries() {
        val r = f.root()
        f.child(r, "a")
        assertTrue(e.delete(listOf(r)))
        assertEquals(MapState.EMPTY, f.state)
        assertFalse(e.delete(listOf(id("ghost"))))
    }

    @Test fun reparentRefusesCycles() {
        val r = f.root()
        val a = f.child(r, "a")
        val a1 = f.child(a, "a1")
        assertFalse(e.reparent(a, a1, 0))
        assertFalse(e.reparent(a, a, 0))
        assertFalse(e.reparent(r, a1, 0))
        assertEquals(3, f.undoCount())
    }

    @Test fun reparentClearsOnlyTheMovedNodesOffset() {
        val r = f.root()
        val a = f.child(r, "a")
        val a1 = f.child(a, "a1")
        val b = f.child(r, "b")
        e.nudge(a, Vec(5.0, 5.0))
        e.nudge(a1, Vec(3.0, 0.0))
        assertTrue(e.reparent(a, b, 0))
        assertEquals(b, f.state.parent[a])
        assertFalse(a in f.state.offsets)
        assertEquals(Vec(3.0, 0.0), f.state.offsets[a1])
        assertValid(f.state)
    }

    @Test fun reparentIndexCountsAfterRemoval() {
        val r = f.root()
        val a = f.child(r, "a")
        val b = f.child(r, "b")
        val c = f.child(r, "c")
        assertTrue(e.reparent(a, r, 2))
        assertEquals(listOf(b, c, a), f.state.childrenOf(r))
        assertFalse(e.reparent(a, r, 2))
        assertFalse("clamped to the end, where it already is", e.reparent(a, r, 99))
        assertTrue(e.reparent(a, r, -3))
        assertEquals(listOf(a, b, c), f.state.childrenOf(r))
    }

    @Test fun reparentingARootMergesItsTree() {
        val r = f.root()
        e.beginRoot(Pt(500.0, 500.0), layer = "L2")
        val other = e.commitDraft("other")!!
        val o1 = f.child(other, "o1")
        assertTrue(e.reparent(other, r, 0))
        assertEquals(listOf(r), f.state.roots)
        assertEquals(r, f.state.parent[other])
        assertEquals(other, f.state.parent[o1])
        assertFalse(other in f.state.rootOrigin)
        assertFalse(other in f.state.layout)
        assertFalse(other in f.state.layers)
        assertValid(f.state)
    }

    @Test fun nudgeAccumulatesOnChildrenAndMovesRoots() {
        val r = f.root()
        val a = f.child(r, "a")
        assertTrue(e.nudge(a, Vec(10.0, 0.0)))
        assertTrue(e.nudge(a, Vec(0.0, 4.0)))
        assertEquals(Vec(10.0, 4.0), f.state.offsets[a])
        assertTrue(e.nudge(a, Vec(-10.0, -4.0)))
        assertFalse("a zero offset is removed", a in f.state.offsets)
        assertFalse(e.nudge(a, Vec.ZERO))
        assertTrue(e.nudge(r, Vec(7.0, 8.0)))
        assertEquals(Pt(7.0, 8.0), f.state.rootOrigin[r])
        assertFalse(r in f.state.offsets)
        assertValid(f.state)
    }

    @Test fun detachMakesAFreeTreeLikeItsOldOne() {
        e.beginRoot(Pt(0.0, 0.0), layer = "L1")
        val r = e.commitDraft("r")!!
        e.setLayout(r, LayoutKind.ORG)
        val a = f.child(r, "a")
        val a1 = f.child(a, "a1")
        e.nudge(a, Vec(1.0, 1.0))
        assertTrue(e.detach(a, Pt(300.0, 300.0)))
        assertEquals(listOf(r, a), f.state.roots)
        assertNull(f.state.parent[a])
        assertEquals(a, f.state.parent[a1])
        assertEquals(Pt(300.0, 300.0), f.state.rootOrigin[a])
        assertEquals(LayoutKind.ORG, f.state.layout[a])
        assertEquals("L1", f.state.layers[a])
        assertFalse(a in f.state.offsets)
        assertFalse(e.detach(a, Pt(0.0, 0.0)))
        assertValid(f.state)
    }

    @Test fun changingLayoutClearsTheTreesOffsetsInOneStep() {
        val r = f.root()
        val a = f.child(r, "a")
        val a1 = f.child(a, "a1")
        val other = f.root("other", Pt(900.0, 0.0))
        val o1 = f.child(other, "o1")
        e.nudge(a, Vec(5.0, 0.0))
        e.nudge(a1, Vec(0.0, 5.0))
        e.nudge(o1, Vec(2.0, 2.0))
        val steps = f.undoCount()
        assertTrue(e.setLayout(r, LayoutKind.INDENT))
        assertEquals(LayoutKind.INDENT, f.state.layout[r])
        assertFalse(a in f.state.offsets)
        assertFalse(a1 in f.state.offsets)
        assertEquals(Vec(2.0, 2.0), f.state.offsets[o1])
        assertEquals(steps + 1, f.undoCount())
        f.history.undo()
        assertEquals(Vec(5.0, 0.0), f.state.offsets[a])
        assertFalse(e.setLayout(r, LayoutKind.BALANCED))
        assertFalse(e.setLayout(a, LayoutKind.RIGHT))
    }

    @Test fun resettingOffsets() {
        val r = f.root()
        val a = f.child(r, "a")
        val a1 = f.child(a, "a1")
        e.nudge(a, Vec(5.0, 0.0))
        e.nudge(a1, Vec(0.0, 5.0))
        assertTrue(e.resetOffsets(listOf(a)))
        assertFalse(a in f.state.offsets)
        assertTrue(a1 in f.state.offsets)
        assertFalse(e.resetOffsets(listOf(a)))
        assertTrue(e.resetTree(r))
        assertTrue(f.state.offsets.isEmpty())
        assertFalse(e.resetTree(r))
    }

    @Test fun applyDropDelegates() {
        val r = f.root()
        val a = f.child(r, "a")
        val b = f.child(r, "b")
        assertTrue(e.applyDrop(a, DropAction.Reparent(b, 0)))
        assertEquals(b, f.state.parent[a])
        assertTrue(e.applyDrop(a, DropAction.Nudge(Vec(1.0, 2.0))))
        assertEquals(Vec(1.0, 2.0), f.state.offsets[a])
        assertFalse(e.applyDrop(a, DropAction.None))
    }

    // ---- relations ----

    @Test fun relationsAreUniquePerPairInEitherDirection() {
        val r = f.root()
        val a = f.child(r, "a")
        val rel = e.addRelation(r, a)!!
        val steps = f.undoCount()
        assertEquals(rel, e.addRelation(a, r))
        assertEquals(rel, e.addRelation(r, a))
        assertEquals(steps, f.undoCount())
        assertNull(e.addRelation(a, a))
        assertNull(e.addRelation(a, id("ghost")))
        val x = f.state.relations.getValue(rel)
        assertEquals(RelationArrow.NONE, x.arrow)
        assertEquals("", x.label)
    }

    @Test fun relationEditsReverseAndDelete() {
        val r = f.root()
        val a = f.child(r, "a")
        val rel = e.addRelation(r, a)!!
        assertTrue(e.updateRelation(rel, label = "依赖于", arrow = RelationArrow.FORWARD))
        assertFalse(e.updateRelation(rel, label = "依赖于"))
        assertTrue(e.reverseRelation(rel))
        val x = f.state.relations.getValue(rel)
        assertEquals(a, x.from)
        assertEquals(r, x.to)
        assertEquals(RelationArrow.BACKWARD, x.arrow)
        assertTrue(e.deleteRelations(listOf(rel)))
        assertTrue(f.state.relations.isEmpty())
        assertFalse(e.deleteRelations(listOf(rel)))
    }

    // ---- paste ----

    @Test fun pasteRenewsEveryIdAndKeepsTheArrangement() {
        val r = f.root()
        val a = f.child(r, "a")
        val rel = e.addRelation(r, a)!!
        e.addSources(a, listOf(src("s1", "p1")))
        val frag = MapClipboard.copy(f.state, e.layout(), listOf(r))
        val roots = e.paste(frag, Pt(1000.0, 1000.0))
        val nr = roots.single()
        assertNotEquals(r, nr)
        val na = f.state.childrenOf(nr).single()
        assertNotEquals(a, na)
        assertEquals("a", f.state.nodes.getValue(na).title)
        assertNotEquals("s1", f.state.nodes.getValue(na).sources.single().id)
        assertEquals("p1", f.state.nodes.getValue(na).sources.single().pageId)
        assertEquals(2, f.state.relations.size)
        assertTrue(f.state.relations.values.any { it.from == nr && it.to == na && it.id != rel })
        val shift = Pt(1000.0, 1000.0) - frag.bounds!!.topLeft
        assertEquals(e.layout().rectOf(r)!!.topLeft + shift, f.state.rootOrigin[nr])
        assertValid(f.state)
        val steps = f.undoCount()
        f.history.undo()
        assertEquals(listOf(r), f.state.roots)
        f.history.redo()
        assertTrue(e.paste(MapFragment(MapState.EMPTY, null), Pt(0.0, 0.0)).isEmpty())
        assertEquals(steps, f.undoCount())
    }

    // ---- history as a whole ----

    @Test fun undoingEverythingReturnsToEmptyAndRedoRebuildsTheSameState() {
        val r = f.root()
        val a = f.child(r, "a")
        val b = f.sibling(a, "b")
        e.addInk(b, pen(200.0 to 100.0, 260.0 to 140.0))
        e.nudge(b, Vec(3.0, 3.0))
        e.addRelation(a, b)
        e.setLayout(r, LayoutKind.ORG)
        e.detach(b, Pt(-400.0, 0.0))
        e.delete(listOf(a))
        val end = f.state
        var steps = 0
        while (f.history.canUndo) { f.history.undo(); assertValid(f.state); steps++ }
        assertEquals(MapState.EMPTY, f.state)
        repeat(steps) { f.history.redo(); assertValid(f.state) }
        assertEquals(end, f.state)
    }

    @Test fun collapseIsOutsideHistory() {
        val r = f.root()
        val a = f.child(r, "a")
        f.child(a, "a1")
        val steps = f.undoCount()
        assertTrue(f.model.setCollapsed(a, true))
        assertEquals(steps, f.undoCount())
        e.setTitle(r, "renamed")
        f.history.undo()
        assertTrue(a in f.model.collapsed)
        assertTrue(a in e.layout().hiddenCount)
    }

    @Test fun layoutFollowsEveryChange() {
        val r = f.root()
        val a = f.child(r, "a")
        val x0 = e.layout().rectOf(a)!!.x
        e.setTitle(r, "a much longer root title")
        assertTrue(e.layout().rectOf(a)!!.x > x0)
        f.model.setCollapsed(r, true)
        assertNull(e.layout().rectOf(a))
    }

    @Test fun listenersHearEveryChange() {
        var heard = 0
        f.model.addListener { heard++ }
        val r = f.root()
        val afterRoot = heard
        assertTrue(afterRoot >= 1)
        e.setTitle(r, "x")
        assertTrue(heard > afterRoot)
        val n = heard
        f.history.undo()
        assertTrue(heard > n)
    }
}
