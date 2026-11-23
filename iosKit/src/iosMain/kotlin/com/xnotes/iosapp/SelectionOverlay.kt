@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.iosapp

import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.*
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.Tool
import platform.CoreGraphics.*

internal fun ItemSelection.paint(context: CGContextRef, space: InkSpace, tool: Tool, viewport: Rect, pages: Int) {
    val box = frame
    if (box == null && ring.size < 2) return
    val zoom = space.zoom * space.density
    val tolerance = StrokeTessellator.DEFAULT_TOLERANCE / zoom
    fun parts(accent: Rgba) = when {
        ring.size > 1 && tool == Tool.LASSO -> OverlayTessellator.lasso(ring, zoom, accent, tolerance, space.density)
        ring.size > 1 -> OverlayTessellator.band(Rect.fromPoints(ring.first(), ring.last()), zoom, accent, tolerance, space.density)
        box != null -> OverlayTessellator.selection(box, zoom, accent, tolerance, space.density)
        else -> emptyList()
    }
    val primary = space.overlayInk(space.pageAt(box?.center ?: ring.first()) ?: 0)
    val base = parts(primary)
    base.forEach { fillMesh(context, it) }
    val inverse by lazy { parts(if (primary == white) black else white) }
    for (page in 0 until pages) {
        val paper = space.paper(page) ?: continue
        if (!paper.intersects(viewport) || space.overlayInk(page) == primary) continue
        CGContextSaveGState(context)
        CGContextClipToRect(context, CGRectMake(paper.x, paper.y, paper.w, paper.h))
        inverse.forEach { fillMesh(context, it) }
        CGContextRestoreGState(context)
    }
}

private fun fillMesh(context: CGContextRef, part: MeshPart) {
    val positions = part.mesh.positions
    val indices = part.mesh.indices
    val color = part.color
    CGContextSetRGBFillColor(context, color.r / 255.0, color.g / 255.0, color.b / 255.0, color.a / 255.0)
    CGContextBeginPath(context)
    for (index in indices.indices step 3) {
        val first = indices[index] * 2; val second = indices[index + 1] * 2; val third = indices[index + 2] * 2
        CGContextMoveToPoint(context, positions[first], positions[first + 1])
        CGContextAddLineToPoint(context, positions[second], positions[second + 1])
        CGContextAddLineToPoint(context, positions[third], positions[third + 1]); CGContextClosePath(context)
    }
    CGContextFillPath(context)
}
