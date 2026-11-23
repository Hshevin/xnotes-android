package com.xnotes.iosapp

import androidx.compose.runtime.*
import com.xnotes.canvas.*
import com.xnotes.core.geometry.*
import com.xnotes.core.history.*
import com.xnotes.core.infinite.*
import com.xnotes.core.model.*
import com.xnotes.core.tools.Tool
import com.xnotes.iosapp.ui.theme.MinTouchTarget
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.platform.ios.CgTextMeasurer
import kotlin.math.atan2

internal val Tool.selectsItems get() = this == Tool.SELECT || this == Tool.LASSO

internal class ItemSelection(private val editor: InkEditor, private val space: InkSpace, private val redraw: () -> Unit) {
    private val note = editor.session.note
    private val canvas = editor.session.canvas
    private val canvasSelection = canvas?.let(::CanvasSelection)
    var members by mutableStateOf(emptyList<Selected>()); private set
    private var box: Obb? = null
    val frame get() = box?.grow(OverlayTessellator.FRAME_MARGIN_PX / space.zoom)
    val ring = mutableListOf<Pt>()
    private var down: Pt? = null
    private var startBox: Obb? = null
    private var handle: ResizeHandle? = null
    private var rotating = false
    private var moved = false
    private var transforms = emptyList<SelectionTransform>()
    val tracking get() = down != null
    private var destination by mutableStateOf<Pt?>(null)
    private var clipboard by editor.selectionClipboard::items
    val canPaste get() = clipboard.isNotEmpty() && destination?.let(space::pageAt)?.let(space::writable) == true

    private fun items(page: Int) = canvas?.items ?: note!!.pages[page].items
    private fun editable(page: Int) = canvas?.editableItems ?: note!!.pages[page].editableItems
    private fun offset(page: Int) = space.fromPage(page, Pt.ZERO)

    private fun select(picked: List<Selected>) {
        members = if (canvasSelection != null) {
            canvasSelection.select(picked.map { it.item }); canvasSelection.items.map { Selected(0, it) }
        } else picked.groupBy { it.pageIndex }.flatMap { (page, group) ->
            Groups.withMembers(group, editable(page).map { Selected(page, it) }, Selected::item)
        }
        box = canvasSelection?.box ?: members.map { (page, item) ->
            val shift = offset(page); item.bounds().translate(shift.x, shift.y)
        }.reduceOrNull(Rect::union)?.let(Obb::fromAabb)
        redraw()
    }

    fun clear() { cancel(); destination = null; select(emptyList()) }

    fun begin(at: Pt, abort: () -> Unit = ::cancel) {
        if (editor.active || tracking) return
        if (members.any { (page, item) -> !space.hasPage(page) || item !in editable(page) }) clear()
        editor.begin(abort); down = at; moved = false
        val chrome = frame
        val target = MinTouchTarget.value / (2 * space.zoom)
        val insideCore = chrome?.let { it.contains(at) && it.center.distanceTo(at) <= target / 2 } == true
        val grip = chrome?.let { ResizeMath.obbRotateGrip(it, OverlayTessellator.GRIP_ARM_PX / (space.zoom * space.density)) }
        val nearest = chrome?.let(ResizeMath::obbHandles)?.minByOrNull { it.content.distanceTo(at) }
        rotating = !insideCore && grip != null && grip.distanceTo(at) <= target && grip.distanceTo(at) < (nearest?.content?.distanceTo(at) ?: Double.MAX_VALUE)
        handle = nearest?.takeIf { !insideCore && !rotating && it.content.distanceTo(at) <= target }
        if (rotating || handle != null || chrome?.contains(at) == true) {
            startBox = box
            transforms = members.groupBy { it.pageIndex }.map { (page, group) ->
                SelectionTransform(group.map { it.item }, editable(page), items(page), offset(page), canvas)
            }
        } else {
            ring.add(at)
        }
    }

