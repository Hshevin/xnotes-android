package com.xnotes.core.model

import com.xnotes.core.shapes.Elbow
import com.xnotes.core.text.FlowTable
import com.xnotes.core.text.TextFlow
import com.xnotes.core.vector.VectorContour
import java.util.IdentityHashMap

/** Detached serializable fields, over the same immutable encoded assets and stroke sample prefixes.
 * Owner-thread only. These copies are writer views, never live editor or renderer objects. */
internal fun List<CanvasItem>.fixedWriteCopies(allowText:Boolean):MutableList<CanvasItem> {
    val copies=IdentityHashMap<CanvasItem,CanvasItem>(size)
    val result=mapTo(ArrayList<CanvasItem>(size)) {item->
        val copy=when(item) {
            is Stroke -> item.copyForWrite()
            is ImageItem -> ImageItem(item.image,item.rect,item.orientation,item.angle,item.flip,item.part)
            is AudioItem -> item.audio.let {a->
                AudioItem(AudioData(a.file,a.ext,a.durationMs,a.peaks.copyOf()),item.rect,item.name,item.rid,item.measurer)
            }
            is TextItem -> {require(allowText) {"Canvas writer does not support text items"};item.copyForWrite()}
            is TapeItem -> TapeItem(item.shape.let {it.copy(points=it.points.toList())},item.rgba,item.pattern)
            is ShapeItem -> ShapeItem(item.shape,item.start,item.end,item.strokeRgba,item.strokeWidth,item.fillRgba,
                item.neon,item.neonStrength,item.path?.map {VectorContour(it.start,it.segments.toList(),it.closed)},
                item.dashed,item.dashLength,item.dashGap,item.rotation,item.adj.copyOf(),item.n,item.headStart,item.headEnd).also {
                it.flip=item.flip;it.label=item.label;it.labelAt=item.labelAt
            }
            is ConnectorItem -> ConnectorItem(item.routing,item.start,item.end,item.strokeRgba,item.strokeWidth,
                item.headStart,item.headEnd,item.dashed,item.dashLength,item.dashGap,item.neon,item.neonStrength).also {
                it.waypoints=item.waypoints.toList();it.elbow=Elbow(item.elbow.first,item.elbow.coords.copyOf())
                it.label=item.label;it.labelAt=item.labelAt
            }
            else -> throw IllegalArgumentException("Unsupported writer object")
        }
        copy.layerId=item.layerId;copy.locked=item.locked;copy.group=item.group;copy.sync=item.sync
        copies[item]=copy
        copy
    }
    // GlueIds uses a page-local (or one canvas) scope. Preserve only joins that its writer encodes.
    fun remap(end:ConnectorEnd)=end.target?.let {copies[it]?.let {copy->ConnectorEnd(end.at,copy,end.point)}} ?: end.loose()
    for(item in result) if(item is ConnectorItem) {item.start=remap(item.start);item.end=remap(item.end)}
    return result
}

internal fun PageStyle.fixedWriteCopy()=copy(params=params?.toMap(),colors=colors?.toMap())
internal fun TextMarkup.fixedWriteCopy()=TextMarkup(id,type,color,intensity,quads.toList(),text,note,created,modified)

internal fun TextFlow.fixedWriteCopy():TextFlow=deepCopy().also {copy->
    val tables=IdentityHashMap<FlowTable,Unit>()
    for(p in copy.paragraphs) p.table?.let {table->if(tables.put(table,Unit)==null) {
        table.widths=table.widths.toList();table.minHeights=table.minHeights.toList()
    }}
}
