package com.xnotes.zotero

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/*
 * Reading a Zotero library (Zotero Web API v3) so a paper's PDF opens as an annotated note.
 * Only an explicit action in the Zotero pane talks to the network. The API key goes only to
 * [ZoteroClient.API]; WebDAV credentials go only to the user's WebDAV host; a file redirect from
 * the API is followed without the API key.
 */

/** One GET. Header names in [ZoteroResponse.headers] are lower-case. Never follows redirects itself. */
fun interface ZoteroHttp {
    fun get(url: String, headers: Map<String, String>): ZoteroResponse
}

class ZoteroResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: InputStream,
    val close: () -> Unit = {},
) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
}

/** Why a Zotero action failed; the UI turns each into its own message. */
enum class ZoteroFailure {
    /** The key is wrong, revoked, or lacks library read access (403 on /keys/current or the library). */
    BAD_KEY,
    /** The key may read the library but not its files. */
    NO_FILE_ACCESS,
    /** The attachment is a linked file or link: it lives only on the computer that added it. */
    NOT_STORED,
    /** Zotero storage has no file for this attachment (404 on /file) and no WebDAV is configured. */
    FILE_MISSING,
    /** WebDAV refused the username or password (401/403). */
    WEBDAV_AUTH,
    /** WebDAV has no `<KEY>.zip` (404), or the zip holds no file. */
    WEBDAV_MISSING,
    /** The downloaded bytes do not match the attachment's md5. */
    CHECKSUM,
    /** 429, or a 503 with Retry-After / Backoff. */
    RATE_LIMITED,
    /** Could not connect, timed out, or the connection broke. */
    NETWORK,
    /** Any other unexpected status or an unreadable response. */
    SERVER,
    CANCELLED,
}

class ZoteroException(val failure: ZoteroFailure, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: failure.name, cause)

data class ZoteroAccount(val userId: Long, val username: String, val libraryAccess: Boolean, val fileAccess: Boolean)

data class ZoteroLibrary(val group: Boolean, val id: Long, val name: String) {
    /** Stable key for links and remembered choices: `u<id>` or `g<id>`. */
    val key: String get() = (if (group) "g" else "u") + id
    /** API path prefix: `users/<id>` or `groups/<id>`. */
    val path: String get() = (if (group) "groups/" else "users/") + id
}

data class ZoteroCollection(val key: String, val name: String, val parentKey: String?)

data class ZoteroAttachment(
    val key: String,
    val title: String,
    val filename: String,
    val contentType: String,
    /** `imported_file`, `imported_url`, `linked_file`, `linked_url`. */
    val linkMode: String,
    /** Lower-case hex, or null when Zotero has none. */
    val md5: String?,
) {
    /** Only stored files can be fetched; linked files never left the computer that added them. */
    val stored: Boolean get() = linkMode == "imported_file" || linkMode == "imported_url"
    val isPdf: Boolean get() = contentType == "application/pdf"
}

data class ZoteroItem(
    val key: String,
    val itemType: String,
    /** `data.title`; for an attachment with no title, its filename; never blank (falls back to the key). */
    val title: String,
    /** Last names (or single-field `name`) of creators whose type is `author`, else of all creators, in order. */
    val creators: List<String>,
    /** The first four characters of `meta.parsedDate` when they are a year, else null. */
    val year: String?,
    /** `data.dateAdded` as given. */
    val dateAdded: String,
    /** `meta.numChildren`, 0 when absent. */
    val childCount: Int,
    /** Set when the top-level item is itself an attachment (a standalone PDF). */
    val attachment: ZoteroAttachment?,
)

data class ZoteroPage(val items: List<ZoteroItem>, val total: Int, val nextStart: Int?)

/** A user's WebDAV file sync. [url] is what Zotero's settings show; files live under `<url>/zotero/`. */
data class ZoteroWebDav(val url: String, val username: String, val password: String)

data class ZoteroConfig(
    val apiKey: String,
    val userId: Long,
    val username: String,
    val webdav: ZoteroWebDav? = null,
)

class ZoteroClient(private val http: ZoteroHttp, private val apiKey: String) {
    companion object {
        const val API = "https://api.zotero.org"
        const val PAGE = 50
    }

    /** GET /keys/current. 403 or 404 → [ZoteroFailure.BAD_KEY]. Access is read from `access.user.library` / `access.user.files`. */
    fun account(): ZoteroAccount = TODO()

