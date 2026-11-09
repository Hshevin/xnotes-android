package com.xnotes.mindmap

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.DashPathEffect
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
    private val textMetrics=Paint.FontMetrics().also {text.getFontMetrics(it)}
    private val strokes = IdentityHashMap<MapInk, Stroke>()
    private val layouts = LinkedHashMap<Pair<String, Int>, StaticLayout>(16,.75f,true)
    private val hierarchyPath=Path()
    private val relationDash=DashPathEffect(floatArrayOf(8f,6f),0f)
    private val stableInk=IdentityHashMap<MapInk,Boolean>()
    private val temporaryInk=IdentityHashMap<MapInk,Boolean>()
    private var stableDocument:MindMapDocument?=null
    private var inkColor=Color.BLACK
    private var inkRgba=Rgba(0,0,0,255)
    private data class HierarchyLine(val x1:Double,val y1:Double,val x2:Double,val y2:Double,val bounds:Rect)
    private data class RelationLine(val edge:MapRelation,val geometry:MapRelationGeometry.Line,val bounds:Rect)
    private class Frame(val document:MindMapDocument,val expanded:Set<String>,val nodes:List<MapNode>,val byId:Map<String,MapNode>,
        val counts:Map<String,Int>,val hierarchy:List<HierarchyLine>,val relations:List<RelationLine>)
    private var cachedFrame:Frame?=null
    var sourceLabel: (Int) -> String = { it.toString() }
    var collapsedLabel:(Int)->String={it.toString()}
    private val badgeText=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {textSize=TextSize.label.value}
    private fun badgeBounds(node:MapNode,label:String):Rect {
        val width=(badgeText.measureText(label)+Space.s16.value).toDouble()
        val height=Space.s24.value.toDouble()
        return Rect(node.width-width/2,node.height-height/2,width,height)
    }
    fun drawingBounds(doc:MindMapDocument,expanded:Set<String> = emptySet()):Rect {
        val frame=frame(doc,expanded)
        val counts=frame.counts
        val nodes=frame.nodes.map {node->
            val label=counts[node.id]?.let(collapsedLabel)
            if(label==null) node.bounds else {
                val badge=badgeBounds(node,label)
                node.bounds.union(Rect(node.x+badge.x,node.y+badge.y,badge.w,badge.h))
            }
        }.reduceOrNull {a,b->a.union(b)} ?: Rect(0.0,0.0,1.0,1.0)
        return relationGeometry(doc,expanded).mapNotNull {it.labelBounds}.fold(nodes) {bounds,label->bounds.union(label)}
            .outset(Space.s32.value.toDouble())
    }
    /** Labels are LEFT aligned at the middle's x, with the same 80-character display and baseline. */
    private fun buildRelationGeometry(doc:MindMapDocument,expanded:Set<String>)=MapRelationGeometry.visible(doc,expanded) {edge,anchor->
        val label=edge.label.take(80)
        if(label.isEmpty()) null else text.fontMetrics.let {metrics->
            Rect(anchor.x,anchor.y+metrics.ascent,text.measureText(label).toDouble(),(metrics.descent-metrics.ascent).toDouble())
        }
    }
    internal fun relationGeometry(doc:MindMapDocument,expanded:Set<String> = emptySet())=frame(doc,expanded).relations.map {it.geometry}

    private fun frame(doc:MindMapDocument,expanded:Set<String>):Frame {
        cachedFrame?.takeIf {it.document===doc && it.expanded==expanded}?.let {return it}
        val snapshot=expanded.toSet();val nodes=doc.visible(snapshot);val byId=nodes.associateBy {it.id}
        val hierarchy=nodes.mapNotNull {node->
            val parent=byId[node.parent] ?: return@mapNotNull null
            val right=node.x+node.width/2>parent.x+parent.width/2
            val x1=if(right) parent.x+parent.width else parent.x
            val x2=if(right) node.x else node.x+node.width
            val y1=parent.y+parent.height/2;val y2=node.y+node.height/2
            // Both Bezier control points lie in this endpoint rectangle.
            HierarchyLine(x1,y1,x2,y2,Rect.ltrb(x1,y1,x2,y2))
        }
        val edges=doc.relations.associateBy {it.id}
        val relations=buildRelationGeometry(doc,snapshot).map {line->
            var bounds=Rect.fromPoints(line.start,line.end)
            for(wing in line.wings) bounds=bounds.union(Rect.fromPoints(wing.start,wing.end))
            line.labelBounds?.let {bounds=bounds.union(it)}
            RelationLine(edges.getValue(line.id),line,bounds)
        }
        return Frame(doc,snapshot,nodes,byId,doc.hiddenNodeCounts(snapshot),hierarchy,relations).also {cachedFrame=it}
    }

    /** quickReject includes the current Canvas transform/clip; never prune logical visibility. */
    @Suppress("DEPRECATION")
    private fun outside(canvas:Canvas,left:Double,top:Double,right:Double,bottom:Double):Boolean {
        val margin=Space.s4.value
        return canvas.quickReject((left-margin).toFloat(),(top-margin).toFloat(),(right+margin).toFloat(),(bottom+margin).toFloat(),Canvas.EdgeType.AA)
    }
    private fun outside(canvas:Canvas,bounds:Rect)=outside(canvas,bounds.left,bounds.top,bounds.right,bounds.bottom)
    internal var geometryBuildCount=0; private set
    /** Retain shared immutable strokes; evict only last frame's temporary ink previews. */
    fun retainInk(stable:List<MapInk>) {
        stableDocument=null;stableInk.clear();stable.forEach {stableInk[it]=true}
        strokes.keys.removeAll { !stableInk.containsKey(it) };temporaryInk.clear()
    }
    /** Reuse stable membership, while still dropping the previous frame's temporary live preview. */
    fun retainInk(document:MindMapDocument) {
        if(stableDocument!==document) {
            stableDocument=document;stableInk.clear()
            for(node in document.nodes) for(ink in node.ink) stableInk[ink]=true
            strokes.keys.removeAll { !stableInk.containsKey(it) }
        } else for(ink in temporaryInk.keys) strokes.remove(ink)
        temporaryInk.clear()
    }
    fun clear() {strokes.clear();layouts.clear();stableInk.clear();temporaryInk.clear();stableDocument=null;cachedFrame=null}
    fun draw(canvas: Canvas, doc: MindMapDocument, dark: Boolean = false, selected: String? = null, dropTarget: String? = null, editingText:String? = null,
             connection:Pair<String,com.xnotes.core.geometry.Pt>?=null,selectedRelation:String?=null,selectedNodes:Set<String> = emptySet(),
             expanded:Set<String> = emptySet()) {
        val bg = if (dark) Color.BLACK else Color.WHITE
        val fg = if (dark) Color.WHITE else Color.BLACK
        val frame=frame(doc,expanded)
        val visible=frame.nodes;val byId=frame.byId;val hiddenCounts=frame.counts
        paint.color = fg; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        connection?.let {(id,end)->byId[id]?.bounds?.center?.let {start->
            paint.style=Paint.Style.STROKE;paint.color=fg;paint.strokeWidth=2f
            paint.pathEffect=relationDash
            canvas.drawLine(start.x.toFloat(),start.y.toFloat(),end.x.toFloat(),end.y.toFloat(),paint)
            paint.pathEffect=null
        }}
        for (line in frame.hierarchy) {
            if(outside(canvas,line.bounds)) continue
            val x1=line.x1;val y1=line.y1;val x2=line.x2;val y2=line.y2
            val mid = ((x1 + x2) / 2).toFloat()
            hierarchyPath.reset()
            hierarchyPath.apply {
                moveTo(x1.toFloat(), y1.toFloat())
                cubicTo(mid, y1.toFloat(), mid, y2.toFloat(), x2.toFloat(), y2.toFloat())
            }
            canvas.drawPath(hierarchyPath, paint)
        }
        for (relation in frame.relations) {
            if(outside(canvas,relation.bounds)) continue
            val line=relation.geometry;val edge=relation.edge
            val start=line.start; val end=line.end
            paint.style=Paint.Style.STROKE;paint.color=fg
            paint.strokeWidth=if(line.id==selectedRelation) Space.s4.value else Space.s2.value
            paint.pathEffect=relationDash
            canvas.drawLine(start.x.toFloat(),start.y.toFloat(),end.x.toFloat(),end.y.toFloat(),paint)
            paint.pathEffect=null
            for(wing in line.wings) {
                canvas.drawLine(wing.start.x.toFloat(),wing.start.y.toFloat(),wing.end.x.toFloat(),wing.end.y.toFloat(),paint)
            }
            text.color=fg
            canvas.drawText(edge.label.take(80),line.labelAnchor.x.toFloat(),line.labelAnchor.y.toFloat(),text)
        }
        var inkRenderer:AndroidRenderer?=null
        for (n in visible) {
            val collapsed=hiddenCounts[n.id]?.let(collapsedLabel)
            val badge=collapsed?.let {badgeBounds(n,it)}
            // Source counts paint after the node's inner clip is restored. Localized labels
            // can therefore remain visible when a narrow node body has left the viewport.
            val sources=n.sources.takeIf {it.isNotEmpty()}?.let {sourceLabel(it.size)}
            val sourceRight=sources?.let {n.x+16+text.measureText(it)} ?: Double.NEGATIVE_INFINITY
            val sourceTop=if(sources!=null) n.y+n.height-10+textMetrics.top else Double.POSITIVE_INFINITY
            val sourceBottom=if(sources!=null) n.y+n.height-10+textMetrics.bottom else Double.NEGATIVE_INFINITY
            val right=maxOf(n.x+n.width,badge?.let {n.x+it.right} ?: Double.NEGATIVE_INFINITY,sourceRight)
            val bottom=maxOf(n.y+n.height,badge?.let {n.y+it.bottom} ?: Double.NEGATIVE_INFINITY,sourceBottom)
            val left=minOf(n.x,badge?.let {n.x+it.left} ?: Double.POSITIVE_INFINITY,if(sources!=null) n.x+16 else Double.POSITIVE_INFINITY)
            val top=minOf(n.y,badge?.let {n.y+it.top} ?: Double.POSITIVE_INFINITY,sourceTop)
            if(outside(canvas,left,top,right,bottom)) continue
            val active = n.id == selected || n.id in selectedNodes
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
            } else {
                val renderer=inkRenderer ?: AndroidRenderer(canvas).also {inkRenderer=it}
                drawInk(renderer,n.ink,color)
            }
            canvas.restore()
            if(sources!=null) {
                text.color=color
                canvas.drawText(sources,16f,n.height.toFloat()-10f,text)
            }
            if(collapsed!=null && badge!=null) {
                val label=collapsed;val bounds=badge
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
        val key=value to w
        layouts[key]?.let {return it}
        val layout=StaticLayout.Builder.obtain(value,0,value.length,text,w).setIncludePad(false).build()
        layouts[key]=layout
        if(layouts.size>MindMapDocument.MAX_NODES*2) layouts.entries.iterator().let {it.next();it.remove()}
        return layout
    }
    fun textHeight(value:String,width:Double):Double = layout(value,width).height.toDouble()
    fun measuredHeight(value: String, width: Double) = maxOf(72.0, layout(value, width).height + 24.0)
    fun drawInk(canvas: Canvas, ink: List<MapInk>, color: Int = Color.BLACK) {
        drawInk(AndroidRenderer(canvas),ink,color)
    }
    private fun drawInk(renderer:AndroidRenderer,ink:List<MapInk>,color:Int) {
        if(inkColor!=color) {inkColor=color;inkRgba=Rgba(Color.red(color),Color.green(color),Color.blue(color),255)}
        for (s in ink) {
            val stroke = strokes.getOrPut(s) { geometryBuildCount++; s.toStroke() }
            if(!stableInk.containsKey(s)) temporaryInk[s]=true
            if(stroke.config.rgba!=inkRgba) stroke.config=stroke.config.copy(rgba=inkRgba)
            stroke.paint(renderer)
        }
    }
    companion object {
        fun bounds(doc: MindMapDocument): Rect = MapPainter().drawingBounds(doc)
    }
}

internal fun MapInk.toStroke() = Stroke(Tool.PEN, ToolConfig(baseWidth = width, rgba = Rgba(0, 0, 0, 255)),
    points.map { Sample(it.x, it.y, it.pressure, it.time) })
