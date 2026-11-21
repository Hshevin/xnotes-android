package com.xnotes.map.source

import com.xnotes.map.SourceRef

/** What a file holds, as far as linking cares; read without changing the file. */
data class SourceCatalog(
    val documentId: String,
    val kind: Kind,
    /** The file's display name. */
    val name: String,
    /** Permanent page ids in page order; null for a canvas, or a note without page ids. */
    val pageIds: List<String>?,
) {
    enum class Kind { NOTE, CANVAS, OTHER }
}

/** Where documents are and what they hold. The app's registry and files; a map in tests. */
interface SourceCatalogs {
    /** Every location the registry knows for [documentId], best first; empty when none. */
    fun locations(documentId: String): List<String>

    /** What is at [location] now; null when nothing is there. Throws when it cannot be read. */
    fun read(location: String): SourceCatalog?
}

/** Where a source stands right now. */
sealed interface SourceStatus {
    /** Openable: at [location], named [name], on 1-based [pageNumber] (null for a canvas). */
    data class Ready(val location: String, val name: String, val pageNumber: Int?) : SourceStatus

    /** The document is there but no longer has the page. The link is kept, never re-pointed. */
    data class PageDeleted(val location: String, val name: String) : SourceStatus

    /** No known location holds the document. */
    data object Missing : SourceStatus

    /** A location could not be read, or the document there breaks the page-id contract (no ids, duplicate ids). */
    data object Unavailable : SourceStatus

    /** The document is there but is not what the link expects (a canvas for a page link, or the reverse). */
    data object WrongKind : SourceStatus
}

/**
 * Resolves sources by permanent identity only: the label is never used, and a file at a known
 * location whose document id differs is someone else's and is skipped.
 *
 * Locations are tried in order. The first whose catalog has the link's document id decides the
 * result (Ready, PageDeleted, WrongKind or Unavailable for a broken page-id contract). When none
 * does: Unavailable if any read threw, else Missing. Both page kinds resolve against a note.
 */
class SourceResolver(private val catalogs: SourceCatalogs) {

    fun resolve(ref: SourceRef): SourceStatus = TODO("phase 3 backend")

    /**
     * [resolve] for each of [refs], keyed by [SourceRef.id], reading each location at most once
     * however many refs share its document.
     */
    fun resolveAll(refs: List<SourceRef>): Map<String, SourceStatus> = TODO("phase 3 backend")
}
