package com.xnotes.edit

import com.xnotes.core.history.Command
import com.xnotes.core.history.History
import com.xnotes.core.text.*

class FlowTextInput(val flow: TextFlow, private val history: History) {
    private val editor = FlowEditor(flow)
    var selection = FlowRange.caret(FlowPos.START)
        get() = FlowRange(clamp(field.start), clamp(field.end))
    var marked: FlowRange? = null
        private set
    var typingStyle: CharStyle? = null
    private var original: FlowRange? = null
    private var preview: Command? = null

    private fun clamp(pos: FlowPos) = if (flow.paragraphs.isEmpty()) FlowPos.START else editor.clamp(pos)
    fun utf16Length() = flow.globalOffset(flow.endPos())
    fun positionAt(offset: Int) = flow.posAtGlobal(offset)
    fun offsetOf(pos: FlowPos) = flow.globalOffset(clamp(pos))
    fun textIn(start: Int, end: Int): String {
        val from = positionAt(start)
        val to = positionAt(end)
        if (from >= to) return ""
        return buildString {
            for (index in from.para..to.para) {
                if (index > from.para) append('\n')
                val paragraph = flow.paragraphs[index].plainText()
                append(paragraph, if (index == from.para) from.offset else 0,
                    if (index == to.para) to.offset else paragraph.length)
            }
        }
    }

    fun insertText(text: String) {
        val range = original ?: selection
        preview?.undo()
        clearMarked()
        val (command, end) = editor.replaceRange(range, text, typingStyle)
        selection = FlowRange.caret(end)
        command?.let(history::push)
    }

    fun setMarkedText(text: String, selectedInMarked: IntRange) {
        val range = original ?: selection.also { original = it }
        preview?.undo()
        val start = offsetOf(range.normalized().start)
        val (command, end) = editor.replaceRange(range, text, typingStyle)
        preview = command
        marked = FlowRange(positionAt(start), end)
        selection = FlowRange(positionAt(start + selectedInMarked.first.coerceIn(0, text.length)),
            positionAt(start + selectedInMarked.last.coerceIn(0, text.length)))
    }

    fun unmarkText() {
        val range = marked ?: return
        val command = preview
        if (range.collapsed) {
            command?.undo()
            selection = checkNotNull(original)
        }
        clearMarked()
        if (!range.collapsed) command?.let(history::push)
    }

    private fun clearMarked() { marked = null; original = null; preview = null }

    fun <T> withCommittedText(read: () -> T): T {
        val pending = preview ?: return read()
        pending.undo()
        return try { read() } finally { pending.redo() }
    }

    fun deleteBackward() {
        val range = selection.normalized()
        if (marked != null) {
            val base = offsetOf(marked!!.start)
            val text = textIn(base, offsetOf(marked!!.end))
            val end = (offsetOf(range.end) - base).coerceIn(0, text.length)
            val erased = if (range.collapsed && end > 0) characterSpan(text, end - 1)
                else (offsetOf(range.start) - base).coerceIn(0, end) until end
            setMarkedText(text.removeRange(erased), erased.first..erased.first)
            return
        }
        if (range.collapsed) {
            val pos = range.start
            if (pos == FlowPos.START) return
            selection = if (pos.offset == 0) FlowRange(positionAt(offsetOf(pos) - 1), pos)
                else characterSpan(flow.paragraphs[pos.para].plainText(), pos.offset - 1).let {
                    FlowRange(FlowPos(pos.para, it.first), FlowPos(pos.para, it.last + 1))
                }
        }
        insertText("")
    }
}

internal expect fun characterSpan(text: String, index: Int): IntRange
