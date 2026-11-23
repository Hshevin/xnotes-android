@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.xnotes.platform.ios

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.RasterSurface
import com.xnotes.core.pal.Renderer
import com.xnotes.core.pal.SurfaceFactory
import kotlin.native.ref.createCleaner
import kotlinx.cinterop.*
import platform.CoreGraphics.*
import platform.posix.memcpy

internal val rgbSpace = checkNotNull(CGColorSpaceCreateWithName(kCGColorSpaceSRGB))
private val rgbaLayout = kCGBitmapByteOrder32Big or CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value

internal fun bitmapContext(width: Int, height: Int): CGContextRef = checkNotNull(
    CGBitmapContextCreate(null, width.toULong(), height.toULong(), 8u, width.toULong() * 4u, rgbSpace, rgbaLayout),
)

internal fun Rect.cg() = CGRectMake(left, top, w, h)

internal fun CGContextRef.topLeft(height: Int) {
    CGContextTranslateCTM(this, 0.0, height.toDouble())
    CGContextScaleCTM(this, 1.0, -1.0)
}

internal fun CGContextRef.fillColor(color: Rgba) =
    CGContextSetRGBFillColor(this, color.r / 255.0, color.g / 255.0, color.b / 255.0, color.a / 255.0)

internal fun straight(channel: Int, alpha: Int) = when (alpha) {
    0 -> 0
    255 -> channel
    else -> ((channel * 255 + alpha / 2) / alpha).coerceAtMost(255)
}

internal class OwnedContext(ref: CGContextRef) {
    private class Handle(var ref: CGContextRef?) {
        fun release() {
            ref?.let { CGContextRelease(it) }
            ref = null
        }
    }

    private val handle = Handle(ref)
    private val cleaner = createCleaner(handle) { it.release() }
    val ref get() = checkNotNull(handle.ref) { "Recycled CG surface" }
    val recycled get() = handle.ref == null
    fun close() = handle.release()
}

class CgSurface internal constructor(
    override val width: Int,
    override val height: Int,
    override val devicePixelRatio: Double,
) : RasterSurface {
    private val backing = OwnedContext(bitmapContext(width, height))

    override fun fill(color: Rgba) {
        val context = backing.ref
        CGContextSetBlendMode(context, CGBlendMode.kCGBlendModeCopy)
        context.fillColor(color)
        CGContextFillRect(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
    }

    fun pixel(x: Int, y: Int): Rgba {
        require(x in 0 until width && y in 0 until height)
        val bytes = checkNotNull(CGBitmapContextGetData(backing.ref)).reinterpret<UByteVar>()
        val at = (y.toLong() * width + x) * 4
        val alpha = bytes[at + 3].toInt()
        return Rgba(straight(bytes[at].toInt(), alpha), straight(bytes[at + 1].toInt(), alpha),
            straight(bytes[at + 2].toInt(), alpha), alpha)
    }

    private fun view(): CGContextRef {
        val owner = checkNotNull(CGContextRetain(backing.ref))
        return CGBitmapContextCreateWithData(CGBitmapContextGetData(owner), width.toULong(), height.toULong(),
            8u, width.toULong() * 4u, rgbSpace, rgbaLayout,
            staticCFunction { info, _ -> CGContextRelease(info?.reinterpret()) }, owner) ?: run {
            CGContextRelease(owner)
            error("Cannot create CG bitmap view")
        }
    }

    override fun renderer(): Renderer = CgRenderer(this, OwnedContext(view().also { it.topLeft(height) }))

    internal fun snapshot(copyPixels: Boolean): CGImageRef? {
        if (backing.recycled) return null
        val reader = if (copyPixels) bitmapContext(width, height) else view()
        try {
            if (copyPixels) memcpy(CGBitmapContextGetData(reader), CGBitmapContextGetData(backing.ref), width.toULong() * height.toULong() * 4u)
            return CGBitmapContextCreateImage(reader)
        } finally {
            CGContextRelease(reader)
        }
    }

    override fun recycle() = backing.close()
}

object CgSurfaceFactory : SurfaceFactory {
    override fun create(widthPx: Int, heightPx: Int, devicePixelRatio: Double) =
        CgSurface(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), devicePixelRatio)
}
