package com.xnotes.edit

import com.xnotes.core.history.History
import com.xnotes.core.text.FlowPos
import com.xnotes.core.text.FlowRange
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.text.TextFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W4-B contract (docs/movinkpad/process/ipados-wave4-contracts-2026-11-24.md, "W4-B"): the bridge between the
 * iPad keyboard (UITextInput, including Chinese marked text) and a TextFlow. Offsets are UTF-16 code units, as
 * UIKit counts them. Composition never enters the undo history; what it settles into is one step.
 */
class FlowTextInputTest {

    private fun flow(vararg lines: String) = TextFlow().apply {
        lines.forEach { paragraphs.add(Paragraph(if (it.isEmpty()) mutableListOf() else mutableListOf(Run(it)))) }
    }

    private fun at(para: Int, offset: Int) = FlowPos(para, offset)

    @Test
    fun typingInsertsAtTheCaretAndEachInsertIsOneStep() {
        val f = flow("ac")
        val h = History()
        val input = FlowTextInput(f, h)
        input.selection = FlowRange(at(0, 1), at(0, 1))
        input.insertText("b")
        assertEquals("abc", f.plainText())
        assertEquals(FlowRange(at(0, 2), at(0, 2)), input.selection)
        h.undo()
        assertEquals("ac", f.plainText())
    }

    @Test
    fun typingReplacesASelection() {
        val f = flow("hello world")
        val input = FlowTextInput(f, History())
        input.selection = FlowRange(at(0, 6), at(0, 11))
        input.insertText("iPad")
        assertEquals("hello iPad", f.plainText())
    }

    @Test
    fun aNewlineSplitsTheParagraph() {
        val f = flow("ab")
        val input = FlowTextInput(f, History())
        input.selection = FlowRange(at(0, 1), at(0, 1))
        input.insertText("\n")
        assertEquals("a\nb", f.plainText())
        assertEquals(FlowRange(at(1, 0), at(1, 0)), input.selection)
    }

    @Test
    fun deleteBackwardJoinsParagraphsAtTheirStart() {
        val f = flow("ab", "cd")
        val input = FlowTextInput(f, History())
        input.selection = FlowRange(at(1, 0), at(1, 0))
        input.deleteBackward()
        assertEquals("abcd", f.plainText())
        assertEquals(FlowRange(at(0, 2), at(0, 2)), input.selection)
        input.selection = FlowRange(at(0, 0), at(0, 0))
        input.deleteBackward() // nothing before the first character
        assertEquals("abcd", f.plainText())
    }

    @Test
    fun markedTextShowsInTheFlowButStaysOutOfHistory() {
        val f = flow("记")
        val h = History()
        val input = FlowTextInput(f, h)
        input.selection = FlowRange(at(0, 0), at(0, 0))
        input.setMarkedText("bi", selectedInMarked = 2..2)
        assertEquals("bi记", f.plainText())
        assertEquals(FlowRange(at(0, 0), at(0, 2)), input.marked)
        input.setMarkedText("笔", selectedInMarked = 1..1)
        assertEquals("笔记", f.plainText())
        assertFalse(h.canUndo, "composition is not an edit yet")

        input.unmarkText()
        assertNull(input.marked)
        assertEquals("笔记", f.plainText())
        assertEquals(FlowRange(at(0, 1), at(0, 1)), input.selection)
        assertTrue(h.canUndo)
        h.undo()
        assertEquals("记", f.plainText(), "the settled composition undoes in one step")
        assertFalse(h.canUndo)
    }

    @Test
    fun insertingWhileComposingReplacesTheMarkedText() {
        val f = flow("")
        val h = History()
        val input = FlowTextInput(f, h)
        input.selection = FlowRange(at(0, 0), at(0, 0))
        input.setMarkedText("zhong", selectedInMarked = 5..5)
        input.insertText("中")
        assertNull(input.marked)
        assertEquals("中", f.plainText())
        h.undo()
        assertEquals("", f.plainText())
    }

    @Test
    fun anAbandonedCompositionLeavesNoTrace() {
        val f = flow("ab")
        val h = History()
        val input = FlowTextInput(f, h)
        input.selection = FlowRange(at(0, 1), at(0, 1))
        input.setMarkedText("x", selectedInMarked = 1..1)
        input.setMarkedText("", selectedInMarked = 0..0)
        input.unmarkText()
        assertEquals("ab", f.plainText())
        assertFalse(h.canUndo)
        assertEquals(FlowRange(at(0, 1), at(0, 1)), input.selection)
    }

    @Test
    fun offsetsAreUtf16CodeUnits() {
        val f = flow("a😀b") // the emoji is two UTF-16 units
        val input = FlowTextInput(f, History())
        assertEquals(4, input.utf16Length())
        assertEquals(at(0, 3), input.positionAt(3))
        assertEquals(3, input.offsetOf(at(0, 3)))
        input.selection = FlowRange(at(0, 3), at(0, 3))
        input.deleteBackward()
        assertEquals("ab", f.plainText(), "deleting backwards removes the whole surrogate pair")
    }

    @Test
    fun paragraphBreaksCountAsOneUnitInDocumentOffsets() {
        val f = flow("ab", "cd")
        val input = FlowTextInput(f, History())
        assertEquals(5, input.utf16Length())
        assertEquals(at(1, 0), input.positionAt(3))
        assertEquals(3, input.offsetOf(at(1, 0)))
        assertEquals("b\nc", input.textIn(1, 4))
    }
}
