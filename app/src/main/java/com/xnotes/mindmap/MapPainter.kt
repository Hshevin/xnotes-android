package com.xnotes.mindmap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.StaticLayout
import android.text.TextPaint
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.platform.AndroidRenderer
import java.util.IdentityHashMap

/** Shared by the live view, explorer preview and PDF export. All coordinates are document-local. */
class MapPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f }
    private val strokes = IdentityHashMap<MapInk, Stroke>()
    private val layouts = hashMapOf<Pair<String, Int>, StaticLayout>()
    var sourceLabel: (Int) -> String = { it.toString() }
    fun clear() { strokes.clear(); layouts.clear() }
    fun draw(canvas: Canvas, doc: MindMapDocument, dark: Boolean = false, selected: String? = null, dropTarget: String? = null) {
        val bg = if (dark) Color.BLACK else Color.WHITE
        val fg = if (dark) Color.WHITE else Color.BLACK
        val visible = doc.visible()
        val byId = visible.associateBy { it.id }
        val parents = doc.nodes.mapNotNullTo(hashSetOf()) { it.parent }
        paint.color = fg; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        for (n in visible) {
            val p = byId[n.parent] ?: continue
            val right = n.bounds.centerX > p.bounds.centerX
            val x1 = if (right) p.x + p.width else p.x
            val x2 = if (right) n.x else n.x + n.width
            val y1 = p.y + p.height / 2; val y2 = n.y + n.height / 2
            val mid = ((x1 + x2) / 2).toFloat()
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                cubicTo(mid, y1.toFloat(), mid, y2.toFloat(), x2.toFloat(), y2.toFloat())
            }
            canvas.drawPath(path, paint)
        }
        for (edge in doc.relations) {
            val a=byId[edge.from] ?: continue
            val b=byId[edge.to] ?: continue
            val start=a.bounds.center; val end=b.bounds.center
            paint.style=Paint.Style.STROKE;paint.color=fg;paint.strokeWidth=2f
            paint.pathEffect=android.graphics.DashPathEffect(floatArrayOf(8f,6f),0f)
            canvas.drawLine(start.x.toFloat(),start.y.toFloat(),end.x.toFloat(),end.y.toFloat(),paint)
            paint.pathEffect=null
            // Arrow near the destination border; endpoints always bind to current node geometry.
            val dx=end.x-start.x;val dy=end.y-start.y
            val factor=minOf(if(dx==0.0) Double.POSITIVE_INFINITY else b.width/2/kotlin.math.abs(dx),if(dy==0.0) Double.POSITIVE_INFINITY else b.height/2/kotlin.math.abs(dy))
            if(edge.directed && factor.isFinite()) {
                val x=end.x-dx*factor;val y=end.y-dy*factor
                val angle=kotlin.math.atan2(dy,dx)
                for(sign in listOf(-1,1)) canvas.drawLine(x.toFloat(),y.toFloat(),(x-14*kotlin.math.cos(angle+sign*.45)).toFloat(),(y-14*kotlin.math.sin(angle+sign*.45)).toFloat(),paint)
            }
            text.color=fg
            canvas.drawText(edge.label.take(80),((start.x+end.x)/2).toFloat(),((start.y+end.y)/2-8).toFloat(),text)
        }
        for (n in visible) {
            val active = n.id == selected
            val color = if (active) bg else fg
            canvas.save(); canvas.translate(n.x.toFloat(), n.y.toFloat())
            paint.style = Paint.Style.FILL; paint.color = if (active) fg else bg
            canvas.drawRect(0f, 0f, n.width.toFloat(), n.height.toFloat(), paint)
            paint.style = Paint.Style.STROKE; paint.color = fg; paint.strokeWidth = if (n.id == dropTarget) 6f else 2f
            canvas.drawRect(0f, 0f, n.width.toFloat(), n.height.toFloat(), paint)
            canvas.save(); canvas.clipRect(2f, 2f, n.width.toFloat() - 2, n.height.toFloat() - 2)
            if (n.kind != NodeKind.INK || n.ink.isEmpty()) {
                val layout = layout(n.text.ifEmpty { if(n.kind==NodeKind.REFERENCE) n.sources.firstOrNull()?.fileName.orEmpty() else "" }, n.width)
                text.color = color
                canvas.translate(16f, maxOf(12f, (n.height.toFloat() - layout.height) / 2))
                layout.draw(canvas)
            } else drawInk(canvas, n.ink, color)
            canvas.restore()
            if(n.sources.isNotEmpty()) {
                text.color=color
                canvas.drawText(sourceLabel(n.sources.size),16f,n.height.toFloat()-10f,text)
            }
            if (n.collapsed && n.id in parents) {
                paint.style = Paint.Style.FILL; paint.color = fg
                canvas.drawCircle(n.width.toFloat(), n.height.toFloat(), 12f, paint)
                paint.color = bg; paint.strokeWidth = 2f
                canvas.drawLine(n.width.toFloat() - 6, n.height.toFloat(), n.width.toFloat() + 6, n.height.toFloat(), paint)
                canvas.drawLine(n.width.toFloat(), n.height.toFloat() - 6, n.width.toFloat(), n.height.toFloat() + 6, paint)
            }
            canvas.restore()
        }
    }
    private fun layout(value: String, width: Double): StaticLayout {
        val w = (width - 32).toInt().coerceAtLeast(1)
        if (layouts.size > MindMapDocument.MAX_NODES * 2) layouts.clear()
        return layouts.getOrPut(value to w) {
            StaticLayout.Builder.obtain(value, 0, value.length, text, w).setIncludePad(false).build()
        }
    }
    fun measuredHeight(value: String, width: Double) = maxOf(72.0, layout(value, width).height + 24.0)
    fun drawInk(canvas: Canvas, ink: List<MapInk>, color: Int = Color.BLACK) {
        val renderer = AndroidRenderer(canvas)
        for (s in ink) {
            val stroke = strokes.getOrPut(s) { s.toStroke() }
            stroke.config = stroke.config.copy(rgba = Rgba(Color.red(color), Color.green(color), Color.blue(color), 255))
            stroke.paint(renderer)
        }
    }
    companion object {
        fun bounds(doc: MindMapDocument): Rect = doc.visible().map { it.bounds }.reduce { a, b -> a.union(b) }.outset(32.0)
    }
}

internal fun MapInk.toStroke() = Stroke(Tool.PEN, ToolConfig(baseWidth = width, rgba = Rgba(0, 0, 0, 255)),
    points.map { Sample(it.x, it.y, it.pressure, it.time) })