    /** The user's own library (named [username]) first, then their groups (GET /users/<id>/groups, all pages) by name. */
    fun libraries(userId: Long, username: String): List<ZoteroLibrary> = TODO()

    /** Every collection of [library] (all pages), sorted by name; [ZoteroCollection.parentKey] null for top level. */
    fun collections(library: ZoteroLibrary): List<ZoteroCollection> = TODO()

    /**
     * One page of top-level, non-note items, newest added first: `<path>/items/top` or
     * `<path>/collections/<key>/items/top`, with `sort=dateAdded&direction=desc&itemType=-note`,
     * `start`/`limit`, and `q`/`qmode=titleCreatorYear` when [query] is not blank.
     * [ZoteroPage.total] is the `Total-Results` header; [ZoteroPage.nextStart] null on the last page.
     */
    fun items(library: ZoteroLibrary, collection: String?, query: String?, start: Int, limit: Int = PAGE): ZoteroPage = TODO()

    /** The PDFs of [item]: itself when it is a PDF attachment, else its PDF children (`<path>/items/<key>/children`) in listed order. */
    fun pdfs(library: ZoteroLibrary, item: ZoteroItem): List<ZoteroAttachment> = TODO()

    /**
     * Writes the file of [attachment] to [sink]. Not [ZoteroAttachment.stored] → [ZoteroFailure.NOT_STORED],
     * without any request. With [webdav]: GET `<url>/zotero/<KEY>.zip` (one `/` between parts) with Basic
     * auth, then the first non-directory entry of the zip. Else GET `<path>/items/<key>/file`; a 3xx is
     * followed (at most 3 hops, https only) to its Location without the API key; 404 →
     * [ZoteroFailure.FILE_MISSING]. A known md5 must match the bytes written, else [ZoteroFailure.CHECKSUM].
     * [isCancelled] is checked between buffers → [ZoteroFailure.CANCELLED]. Every response is closed.
     */
    fun download(library: ZoteroLibrary, attachment: ZoteroAttachment, webdav: ZoteroWebDav?, sink: OutputStream, isCancelled: () -> Boolean = { false }): Unit = TODO()
}

/** JSON form of [ZoteroConfig]; [decode] rejects anything that is not a version-1 object it wrote. */
object ZoteroConfigCodec {
    const val VERSION = 1
    fun encode(config: ZoteroConfig): String = TODO()
    fun decode(text: String): ZoteroConfig = TODO()
}

/** Which note a Zotero PDF became: the note is found again through its document UUID, wherever it was moved. */
data class ZoteroLink(val libraryKey: String, val itemKey: String, val attachmentKey: String, val documentId: String, val md5: String?)

/**
 * The links, in one JSON file. Writes go to a sibling temp file and replace [file] only when complete;
 * a failed write leaves the old file. A file that does not parse is kept untouched (renamed aside to
 * `<name>.corrupt-<millis>`) and the store starts empty.
 */
class ZoteroLinkStore(private val file: File) {
    fun find(libraryKey: String, attachmentKey: String): ZoteroLink? = TODO()
    /** Replaces any link for the same library + attachment. */
    fun put(link: ZoteroLink): Unit = TODO()
    fun remove(libraryKey: String, attachmentKey: String): Unit = TODO()
    /** Item keys of [libraryKey] with at least one link, to mark papers already opened. */
    fun linkedItems(libraryKey: String): Set<String> = TODO()
}

/** A note name for [item]: its title with `/\:*?"<>|` and control characters replaced by a space, runs of spaces collapsed, trimmed, at most 120 characters; [fallback] when nothing is left. */
fun zoteroNoteName(item: ZoteroItem, fallback: String): String = TODO()

/** [ZoteroHttp] over HttpURLConnection: GET, `instanceFollowRedirects = false`, 20 s connect / 60 s read timeouts; IOException → [ZoteroFailure.NETWORK]. */
class UrlConnectionZoteroHttp : ZoteroHttp {
    override fun get(url: String, headers: Map<String, String>): ZoteroResponse = TODO()
}

/** [ZoteroConfig] encrypted with an AndroidKeyStore AES/GCM key (alias `zotero`) in `noBackupFilesDir/zotero.config`, written atomically, like `AiConfigStore`. */
class ZoteroConfigStore(context: android.content.Context) {
    /** Null when nothing is saved or the file cannot be decrypted or decoded (that file is left in place). */
    fun load(): ZoteroConfig? = TODO()
    fun save(config: ZoteroConfig): Unit = TODO()
    fun clear(): Unit = TODO()
}
