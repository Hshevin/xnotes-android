package com.xnotes.zotero

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal inline fun <reified T> JSONObject.field(name: String): T =
    opt(name) as? T ?: throw JSONException("Invalid $name")

internal inline fun <reified T> JSONObject.optional(name: String): T? =
    if (isNull(name)) null else field<T>(name)

internal fun JSONObject.integer(name: String): Long = when (val n = opt(name)) {
    is Int -> n.toLong()
    is Long -> n
    else -> throw JSONException("Invalid $name")
}

internal inline fun <reified T> parseZoteroJson(text: String): T {
    val reader = JSONTokener(text)
    val value = reader.nextValue()
    if (value !is T || reader.nextClean() != '\u0000') throw JSONException("Unexpected JSON")
    return value
}

internal fun <T> JSONArray.mapObjects(parse: (JSONObject) -> T): List<T> =
    List(length()) { parse(getJSONObject(it)) }

internal fun parseZoteroItem(obj: JSONObject): ZoteroItem {
    val key = obj.field<String>("key")
    val data = obj.field<JSONObject>("data")
    val meta = obj.optional<JSONObject>("meta")
    val type = data.field<String>("itemType")
    val filename = data.optional<String>("filename").orEmpty()
    val title = data.optional<String>("title").orEmpty().ifBlank {
        if (type == "attachment") filename.ifBlank { key } else key
    }
    val attachment = if (type == "attachment") ZoteroAttachment(
        key, title, filename, data.optional<String>("contentType").orEmpty(),
        data.field("linkMode"), data.optional<String>("md5")?.lowercase()?.ifBlank { null },
    ) else null
    val creators = data.optional<JSONArray>("creators")?.mapObjects {
        it.optional<String>("creatorType") to
            (it.optional<String>("lastName")?.takeIf(String::isNotBlank) ?: it.optional<String>("name"))
    }.orEmpty()
    val authors = creators.filter { it.first == "author" }
    val year = meta?.optional<String>("parsedDate")?.take(4)?.takeIf { it.matches(Regex("[0-9]{4}")) }
    val count = if (meta?.has("numChildren") == true) meta.integer("numChildren") else 0L
    if (count !in 0..Int.MAX_VALUE.toLong()) throw JSONException("Invalid numChildren")
    return ZoteroItem(key, type, title, authors.ifEmpty { creators }.mapNotNull { it.second },
        year, data.optional<String>("dateAdded").orEmpty(), count.toInt(), attachment)
}
