package com.xnotes.pen

import org.json.JSONArray
import org.json.JSONObject

/** New schema intentionally ignores the old pro_pen_bindings preference. */
object PenBindingsJson {
    fun read(value: JSONArray?): List<PenShortcut> = runCatching {
        if (value == null) return emptyList()
        require(value.length() <= PenShortcut.MAX_SHORTCUTS)
        val shortcuts = (0 until value.length()).map { index ->
            val entry = value.getJSONObject(index)
            val triggers = entry.getJSONArray("triggers")
            require(triggers.length() <= PenShortcut.MAX_SIGNALS)
            PenShortcut(entry.getString("name"), (0 until triggers.length()).map { i ->
                val t = triggers.getJSONObject(i)
                PenTrigger(PenTrigger.Kind.valueOf(t.getString("kind")), t.getInt("code"),
                    t.getString("descriptor"), t.getInt("source"), t.optInt("scanCode"))
            }, PenBinding(entry.getString("action"), PenBindingMode.valueOf(entry.getString("mode"))))
        }
        require(PenShortcut.valid(shortcuts))
        shortcuts
    }.getOrDefault(emptyList())

    fun write(shortcuts: List<PenShortcut>): JSONArray {
        require(PenShortcut.valid(shortcuts))
        return JSONArray().apply {
            shortcuts.forEach { s ->
                put(JSONObject().put("name", s.name).put("action", s.binding.action).put("mode", s.binding.mode.name)
                    .put("triggers", JSONArray().apply {
                        s.triggers.forEach { t -> put(JSONObject().put("kind", t.kind.name).put("code", t.code)
                            .put("descriptor", t.descriptor).put("source", t.source).put("scanCode", t.scanCode)) }
                    }))
            }
        }
    }
}
