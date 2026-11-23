@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.xnotes.iosapp

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.NightInk
import kotlinx.cinterop.*
import platform.CoreGraphics.*
import platform.UIKit.*

internal class LayeredInk(val handles: (Int) -> Boolean, private val publish: (Map<Int, List<Stroke>>) -> Unit) {
    private var hadWet = false
    fun update(gesture: InkGesture?) {
        val ink = mutableMapOf<Int, MutableList<Stroke>>()
        if (gesture?.needsLayerComposition(handles) == true) gesture.preview { index, stroke ->
            if (handles(index)) ink.getOrPut(index, ::mutableListOf).add(stroke)
        }
        if (hadWet || ink.isNotEmpty()) publish(ink)
        hadWet = ink.isNotEmpty()
    }
}

internal class PageWetImage(frame: CValue<CGRect>, private val paper: Rect, private val night: Boolean, private val upper: UIImage) : UIView(frame) {
    var strokes = emptyList<Stroke>()
    init { opaque = false; userInteractionEnabled = false; backgroundColor = UIColor.clearColor }
    override fun drawRect(rect: CValue<CGRect>) {
        val context = UIGraphicsGetCurrentContext() ?: return
        CGContextSaveGState(context); CGContextTranslateCTM(context, -paper.x, -paper.y)
        strokes.forEach { drawWetStroke(context, it, night) }
        CGContextRestoreGState(context); upper.drawInRect(bounds)
    }
}

internal fun drawWetStroke(context: CGContextRef, stroke: Stroke, night: Boolean) {
    val color = NightInk.displayColor(stroke.renderColor, night)
    CGContextSetRGBFillColor(context, color.r / 255.0, color.g / 255.0, color.b / 255.0, color.a / 255.0)
    val geometry = stroke.geometry()
    val path = CGPathCreateMutable() ?: return
    try {
        fun disc(x: Double, y: Double, radius: Double) = CGPathAddEllipseInRect(path, null, CGRectMake(x-radius, y-radius, radius*2, radius*2))
        if (geometry.hasRails) {
            CGPathMoveToPoint(path, null, geometry.leftX(0), geometry.leftY(0))
            for (index in 1 until geometry.pointCount) CGPathAddLineToPoint(path, null, geometry.leftX(index), geometry.leftY(index))
            for (index in geometry.pointCount-1 downTo 0) CGPathAddLineToPoint(path, null, geometry.rightX(index), geometry.rightY(index))
            CGPathCloseSubpath(path)
        }
        if (geometry.pointCount > 0) {
            val end = geometry.pointCount-1
            disc(geometry.cx(0), geometry.cy(0), geometry.hw(0)); disc(geometry.cx(end), geometry.cy(end), geometry.hw(end))
            CGContextAddPath(context, path); CGContextFillPath(context)
        }
    } finally { CGPathRelease(path) }
}
