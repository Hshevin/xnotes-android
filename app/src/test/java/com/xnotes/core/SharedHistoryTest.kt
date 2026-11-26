package com.xnotes.core

import com.xnotes.core.history.Command
import com.xnotes.core.history.History
import org.junit.Assert.*
import org.junit.Test

class SharedHistoryTest {
    private fun append(items: MutableList<String>, value: String) = object : Command {
        override fun redo() { items.add(value) }
        override fun undo() { items.removeAt(items.lastIndex) }
    }

    @Test fun alternatingViewsUndoAndRedoOneTimeline() {
        val a = History()
        val b = History()
        b.shareWith(a)
        val items = mutableListOf<String>()
        append(items, "left").also { it.redo(); a.push(it) }
        append(items, "right").also { it.redo(); b.push(it) }
        a.undo()
        assertEquals(listOf("left"), items)
        b.undo()
        assertTrue(items.isEmpty())
        a.redo(); b.redo()
        assertEquals(listOf("left", "right"), items)
        a.undo()
        append(items, "replacement").also { it.redo(); b.push(it) }
        assertFalse(a.canRedo)
    }

    @Test fun leavingAViewDoesNotClearTheRemainingHistory() {
        val a = History()
        val b = History()
        val items = mutableListOf<String>()
        append(items, "before split").also { it.redo(); a.push(it) }
        b.shareWith(a)
        assertTrue(b.canUndo)
        b.detach()
        b.clear()
        assertTrue(a.canUndo)
        assertFalse(b.canUndo)
        a.undo()
        assertTrue(items.isEmpty())
    }

    @Test fun sharedHistoryKeepsCallbacksLocalAndRespectsLimit() {
        val a = History(1)
        val b = History()
        var left = 0
        var right = 0
        a.onPush = { left++ }; b.onPush = { right++ }
        b.shareWith(a)
        val items = mutableListOf<String>()
        append(items, "one").also { it.redo(); a.push(it) }
        append(items, "two").also { it.redo(); b.push(it) }
        b.undo(); a.undo()
        assertEquals(listOf("one"), items)
        assertEquals(1, left); assertEquals(1, right)
    }
}
