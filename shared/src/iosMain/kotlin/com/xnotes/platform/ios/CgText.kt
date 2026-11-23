@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.xnotes.platform.ios

import com.xnotes.core.geometry.Rect
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.*
import com.xnotes.core.vector.VectorContour
import com.xnotes.core.vector.VectorSeg
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreText.*
import platform.Foundation.*
import platform.UIKit.*
import kotlin.math.abs

object CgTextMeasurer : TextMeasurer {
    private fun face(spec: FontSpec): UIFont {
        val size = spec.pointSize * 150.0 / 72.0
        val family = when (spec.face) {
            FontFace.MONO -> "Menlo"
            FontFace.SERIF -> "Georgia"
            FontFace.HAND -> "Noteworthy"
            FontFace.SANS -> "Helvetica Neue"
            else -> spec.face.id
        }
        val base = UIFont.fontWithName(family, size) ?: UIFont.systemFontOfSize(size)
        val traits = (if (spec.bold) UIFontDescriptorTraitBold else 0u) or
            (if (spec.italic) UIFontDescriptorTraitItalic else 0u)
        return if (traits == 0u) base else base.fontDescriptor.fontDescriptorWithSymbolicTraits(traits)?.let {
            UIFont.fontWithDescriptor(it, size)
        } ?: base
    }

    private inline fun <T> withSetter(text: String, spec: FontSpec, color: Rgba?, block: (CTTypesetterRef) -> T): T {
        val attrs = mutableMapOf<Any?, Any?>(NSFontAttributeName to face(spec))
        if (color != null) attrs[NSForegroundColorAttributeName] = UIColor.colorWithRed(color.r / 255.0,
            color.g / 255.0, color.b / 255.0, color.a / 255.0)
        val attributed = checkNotNull(CFBridgingRetain(NSAttributedString.create(text, attrs)))
        val setter = try { checkNotNull(CTTypesetterCreateWithAttributedString(attributed.reinterpret())) }
            finally { CFRelease(attributed) }
        return try { block(setter) } finally { CFRelease(setter) }
    }

    private fun lines(text: String, spec: FontSpec, width: Double, wrap: Boolean, color: Rgba? = null,
                      paint: ((CTLineRef, Int) -> Unit)? = null): Int {
        var count = 0
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) { count++; continue }
            withSetter(paragraph, spec, color) { setter ->
                var start = 0L
                while (start < paragraph.length) {
                    val length = if (wrap) CTTypesetterSuggestLineBreak(setter, start, width.coerceAtLeast(1.0)).coerceAtLeast(1L)
                        else paragraph.length - start
                    if (paint != null) {
                        val line = checkNotNull(CTTypesetterCreateLine(setter, CFRangeMake(start, length)))
                        try { paint(line, count) } finally { CFRelease(line) }
                    }
                    count++; start += length
                }
            }
        }
        return count
    }

    override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags) =
        Rect(0.0, 0.0, wrapWidth, lines(text, font, wrapWidth, flags.wordWrap) * lineHeight(font))

    override fun lineHeight(font: FontSpec) = metrics(font).height
    override fun metrics(font: FontSpec) = face(font).let { LineMetrics(it.ascender, -it.descender) }

    override fun advances(text: String, font: FontSpec): DoubleArray {
        if (text.isEmpty()) return DoubleArray(0)
        return withSetter(text, font, null) { setter ->
            val line = checkNotNull(CTTypesetterCreateLine(setter, CFRangeMake(0, text.length.toLong())))
            try {
                var previous = CTLineGetOffsetForStringIndex(line, 0, null)
                DoubleArray(text.length) { index ->
                    val next = CTLineGetOffsetForStringIndex(line, index + 1L, null)
                    abs(next - previous).also { previous = next }
                }
            } finally { CFRelease(line) }
        }
    }

    override fun outline(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags): List<VectorContour> = buildList {
        val metrics = metrics(font)
        lines(text, font, wrapWidth, flags.wordWrap) { line, index ->
            val left = if (flags.alignLeft) 0.0 else (wrapWidth - CTLineGetTypographicBounds(line, null, null, null)) / 2
            val baseline = metrics.ascent + index * metrics.height
            val runs = CTLineGetGlyphRuns(line)
            for (runIndex in 0 until CFArrayGetCount(runs)) memScoped {
                val run: CTRunRef = checkNotNull(CFArrayGetValueAtIndex(runs, runIndex)).reinterpret()
                val face: CTFontRef = checkNotNull(CFDictionaryGetValue(CTRunGetAttributes(run), kCTFontAttributeName)).reinterpret()
                val count = CTRunGetGlyphCount(run)
                val glyphs = allocArray<UShortVar>(count); val positions = allocArray<CGPoint>(count)
                CTRunGetGlyphs(run, CFRangeMake(0, count), glyphs); CTRunGetPositions(run, CFRangeMake(0, count), positions)
                for (at in 0 until count) {
                    val path = CTFontCreatePathForGlyph(face, glyphs[at], null) ?: continue
                    try {
                        var start = Pt(0.0, 0.0); var current = start
                        var segments = mutableListOf<VectorSeg>()
                        fun finish(closed: Boolean) {
                            if (segments.isNotEmpty()) add(VectorContour(start, segments, closed))
                            segments = mutableListOf()
                        }
                        CGPathApplyWithBlock(path) { element ->
                            val part = checkNotNull(element).pointed
                            fun point(i: Int) = part.points!![i].let {
                                Pt(left + positions[at].x + it.x, baseline - positions[at].y - it.y)
                            }
                            when (part.type) {
                                CGPathElementType.kCGPathElementMoveToPoint -> { finish(false); start = point(0); current = start }
                                CGPathElementType.kCGPathElementAddLineToPoint -> { current = point(0); segments += VectorSeg.Line(current) }
                                CGPathElementType.kCGPathElementAddQuadCurveToPoint -> {
                                    val control = point(0); val end = point(1)
                                    segments += VectorSeg.Cubic(Pt((current.x + 2*control.x)/3, (current.y + 2*control.y)/3),
                                        Pt((end.x + 2*control.x)/3, (end.y + 2*control.y)/3), end)
                                    current = end
                                }
                                CGPathElementType.kCGPathElementAddCurveToPoint -> { current = point(2); segments += VectorSeg.Cubic(point(0), point(1), current) }
                                CGPathElementType.kCGPathElementCloseSubpath -> { finish(true); current = start }
                                else -> Unit
                            }
                        }
                        finish(false)
                    } finally { CGPathRelease(path) }
                }
            }
        }
    }

    internal fun draw(context: CGContextRef, text: String, x: Double, baseline: Double, width: Double,
                      font: FontSpec, color: Rgba, flags: TextFlags) {
        val height = lineHeight(font)
        lines(text, font, width, flags.wordWrap, color) { line, index ->
            val left = if (flags.alignLeft) x else x + (width - CTLineGetTypographicBounds(line, null, null, null)) / 2
            CGContextSaveGState(context)
            CGContextTranslateCTM(context, left, baseline + index * height)
            CGContextScaleCTM(context, 1.0, -1.0)
            CGContextSetTextMatrix(context, CGAffineTransformIdentity.readValue())
            CGContextSetTextPosition(context, 0.0, 0.0)
            CTLineDraw(line, context)
            CGContextRestoreGState(context)
        }
    }
}
