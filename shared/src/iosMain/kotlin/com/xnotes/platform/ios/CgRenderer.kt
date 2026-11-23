@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.platform.ios

import com.xnotes.core.geometry.*
import com.xnotes.core.model.ImageCrop
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.*
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import kotlinx.cinterop.*
import platform.CoreGraphics.*
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

internal class CgRenderer(private val surface: CgSurface, private val bitmap: OwnedContext, private val nightInk: Boolean) : Renderer {
    override fun inkColor(color: Rgba) = NightInk.displayColor(color, nightInk)
    private val cg get() = bitmap.ref
    private val layers = ArrayDeque<Boolean>()
    private val tangents = DoubleArray(4)
    private val scaleX get() = CGContextGetCTM(cg).useContents { hypot(a, b) }
    private val scaleY get() = CGContextGetCTM(cg).useContents { hypot(c, d) }
    private val averageScale get() = ((scaleX + scaleY) * 0.5).coerceAtLeast(1e-4)

    override fun save() {
        CGContextSaveGState(cg)
        layers.addLast(false)
    }

    override fun restore() {
        if (layers.removeLast()) CGContextEndTransparencyLayer(cg)
        CGContextRestoreGState(cg)
    }

    override fun saveLayerAlpha(bounds: Rect, alpha: Double) = saveLayerBlended(bounds, alpha, BlendMode.SRC_OVER)

    override fun saveLayerBlended(bounds: Rect, alpha: Double, blend: BlendMode) {
        CGContextSaveGState(cg)
        clipRect(bounds)
        CGContextSetAlpha(cg, alpha.coerceIn(0.0, 1.0))
        CGContextSetBlendMode(cg, blend.native())
        CGContextBeginTransparencyLayerWithRect(cg, bounds.cg(), null)
        layers.addLast(true)
    }

    override fun translate(dx: Double, dy: Double) = CGContextTranslateCTM(cg, dx, dy)
    override fun scale(sx: Double, sy: Double) = CGContextScaleCTM(cg, sx, sy)
    override fun rotate(degrees: Double) = CGContextRotateCTM(cg, degrees * PI / 180.0)
    override fun clipRect(rect: Rect) = CGContextClipToRect(cg, rect.cg())

    override fun clipPolygon(points: List<Pt>) {
        if (points.size < 3) return
        polygon(points, true)
        CGContextClip(cg)
    }

    override fun clear() = CGContextClearRect(cg, CGContextGetClipBoundingBox(cg))
    override fun fillBackground(rect: Rect, color: Rgba) = fillRect(rect, color)
    override fun fillRect(rect: Rect, color: Rgba) {
        cg.fillColor(inkColor(color))
        CGContextFillRect(cg, rect.cg())
    }

    private fun polygon(points: List<Pt>, closed: Boolean) {
        CGContextBeginPath(cg)
        CGContextMoveToPoint(cg, points.first().x, points.first().y)
        for (index in 1 until points.size) CGContextAddLineToPoint(cg, points[index].x, points[index].y)
        if (closed) CGContextClosePath(cg)
    }

    private fun contours(contours: List<VectorContour>) {
        CGContextBeginPath(cg)
        for (contour in contours) {
            CGContextMoveToPoint(cg, contour.start.x, contour.start.y)
            for (seg in contour.segments) when (seg) {
                is VectorSeg.Line -> CGContextAddLineToPoint(cg, seg.end.x, seg.end.y)
                is VectorSeg.Cubic -> CGContextAddCurveToPoint(cg, seg.c1.x, seg.c1.y, seg.c2.x, seg.c2.y, seg.end.x, seg.end.y)
            }
            if (contour.closed) CGContextClosePath(cg)
        }
    }

