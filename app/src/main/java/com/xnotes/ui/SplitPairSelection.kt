package com.xnotes.ui

import com.xnotes.core.util.DocumentKind

/** The explorer keeps the user's selection order when offering its two-file open action. */
internal fun splitPairSelection(entries:Collection<BrowseEntry>,allowNativePairs:Boolean=true,allowMapPairs:Boolean=true):List<String>? {
    val files=entries.filterNot {it.isDir}
    val kinds=files.map {DocumentKind.ofName(it.name)}
    return files.map {it.documentUri}.distinct().takeIf {uris->
        uris.size==2 && files.size==entries.size &&
            kinds.all {it==DocumentKind.NOTE || it==DocumentKind.MIND_MAP} &&
            kinds.count {it==DocumentKind.MIND_MAP}<=1 &&
            (if(DocumentKind.MIND_MAP in kinds) allowMapPairs else allowNativePairs)
    }
}
