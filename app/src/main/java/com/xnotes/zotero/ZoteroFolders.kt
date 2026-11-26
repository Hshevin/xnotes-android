package com.xnotes.zotero

/*
 * Where an imported paper lands below the workspace's `Zotero` folder: the path of the Zotero
 * collection it was opened from, so the notes mirror the library. A note is placed once, when it is
 * made; later changes in Zotero never move it (it is found again by its document UUID).
 */

private val unsafeNameChars = Regex("""[/\\:*?"<>|\p{Cc} ]+""")

/** At most [maxBytes] of UTF-8, cut on a character boundary. */
private fun String.takeUtf8(maxBytes: Int): String {
    var bytes = 0
    var end = 0
    while (end < length) {
        val cp = codePointAt(end)
        val size = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
        if (bytes + size > maxBytes) break
        bytes += size
        end += Character.charCount(cp)
    }
    return substring(0, end)
}

/** [raw] with `/\:*?"<>|`, control characters and space runs turned into one space, without leading or trailing dots or spaces, at most [maxBytes] of UTF-8; blank stays blank. */
private fun cleanName(raw: String, maxBytes: Int): String {
    fun tidy(s: String) = s.trim().trim('.').trim()
    return tidy(tidy(raw.replace(unsafeNameChars, " ")).takeUtf8(maxBytes))
}

/** Most UTF-8 bytes in a note's name before its extension, inside the 255-byte limit of common file systems. */
const val ZOTERO_NOTE_NAME_BYTES = 180

/** Most UTF-8 bytes in one folder name. */
const val ZOTERO_FOLDER_NAME_BYTES = 120

/** Deepest collection path kept; deeper ones keep the first `ZOTERO_MAX_DEPTH - 1` levels and the leaf. */
const val ZOTERO_MAX_DEPTH = 8

/** A note name for [item]: its title made safe (see [cleanName]), at most [ZOTERO_NOTE_NAME_BYTES] of UTF-8; [fallback] when nothing is left. */
fun zoteroNoteName(item: ZoteroItem, fallback: String): String =
    cleanName(item.title, ZOTERO_NOTE_NAME_BYTES).ifBlank { fallback }

/** A folder name for a collection or group called [name]; never starts with a dot, so it is never hidden. [fallback] when nothing is left. */
fun zoteroFolderName(name: String, fallback: String): String =
    cleanName(name, ZOTERO_FOLDER_NAME_BYTES).ifBlank { fallback }

/**
 * The folders below `Zotero/` for a note of [item] from [library], given that library's [collections].
 * A group library starts with its own folder; My Library starts at `Zotero/` itself. Opened from
 * collection [openedFrom] (still in [collections]) → that collection's path. Otherwise the item's own
 * collections that still exist: none → the library's root; one → its path; several → the path that
 * sorts first (then by key), so the choice is the same every time.
 */
fun zoteroFolderPath(library: ZoteroLibrary, collections: List<ZoteroCollection>, item: ZoteroItem, openedFrom: String?): List<String> {
    val byKey = collections.associateBy { it.key }
    fun chain(key: String): List<String> {
        val names = ArrayList<String>()
        val seen = HashSet<String>()
        var at = byKey[key]
        while (at != null && seen.add(at.key)) {
            names += zoteroFolderName(at.name, "collection_${at.key}")
            at = at.parentKey?.let(byKey::get)
        }
        names.reverse()
        return if (names.size <= ZOTERO_MAX_DEPTH) names else names.take(ZOTERO_MAX_DEPTH - 1) + names.last()
    }
    val root = if (library.group) listOf(zoteroFolderName(library.name, "group_${library.id}")) else emptyList()
    val target = openedFrom?.takeIf(byKey::containsKey)
        ?: item.collections.filter(byKey::containsKey).distinct()
            .minWithOrNull(compareBy<String>({ chain(it).joinToString("/") }, { it }))
    return root + (target?.let(::chain) ?: emptyList())
}