    override fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule) {
        if (points.size < 3) return
        polygon(points, true)
        fill(color, rule)
    }

    override fun fillPath(contours: List<VectorContour>, color: Rgba, rule: FillRule) {
        contours(contours)
        fill(color, rule)
    }

    private fun fill(color: Rgba, rule: FillRule = FillRule.NONZERO) {
        cg.fillColor(inkColor(color))
        CGContextDrawPath(cg, rule.native())
    }

    override fun fillCircle(center: Pt, radius: Double, color: Rgba) = fillEllipse(center, radius, radius, color)
    override fun fillEllipse(center: Pt, rx: Double, ry: Double, color: Rgba) {
        cg.fillColor(inkColor(color))
        CGContextFillEllipseInRect(cg, oval(center, rx, ry))
    }

    override fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, from: Int, count: Int, color: Rgba) {
        if (count < 1) return
        CGContextBeginPath(cg)
        fun disc(index: Int) {
            val radius = radii[index].toDouble()
            if (radius <= 0.0) return
            val cx = centers[index * 2].toDouble()
            val cy = centers[index * 2 + 1].toDouble()
            CGContextMoveToPoint(cg, cx + radius, cy)
            CGContextAddArc(cg, cx, cy, radius, 0.0, 2 * PI, 0)
            CGContextClosePath(cg)
        }
        if (count == 1) disc(from)
        for (index in from until from + count - 1) {
            val firstX = centers[index * 2].toDouble()
            val firstY = centers[index * 2 + 1].toDouble()
            val nextX = centers[index * 2 + 2].toDouble()
            val nextY = centers[index * 2 + 3].toDouble()
            val first = radii[index].toDouble()
            val next = radii[index + 1].toDouble()
            if (Geometry.capsule(firstX, firstY, first, nextX, nextY, next, tangents) == Geometry.SWALLOWED) {
                disc(if (first >= next) index else index + 1)
                continue
            }
            val start = atan2(tangents[1], tangents[0])
            val end = atan2(tangents[3], tangents[2])
            CGContextMoveToPoint(cg, firstX + first * tangents[0], firstY + first * tangents[1])
            CGContextAddLineToPoint(cg, nextX + next * tangents[0], nextY + next * tangents[1])
            if (next > 0) CGContextAddArc(cg, nextX, nextY, next, start, end, 0)
            CGContextAddLineToPoint(cg, firstX + first * tangents[2], firstY + first * tangents[3])
            if (first > 0) CGContextAddArc(cg, firstX, firstY, first, end, start, 0)
            CGContextClosePath(cg)
        }
        fill(color)
    }

    override fun strokePolyline(points: List<Pt>, pen: Pen) {
        if (points.size < 2) return
        polygon(points, false)
        stroke(pen)
    }

    override fun strokePolyline(pts: FloatArray, from: Int, count: Int, pen: Pen) {
        if (count < 2) return
        CGContextBeginPath(cg)
        CGContextMoveToPoint(cg, pts[2 * from].toDouble(), pts[2 * from + 1].toDouble())
        for (index in from + 1 until from + count) CGContextAddLineToPoint(cg, pts[2 * index].toDouble(), pts[2 * index + 1].toDouble())
        stroke(pen)
    }

    override fun strokePolygon(points: List<Pt>, pen: Pen) {
        if (points.size < 2) return
        polygon(points, true)
        stroke(pen)
    }

    override fun strokeRect(rect: Rect, pen: Pen) {
        CGContextBeginPath(cg)
        CGContextAddRect(cg, rect.cg())
        stroke(pen)
    }

    override fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: Pen) {
        CGContextBeginPath(cg)
        CGContextAddEllipseInRect(cg, oval(center, rx, ry))
        stroke(pen)
    }

    override fun strokePath(contours: List<VectorContour>, pen: Pen) {
        contours(contours)
        stroke(pen)
    }

    private fun stroke(pen: Pen) {
        val unit = if (pen.cosmetic) 1.0 / averageScale else 1.0
        CGContextSetLineWidth(cg, pen.width * unit)
        CGContextSetLineCap(cg, CGLineCap.kCGLineCapRound)
        CGContextSetLineJoin(cg, CGLineJoin.kCGLineJoinRound)
        if (pen.dashed) {
            doubleArrayOf(max(0.1, pen.dashOn * unit), max(0.1, pen.dashGap * unit)).usePinned {
                CGContextSetLineDash(cg, pen.dashPhase * unit, it.addressOf(0), 2u)
            }
        } else CGContextSetLineDash(cg, 0.0, null, 0u)
        if (pen.glowRadius > 0.0) {
            CGContextReplacePathWithStrokedPath(cg)
            cg.glowPath(inkColor(pen.color), FillRule.NONZERO, pen.glowRadius, false, scaleX, scaleY)
        } else {
            val color = inkColor(pen.color)
            CGContextSetRGBStrokeColor(cg, color.r / 255.0, color.g / 255.0, color.b / 255.0, color.a / 255.0)
            CGContextStrokePath(cg)
        }
    }

    override fun fillPolygonGlow(pts: FloatArray, color: Rgba, rule: FillRule, blurRadius: Double, inner: Boolean) {
        if (pts.size < 6) return
        CGContextBeginPath(cg)
        CGContextMoveToPoint(cg, pts[0].toDouble(), pts[1].toDouble())
        for (index in 1 until pts.size / 2) CGContextAddLineToPoint(cg, pts[2 * index].toDouble(), pts[2 * index + 1].toDouble())
        CGContextClosePath(cg)
        cg.glowPath(inkColor(color), rule, blurRadius, inner, scaleX, scaleY)
    }

    override fun fillCircleGlow(center: Pt, radius: Double, color: Rgba, blurRadius: Double, inner: Boolean) {
        CGContextBeginPath(cg)
        CGContextAddEllipseInRect(cg, oval(center, radius, radius))
        cg.glowPath(inkColor(color), FillRule.NONZERO, blurRadius, inner, scaleX, scaleY)
    }

    override fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect?) =
        drawRasterBlended(raster, dest, 1.0, BlendMode.SRC_OVER, src)

    override fun drawRasterBlended(raster: RasterSurface, dest: Rect, alpha: Double, blend: BlendMode, src: Rect?) {
        val image = (raster as? CgSurface)?.snapshot(copyPixels = raster === surface) ?: return
        try {
            cg.saved {
                CGContextSetAlpha(cg, alpha.coerceIn(0.0, 1.0))
                CGContextSetBlendMode(cg, blend.native())
                if (src == null) cg.blit(image, dest) else {
                    val crop = CGRectMake(src.left.toInt().toDouble(), src.top.toInt().toDouble(),
                        (src.right.toInt() - src.left.toInt()).toDouble(), (src.bottom.toInt() - src.top.toInt()).toDouble())
                    CGImageCreateWithImageInRect(image, crop)?.let {
                        try { cg.blit(it, dest) } finally { CGImageRelease(it) }
                    }
                }
            }
        } finally { CGImageRelease(image) }
    }

    override fun drawImage(image: ImageData, dest: Rect, orientation: Int, angle: Double, flip: Flip, part: Rect?) {
        if (dest.w <= 0.0 || dest.h <= 0.0) return
        val crop = part?.takeIf { it.w > 0.0 && it.h > 0.0 && !ImageCrop.isWhole(it) }
        val quarter = ((orientation % 360) + 360) % 360
        val turned = quarter == 90 || quarter == 270
        val width = (if (turned) dest.h else dest.w) / (crop?.w ?: 1.0)
        val height = (if (turned) dest.w else dest.h) / (crop?.h ?: 1.0)
        val bitmap = decodeImage(image, width * (if (turned) scaleY else scaleX), height * (if (turned) scaleX else scaleY)) ?: return
        try {
            cg.saved {
                translate(dest.centerX, dest.centerY)
                CGContextRotateCTM(cg, angle)
                if (crop != null) clipRect(Rect(-dest.w / 2, -dest.h / 2, dest.w, dest.h))
                scale(if (flip.x) -1.0 else 1.0, if (flip.y) -1.0 else 1.0)
                rotate(quarter.toDouble())
                cg.blit(bitmap, Rect(-(crop?.centerX ?: 0.5) * width, -(crop?.centerY ?: 0.5) * height, width, height))
            }
        } finally { CGImageRelease(bitmap) }
    }

    override fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags) = withSave {
        clipRect(rect)
        val inset = if (flags.alignTop) 0.0 else (rect.h - CgTextMeasurer.measure(text, font, rect.w, flags).h) / 2
        CgTextMeasurer.draw(cg, text, rect.x, rect.y + inset + CgTextMeasurer.metrics(font).ascent, rect.w, font, inkColor(color), flags)
    }

    override fun drawTextTurned(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags, angle: Double) = withSave {
        translate(rect.centerX, rect.centerY)
        CGContextRotateCTM(cg, angle)
        translate(-rect.centerX, -rect.centerY)
        drawText(text, rect, font, color, flags)
    }

    override fun drawTextRun(text: String, x: Double, baseline: Double, font: FontSpec, color: Rgba) =
        CgTextMeasurer.draw(cg, text, x, baseline, 0.0, font, inkColor(color), TextFlags(wordWrap = false))

    override fun drawMath(latex: String, x: Double, baseline: Double, sizePt: Double, color: Rgba, display: Boolean): Boolean {
        drawTextRun(latex, x, baseline, FontSpec(sizePt), color)
        return true
    }

    override fun drawMathPending(latex: String, sizePt: Double, display: Boolean, box: Rect, color: Rgba) =
        drawText(latex, box, FontSpec(sizePt), color, TextFlags())
}

internal inline fun CGContextRef.saved(block: () -> Unit) {
    CGContextSaveGState(this)
    try { block() } finally { CGContextRestoreGState(this) }
}

internal fun CGContextRef.blit(image: CGImageRef, dest: Rect) = saved {
    CGContextTranslateCTM(this, dest.x, dest.bottom)
    CGContextScaleCTM(this, 1.0, -1.0)
    CGContextSetInterpolationQuality(this, kCGInterpolationMedium)
    CGContextDrawImage(this, CGRectMake(0.0, 0.0, dest.w, dest.h), image)
}

internal fun FillRule.native() = when (this) {
    FillRule.EVEN_ODD -> CGPathDrawingMode.kCGPathEOFill
    FillRule.NONZERO -> CGPathDrawingMode.kCGPathFill
}

private fun BlendMode.native() = when (this) {
    BlendMode.SRC_OVER -> CGBlendMode.kCGBlendModeNormal
    BlendMode.MULTIPLY -> CGBlendMode.kCGBlendModeMultiply
    BlendMode.SCREEN -> CGBlendMode.kCGBlendModeScreen
}

private fun oval(center: Pt, rx: Double, ry: Double) = CGRectMake(center.x - rx, center.y - ry, rx * 2, ry * 2)
