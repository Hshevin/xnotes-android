package com.xnotes.mindmap

import android.content.Context
import android.graphics.Canvas
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import com.xnotes.core.geometry.Pt
import kotlin.math.hypot

/** One native surface for the graph; no nested AndroidViews or wet-ink Surface reattachment. */
class MindMapView(context: Context) : View(context) {
    var document = MindMapDocument()
        set(value) { if (field != value) { field = value; painter.clear(); invalidate() } }
    var selected: String? = null
        set(value) { field = value; invalidate() }
    var dark = false
        set(value) { field = value; invalidate() }
    var onSelect: (String?) -> Unit = {}
    var onSources: (String) -> Unit = {}
    var onEdit: (String) -> Unit = {}
    var onChange: (MindMapDocument) -> Unit = {}
    private val painter = MapPainter()
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var initialized = false
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var dragging = false
    private var cancelled = false
    private var pointer = -1
    private var dragged: String? = null
    private var before: MindMapDocument? = null
    private var preview: MindMapDocument? = null
    private var dropTarget: String? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleAt(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { performClick(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            hit(e.x, e.y)?.let { onEdit(it.id) }
            cancelled = true; preview = null
            return true
        }
        override fun onLongPress(e: MotionEvent) { if (!dragging) hit(e.x, e.y)?.let { onEdit(it.id) }; cancelGesture() }
    })
    init { painter.sourceLabel={ resources.getString(com.xnotes.R.string.graph_sources_count,it) }; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun performClick(): Boolean { super.performClick(); return true }
    private val scale get() = resources.displayMetrics.density * zoom
    private fun point(x: Float, y: Float) = Pt(((x - panX) / scale).toDouble(), ((y - panY) / scale).toDouble())
    private fun hit(x: Float, y: Float, doc: MindMapDocument = document) = doc.visible().lastOrNull { it.bounds.contains(point(x, y)) }
    fun fit() {
        if (width == 0 || height == 0) return
        val b = MapPainter.bounds(document)
        val s = minOf(width / b.w, height / b.h).toFloat().coerceAtMost(resources.displayMetrics.density * 1.5f)
        zoom = (s / resources.displayMetrics.density).coerceIn(.05f, 4f)
        panX = width / 2f - b.centerX.toFloat() * scale
        panY = height / 2f - b.centerY.toFloat() * scale
        initialized = true; invalidate()
    }
    fun centerOn(id: String) {
        document.nodes.firstOrNull { it.id == id }?.bounds?.let {
            panX = width / 2f - it.centerX.toFloat() * scale
            panY = height / 2f - it.centerY.toFloat() * scale
            invalidate()
        }
    }
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val visible = document.visible()
        val at = visible.indexOfFirst { it.id == selected }.coerceAtLeast(0)
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                val next = if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) at + 1 else at - 1
                selected = visible[next.coerceIn(0, visible.lastIndex)].id
                onSelect(selected); centerOn(requireNotNull(selected)); return true
            }
            android.view.KeyEvent.KEYCODE_ENTER -> { selected?.let(onEdit); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    fun zoomBy(factor: Float) = scaleAt(factor, width / 2f, height / 2f)
    private fun scaleAt(factor: Float, x: Float, y: Float) {
        val p = point(x, y)
        zoom = (zoom * factor).coerceIn(.05f, 4f)
        panX = x - p.x.toFloat() * scale; panY = y - p.y.toFloat() * scale
        invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { if (!initialized) fit() }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        canvas.save(); canvas.translate(panX, panY); canvas.scale(scale, scale)
        painter.draw(canvas, preview ?: document, dark, selected, dropTarget)
        canvas.restore()
    }
    private fun cancelGesture() { cancelled = true; preview = null; dropTarget = null; before = null; dragged = null; invalidate() }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val stylus = (0 until e.pointerCount).any { e.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER }
        // A second pointer while the pen owns a drag is a palm, never a pinch or a new drag.
        if (pointer >= 0 && e.findPointerIndex(pointer) >= 0 && e.getToolType(e.findPointerIndex(pointer)) in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)) {
            if (e.actionMasked in listOf(MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP) && e.getPointerId(e.actionIndex) != pointer) return true
        } else if (!stylus) scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(e, 0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.getToolType(e.actionIndex) in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)) begin(e, e.actionIndex)
                else cancelGesture()
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointer)
                if (i < 0 || cancelled || (scaleDetector.isInProgress && !stylus)) return true
                val x = e.getX(i); val y = e.getY(i)
                if (hypot((x - downX).toDouble(), (y - downY).toDouble()) > slop) dragging = true
                if (dragging) {
                    val id = dragged
                    if (id != null) {
                        val base = before ?: return true
                        preview = base.translate(id, ((x - downX) / scale).toDouble(), ((y - downY) / scale).toDouble())
                        val excluded = base.descendants(id)
                        dropTarget = document.visible().lastOrNull { it.id !in excluded && it.bounds.contains(point(x, y)) }?.id
                    } else { panX += x - lastX; panY += y - lastY }
                    invalidate()
                }
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (pointer < 0 || e.getPointerId(e.actionIndex) != pointer) return true
                if (!cancelled && dragging && (e.flags and 0x20) == 0) preview?.let { moved ->
                    val parent = dropTarget
                    onChange(if (parent != null) moved.reparent(requireNotNull(dragged), parent) else moved)
                }
                cancelGesture(); pointer = -1
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> { cancelGesture(); pointer = -1; parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
    private fun begin(e: MotionEvent, index: Int) {
        parent?.requestDisallowInterceptTouchEvent(true)
        pointer = e.getPointerId(index)
        downX = e.getX(index); downY = e.getY(index); lastX = downX; lastY = downY
        dragging = false; cancelled = false; before = document; preview = null; dropTarget = null
        val node=hit(downX,downY)
        dragged = node?.id
        onSelect(dragged)
        if(node != null && node.sources.isNotEmpty() && point(downX,downY).y >= node.y+node.height-28) {
            cancelled=true;dragged=null;onSources(node.id)
        }
    }
    override fun onDetachedFromWindow() { cancelGesture(); super.onDetachedFromWindow() }
}
