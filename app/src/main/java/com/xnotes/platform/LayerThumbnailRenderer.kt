package com.xnotes.platform

import android.graphics.Bitmap
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem

/** Transparent, bounded-size layer contents only: no paper, PDF or other layers. */
object LayerThumbnailRenderer {
    fun render(items: List<CanvasItem>, frame: Rect, side: Int = 128, active: () -> Boolean = { true }): Bitmap? {
        require(side in 1..256)
        val surface = AndroidRasterSurface.create(side, side)
        try {
            val scale = minOf(side / frame.w, side / frame.h)
            val r = surface.renderer()
            r.translate((side - frame.w * scale) / 2, (side - frame.h * scale) / 2)
            r.scale(scale, scale)
            r.translate(-frame.x, -frame.y)
            r.clipRect(frame)
            for (item in items) {
                if (!active()) { surface.recycle(); return null }
                item.paint(r)
            }
            if (!active()) { surface.recycle(); return null }
            return surface.bitmap
        } catch (error: Throwable) {
            surface.recycle()
            throw error
        }
    }
}
