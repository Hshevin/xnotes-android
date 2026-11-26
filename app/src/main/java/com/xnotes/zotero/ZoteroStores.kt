package com.xnotes.zotero

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** JSON form of [ZoteroConfig]; [decode] rejects anything that is not a version-1 object it wrote. */
object ZoteroConfigCodec {
    const val VERSION = 1
    fun encode(config: ZoteroConfig): String = JSONObject().apply {
        put("version", VERSION)
        put("apiKey", config.apiKey)
        put("userId", config.userId)
        put("username", config.username)
        config.webdav?.let {
            put("webdav", JSONObject().put("url", it.url).put("username", it.username).put("password", it.password))
        }
    }.toString()

    fun decode(text: String): ZoteroConfig {
        val obj = parseZoteroJson<JSONObject>(text)
        require(obj.integer("version") == VERSION.toLong())
        require(obj.keys().asSequence().all { it in setOf("version", "apiKey", "userId", "username", "webdav") })
        val dav = if (obj.has("webdav")) obj.field<JSONObject>("webdav").let {
            require(it.length() == 3)
            ZoteroWebDav(it.field("url"), it.field("username"), it.field("password"))
        } else null
        return ZoteroConfig(obj.field("apiKey"), obj.integer("userId"), obj.field("username"), dav)
    }
}

/** Which note a Zotero PDF became: the note is found again through its document UUID, wherever it was moved. */
data class ZoteroLink(val libraryKey: String, val itemKey: String, val attachmentKey: String, val documentId: String, val md5: String?)

/**
 * The links, in one JSON file. Writes go to a sibling temp file and replace [file] only when complete;
 * a failed write leaves the old file. A file that does not parse is kept untouched (renamed aside to
 * `<name>.corrupt-<millis>`) and the store starts empty.
 */
class ZoteroLinkStore(private val file: File) {
    private var cached: Map<Pair<String, String>, ZoteroLink>? = null

    private fun links(): Map<Pair<String, String>, ZoteroLink> {
        cached?.let { return it }
        val entries = if (Files.notExists(file.toPath())) emptyList() else {
            val text = file.readText()
            try {
                parseZoteroJson<JSONArray>(text).mapObjects {
                    ZoteroLink(it.field("libraryKey"), it.field("itemKey"), it.field("attachmentKey"),
                        it.field("documentId"), it.optional("md5"))
                }
            } catch (_: JSONException) {
                Files.move(file.toPath(), file.toPath().resolveSibling("${file.name}.corrupt-${System.currentTimeMillis()}"))
                emptyList()
            }
        }
        return entries.associateBy { it.libraryKey to it.attachmentKey }.also { cached = it }
    }

    @Synchronized
    fun find(libraryKey: String, attachmentKey: String): ZoteroLink? = links()[libraryKey to attachmentKey]

    /** Replaces any link for the same library + attachment. */
    @Synchronized
    fun put(link: ZoteroLink) {
        save(links() + ((link.libraryKey to link.attachmentKey) to link))
    }

    @Synchronized
    fun remove(libraryKey: String, attachmentKey: String) {
        val key = libraryKey to attachmentKey
        val current = links()
        if (key in current) save(current - key)
    }

    /** Item keys of [libraryKey] with at least one link, to mark papers already opened. */
    @Synchronized
    fun linkedItems(libraryKey: String): Set<String> =
        links().values.filter { it.libraryKey == libraryKey }.mapTo(mutableSetOf()) { it.itemKey }

    private fun save(updated: Map<Pair<String, String>, ZoteroLink>) {
        val json = JSONArray()
        for (link in updated.values) {
            json.put(JSONObject().put("libraryKey", link.libraryKey).put("itemKey", link.itemKey)
                .put("attachmentKey", link.attachmentKey).put("documentId", link.documentId).put("md5", link.md5))
        }
        val target = file.toPath().toAbsolutePath()
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, file.name, ".tmp")
        try {
            temp.toFile().writeText(json.toString())
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            cached = updated
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

