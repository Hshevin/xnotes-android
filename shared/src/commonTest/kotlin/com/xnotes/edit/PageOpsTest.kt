package com.xnotes.edit

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * W4-C contract (docs/movinkpad/process/ipados-wave4-contracts-2026-11-24.md, "W4-C"): page management on the iPad.
 * Each operation is applied, returned as one command for History, and keeps the permanent page id rules
 * (AGENTS.md: 删页后显示已删除、不改指；复制取得新页编号).
 */
class PageOpsTest {

    private fun note(pages: Int = 3) = Document.blank(pages).also { it.enableReferenceAnchors() }

    private fun ids(doc: Document) = doc.pages.map { it.referenceIdentity.id }

    private fun ink() = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(1.0, 1.0, 0.5), Sample(5.0, 5.0, 0.5, 4.0)))

    @Test
    fun aPageAddedAfterTakesItsNeighboursSizeAndAFreshId() {
        val doc = note(2)
        doc.pages[0].width = 800.0
        doc.pages[0].height = 600.0
        val before = ids(doc)
        val history = History()
        history.push(PageOps.addAfter(doc, 0))
        assertEquals(3, doc.pages.size)
        val added = doc.pages[1]
        assertEquals(800.0, added.width)
        assertEquals(600.0, added.height)
        val id = assertNotNull(added.referenceIdentity.id)
        assertTrue(id !in before)
        assertTrue(doc.dirty)

        history.undo()
        assertEquals(before, ids(doc))
        history.redo()
        assertSame(added, doc.pages[1])
        assertEquals(id, doc.pages[1].referenceIdentity.id, "redo brings back the same page, same id")
    }

    @Test
    fun deletingThenUndoingRestoresTheSamePageAndId() {
        val doc = note(3)
        doc.pages[1].items.add(ink())
        val page = doc.pages[1]
        val id = page.referenceIdentity.id
        val history = History()
        history.push(PageOps.delete(doc, 1))
        assertEquals(2, doc.pages.size)
        assertTrue(doc.pages.none { it === page })
        history.undo()
        assertSame(page, doc.pages[1])
        assertEquals(id, doc.pages[1].referenceIdentity.id)
        assertEquals(1, doc.pages[1].items.size)
    }

    @Test
    fun theLastPageCannotBeDeleted() {
        val doc = note(1)
        val result = runCatching { PageOps.delete(doc, 0) }
        assertTrue(result.isFailure)
        assertEquals(1, doc.pages.size)
    }

    @Test
    fun movingKeepsEveryIdAndUndoes() {
        val doc = note(3)
        val before = ids(doc)
        val history = History()
        history.push(PageOps.move(doc, from = 0, to = 2))
        assertEquals(listOf(before[1], before[2], before[0]), ids(doc))
        history.undo()
        assertEquals(before, ids(doc))
    }

    @Test
    fun aDuplicateIsADeepCopyWithANewId() {
        val doc = note(2)
        doc.pages[0].items.add(ink())
        val original = doc.pages[0]
        val history = History()
        history.push(PageOps.duplicate(doc, 0, FakeTextMeasurer()))
        assertEquals(3, doc.pages.size)
        val copy = doc.pages[1]
        assertNotSame(original, copy)
        assertNotEquals(original.referenceIdentity.id, copy.referenceIdentity.id)
        assertEquals(1, copy.items.size)
        assertNotSame(original.items[0], copy.items[0])
        (copy.items[0] as Stroke).translate(10.0, 0.0)
        assertEquals(1.0, (original.items[0] as Stroke).samples[0].x, "editing the copy leaves the original alone")
        history.undo()
        assertEquals(2, doc.pages.size)
    }

    @Test
    fun outOfRangeIndicesAreRefusedWithoutChanges() {
        val doc = note(2)
        val before = ids(doc)
        assertTrue(runCatching { PageOps.addAfter(doc, 5) }.isFailure)
        assertTrue(runCatching { PageOps.move(doc, 0, 9) }.isFailure)
        assertTrue(runCatching { PageOps.duplicate(doc, -1, FakeTextMeasurer()) }.isFailure)
        assertEquals(before, ids(doc))
    }
}
