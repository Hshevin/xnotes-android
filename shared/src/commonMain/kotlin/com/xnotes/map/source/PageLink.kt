package com.xnotes.map.source

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.map.SourceKind
import com.xnotes.map.SourceRef
import com.xnotes.kio.Uuids
import kotlin.text.CharacterCodingException

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
    fun encode(ref: SourceRef): String = buildString {
        require(isUuid(ref.documentId))
        require(validPage(ref.kind, ref.pageId))
        append(PREFIX).append("v=").append(VERSION).append("&kind=").append(ref.kind.id)
        append("&doc=").append(ref.documentId)
        ref.pageId?.let { append("&page=").append(it) }
        append("&label=")
        for (byte in ref.label.encodeToByteArray()) {
            val value = byte.toInt() and 255
            val char = value.toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._~") append(char)
            else append('%').append("0123456789ABCDEF"[value ushr 4]).append("0123456789ABCDEF"[value and 15])
        }
    }

    /**
     * The source in [text], with [newId] as its id; null unless [text] (surrounding whitespace
     * aside) is exactly one well-formed link: the prefix, `v=1`, a known kind, lowercase canonical
     * UUIDs, a page for page kinds and none for a canvas, each parameter at most once, and
     * percent-encoding that decodes as UTF-8. The label is decoded, has control characters
     * removed and its ends trimmed, and is cut to [MAX_LABEL].
     */
    fun parse(text: String, newId: () -> String): SourceRef? {
        val link = text.trim()
        if (!link.startsWith(PREFIX)) return null
        val query = link.substring(PREFIX.length)
        if (query.any { it.isWhitespace() || it.isISOControl() || it in "#<>\"{}|\\^`" }) return null
        val fields = mutableMapOf<String, String>()
        try {
            for (pair in query.splitToSequence('&')) {
                val separator = pair.indexOf('=')
                if (separator <= 0) return null
                val key = decode(pair.substring(0, separator)) ?: return null
                val value = decode(pair.substring(separator + 1)) ?: return null
                if (fields.put(key, value) != null) return null
            }
        } catch (_: CharacterCodingException) {
            return null
        }
        if (fields["v"] != VERSION.toString()) return null
        val kind = SourceKind.fromId(fields["kind"]) ?: return null
        val doc = fields["doc"] ?: return null
        val page = fields["page"]
        if (!isUuid(doc) || !validPage(kind, page)) return null
        val label = fields["label"].orEmpty().filterNot(Char::isISOControl).trim()
        var end = 0
        repeat(MAX_LABEL) {
            if (end < label.length) {
                val high = label[end++].isHighSurrogate()
                if (high && end < label.length && label[end].isLowSurrogate()) end++
            }
        }
        return SourceRef(newId(), kind, doc, page, label.substring(0, end))
    }

    private fun isUuid(value: String?): Boolean = value != null && value == value.lowercase() && Uuids.isValid(value)

    private fun validPage(kind: SourceKind, page: String?): Boolean =
        if (kind == SourceKind.CANVAS) page == null else isUuid(page)

    // URI checked the escape syntax. Decode the octets strictly, leaving literal '+' alone.
    private fun decode(raw: String): String? {
        val bytes = raw.encodeToByteArray(throwOnInvalidSequence = true)
        var read = 0
        var write = 0
        while (read < bytes.size) {
            if (bytes[read] == '%'.code.toByte()) {
                if (read + 2 >= bytes.size) return null
                val high = bytes[read + 1].toInt().toChar().digitToIntOrNull(16) ?: return null
                val low = bytes[read + 2].toInt().toChar().digitToIntOrNull(16) ?: return null
                bytes[write++] = (high * 16 + low).toByte()
                read += 3
            } else {
                bytes[write++] = bytes[read++]
            }
        }
        return bytes.decodeToString(0, write, throwOnInvalidSequence = true)
    }
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
    fun forPage(doc: Document, page: Page, newId: () -> String): PageLinkOffer {
        if (doc.path.isNullOrBlank()) return PageLinkOffer.Unsaved
        val index = doc.pages.indexOfFirst { it === page }
        if (index < 0) return PageLinkOffer.NotInDocument
        if (!doc.hasReferenceAnchors) return PageLinkOffer.NeedsPageIds
        val pageId = page.referenceIdentity.id ?: return PageLinkOffer.PageNotSaved
        val kind = if (page.pdfPage == null) SourceKind.NOTE_PAGE else SourceKind.PDF_PAGE
        return PageLinkOffer.Ready(SourceRef(newId(), kind, doc.identity.documentId, pageId, "${doc.title} · ${index + 1}"))
    }

    /** A link to the whole canvas [doc] (label: its title); Unsaved when it has no path. */
    fun forCanvas(doc: InfiniteDocument, newId: () -> String): PageLinkOffer {
        if (doc.path.isNullOrBlank()) return PageLinkOffer.Unsaved
        return PageLinkOffer.Ready(SourceRef(newId(), SourceKind.CANVAS, doc.identity.documentId, null, doc.title))
    }
}
