@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.iosapp

import androidx.compose.runtime.*
import com.xnotes.core.geometry.*
import com.xnotes.core.model.*
import com.xnotes.core.text.*
import com.xnotes.core.tools.Tool
import com.xnotes.iosapp.ui.theme.Space
import com.xnotes.iosapp.ui.theme.ToolbarPalette
import kotlinx.cinterop.*
import platform.CoreGraphics.*
import platform.UIKit.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal class TextSurface(val editor: InkEditor, private val space: InkSpace,
                           private val images: PageImages? = null, private val darkPaper: (Int) -> Boolean) {
    val view = TextKeyboard(this)
    var target: TextTarget? = null; private set
    private var anchor: FlowPos? = null
    private var bold by mutableStateOf(false)
    private var italic by mutableStateOf(false)
    private var list by mutableStateOf(false)
    private var editing by mutableStateOf(false)
    private var keyboardCover: Rect? = null

    fun configure() {
        val enabled = editor.tool == Tool.TEXT
        view.hidden = !enabled
        if (!enabled) close() else editor.textSurface = this
    }
    fun close() {
        finishEditing(); view.hidden = true
        if (editor.textSurface === this) editor.textSurface = null
    }
    fun finishEditing() {
        view.cancelTracking(); anchor = null
        if (target != null) {
            settle(); target = null; editing = false; space.scrollForCaret(Pt.ZERO, 0.0)
            view.resignFirstResponder(); view.setNeedsDisplay()
        }
    }
    fun settle() {
        if (target?.input?.marked != null) { target?.input?.unmarkText(); changed() }
    }
    fun withCommittedText(read: () -> Unit) {
        val current = target ?: return read()
        try { current.input.withCommittedText { current.reflectPreview(); read() } }
        finally { current.reflectPreview() }
    }
    fun replayed() {
        val current = target ?: return
        view.externalChange(true) {
            if (current is BoxText) {
                if (!current.writable()) { close(); configure(); return@externalChange }
                val pos = current.input.selection.start
                target = BoxText(editor, current.shape).also { it.input.selection = FlowRange.caret(pos) }
            }
            target?.input?.typingStyle = null
            selectionChanged()
        }
    }
    fun changed() {
        val current = target
        if (current is PageText) current.layoutPreview() else current?.reflectPreview()
        editor.invalidate?.invoke()
        selectionChanged()
    }
    fun selectionChanged() {
        val input = target?.input ?: return
        val style = input.typingStyle ?: FlowEditor(input.flow).styleAtRangeStart(input.selection)
        bold = style.bold; italic = style.italic
        list = input.flow.paragraphs.getOrNull(input.selection.normalized().start.para)?.list == ListKind.BULLET
        editing = true; view.setNeedsDisplay()
        ensureCaretVisible()
    }

    fun viewportChanged(cover: Rect?) { keyboardCover = cover; ensureCaretVisible() }

    private fun ensureCaretVisible() {
        val current = target ?: return
        val mark = current.caret(current.input.selection.end)?.takeIf { space.hasPage(it.page) } ?: return
        val bounds = view.bounds.useContents { Rect(0.0, 0.0, size.width, size.height) }
        val margin = Space.s24.value.toDouble()
        if (bounds.w <= 0.0 || bounds.h <= 0.0) return
        val caret = screen(mark)
        fun delta(start: Double, end: Double, low: Double, high: Double): Double {
            if (high <= low) return 0.0
            val pad = margin.coerceAtMost(((high - low - (end - start)) / 2).coerceAtLeast(0.0))
            return when {
                end - start >= high - low || end > high - pad -> end - high + pad
                start < low + pad -> start - low - pad
                else -> 0.0
            }
        }
        val dx = delta(caret.left, caret.right, 0.0, bounds.w)
        val unoccluded = caret.translate(-dx, -delta(caret.top, caret.bottom, 0.0, bounds.h))
        var top = 0.0; var bottom = bounds.h
        val cover = keyboardCover
        val padded = unoccluded.outset(margin)
        if (cover != null && cover.w > 0 && cover.h > 0 && cover.intersects(bounds) && padded.right >= cover.left &&
            padded.left <= cover.right && (cover.bottom >= bounds.h || padded.intersects(cover))) {
            if (cover.top >= caret.h + margin * 2 || cover.bottom >= bounds.h) bottom = cover.top.coerceIn(0.0, bounds.h)
            else top = cover.bottom.coerceIn(0.0, bounds.h)
        }
        val dy = delta(caret.top, caret.bottom, top, bottom)
        space.scrollForCaret(Pt(dx, dy), bounds.h - bottom)
    }

    private fun location(screen: Pt): Pair<Int, Pt>? {
        val content = space.origin + screen / space.zoom
        val page = space.pageAt(content) ?: return null
        return page to space.toPage(page, content)
    }
    fun hit(screen: Pt): FlowPos? {
        val (page, point) = location(screen) ?: return null
        return target?.hit(page, point)
    }
    fun start(screen: Pt, tap: Boolean): Boolean {
        val (page, point) = location(screen) ?: return false
        if (editor.textSurface !== this || editor.tool != Tool.TEXT || editor.active) return false
        val canvas = editor.session.canvas
        val next = images?.let { target as? PageText ?: PageText(editor, it) } ?: canvas?.let { doc ->
            val existing = doc.layers.ordered(doc.items).asReversed().firstOrNull {
                if (it is ShapeItem && BoxText.matches(it)) it.box.contains(it.toLocal(point)) || it.labelContains(point)
                else it.contains(point)
            }
            val shape = if (existing != null) {
                if (existing !is ShapeItem || !BoxText.matches(existing) || !doc.layers.editable(existing)) return false
                existing
            } else {
                if (!tap || !doc.layers.writable) return false
                settle()
                val color = if (!space.night(0) && darkPaper(0)) white else black
                BoxText.newShape(point, color).also(doc.layers::adopt)
            }
            (target as? BoxText)?.takeIf { it.shape === shape } ?: BoxText(editor, shape)
        } ?: return false
        view.externalChange(target !== next || (tap && next is PageText && next.beyondEnd(page, point))) {
            settle(); target = next
            val pos = if (tap) next.tap(page, point) else next.hit(page, point)
            next.input.selection = FlowRange.caret(pos); next.input.typingStyle = null
            anchor = pos; selectionChanged()
        }
        view.becomeFirstResponder()
        return true
    }
    fun drag(screen: Pt) {
        val start = anchor ?: return
        val end = hit(screen) ?: return
        view.externalChange(false) { target?.input?.selection = FlowRange(start, end); selectionChanged() }
    }
    private fun screenFrame(mark: TextMark): Rect {
        val pos = (space.fromPage(mark.page, mark.rect.topLeft) - space.origin) * space.zoom
        return Rect(pos.x, pos.y, mark.rect.w * space.zoom, mark.rect.h * space.zoom)
    }
    fun screen(mark: TextMark): Rect {
        val rect = screenFrame(mark)
        if (mark.turn == 0.0) return rect
        val width = abs(cos(mark.turn)) * rect.w + abs(sin(mark.turn)) * rect.h
        val height = abs(sin(mark.turn)) * rect.w + abs(cos(mark.turn)) * rect.h
        return Rect(rect.centerX - width/2, rect.centerY - height/2, width, height)
    }
    fun draw(context: CGContextRef) {
        val current = target?.takeIf { it.writable() } ?: return
        val scale = view.window?.screen?.scale ?: 1.0
        val pixel = 1.0 / scale
        fun paint(mark: TextMark, kind: Int) {
            val area = screenFrame(mark)
            CGContextSaveGState(context)
            CGContextTranslateCTM(context, area.centerX, area.centerY)
            CGContextRotateCTM(context, mark.turn)
            CGContextTranslateCTM(context, -area.centerX, -area.centerY)
            val rect = when (kind) {
                1 -> CGRectMake(area.x, area.y, maxOf(1.5, area.w), area.h)
                2 -> CGRectMake(area.x, area.bottom - 2, maxOf(pixel, area.w), 2.0)
                else -> area.cg()
            }
            val light = darkPaper(mark.page)
            val main = if (light) 1.0 else if (current is BoxText) 1.0 / 255 else 0.0
            val alpha = if (kind == 0) 0.26 else 1.0
            if (current is BoxText) {
                CGContextSetGrayStrokeColor(context, if (light) 0.0 else 1.0, 1.0)
                CGContextStrokeRectWithWidth(context, rect, if (kind == 3) pixel * 3 else pixel * 2)
            }
            if (kind == 3) {
                CGContextSetGrayStrokeColor(context, main, 1.0); CGContextStrokeRectWithWidth(context, rect, pixel)
            } else {
                CGContextSetGrayFillColor(context, main, alpha); CGContextFillRect(context, rect)
            }
            CGContextRestoreGState(context)
        }
        current.border()?.let { paint(it, 3) }
        val range = current.input.selection
        if (range.collapsed) current.caret(range.end)?.let { paint(it, 1) } else current.rects(range).forEach { paint(it, 0) }
        current.input.marked?.let { current.rects(it).forEach { mark -> paint(mark, 2) } }
    }

    @Composable fun formatButtons(palette: ToolbarPalette) {
        if (images == null) return
        ToolbarIcon(ToolbarButton.BOLD, palette, chosen = bold, enabled = editing) { format("bold") }
        ToolbarIcon(ToolbarButton.ITALIC, palette, chosen = italic, enabled = editing) { format("italic") }
        ToolbarIcon(ToolbarButton.LIST, palette, chosen = list, enabled = editing) { format("list") }
    }
    internal fun format(kind: String) {
        val current = target as? PageText ?: return
        settle()
        val input = current.input; val flow = current.flow; val editor = FlowEditor(flow)
        val range = input.selection
        if (kind == "list") {
            val create = if (flow.paragraphs.isEmpty()) editor.appendEmptyLines(1).first else null
            val next = if (list) ListKind.NONE else ListKind.BULLET
            val style = editor.setParaStyle(range) { it.list = next }
            editor.combined(listOfNotNull(create, style))?.let(current::committed)
        } else {
            val transform: (CharStyle) -> CharStyle = { if (kind == "bold") it.copy(bold = !bold) else it.copy(italic = !italic) }
            if (range.collapsed) input.typingStyle = transform(input.typingStyle ?: editor.styleAtRangeStart(range))
            else editor.setCharStyle(range, transform)?.let(current::committed)
        }
        changed()
    }
}

internal fun Rect.cg() = CGRectMake(x, y, w, h)