    fun move(at: Pt) {
        val start = down ?: return
        if (!moved && start.distanceTo(at) * space.zoom < Space.s4.value) return
        moved = true
        val original = startBox
        if (original == null) {
            if (editor.tool == Tool.LASSO) {
                if (ring.last() != at) ring.add(at)
            } else if (ring.size == 1) ring.add(at) else ring[1] = at
        } else {
            val delta = at - start
            val grabbed = handle
            val transform = when {
                rotating -> {
                    fun angle(point: Pt) = atan2(point.y - original.center.y, point.x - original.center.x)
                    val turn = angle(at) - angle(start)
                    box = original.copy(angle = original.angle + turn)
                    Affine.rotateAbout(original.center, turn)
                }
                grabbed != null -> ResizeMath.obbResize(original, grabbed.id, grabbed.content + delta, OverlayTessellator.FRAME_MARGIN_PX / space.zoom).also { box = it.obb }.transform
                else -> Affine.translateBy(delta.x, delta.y).also { box = original.translate(delta.x, delta.y) }
            }
            transforms.forEach { it.preview(transform) }; editor.invalidate?.invoke()
        }
        redraw()
    }

    private fun pick(at: Pt): List<Selected> {
        val page = space.pageAt(at) ?: return emptyList()
        val hit = SelectionMath.pick(editable(page), space.toPage(page, at), SelectionMath.PICK_REACH_DP / space.zoom)
        return listOfNotNull(hit?.let { Selected(page, it) })
    }

    private fun enclosed(): List<Selected> {
        val band = Rect.bounding(ring)
        val lasso = editor.tool == Tool.LASSO
        if (canvas != null) {
            val candidates = canvas.itemsIn(band).filter(canvas.layers::editable)
            val hits = if (lasso) SelectionMath.lassoMembers(candidates, ring) else SelectionMath.bandMembers(candidates, band)
            return hits.map { Selected(0, it) }
        }
        val pages = note!!.pages
        val paper = pages.indices.map { checkNotNull(space.paper(it)) }
        return if (lasso) SelectionMath.lassoMembers(pages, paper, ring, space::toPage)
        else SelectionMath.bandMembers(pages, paper, band, space::toPage)
    }

    fun finish(at: Pt) {
        if (!tracking) return
        move(at)
        val command = if (startBox != null) {
            if (note != null && moved && members.any { landing(it)?.let(space::writable) != true }) { cancel(); return }
            val edits = transforms.mapNotNull { it.commit() }.toMutableList()
            if (edits.isNotEmpty()) transfer()?.let(edits::add)
            edits.oneEdit()
        } else {
            select(if (moved) enclosed() else pick(at)); null
        }
        destination = members.firstOrNull()?.let { space.fromPage(it.pageIndex, it.item.centroid()) } ?: at
        reset(); editor.end(command, false); redraw()
    }

    private fun landing(row: Selected): Int? {
        val shift = offset(row.pageIndex); val bounds = row.item.bounds().translate(shift.x, shift.y)
        space.pageAt(bounds.center)?.let { return it }
        return note!!.pages.indices.filter { space.paper(it)?.intersects(bounds) == true }.minByOrNull { space.paper(it)!!.distanceTo(bounds.center) }
    }

    private fun transfer(): Command? {
        val pages = note?.pages ?: return null
        val entries = mutableListOf<TransferItems.Transfer>()
        val next = members.map { row ->
            val target = landing(row)
            if (target == null || target == row.pageIndex || !space.writable(target)) row else {
                val shift = space.toPage(target, offset(row.pageIndex))
                entries += TransferItems.Transfer(pages[row.pageIndex], pages[target], row.item, shift.x, shift.y)
                Selected(target, row.item)
            }
        }
        if (entries.isEmpty()) return null
        val originalSlots = entries.groupBy { it.from }.mapValues { (owner, group) ->
            val departing = group.map { it.item }.toSet()
            owner.items.withIndex().filter { it.value in departing }
        }
        val transfer = TransferItems(entries)
        val moved = object : Command by transfer {
            override fun undo() {
                transfer.undo()
                for ((owner, slots) in originalSlots) {
                    for ((_, item) in slots) owner.items.remove(item)
                    for ((index, item) in slots) owner.items.add(index, item)
                }
            }
        }
        moved.redo(); members = next
        val crossed = entries.map { it.item }.toSet()
        return (listOf(moved) + entries.flatMap { listOf(it.from, it.to) }.distinct().mapNotNull { detachLost(it.items, crossed) }).oneEdit()
    }

