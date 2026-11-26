package com.xnotes.storage.library

import com.xnotes.core.model.Rgba
import com.xnotes.format.JsonPull
import com.xnotes.format.JsonWrite
import kotlin.time.Instant

/** The version-1 JSON documents written by Android's Editor. */
internal object SidecarJson {
    private fun encode(fields: JsonWrite.() -> Unit) = buildString {
        JsonWrite(this).apply { beginObject().name("version").value(1); fields(); endObject() }
    }

    private fun decode(text: String, field: JsonPull.(String) -> Unit) {
        val reader = JsonPull(text, strict = true)
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (key == "version") require(reader.nextInt() == 1) else reader.field(key)
        }
        reader.endObject()
        require(reader.peek() == JsonPull.Token.END_DOCUMENT)
    }

    private fun stringMap(text: String, key: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        decode(text) { field ->
            if (field != key) skipValue() else {
                beginObject()
                while (hasNext()) {
                    val name = nextName()
                    if (peek() == JsonPull.Token.STRING) result[name] = nextString() else skipValue()
                }
                endObject()
            }
        }
        return result
    }

    private fun mapJson(key: String, values: Map<String, String>) = encode {
        name(key).beginObject()
        for ((name, value) in values) name(name).value(value)
        endObject()
    }

    fun colors(text: String): Map<String, Rgba> = stringMap(text, "colors").mapNotNull { (name, hex) ->
        Rgba.fromHex(hex)?.let { name to it }
    }.toMap()

    fun colors(values: Map<String, Rgba>) = mapJson("colors", values.mapValues { Rgba.toHex(it.value) })

    fun names(text: String): Map<Rgba, String> = stringMap(text, "names").mapNotNull { (hex, name) ->
        val value = name.trim()
        Rgba.fromHex(hex)?.takeIf { value.isNotEmpty() }?.let { it to value }
    }.toMap()

    fun names(values: Map<Rgba, String>) = mapJson("names", values.mapKeys { Rgba.toHex(it.key) })

    private fun JsonPull.strings() = buildList {
        beginArray()
        while (hasNext()) add(nextString())
        endArray()
    }

    private fun JsonWrite.strings(key: String, values: List<String>) {
        name(key).beginArray()
        values.forEach { value(it) }
        endArray()
    }

    fun pins(text: String): List<String> {
        var pins = emptyList<String>()
        decode(text) { if (it == "pins") pins = strings() else skipValue() }
        return pins
    }

    fun pins(values: List<String>) = encode { strings("pins", values) }

    fun trash(item: TrashItem) = encode {
        name("name").value(item.path.name)
        strings("path", item.originalFolder)
        name("deleted").value(Instant.fromEpochMilliseconds(item.deleted).toString())
        item.color?.let { name("colour").value(Rgba.toHex(it)) }
    }

    /** As on Android, the actual payload supplies the name; the wrapper timestamp is the fallback. */
    fun trash(text: String, fallback: TrashItem): TrashItem {
        var item = fallback
        decode(text) { field ->
            when (field) {
                "path" -> item = item.copy(originalFolder = strings())
                "deleted" -> Instant.parseOrNull(nextString())?.let { item = item.copy(deleted = it.toEpochMilliseconds()) }
                "colour" -> item = item.copy(color = Rgba.fromHex(nextString()))
                else -> skipValue()
            }
        }
        return item
    }
}
