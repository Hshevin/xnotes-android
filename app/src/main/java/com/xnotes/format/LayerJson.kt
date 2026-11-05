package com.xnotes.format

import com.xnotes.core.model.DrawingLayer
import com.xnotes.core.model.DrawingLayers

/** Additive metadata: legacy files with no entry retain the default layer. */
internal object LayerJson {
    fun write(j: JsonWrite, layers: DrawingLayers) {
        if (layers.entries == listOf(DrawingLayer(DrawingLayers.DEFAULT)) && layers.activeId == DrawingLayers.DEFAULT) return
        j.name("layers").beginObject()
        j.name("active").value(layers.activeId)
        j.name("entries").beginArray()
        for (layer in layers.entries) {
            j.beginObject()
            j.name("id").value(layer.id)
            j.name("name").value(layer.name)
            j.name("visible").value(layer.visible)
            j.name("locked").value(layer.locked)
            j.endObject()
        }
        j.endArray().endObject()
    }
    fun read(p: JsonPull): DrawingLayers {
        var active = DrawingLayers.DEFAULT
        val entries = mutableListOf<DrawingLayer>()
        p.beginObject()
        while (p.hasNext()) when (p.nextName()) {
            "active" -> active = p.nextString()
            "entries" -> {
                p.beginArray()
                while (p.hasNext()) {
                    var id = ""
                    var name = ""
                    var visible = true
                    var locked = false
                    p.beginObject()
                    while (p.hasNext()) when (p.nextName()) {
                        "id" -> id = p.nextString()
                        "name" -> name = p.nextString()
                        "visible" -> visible = p.nextBoolean()
                        "locked" -> locked = p.nextBoolean()
                        else -> p.skipValue()
                    }
                    p.endObject()
                    entries.add(DrawingLayer(id, name, visible, locked))
                }
                p.endArray()
            }
            else -> p.skipValue()
        }
        p.endObject()
        return DrawingLayers().also { it.restore(entries, active) }
    }
}
