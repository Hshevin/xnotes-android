package com.xnotes.mindmap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import kotlin.math.hypot

enum class InkMode { PEN, ERASER, LASSO }

/** Transactional node editor: pen-up publishes one operation; CANCEL discards only the live gesture. */
class InkPadView(context: Context) : View(context) {
    var nodeWidth = 320.0
    var nodeHeight = 200.0
    var ink: List<MapInk> = emptyList()
        set(value) { if (field !== value) { field = value; painter.clear(); invalidate() } }
    var mode = InkMode.PEN
        set(value) { field = value; selection = emptySet(); invalidate() }
    var penWidth = 3.0
    var onCommit: (List<MapInk>) -> Unit = {}
    private val painter = MapPainter()
    private val livePainter = MapPainter()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE }
    private var pointer = -1
    private var gestureMode = InkMode.PEN
    private var gestureWidth = 3.0
    private var points = ArrayList<InkPoint>()
    private var preview: List<MapInk>? = null
    private var selection = emptySet<Int>()
    private var moveSelection = false
    private var down = Pt(0.0, 0.0)
    private var before: List<MapInk> = emptyList()
    private val scale get() = minOf(width / nodeWidth, height / nodeHeight).coerceAtLeast(.001).toFloat()
    private val offsetX get() = (width - nodeWidth * scale).toFloat() / 2
    private val offsetY get() = (height - nodeHeight * scale).toFloat() / 2
    private fun point(x: Float, y: Float) = Pt(((x - offsetX) / scale).toDouble(), ((y - offsetY) / scale).toDouble())
    private fun within(p: Pt) = p.x in 0.0..nodeWidth && p.y in 0.0..nodeHeight
    private fun selectedBounds(): Rect? = selection.mapNotNull { ink.getOrNull(it) }.flatMap { s -> s.points.map { Pt(it.x, it.y) } }
        .takeIf { it.isNotEmpty() }?.let(Rect::bounding)
    init { isFocusable = true }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.LTGRAY)
        canvas.save(); canvas.translate(offsetX, offsetY); canvas.scale(scale, scale)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        canvas.drawRect(0f, 0f, nodeWidth.toFloat(), nodeHeight.toFloat(), paint)
        canvas.clipRect(0f, 0f, nodeWidth.toFloat(), nodeHeight.toFloat())
        if (preview != null) painter.clear()
        painter.drawInk(canvas, preview ?: ink)
        if (points.isNotEmpty() && gestureMode == InkMode.PEN) {
            livePainter.clear()
            livePainter.drawInk(canvas, listOf(MapInk(points.toList(), gestureWidth)))
        }
        paint.color = Color.BLACK; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f / scale
        if (gestureMode == InkMode.LASSO && points.isNotEmpty() && !moveSelection) {
            val path = Path().apply { moveTo(points.first().x.toFloat(), points.first().y.toFloat()); points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) } }
            canvas.drawPath(path, paint)
        }
        selectedBounds()?.let { canvas.drawRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(), paint) }
        canvas.restore()
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                if (pointer >= 0 || e.getToolType(i) !in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER, MotionEvent.TOOL_TYPE_MOUSE)) return true
                val p = point(e.getX(i), e.getY(i))
                if (!within(p)) return true
                pointer = e.getPointerId(i); down = p; before = ink; preview = null; points.clear()
                gestureWidth = penWidth
                gestureMode = if (e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER) InkMode.ERASER else mode
                moveSelection = gestureMode == InkMode.LASSO && selectedBounds()?.outset(8.0)?.contains(p) == true
                if (!moveSelection) selection = emptySet()
                parent?.requestDisallowInterceptTouchEvent(true)
                sample(e, i)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointer)
                if (i < 0) return true
                if (moveSelection) {
                    val p = point(e.getX(i), e.getY(i))
                    val b = selectedBounds() ?: return true
                    val dx = (p.x - down.x).coerceIn(-b.left, nodeWidth - b.right)
                    val dy = (p.y - down.y).coerceIn(-b.top, nodeHeight - b.bottom)
                    preview = before.mapIndexed { index, stroke -> if (index in selection) stroke.copy(points = stroke.points.map { it.copy(x = it.x + dx, y = it.y + dy) }) else stroke }
                } else {
                    for (h in 0 until e.historySize) sample(e, i, h)
                    sample(e, i)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (pointer < 0 || e.getPointerId(e.actionIndex) != pointer) return true
                if ((e.flags and 0x20) == 0) {
                    if (!moveSelection) sample(e, e.actionIndex)
                    when {
                        moveSelection -> preview?.let(onCommit)
                        gestureMode == InkMode.PEN && points.isNotEmpty() -> onCommit(before + MapInk(points.toList(), gestureWidth))
                        gestureMode == InkMode.ERASER -> preview?.let(onCommit)
                        gestureMode == InkMode.LASSO -> {
                            val polygon = points.map { Pt(it.x, it.y) }
                            selection = before.indices.filterTo(hashSetOf()) { i -> before[i].points.any { Geometry.pointInPolygon(polygon, Pt(it.x, it.y)) } }
                        }
                    }
                    performClick()
                }
                resetGesture()
            }
            MotionEvent.ACTION_CANCEL -> resetGesture()
        }
        invalidate()
        return true
    }
    private fun sample(e: MotionEvent, index: Int, history: Int? = null) {
        val x = if (history == null) e.getX(index) else e.getHistoricalX(index, history)
        val y = if (history == null) e.getY(index) else e.getHistoricalY(index, history)
        val p = point(x, y)
        // Clamp the path to the node's paper; raw MotionEvent values are never rewritten.
        val next = InkPoint(p.x.coerceIn(0.0, nodeWidth), p.y.coerceIn(0.0, nodeHeight),
            (if (history == null) e.getPressure(index) else e.getHistoricalPressure(index, history)).toDouble().coerceIn(0.0, 2.0),
            (if (history == null) e.eventTime else e.getHistoricalEventTime(history)).toDouble())
        if (gestureMode == InkMode.ERASER) {
            val a = points.lastOrNull()?.let { Pt(it.x, it.y) } ?: p
            preview = (preview ?: before).filterNot { s -> s.points.any { Geometry.distancePointToSegment(Pt(it.x, it.y), a, p) <= 10.0 + s.width / 2 } ||
                s.points.zipWithNext().any { (c, d) -> Geometry.distanceSegmentToSegment(a, p, Pt(c.x, c.y), Pt(d.x, d.y)) <= 10.0 + s.width / 2 } }
        }
        points.add(next)
    }
    private fun resetGesture() {
        pointer = -1; points.clear(); preview = null; before = emptyList(); moveSelection = false
        painter.clear(); parent?.requestDisallowInterceptTouchEvent(false); invalidate()
    }
    override fun onDetachedFromWindow() { resetGesture(); super.onDetachedFromWindow() }
}
