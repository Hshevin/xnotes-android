package com.xnotes.ui

import com.xnotes.explorer.ExplorerSortKey

internal fun explorerComparator(
    key: ExplorerSortKey,
    descending: Boolean,
    foldersFirst: Boolean = true,
    createdOf: (BrowseEntry) -> Long,
): Comparator<BrowseEntry> = com.xnotes.explorer.explorerComparator(key, descending, foldersFirst, createdOf)
