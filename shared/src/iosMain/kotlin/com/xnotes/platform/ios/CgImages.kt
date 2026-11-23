@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.platform.ios

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FillRule
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreImage.*
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSURL
import platform.ImageIO.*
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val MAX_EDGE = 4096
private val blurContext by lazy { CIContext.contextWithOptions(null) }

internal fun decodeImage(image: ImageData, width: Double, height: Double): CGImageRef? {
    if (image.width <= 0 || image.height <= 0) return null
    val scale = min(width / image.width, height / image.height).coerceAtMost(1.0)
    val edge = ceil(max(image.width, image.height) * scale).toInt().coerceIn(1, MAX_EDGE)
    val url = checkNotNull(CFBridgingRetain(NSURL.fileURLWithPath(image.file.toString())))
    val source = try { CGImageSourceCreateWithURL(url.reinterpret(), null) } finally { CFRelease(url) } ?: return null
    try {
        val options = checkNotNull(CFDictionaryCreateMutable(null, 2, null, null))
        try {
            val size = checkNotNull(CFNumberCreate(null, kCFNumberIntType, cValuesOf(edge)))
            try {
                CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
                CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, size)
                return CGImageSourceCreateThumbnailAtIndex(source, 0u, options)
            } finally { CFRelease(size) }
        } finally { CFRelease(options) }
    } finally { CFRelease(source) }
}

private fun CValue<CGRect>.rect() = useContents { Rect(origin.x, origin.y, size.width, size.height) }

internal fun CGContextRef.glowPath(color: Rgba, rule: FillRule, radius: Double, inner: Boolean, sx: Double, sy: Double) {
    if (radius <= 0.0) {
        fillColor(color)
        CGContextDrawPath(this, rule.native())
        return
    }
    val path = CGContextCopyPath(this) ?: return
    CGContextBeginPath(this)
    try {
        val sigma = radius * 0.57735 + 0.5
        val pad = 3 * sigma + 1
        val bounds = CGPathGetBoundingBox(path).rect().outset(pad)
        val visible = CGContextGetClipBoundingBox(this).rect().outset(pad)
        val left = max(bounds.left, visible.left)
        val top = max(bounds.top, visible.top)
        val width = min(bounds.right, visible.right) - left
        val height = min(bounds.bottom, visible.bottom) - top
        if (width <= 0 || height <= 0) return
        val density = min((sx + sy) * 0.5, MAX_EDGE / max(width, height))
        if (density <= 0.0) return
        val pixelsWide = ceil(width * density).toInt().coerceAtLeast(1)
        val pixelsHigh = ceil(height * density).toInt().coerceAtLeast(1)
        val mask = bitmapContext(pixelsWide, pixelsHigh)
        val bitmap = try {
            mask.topLeft(pixelsHigh)
            CGContextScaleCTM(mask, density, density)
            CGContextTranslateCTM(mask, -left, -top)
            CGContextAddPath(mask, path)
            mask.fillColor(color)
            CGContextDrawPath(mask, rule.native())
            checkNotNull(CGBitmapContextCreateImage(mask))
        } finally { CGContextRelease(mask) }
        try {
            val input = CIImage.imageWithCGImage(bitmap)
            val output = input.imageByApplyingGaussianBlurWithSigma(sigma * density)
            val blurred = checkNotNull(blurContext.createCGImage(output, input.extent))
            try {
                saved {
                    if (inner) {
                        CGContextAddPath(this, path)
                        if (rule == FillRule.EVEN_ODD) CGContextEOClip(this) else CGContextClip(this)
                    }
                    blit(blurred, Rect(left, top, pixelsWide / density, pixelsHigh / density))
                }
            } finally { CGImageRelease(blurred) }
        } finally { CGImageRelease(bitmap) }
    } finally { CGPathRelease(path) }
}