    fun cancel() {
        if (!tracking) return
        transforms.forEach { it.cancel() }
        startBox?.let { box = it; editor.invalidate?.invoke() }
        reset(); editor.end(null, true); redraw()
    }

    private fun reset() { down = null; startBox = null; ring.clear(); handle = null; rotating = false; transforms = emptyList() }

    private fun validMembers() = members.isNotEmpty() && members.all { (page, item) -> space.hasPage(page) && item in editable(page) }

    fun delete() {
        if (editor.active || !validMembers()) return
        val command = canvas?.let { EraseCanvasItems.capture(it, members.map(Selected::item)) } ?: members.groupBy(Selected::pageIndex).map { (page, group) ->
            val owner = note!!.pages[page]; val before = owner.items.toList(); val gone = group.map(Selected::item).toSet()
            ReplacePageItems(owner, before, before.filterNot { it in gone })
        }.oneEdit() ?: return
        val gone = members.map(Selected::item).toSet(); val owners = members.map(Selected::pageIndex).distinct()
        editor.begin(); command.redo()
        val result = (listOf(command) + owners.mapNotNull { detachLost(items(it), gone) }).oneEdit()
        select(emptyList()); editor.end(result, false)
    }

    private fun detachLost(items: List<CanvasItem>, changed: Set<CanvasItem>): Command? {
        val present = items.toHashSet()
        val commands = items.filterIsInstance<ConnectorItem>().mapNotNull { connector ->
            fun detached(end: ConnectorEnd) = if (end.target != null && end.target !in present && (end.target in changed || connector in changed)) end.loose() else end
            val start = detached(connector.start); val end = detached(connector.end)
            if (start === connector.start && end === connector.end) return@mapNotNull null
            val before = connector.snapshotGeometry(); connector.start = start; connector.end = end
            TransformItems(listOf(connector), listOf(before), listOf(connector.snapshotGeometry()))
        }
        return commands.oneEdit()
    }

    fun copy() {
        if (editor.active || !validMembers()) return
        clipboard = members.map(Selected::item).deepCopyAll(CgTextMeasurer).onEachIndexed { index, clone ->
            val origin = offset(members[index].pageIndex); clone.translate(origin.x, origin.y)
        }
    }

    fun insert(duplicate: Boolean) {
        if (editor.active) return
        val copies = if (duplicate) {
            if (!validMembers()) return
            val clones = members.map(Selected::item).deepCopyAll(CgTextMeasurer)
            members.mapIndexed { index, row -> Selected(row.pageIndex, clones[index].also { it.translate(NUDGE, NUDGE) }) }
        } else {
            val at = destination ?: return
            val page = space.pageAt(at) ?: return
            if (!space.writable(page) || clipboard.isEmpty()) return
            val source = clipboard.map(CanvasItem::bounds).reduce(Rect::union)
            val target = space.toPage(page, box?.let { Rect.bounding(it.corners()).topLeft + Pt(NUDGE, NUDGE) } ?: at)
            clipboard.deepCopyAll(CgTextMeasurer).map { clone ->
                clone.translate(target.x - source.left, target.y - source.top); Selected(page, clone)
            }
        }
        Groups.refresh(copies.map(Selected::item), canvas?.items ?: note!!.pages.flatMap(Page::items))
        val commands = copies.groupBy(Selected::pageIndex).map { (page, group) ->
            val additions = group.map { it.item.also { clone -> clone.layerId = null } }
            canvas?.let { AddCanvasItems(it, additions) } ?: AddItems(note!!.pages[page], additions)
        }.oneEdit() ?: return
        editor.begin(); commands.redo(); select(copies); editor.end(commands, false)
    }

    companion object { private const val NUDGE = 24.0 }
}
