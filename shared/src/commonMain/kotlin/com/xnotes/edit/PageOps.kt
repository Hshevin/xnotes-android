package com.xnotes.edit

import com.xnotes.core.history.AddPage
import com.xnotes.core.history.Command
import com.xnotes.core.history.DeletePage
import com.xnotes.core.history.MovePages
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.deepCopy
import com.xnotes.core.model.prepareReferenceAnchors
import com.xnotes.core.pal.TextMeasurer

object PageOps {
    fun addAfter(doc: Document, index: Int): Command {
        val previous = doc.pages[index]
        return insert(doc, index + 1, Page(previous.width, previous.height))
    }

    fun duplicate(doc: Document, index: Int, measurer: TextMeasurer): Command {
        val original = doc.pages[index]
        return insert(doc, index + 1, original.deepCopy(measurer))
    }

    private fun insert(doc: Document, index: Int, page: Page): Command {
        val command = AddPage(doc, page, index)
        command.redo()
        try { doc.prepareReferenceAnchors() } catch (failure: Exception) { command.undo(); throw failure }
        doc.dirty = true
        return command
    }

    fun delete(doc: Document, index: Int): Command {
        require(doc.pages.size > 1)
        return DeletePage(doc, doc.pages[index], index).also { it.redo(); doc.dirty = true }
    }

    fun move(doc: Document, from: Int, to: Int): Command {
        require(from in doc.pages.indices && to in doc.pages.indices)
        val order = doc.pages.toMutableList().apply { add(to, removeAt(from)) }
        return MovePages(doc, doc.pages.toList(), order).also { it.redo(); doc.dirty = true }
    }
}
