package com.xnotes.edit

import com.xnotes.core.history.History
import com.xnotes.core.text.*
import kotlin.test.*

class FlowCompositionSelectionTest {
    @Test fun deletingFromInsideAGlyphNeverLeavesAHalfSurrogateOrCombiningMark() {
        val flow = TextFlow().apply { paragraphs.add(Paragraph(mutableListOf(Run("a😀e\u0301")))) }
        val input = FlowTextInput(flow, History())
        input.selection = FlowRange.caret(FlowPos(0, 2)); input.deleteBackward()
        assertEquals("ae\u0301", flow.plainText())
        input.selection = FlowRange.caret(FlowPos(0, 2)); input.deleteBackward()
        assertEquals("a", flow.plainText())
    }

    @Test fun cancelledMarkedReplacementRestoresBothStyledParagraphsAndBackwardsSelection() {
        val bold = CharStyle.DEFAULT.copy(bold = true)
        val italic = CharStyle.DEFAULT.copy(italic = true)
        val first = Paragraph(mutableListOf(Run("abc", bold)), list = ListKind.BULLET)
        val second = Paragraph(mutableListOf(Run("def", italic)))
        val flow = TextFlow().apply { paragraphs.addAll(listOf(first, second)) }
        val history = History()
        val input = FlowTextInput(flow, history)
        val original = FlowRange(FlowPos(1, 2), FlowPos(0, 1))
        input.selection = original
        input.setMarkedText("bi", 2..2)
        assertEquals("abif", flow.plainText())
        assertFailsWith<IllegalStateException> {
            input.withCommittedText { assertEquals("abc\ndef", flow.plainText()); error("capture failed") }
        }
        assertEquals("abif", flow.plainText())
        input.setMarkedText("", 0..0); input.unmarkText()
        assertEquals("abc\ndef", flow.plainText()); assertEquals(original, input.selection)
        assertSame(first, flow.paragraphs[0]); assertSame(second, flow.paragraphs[1])
        assertEquals(bold, first.runs.single().style); assertEquals(italic, second.runs.single().style)
        assertEquals(ListKind.BULLET, first.list); assertFalse(history.canUndo)
    }
}
