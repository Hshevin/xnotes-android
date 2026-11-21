package com.xnotes.map.source

import com.xnotes.map.NodeId

/**
 * Where to come back to after following a source: the canvas (by identity and location), the node
 * whose chip was tapped, and the view as it was. Held in memory for the session only.
 */
data class MapReturn(
    val canvasDocumentId: String,
    val canvasLocation: String,
    val node: NodeId,
    val viewX: Double,
    val viewY: Double,
    val zoom: Double,
)

/** What returning finds. */
sealed interface ReturnCheck {
    /** Open the canvas at [to]'s location and restore its view; the UI selects the node if it is still there. */
    data class Go(val to: MapReturn) : ReturnCheck

    /** The canvas is gone from its location (moved, deleted or replaced by another document). */
    data object CanvasGone : ReturnCheck
}

/**
 * One "back to map" bookmark at a time: following a source from a map replaces it, and it is
 * dropped once used or when the user opens anything else themselves.
 */
class SourceTrip {
    @Volatile
    var pending: MapReturn? = null
        private set

    /** Following a source from a map. */
    @Synchronized
    fun leave(from: MapReturn): Unit { pending = from }

    /** The user opened something else themselves: the way back is no longer offered. */
    @Synchronized
    fun forget(): Unit { pending = null }

    /**
     * Returning: checks the file still at [MapReturn.canvasLocation] is the same canvas
     * ([canvasIdAt] reads its document id; null for nothing there; a throw counts as gone).
     * Consumes the bookmark either way; null when there is none.
     */
    fun back(canvasIdAt: (String) -> String?): ReturnCheck? {
        val to = synchronized(this) { pending.also { pending = null } } ?: return null
        val id = runCatching { canvasIdAt(to.canvasLocation) }.getOrNull()
        return if (id == to.canvasDocumentId) ReturnCheck.Go(to) else ReturnCheck.CanvasGone
    }
}
