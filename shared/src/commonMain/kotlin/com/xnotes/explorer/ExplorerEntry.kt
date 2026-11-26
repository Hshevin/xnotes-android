package com.xnotes.explorer

import com.xnotes.core.model.Rgba

/** File metadata used for ordering and grouping entries on both platforms. Times are epoch milliseconds. */
interface ExplorerEntry {
    val name: String
    val isDir: Boolean
    val size: Long
    val modified: Long
    val created: Long
    val color: Rgba?
}
