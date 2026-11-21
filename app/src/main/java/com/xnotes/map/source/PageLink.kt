package com.xnotes.map.source

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.map.SourceKind
import com.xnotes.map.SourceRef

/**
 * The text a "Copy page link" puts on the clipboard, and its reading back when pasted into a map.
 *
 * Form: `xnotes://link?v=1&kind=<SourceKind.id>&doc=<uuid>&page=<uuid>&label=<percent-encoded>`.
 * `page` is absent for [SourceKind.CANVAS]. Parameters may come in any order; unknown parameters
 * are ignored so a later version can add some; any other version is refused.
 *
 * [encode] writes the parameters in the order above and percent-encodes the label's UTF-8 bytes,
 * leaving only `A–Z a–z 0–9 - . _ ~` as they are (a space is `%20`; uppercase hex). [parse] reads
 * `+` as a plain plus, not a space. An absent label reads as "".
 */
object PageLink {
    const val PREFIX = "xnotes://link?"
    const val VERSION = 1

    /** Longest label kept; a longer one is cut (on a code-point boundary). */
    const val MAX_LABEL = 200

    /** The link text for [ref]. Its [SourceRef.id] is not part of the link. */
    fun encode(ref: SourceRef): String = TODO("phase 3 backend")

    /**
     * The source in [text], with [newId] as its id; null unless [text] (surrounding whitespace
     * aside) is exactly one well-formed link: the prefix, `v=1`, a known kind, lowercase canonical
     * UUIDs, a page for page kinds and none for a canvas, each parameter at most once, and
     * percent-encoding that decodes as UTF-8. The label is decoded, has control characters
     * removed and its ends trimmed, and is cut to [MAX_LABEL].
     */
    fun parse(text: String, newId: () -> String): SourceRef? = TODO("phase 3 backend")
}

/** Whether the page a menu was opened on can be linked now. */
sealed interface PageLinkOffer {
    data class Ready(val ref: SourceRef) : PageLinkOffer

    /** The document has never been saved, so it has no file to point at yet. */
    data object Unsaved : PageLinkOffer

    /** The document has permanent page ids but this page gets its id at the next save. */
    data object PageNotSaved : PageLinkOffer

    /** The document has no permanent page ids; they must be enabled first (a file upgrade). */
    data object NeedsPageIds : PageLinkOffer

    /** The page is not in the document. */
    data object NotInDocument : PageLinkOffer
}

/** Links to what is open: the in-memory model is read only, never changed. */
object SourceLinks {
    /**
     * A link to [page] of [doc]: [SourceKind.PDF_PAGE] when the page shows a PDF page, else
     * [SourceKind.NOTE_PAGE]. Checks run in the order of [PageLinkOffer]'s cases after Ready:
     * no path → Unsaved; page absent → NotInDocument; no page ids → NeedsPageIds; page without
     * an id → PageNotSaved. The label is "<title> · <1-based page number>".
     */
    fun forPage(doc: Document, page: Page, newId: () -> String): PageLinkOffer = TODO("phase 3 backend")

    /** A link to the whole canvas [doc] (label: its title); Unsaved when it has no path. */
    fun forCanvas(doc: InfiniteDocument, newId: () -> String): PageLinkOffer = TODO("phase 3 backend")
}
