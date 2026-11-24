package com.xnotes.settings

import com.xnotes.explorer.ExplorerLayout
import com.xnotes.explorer.ExplorerView
import com.xnotes.explorer.FolderPlacement
import com.xnotes.explorer.ThumbShape
import com.xnotes.explorer.TileSize
import org.json.JSONObject

typealias ExplorerSortKey = com.xnotes.explorer.ExplorerSortKey
typealias GroupBy = com.xnotes.explorer.GroupBy

internal fun ExplorerView.toJson(): JSONObject = JSONObject()
    .put("layout", layout.id)
    .put("tile_size", tileSize.id)
    .put("thumb", thumb.id)
    .put("show_kind", showKind)
    .put("show_pages", showPages)
    .put("show_time", showTime)
    .put("show_size", showSize)
    .put("show_colour", showColour)
    .put("group_by", groupBy.id)
    .put("sort_key", sortKey.id)
    .put("descending", descending)
    .put("folders", folders.id)
    .put("compact_rows", compactRows)
    .put("timeline_by_created", timelineByCreated)

internal fun ExplorerView.Companion.fromJson(o: JSONObject?): ExplorerView {
    if (o == null) return ExplorerView()
    return ExplorerView(
        layout = ExplorerLayout.fromId(o.optString("layout")) ?: ExplorerLayout.GRID,
        tileSize = TileSize.fromId(o.optString("tile_size")),
        thumb = ThumbShape.fromId(o.optString("thumb")),
        showKind = o.optBoolean("show_kind", true),
        showPages = o.optBoolean("show_pages", true),
        showTime = o.optBoolean("show_time", true),
        showSize = o.optBoolean("show_size", false),
        showColour = o.optBoolean("show_colour", true),
        groupBy = GroupBy.fromId(o.optString("group_by")),
        sortKey = ExplorerSortKey.fromId(o.optString("sort_key", "modified")),
        descending = o.optBoolean("descending", true),
        folders = FolderPlacement.fromId(o.optString("folders")),
        compactRows = o.optBoolean("compact_rows", false),
        timelineByCreated = o.optBoolean("timeline_by_created", true),
    )
}

/** A note or canvas opened on this device, newest first in [Settings.recentDocs]. */
data class RecentDoc(val uri: String, val name: String, val opened: Long, val documentId: String = "")
