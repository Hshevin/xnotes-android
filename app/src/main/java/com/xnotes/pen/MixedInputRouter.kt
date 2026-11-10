package com.xnotes.pen

import com.xnotes.ui.Pane

/** Window coordinates belong only to the current composition, never to the retained map owner. */
internal class MixedInputRouter {
    enum class Kind { MAP, NATIVE, HOME }
    data class Target(val pane: Pane, val kind: Kind)
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    }
    private val panes = linkedMapOf<Pane, Pair<Target, Bounds>>()
    private var touching = false
    var touchOwner: Target? = null
        private set
    val hasContact get() = touching

    fun place(target: Target, bounds: Bounds) { panes[target.pane] = target to bounds }
    fun remove(pane: Pane) { panes.remove(pane) }
    fun hit(x: Float, y: Float): Target? = panes.values.firstOrNull { it.second.contains(x, y) }?.first

    /** The first DOWN owns the complete stream, including later pointers and out-of-pane moves. */
    fun begin(x: Float, y: Float): Target? {
        if (!touching) { touchOwner = hit(x, y); touching = true }
        return touchOwner
    }

    fun hover(x: Float, y: Float): Target? = if (touching) touchOwner else hit(x, y)
    fun finish() { touching = false; touchOwner = null }
    fun clear() { finish(); panes.clear() }
}
