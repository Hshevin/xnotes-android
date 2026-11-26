package com.xnotes.explorer

/**
 * Orders explorer entries: folders first when [foldersFirst] (rendered on their own), then files,
 * with the chosen [key]/[descending] sort applied within each group. [createdOf] supplies the
 * creation time, which breaks ties on the modified and size keys. Name is the final ascending tiebreaker.
 */
fun <E : ExplorerEntry> explorerComparator(
    key: ExplorerSortKey,
    descending: Boolean,
    foldersFirst: Boolean = true,
    createdOf: (E) -> Long = { it.created },
): Comparator<E> {
    val base: Comparator<E> = when (key) {
        ExplorerSortKey.NAME -> compareBy { it.name.lowercase() }
        ExplorerSortKey.MODIFIED -> compareBy({ it.modified }, { createdOf(it) })
        ExplorerSortKey.CREATED -> compareBy({ createdOf(it) }, { it.modified })
        ExplorerSortKey.SIZE -> compareBy({ it.size }, { createdOf(it) })
    }
    val within = (if (descending) base.reversed() else base).thenBy { it.name.lowercase() }
    if (!foldersFirst) return within
    return Comparator { a, b ->
        when {
            a.isDir && !b.isDir -> -1
            !a.isDir && b.isDir -> 1
            else -> within.compare(a, b)
        }
    }
}
