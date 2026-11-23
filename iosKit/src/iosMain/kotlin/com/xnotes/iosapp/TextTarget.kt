package com.xnotes.iosapp

import com.xnotes.core.geometry.*
import com.xnotes.core.history.*
import com.xnotes.core.infinite.*
import com.xnotes.core.model.*
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.text.*
import com.xnotes.edit.FlowTextInput
import com.xnotes.platform.ios.CgTextMeasurer
import kotlin.math.abs

internal data class TextMark(val page: Int, val rect: Rect, val turn: Double = 0.0)

internal abstract class TextTarget(val flow: TextFlow) {
    private val relay = History(0).apply { onPush = { committed(it) } }
    val input = FlowTextInput(flow, relay)
    abstract fun committed(command: Command)
    abstract fun hit(page: Int, point: Pt): FlowPos
    abstract fun caret(pos: FlowPos): TextMark?
    abstract fun rects(range: FlowRange): List<TextMark>
    open fun reflectPreview() {}
    open fun writable() = true
    open fun border(): TextMark? = null
}

internal class PageText(private val editor: InkEditor, private val images: PageImages) : TextTarget(editor.session.note!!.flow) {
    private val frame get() = images.flow(false)
    override fun committed(command: Command) = editor.recordText(command)
    override fun hit(page: Int, point: Pt): FlowPos = when (val hit = frame.hitTest(page, point)) {
        is FlowHit.Caret -> hit.pos
        is FlowHit.Checkbox -> FlowPos(hit.paraIndex, 0)
        FlowHit.BeyondEnd -> flow.endPos()
    }
    override fun caret(pos: FlowPos): TextMark? {
        frame.caretRect(pos)?.let { return TextMark(it.first, it.second) }
        if (flow.paragraphs.isNotEmpty()) return null
        val page = frame.firstPage
        val bounds = frame.pages.getOrNull(page)?.contentRect ?: return null
        return TextMark(page, Rect(bounds.x, bounds.y, 1.0, CgTextMeasurer.lineHeight(FontSpec(flow.defaultSizePt, flow.defaultFace))))
    }
    override fun rects(range: FlowRange) = frame.selectionRects(range).map { TextMark(it.first, it.second) }
}

internal class BoxText(private val editor: InkEditor, val shape: ShapeItem) : TextTarget(TextFlow().apply {
    paragraphs.addAll(checkNotNull(shape.label).text.split('\n').map { Paragraph(mutableListOf<Run>().apply { if (it.isNotEmpty()) add(Run(it)) }) })
}) {
    private val doc = checkNotNull(editor.session.canvas)
    private var draft = !doc.containsRef(shape)
    private var saved = checkNotNull(shape.label)
    private val item = TextItem(shape.box.topLeft, text = saved.text, rgba = saved.style.rgba,
        pointSize = saved.style.pointSize, face = saved.style.face, measurer = CgTextMeasurer)
    private var cachedText: String? = null
    private var cachedWidth = 0.0
    private var lines = emptyList<Pair<Int, DoubleArray>>()
    private val height get() = CgTextMeasurer.lineHeight(item.font)
    private fun rows(): List<Pair<Int, DoubleArray>> {
        val frame = checkNotNull(shape.labelFrame())
        item.pos = frame.topLeft; item.width = frame.w
        if (cachedText != item.text || cachedWidth != item.width) {
            lines = CgTextMeasurer.labelCaretLines(item.text, item.font, item.width)
            cachedText = item.text; cachedWidth = item.width
        }
        return lines
    }
    override fun reflectPreview() {
        val text = flow.plainText()
        if (item.text == text) return
        item.text = text
        shape.label = saved.withText(text)
        if (draft && text.isEmpty()) doc.remove(shape)
        else if (!doc.containsRef(shape) && draft) doc.add(shape)
        else if (doc.containsRef(shape)) doc.itemChanged(shape)
    }
    override fun committed(command: Command) {
        reflectPreview()
        if (saved.text == item.text) return
        val after = checkNotNull(shape.label)
        val edit = OnCanvas(doc, EditLabel(shape, saved, after), listOf(shape))
        val change = when {
            draft -> AddCanvasItem(doc, shape)
            item.text.isEmpty() -> CompositeCommand(listOf(edit, EraseCanvasItems.capture(doc, listOf(shape)).also { it.redo() }))
            else -> edit
        }
        saved = after; draft = item.text.isEmpty()
        editor.recordText(change)
    }
    override fun writable() = (draft || doc.containsRef(shape)) && doc.layers.editable(shape)
    private fun mark(rect: Rect): TextMark {
        val center = shape.toPage(rect.center)
        return TextMark(0, Rect(center.x - rect.w/2, center.y - rect.h/2, rect.w, rect.h), shape.rotation)
    }
    override fun border() = mark(shape.box.union(checkNotNull(shape.labelFrame())))
    override fun hit(page: Int, point: Pt): FlowPos {
        val rows = rows()
        val local = shape.toLocal(point)
        val line = rows[((local.y - item.pos.y) / height).toInt().coerceIn(rows.indices)]
        val offset = line.second.indices.minBy { abs(item.pos.x + line.second[it] - local.x) }
        return input.positionAt(line.first + offset)
    }
    override fun caret(pos: FlowPos): TextMark {
        val offset = input.offsetOf(pos)
        val rows = rows()
        val row = rows.indexOfLast { it.first <= offset }.coerceAtLeast(0)
        val (start, xs) = rows[row]
        return mark(Rect(item.pos.x + xs[(offset - start).coerceIn(xs.indices)], item.pos.y + row * height, 1.0, height))
    }
    override fun rects(range: FlowRange): List<TextMark> = buildList {
        val sorted = range.normalized(); val from = input.offsetOf(sorted.start); val to = input.offsetOf(sorted.end)
        for ((row, line) in rows().withIndex()) {
            val (start, xs) = line
            val first = maxOf(start, from); val last = minOf(start + xs.lastIndex, to)
            if (first >= last) continue
            val left = xs[first - start]; val right = xs[last - start]
            add(mark(Rect(item.pos.x + minOf(left, right), item.pos.y + row * height, abs(right - left), height)))
        }
    }

    companion object {
        fun matches(item: ShapeItem) = item.shape == ShapeKind.RECTANGLE && item.strokeRgba.a == 0 &&
            (item.fillRgba?.a ?: 0) == 0 && item.label != null

        fun newShape(center: Pt, color: Rgba): ShapeItem {
            val style = TextStyle(color, TextItem.DEFAULT_POINT_SIZE, TextItem.DEFAULT_FACE)
            val label = ShapeLabel("", style, CgTextMeasurer)
            val half = Pt(TextItem.DEFAULT_WIDTH / 2, label.lineHeight)
            return ShapeItem(ShapeKind.RECTANGLE, center - half, center + half, Rgba(0, 0, 0, 0), 0.0).also { it.label = label }
        }
    }
}
