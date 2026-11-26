package com.xnotes.zotero

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

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
    fun account(): ZoteroAccount = api("keys/current") { body, _ ->
        val obj = parseZoteroJson<JSONObject>(body)
        val access = obj.field<JSONObject>("access").optional<JSONObject>("user")
        ZoteroAccount(obj.integer("userID"), obj.field("username"),
            access?.optional<Boolean>("library") ?: false, access?.optional<Boolean>("files") ?: false)
    }

    /** The user's own library (named [username]) first, then their groups (GET /users/<id>/groups, all pages) by name. */
    fun libraries(userId: Long, username: String): List<ZoteroLibrary> {
        val groups = all("users/$userId/groups") {
            ZoteroLibrary(true, it.integer("id"), it.field<JSONObject>("data").field("name"))
        }
        return listOf(ZoteroLibrary(false, userId, username)) + groups.sortedBy { it.name }
    }

    /** Every collection of [library] (all pages), sorted by name; [ZoteroCollection.parentKey] null for top level. */
    fun collections(library: ZoteroLibrary): List<ZoteroCollection> = all("${library.path}/collections") {
        val data = it.field<JSONObject>("data")
        val parent = when (val value = data.opt("parentCollection")) {
            false, null, JSONObject.NULL -> null
            is String -> value
            else -> throw JSONException("Invalid parentCollection")
        }
        ZoteroCollection(it.field("key"), data.field("name"), parent)
    }.sortedBy { it.name }

    /**
     * One page of top-level, non-note items, newest added first: `<path>/items/top` or
     * `<path>/collections/<key>/items/top`, with `sort=dateAdded&direction=desc&itemType=-note`,
     * `start`/`limit`, and `q`/`qmode=titleCreatorYear` when [query] is not blank.
     * [ZoteroPage.total] is the `Total-Results` header; [ZoteroPage.nextStart] null on the last page.
     */
    fun items(library: ZoteroLibrary, collection: String?, query: String?, start: Int, limit: Int = PAGE): ZoteroPage {
        require(start >= 0 && limit in 1..100)
        val path = buildString {
            append(library.path)
            if (collection != null) append("/collections/${encoded(collection)}")
            append("/items/top?sort=dateAdded&direction=desc&itemType=-note&start=$start&limit=$limit")
            if (!query.isNullOrBlank()) append("&q=${encoded(query)}&qmode=titleCreatorYear")
        }
        return api(path) { body, response ->
            val entries = parseZoteroJson<JSONArray>(body).mapObjects(::parseZoteroItem)
            val total = response.total() ?: Math.addExact(start, entries.size)
            val next = start.toLong() + limit
            ZoteroPage(entries, total, next.takeIf { it < total }?.toInt())
        }
    }

    /** The PDFs of [item]: itself when it is a PDF attachment, else its PDF children (`<path>/items/<key>/children`) in listed order. */
    fun pdfs(library: ZoteroLibrary, item: ZoteroItem): List<ZoteroAttachment> {
        if (item.attachment?.isPdf == true) return listOf(item.attachment)
        return all("${library.path}/items/${encoded(item.key)}/children", ::parseZoteroItem)
            .mapNotNull { it.attachment?.takeIf(ZoteroAttachment::isPdf) }
    }

    /**
     * Writes the file of [attachment] to [sink]. Not [ZoteroAttachment.stored] → [ZoteroFailure.NOT_STORED],
     * without any request. With [webdav]: GET `<url>/zotero/<KEY>.zip` (one `/` between parts) with Basic
     * auth, then the first non-directory entry of the zip. Else GET `<path>/items/<key>/file`; a 3xx is
     * followed (at most 3 hops, https only) to its Location without the API key; 404 →
     * [ZoteroFailure.FILE_MISSING]. A known md5 must match the bytes written, else [ZoteroFailure.CHECKSUM].
     * [isCancelled] is checked between buffers → [ZoteroFailure.CANCELLED]. Every response is closed.
     */
    fun download(library: ZoteroLibrary, attachment: ZoteroAttachment, webdav: ZoteroWebDav?, sink: OutputStream, isCancelled: () -> Boolean = { false }) {
        if (!attachment.stored) fail(ZoteroFailure.NOT_STORED)
        val key = encoded(attachment.key)
        var url: String
        var headers: Map<String, String>
        if (webdav == null) {
            url = "$API/${library.path}/items/$key/file"
            headers = apiHeaders
        } else {
            url = "${webdav.url.trimEnd('/')}/zotero/$key.zip"
            val credentials = Base64.getEncoder().encodeToString("${webdav.username}:${webdav.password}".toByteArray())
            headers = mapOf("Authorization" to "Basic $credentials")
        }
        for (hop in 0..3) {
            if (isCancelled()) fail(ZoteroFailure.CANCELLED)
            val redirect = request(url, headers) { response ->
                if (response.status in 300..399) {
                    if (hop == 3) fail(ZoteroFailure.SERVER)
                    val location = response.header("location")?.takeIf { it.isNotBlank() } ?: fail(ZoteroFailure.SERVER)
                    val target = try { URI(url).resolve(URI(location)) } catch (_: URISyntaxException) { fail(ZoteroFailure.SERVER) }
                    if (!target.scheme.equals("https", true) || target.host.isNullOrBlank()) fail(ZoteroFailure.SERVER)
                    return@request target.toString()
                }
                if (hop == 0) when (response.status) {
                    401 -> if (webdav != null) fail(ZoteroFailure.WEBDAV_AUTH)
                    403 -> fail(if (webdav != null) ZoteroFailure.WEBDAV_AUTH else ZoteroFailure.NO_FILE_ACCESS)
                    404 -> fail(if (webdav != null) ZoteroFailure.WEBDAV_MISSING else ZoteroFailure.FILE_MISSING)
                }
                response.checkSuccess()
                if (webdav == null) copy(response.body, sink, attachment.md5, isCancelled)
                else ZipInputStream(response.body).use { zip ->
                    while (true) {
                        if (isCancelled()) fail(ZoteroFailure.CANCELLED)
                        val entry = zip.nextEntry ?: fail(ZoteroFailure.WEBDAV_MISSING)
                        if (!entry.isDirectory) break
                    }
                    copy(zip, sink, attachment.md5, isCancelled)
                }
                null
            } ?: return
            url = redirect
            headers = emptyMap()
        }
    }

    private val apiHeaders = mapOf("Zotero-API-Version" to "3", "Zotero-API-Key" to apiKey)

    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun <T> request(url: String, headers: Map<String, String>, read: (ZoteroResponse) -> T): T {
        try {
            val response = http.get(url, headers)
            try { return read(response) } finally { response.close() }
        } catch (e: IOException) {
            throw ZoteroException(ZoteroFailure.NETWORK, cause = e)
        }
    }

    private fun <T> api(path: String, parse: (String, ZoteroResponse) -> T): T =
        request("$API/$path", apiHeaders) { response ->
            if (response.status == 403 || (response.status == 404 && path == "keys/current")) fail(ZoteroFailure.BAD_KEY)
            response.checkSuccess()
            try {
                parse(response.body.bufferedReader().readText(), response)
            } catch (e: JSONException) {
                throw ZoteroException(ZoteroFailure.SERVER, cause = e)
            }
        }

    private fun <T> all(path: String, parse: (JSONObject) -> T): List<T> = buildList {
        while (true) {
            val (entries, total) = api("$path?start=$size&limit=100") { body, response ->
                parseZoteroJson<JSONArray>(body).mapObjects(parse) to response.total()
            }
            addAll(entries)
            if (entries.isEmpty() || (total?.let { size >= it } ?: (entries.size < 100))) break
        }
    }

    private fun ZoteroResponse.total(): Int? = header("total-results")?.let {
        it.toIntOrNull()?.takeIf { count -> count >= 0 } ?: fail(ZoteroFailure.SERVER)
    }

    private fun ZoteroResponse.checkSuccess() {
        if (status == 429 || status == 503) fail(ZoteroFailure.RATE_LIMITED)
        if (status !in 200..299) fail(ZoteroFailure.SERVER)
    }

    private fun copy(input: InputStream, sink: OutputStream, md5: String?, isCancelled: () -> Boolean) {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            if (isCancelled()) fail(ZoteroFailure.CANCELLED)
            val length = input.read(buffer)
            if (length == -1) break
            digest.update(buffer, 0, length)
            sink.write(buffer, 0, length)
        }
        if (md5 != null && digest.digest().joinToString("") { "%02x".format(it) } != md5) fail(ZoteroFailure.CHECKSUM)
    }

    private fun fail(reason: ZoteroFailure): Nothing = throw ZoteroException(reason)
}

/** A note name for [item]: its title with `/\:*?"<>|` and control characters replaced by a space, runs of spaces collapsed, trimmed, at most 120 characters; [fallback] when nothing is left. */
fun zoteroNoteName(item: ZoteroItem, fallback: String): String =
    item.title.replace(unsafeNameChars, " ").trim().take(120).trimEnd().ifBlank { fallback }

private val unsafeNameChars = Regex("""[/\\:*?"<>|\p{Cc} ]+""")
