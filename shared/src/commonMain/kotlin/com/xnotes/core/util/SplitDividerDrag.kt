package com.xnotes.core.util

import kotlin.math.abs

/** Accumulate the unsnapped position so the center magnet can be escaped without a jump. */
class SplitDividerDrag(start: Float) {
    private var raw = start.coerceIn(0.18f, 0.82f)
    private var snapped = abs(raw - 0.5f) < 0.0001f
    fun move(delta: Float): Float {
        raw = (raw + delta).coerceIn(0.18f, 0.82f)
        snapped = abs(raw - 0.5f) <= if (snapped) 0.06f else 0.035f
        return if (snapped) 0.5f else raw
    }
}

/** A title must be released inside the opposite pane, along either split orientation. */
fun isOppositeSplitPane(sourceFirst: Boolean, axis: Float, cross: Float,
    extent: Float, crossExtent: Float, ratio: Float): Boolean =
    extent > 0f && crossExtent > 0f && cross >= 0f && cross < crossExtent &&
        axis >= 0f && axis < extent && ((axis >= extent * ratio) == sourceFirst)
