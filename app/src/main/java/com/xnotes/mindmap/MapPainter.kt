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
import com.xnotes.ui.theme.Space
import com.xnotes.ui.theme.TextSize

/** Shared by the live view, explorer preview and PDF export. All coordinates are document-local. */
class MapPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f }
    private val strokes = IdentityHashMap<MapInk, Stroke>()
    private val layouts = hashMapOf<Pair<String, Int>, StaticLayout>()
    var sourceLabel: (Int) -> String = { it.toString() }
    var collapsedLabel:(Int)->String={it.toString()}
    private val badgeText=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {textSize=TextSize.label.value}
    private fun badgeBounds(node:MapNode,label:String):Rect {
        val width=(badgeText.measureText(label)+Space.s16.value).toDouble()
        val height=Space.s24.value.toDouble()
        return Rect(node.width-width/2,node.height-height/2,width,height)
    }
    fun drawingBounds(doc:MindMapDocument):Rect {
        val counts=doc.hiddenNodeCounts()
        val nodes=doc.visible().map {node->
            val label=counts[node.id]?.let(collapsedLabel)
            if(label==null) node.bounds else {
                val badge=badgeBounds(node,label)
                node.bounds.union(Rect(node.x+badge.x,node.y+badge.y,badge.w,badge.h))
            }
        }.reduce {a,b->a.union(b)}
        return relationGeometry(doc).mapNotNull {it.labelBounds}.fold(nodes) {bounds,label->bounds.union(label)}
            .outset(Space.s32.value.toDouble())
    }
    /** Labels are LEFT aligned at the middle's x, with the same 80-character display and baseline. */
    internal fun relationGeometry(doc:MindMapDocument)=MapRelationGeometry.visible(doc) {edge,anchor->
        val label=edge.label.take(80)
        if(label.isEmpty()) null else text.fontMetrics.let {metrics->
            Rect(anchor.x,anchor.y+metrics.ascent,text.measureText(label).toDouble(),(metrics.descent-metrics.ascent).toDouble())
        }
    }
    internal var geometryBuildCount=0; private set
    /** Retain shared immutable strokes; evict only last frame's temporary ink previews. */
    fun retainInk(stable:List<MapInk>) {
        val keep=IdentityHashMap<MapInk,Boolean>()
        stable.forEach {keep[it]=true}
        strokes.keys.removeAll { !keep.containsKey(it) }
    }
    fun clear() { strokes.clear(); layouts.clear() }
    fun draw(canvas: Canvas, doc: MindMapDocument, dark: Boolean = false, selected: String? = null, dropTarget: String? = null, editingText:String? = null,
             connection:Pair<String,com.xnotes.core.geometry.Pt>?=null,selectedRelation:String?=null) {
        val bg = if (dark) Color.BLACK else Color.WHITE
        val fg = if (dark) Color.WHITE else Color.BLACK
        val visible = doc.visible()
        val byId = visible.associateBy { it.id }
        val hiddenCounts=doc.hiddenNodeCounts()
        paint.color = fg; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        connection?.let {(id,end)->byId[id]?.bounds?.center?.let {start->
            paint.style=Paint.Style.STROKE;paint.color=fg;paint.strokeWidth=2f
            paint.pathEffect=android.graphics.DashPathEffect(floatArrayOf(8f,6f),0f)
            canvas.drawLine(start.x.toFloat(),start.y.toFloat(),end.x.toFloat(),end.y.toFloat(),paint)
            paint.pathEffect=null
        }}
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
        val edges=doc.relations.associateBy {it.id}
        for (line in relationGeometry(doc)) {
            val edge=edges.getValue(line.id)
            val start=line.start; val end=line.end
            paint.style=Paint.Style.STROKE;paint.color=fg
            paint.strokeWidth=if(line.id==selectedRelation) Space.s4.value else Space.s2.value
            paint.pathEffect=android.graphics.DashPathEffect(floatArrayOf(8f,6f),0f)
            canvas.drawLine(start.x.toFloat(),start.y.toFloat(),end.x.toFloat(),end.y.toFloat(),paint)
            paint.pathEffect=null
            for(wing in line.wings) {
                canvas.drawLine(wing.start.x.toFloat(),wing.start.y.toFloat(),wing.end.x.toFloat(),wing.end.y.toFloat(),paint)
            }
            text.color=fg
            canvas.drawText(edge.label.take(80),line.labelAnchor.x.toFloat(),line.labelAnchor.y.toFloat(),text)
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
            if (n.id==editingText) {
                // The native text overlay paints this node's draft, including any overflow.
            } else if (n.kind != NodeKind.INK || n.ink.isEmpty()) {
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
            hiddenCounts[n.id]?.let {count->
                val label=collapsedLabel(count)
                val bounds=badgeBounds(n,label)
                paint.style = Paint.Style.FILL; paint.color = fg
                canvas.drawRoundRect(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat(),Space.s4.value,Space.s4.value,paint)
                badgeText.color=bg
                val baseline=n.height.toFloat()-(badgeText.ascent()+badgeText.descent())/2
                canvas.drawText(label,n.width.toFloat()-badgeText.measureText(label)/2,baseline,badgeText)
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
    fun textHeight(value:String,width:Double):Double = layout(value,width).height.toDouble()
    fun measuredHeight(value: String, width: Double) = maxOf(72.0, layout(value, width).height + 24.0)
    fun drawInk(canvas: Canvas, ink: List<MapInk>, color: Int = Color.BLACK) {
        val renderer = AndroidRenderer(canvas)
        for (s in ink) {
            val stroke = strokes.getOrPut(s) { geometryBuildCount++; s.toStroke() }
            stroke.config = stroke.config.copy(rgba = Rgba(Color.red(color), Color.green(color), Color.blue(color), 255))
            stroke.paint(renderer)
        }
    }
    companion object {
        fun bounds(doc: MindMapDocument): Rect = MapPainter().drawingBounds(doc)
    }
}

internal fun MapInk.toStroke() = Stroke(Tool.PEN, ToolConfig(baseWidth = width, rgba = Rgba(0, 0, 0, 255)),
    points.map { Sample(it.x, it.y, it.pressure, it.time) })
