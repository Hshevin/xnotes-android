package com.xnotes.map.source

import com.xnotes.map.SourceRef

/** Enabling permanent page ids on the open note (a byte-preserving file upgrade, then a reopen). */
enum class PageIdEnableResult {
    /** The file now has page ids and the open model was reloaded from it, at the same page. */
    ENABLED,

    /** The note, its source or its owner changed. The old page index cannot be used. Accepted writes remain durable. */
    CHANGED,

    /** It could not be done; the file and the open note are as they were. */
    FAILED,

    /** The upgrade has a retained candidate. Reopening offers explicit continuation of that candidate. */
    RETAINED,
}

/** Following a source from a map. */
sealed interface FollowResult {
    /** The target is open (at the page for a page link) and the way back is remembered. */
    data object Opened : FollowResult

    /** The user backed out of the unsaved-changes prompt. Nothing changed. */
    data object Cancelled : FollowResult

    /** Not opened; [status] says why when it is known (deleted page, missing file, ...). */
    data class Failed(val status: SourceStatus?) : FollowResult
}

/** Coming back to the map after following a source. */
enum class BackResult {
    /** The canvas is open again at the view it was left at; the node is selected if still there. */
    BACK,

    /** The canvas is no longer where it was; the source stays open. */
    CANVAS_GONE,

    /** The user backed out, or something changed meanwhile; the source stays open. */
    STAYED,
}

/**
 * Opens sources from a map and brings the user back. Bound to the window's root editor. One
 * operation at a time: a call while one runs returns Failed(null) / STAYED at once.
 *
 * [follow]: commit the map's title edit, save the canvas through the normal open path (the
 * unsaved-changes prompt where the app shows one), open the target by permanent identity —
 * the page for a page link, verified against the actual file — and remember [from] in the
 * [SourceTrip]. A page that is gone, or a document that cannot be found, is reported, never
 * substituted. The map canvas is not changed by following.
 *
 * [back]: reopen the canvas the trip remembers if the file there is still that canvas, restore
 * its view, select the node when it is still in the map. Opening anything else by the user's own
 * action forgets the trip ([SourceTrip.forget]).
 */
interface MapSourceOpener {
    val trip: SourceTrip
    val busy: Boolean
    suspend fun follow(ref: SourceRef, from: MapReturn): FollowResult
    suspend fun back(): BackResult
}
