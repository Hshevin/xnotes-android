package com.xnotes.edit

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.History
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W4-A contract (docs/movinkpad/process/ipados-wave4-contracts-2026-11-24.md, "W4-A"): a live move / scale / rotate
 * of selected items. Previews never touch history; cancelling puts every item back exactly; a commit is one
 * undo step (AGENTS.md: 选择移动／缩放／旋转等未确认预览在取消…前恢复原几何…不产生内容历史).
 */
class SelectionEditTest {

    private fun stroke(x: Double, y: Double) =
        Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(x, y, 0.5), Sample(x + 10.0, y + 10.0, 0.5, 8.0)))

    private fun xs(s: Stroke) = s.samples.map { it.x }

    @Test
    fun aPreviewMovesItemsFromWhereTheyStartedNotCumulatively() {
        val a = stroke(0.0, 0.0)
        val edit = SelectionEdit(listOf(a))
        edit.preview(Affine.translateBy(5.0, 0.0))
        edit.preview(Affine.translateBy(7.0, 0.0))
        assertEquals(listOf(7.0, 17.0), xs(a))
    }

    @Test
    fun cancelRestoresEveryItemExactly() {
        val a = stroke(0.0, 0.0)
        val b = stroke(30.0, 30.0)
        val before = listOf(a.samples.toList(), b.samples.toList())
        val edit = SelectionEdit(listOf(a, b))
        edit.preview(Affine.rotateAbout(Pt(15.0, 15.0), 0.7))
        edit.cancel()
        assertEquals(before, listOf(a.samples.toList(), b.samples.toList()))
        assertFalse(edit.active)
    }

    @Test
    fun aCommitIsOneUndoStep() {
        val a = stroke(0.0, 0.0)
        val b = stroke(30.0, 30.0)
        val history = History()
        val edit = SelectionEdit(listOf(a, b))
        edit.preview(Affine.translateBy(0.0, 3.0))
        edit.preview(Affine.scaleAbout(Pt.ZERO, 2.0, 2.0))
        val command = assertNotNull(edit.commit())
        history.push(command)
        assertEquals(listOf(0.0, 20.0), xs(a))
        assertEquals(listOf(60.0, 80.0), xs(b))

        history.undo()
        assertEquals(listOf(0.0, 10.0), xs(a))
        assertEquals(listOf(30.0, 40.0), xs(b))
        assertFalse(history.canUndo)
        history.redo()
        assertEquals(listOf(0.0, 20.0), xs(a))
    }

    @Test
    fun anUnchangedCommitLeavesNoHistory() {
        val a = stroke(0.0, 0.0)
        val edit = SelectionEdit(listOf(a))
        edit.preview(Affine.translateBy(0.0, 0.0))
        assertNull(edit.commit())
        val untouched = SelectionEdit(listOf(a))
        assertNull(untouched.commit())
    }

    @Test
    fun aClosedEditIgnoresLatePreviews() {
        val a = stroke(0.0, 0.0)
        val edit = SelectionEdit(listOf(a))
        edit.preview(Affine.translateBy(4.0, 0.0))
        assertNotNull(edit.commit())
        edit.preview(Affine.translateBy(100.0, 0.0))
        edit.cancel()
        assertEquals(listOf(4.0, 14.0), xs(a), "a late callback must not move or restore committed items")
    }

    @Test
    fun lockedItemsAreLeftOut() {
        val free = stroke(0.0, 0.0)
        val locked = stroke(50.0, 0.0).also { it.locked = true }
        val edit = SelectionEdit(listOf(free, locked))
        assertEquals(listOf(free), edit.items)
        edit.preview(Affine.translateBy(1.0, 0.0))
        assertEquals(listOf(50.0, 60.0), xs(locked))
        assertTrue(edit.active)
    }
}
