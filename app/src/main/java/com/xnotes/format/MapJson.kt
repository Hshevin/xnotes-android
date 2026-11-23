package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.DrawingLayers
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.map.LayoutKind
import com.xnotes.map.MapInvariants
import com.xnotes.map.MapModel
import com.xnotes.map.MapNode
import com.xnotes.map.MapState
import com.xnotes.map.NodeId
import com.xnotes.map.NodeInk
import com.xnotes.map.NodeShape
import com.xnotes.map.NodeStyle
import com.xnotes.map.Relation
import com.xnotes.map.RelationArrow
import com.xnotes.map.RelationId
import com.xnotes.map.SourceKind
import com.xnotes.map.SourceRef
import com.xnotes.map.Vec
import com.xnotes.map.walkTrees
import java.io.InputStreamReader
import java.util.zip.ZipFile

/**
 * The `map.json` entry of a canvas bundle that holds mind maps (phase 2 of
 * docs/movinkpad/mind-map/mind-map-v2-design-2026-11-21.md).
 *
 * ## Where it goes
 *
 * [CanvasCodec.write] writes this entry only when the document's [MapModel.state] is not empty. It is
 * deflated, written after the assets and the identity entry and before `manifest.json`, which stays
 * the last entry. Such a canvas's manifest says `"format": "xcanvas-map"`
 * ([CanvasCodec.FORMAT_WITH_MAP]) instead of `"xcanvas"`. Released readers (release 98 and earlier)
 * accept only `"xcanvas"`, so they refuse a canvas with maps rather than open it, drop the maps and
 * save over them. A canvas whose maps were all deleted is written as a plain `"xcanvas"` again with
 * no `map.json`.
 *
 * [CanvasCodec.read] accepts both formats. A `"xcanvas-map"` manifest without a `map.json`, a
 * `map.json` it cannot read, a newer [VERSION], a state that fails `MapInvariants`, or a root on a
 * layer the canvas does not have all throw [XCanvasFormatException] and leave nothing behind (temp
 * files created by the read are deleted, as for any failed read). Nothing is repaired or guessed: a
 * file that cannot be read exactly is refused and left untouched.
 *
 * ## Schema (version 1)
 *
 * ```
 * {
 *   "format": "xnotes-map",
 *   "version": 1,
 *   "roots": ["<node id>", ...],                      // MapState.roots order
 *   "nodes": [                                        // tree order: roots in order, depth-first, siblings in order
 *     {
 *       "id": "<node id>",
 *       "title": "...",                               // omitted when empty
 *       "style": {
 *         "shape": "rounded" | "ellipse" | "underline",
 *         "border": [r, g, b, a], "fill": [r, g, b, a] | null, "text": [r, g, b, a],
 *         "borderWidth": 2.0, "textSize": 18.0, "bold": false
 *       },
 *       "ink": { "w": 160.0, "h": 80.0, "strokes": [ <stroke> ... ] },   // omitted when NodeInk.NONE
 *       "sources": [ { "id": "...", "kind": "note_page" | "pdf_page" | "canvas",
 *                      "document": "<uuid>", "page": "<page id>" (omitted when null), "label": "..." } ],  // omitted when empty
 *       "children": ["<node id>", ...],               // omitted when none
 *       "origin": [x, y], "layout": "right" | "balanced" | "org" | "indent",   // roots only
 *       "layer": "<layer id>",                        // roots only, omitted for the default layer
 *       "offset": [dx, dy],                           // children only, omitted when none
 *       "collapsed": true                             // omitted when not collapsed (only pruned collapsed nodes are written)
 *     }
 *   ],
 *   "relations": [ { "id": "...", "from": "<node id>", "to": "<node id>",
 *                    "label": "..." (omitted when empty), "arrow": "none" | "forward" | "backward" | "both",
 *                    "color": [r, g, b, a] } ]
 * }
 * ```
 *
 * `<stroke>` is exactly the JSON [CanvasCodec] writes for a stroke item on the canvas, minus any
 * layer field, and is read back by the same parser, so node ink keeps every raw sample bit for bit
 * and is never re-encoded. Parent links are derived from the `children` lists. Unknown fields at any
 * level are ignored, so later additive fields need no version bump. Omitted optional fields take the
 * defaults the comments above imply (a missing `style` is [com.xnotes.map.NodeStyle.TOPIC], a missing
 * `relations` is none). An unknown enum value is a format error (it would need a version bump).
 *
 * ## Storage pipelines
 *
 * Every place outside [CanvasCodec] that checks a manifest's `format` against `"xcanvas"` (bundle
 * reference info, copy plans, protected canvas preparation, migration, recovery, working sessions)
 * must use [CanvasCodec.isCanvasFormat] and must carry the `map.json` entry through byte for byte,
 * exactly as it carries the other canvas entries. Each such pipeline gets a test with a canvas that
 * has maps.
 */
internal object MapJson {
    const val ENTRY = "map.json"
    const val FORMAT = "xnotes-map"
    const val VERSION = 1

    fun write(j: JsonWrite, model: MapModel, writeStroke: (JsonWrite, Stroke) -> Unit) {
        val state = model.state
        validate(state)
        val collapsed = model.prunedCollapsed()
        j.beginObject().name("format").value(FORMAT).name("version").value(VERSION)
        j.name("roots").ids(state.roots)
        j.name("nodes").beginArray()
        state.walkTrees(state.roots) { id ->
            val node = state.nodes.getValue(id)
            j.beginObject().name("id").value(id.value)
            if (node.title.isNotEmpty()) j.name("title").value(node.title)
            val style = node.style
            j.name("style").beginObject().name("shape").value(style.shape.id)
            j.name("border").color(style.border)
            j.name("fill")
            if (style.fill == null) j.nullValue() else j.color(style.fill)
            j.name("text").color(style.text)
            j.name("borderWidth").value(style.borderWidth).name("textSize").value(style.textSize)
            j.name("bold").value(style.bold).endObject()
            if (!node.ink.isEmpty) {
                j.name("ink").beginObject().name("w").value(node.ink.width).name("h").value(node.ink.height)
                j.name("strokes").beginArray()
                node.ink.strokes.forEach { writeStroke(j, it) }
                j.endArray().endObject()
            }
            if (node.sources.isNotEmpty()) {
                j.name("sources").beginArray()
                for (source in node.sources) {
                    j.beginObject().name("id").value(source.id).name("kind").value(source.kind.id)
                    j.name("document").value(source.documentId)
                    source.pageId?.let { j.name("page").value(it) }
                    j.name("label").value(source.label).endObject()
                }
                j.endArray()
            }
            val children = state.childrenOf(id)
            if (children.isNotEmpty()) j.name("children").ids(children)
            state.rootOrigin[id]?.let { j.name("origin").beginArray().value(it.x).value(it.y).endArray() }
            state.layout[id]?.let { j.name("layout").value(it.id) }
            state.layers[id]?.takeUnless { it == DrawingLayers.DEFAULT }?.let { j.name("layer").value(it) }
            state.offsets[id]?.let { j.name("offset").beginArray().value(it.dx).value(it.dy).endArray() }
            if (id in collapsed) j.name("collapsed").value(true)
            j.endObject()
            true
        }
        j.endArray().name("relations").beginArray()
        for (relation in state.relations.values) {
            j.beginObject().name("id").value(relation.id.value)
            j.name("from").value(relation.from.value).name("to").value(relation.to.value)
            if (relation.label.isNotEmpty()) j.name("label").value(relation.label)
            j.name("arrow").value(relation.arrow.id).name("color").color(relation.color)
            j.endObject()
        }
        j.endArray().endObject()
    }

    private fun JsonWrite.ids(ids: List<NodeId>) {
        beginArray()
        ids.forEach { value(it.value) }
        endArray()
    }

    private fun JsonWrite.color(c: Rgba) {
        if (c.r !in 0..255 || c.g !in 0..255 || c.b !in 0..255 || c.a !in 0..255) invalid("RGBA channel out of range")
        beginArray().value(c.r).value(c.g).value(c.b).value(c.a).endArray()
    }

    fun read(p: JsonPull, readStroke: (JsonPull) -> Stroke): MapModel = try {
        var roots: List<NodeId>? = null
        val tree = TreeReader()
        val relations = LinkedHashMap<RelationId, Relation>()
        val keys = p.fields { key ->
            when (key) {
                "format" -> if (p.nextString() != FORMAT) invalid("Unknown map format")
                "version" -> if (p.nextNumberLiteral() != VERSION.toString()) invalid("Unsupported map version")
                "roots" -> roots = p.array { NodeId(p.nextString()) }
                "nodes" -> p.elements { tree.node(p, readStroke) }
                "relations" -> p.elements {
                    val relation = readRelation(p)
                    if (relations.put(relation.id, relation) != null) invalid("Duplicate relation id")
                }
                else -> p.skipValue()
            }
        }
        if ("format" !in keys || "version" !in keys || "nodes" !in keys) invalid("Missing map header or nodes")
        if (p.peek() != JsonPull.Token.END_DOCUMENT) invalid("Trailing map data")
        val state = MapState(tree.nodes, roots ?: missing("roots"), tree.parent, tree.children,
            tree.origins, tree.layouts, tree.offsets, tree.layers, relations)
        validate(state)
        MapModel(state, tree.collapsed)
    } catch (e: JsonPullException) {
        invalid(e.message ?: "Malformed map JSON")
    } catch (e: java.nio.charset.CharacterCodingException) {
        invalid("Invalid UTF-8 in map entry")
    }

    private class TreeReader {
        val nodes = LinkedHashMap<NodeId, MapNode>()
        val parent = HashMap<NodeId, NodeId>()
        val children = HashMap<NodeId, List<NodeId>>()
        val origins = HashMap<NodeId, Pt>()
        val layouts = HashMap<NodeId, LayoutKind>()
        val layers = HashMap<NodeId, String>()
        val offsets = HashMap<NodeId, Vec>()
        val collapsed = HashSet<NodeId>()

        fun node(p: JsonPull, readStroke: (JsonPull) -> Stroke) {
            var id: NodeId? = null
            var title = ""
            var style = NodeStyle.TOPIC
            var ink = NodeInk.NONE
            var sources = emptyList<SourceRef>()
            var kids = emptyList<NodeId>()
            var origin: Pt? = null
            var layout: LayoutKind? = null
            var layer: String? = null
            var offset: Vec? = null
            var folded = false
            p.fields { key ->
                when (key) {
                    "id" -> id = NodeId(p.nextString())
                    "title" -> title = p.nextString()
                    "style" -> style = readStyle(p)
                    "ink" -> ink = readInk(p, readStroke)
                    "sources" -> sources = p.array { readSource(p) }
                    "children" -> kids = p.array { NodeId(p.nextString()) }
                    "origin" -> origin = p.pair(::Pt)
                    "layout" -> layout = LayoutKind.fromId(p.nextString()) ?: invalid("Unknown layout")
                    "layer" -> layer = p.nextString()
                    "offset" -> offset = p.pair(::Vec)
                    "collapsed" -> folded = p.nextBoolean()
                    else -> p.skipValue()
                }
            }
            val key = id ?: missing("node id")
            if (nodes.put(key, MapNode(key, title, style, ink, sources)) != null) invalid("Duplicate node id")
            if (kids.isNotEmpty()) children[key] = kids
            for (child in kids) if (parent.put(child, key) != null) invalid("Child listed more than once")
            origin?.let { origins[key] = it }
            layout?.let { layouts[key] = it }
            layer?.let { layers[key] = it }
            offset?.let { offsets[key] = it }
            if (folded) {
                if (kids.isEmpty()) invalid("Collapsed node has no children")
                collapsed += key
            }
        }
    }

    private fun readStyle(p: JsonPull): NodeStyle {
        var shape: NodeShape? = null
        var border: Rgba? = null
        var fill: Rgba? = null
        var text: Rgba? = null
        var width: Double? = null
        var size: Double? = null
        var bold: Boolean? = null
        val keys = p.fields { key ->
            when (key) {
                "shape" -> shape = NodeShape.fromId(p.nextString()) ?: invalid("Unknown node shape")
                "border" -> border = color(p)
                "text" -> text = color(p)
                "fill" -> if (p.peek() == JsonPull.Token.NULL) p.nextNull() else fill = color(p)
                "borderWidth" -> width = number(p)
                "textSize" -> size = number(p)
                "bold" -> bold = p.nextBoolean()
                else -> p.skipValue()
            }
        }
        if ("fill" !in keys) missing("fill")
        return NodeStyle(shape ?: missing("shape"), border ?: missing("border"), fill, text ?: missing("text"),
            width ?: missing("borderWidth"), size ?: missing("textSize"), bold ?: missing("bold"))
    }

    private fun readInk(p: JsonPull, readStroke: (JsonPull) -> Stroke): NodeInk {
        var w: Double? = null
        var h: Double? = null
        var strokes: List<Stroke>? = null
        p.fields { key ->
            when (key) {
                "w" -> w = number(p)
                "h" -> h = number(p)
                "strokes" -> strokes = p.array { readStroke(p) }
                else -> p.skipValue()
            }
        }
        return NodeInk(w ?: missing("ink width"), h ?: missing("ink height"), strokes ?: missing("ink strokes"))
    }

    private fun readSource(p: JsonPull): SourceRef {
        var id: String? = null
        var kind: SourceKind? = null
        var document: String? = null
        var page: String? = null
        var label: String? = null
        p.fields { key ->
            when (key) {
                "id" -> id = p.nextString()
                "kind" -> kind = SourceKind.fromId(p.nextString()) ?: invalid("Unknown source kind")
                "document" -> document = p.nextString()
                "page" -> page = p.nextString()
                "label" -> label = p.nextString()
                else -> p.skipValue()
            }
        }
        val type = kind ?: missing("source kind")
        return SourceRef(id ?: missing("source id"), type, document ?: missing("document"), page, label ?: missing("label"))
    }

    private fun readRelation(p: JsonPull): Relation {
        var id: RelationId? = null
        var from: NodeId? = null
        var to: NodeId? = null
        var label = ""
        var arrow: RelationArrow? = null
        var color: Rgba? = null
        p.fields { key ->
            when (key) {
                "id" -> id = RelationId(p.nextString())
                "from" -> from = NodeId(p.nextString())
                "to" -> to = NodeId(p.nextString())
                "label" -> label = p.nextString()
                "arrow" -> arrow = RelationArrow.fromId(p.nextString()) ?: invalid("Unknown relation arrow")
                "color" -> color = color(p)
                else -> p.skipValue()
            }
        }
        return Relation(id ?: missing("relation id"), from ?: missing("from"), to ?: missing("to"),
            label, arrow ?: missing("arrow"), color ?: missing("color"))
    }

    internal fun color(p: JsonPull): Rgba {
        fun channel() = p.nextInt().also { if (it !in 0..255) invalid("RGBA channel out of range") }
        p.beginArray()
        val rgba = Rgba(channel(), channel(), channel(), channel())
        p.endArray()
        return rgba
    }

    private inline fun <T> JsonPull.pair(make: (Double, Double) -> T): T {
        beginArray()
        val point = make(number(this), number(this))
        endArray()
        return point
    }

    internal fun number(p: JsonPull): Double =
        p.nextDouble().also { if (!it.isFinite()) invalid("Non-finite map number") }

    internal inline fun JsonPull.fields(read: (String) -> Unit): Set<String> {
        val keys = HashSet<String>()
        beginObject()
        while (hasNext()) {
            val key = nextName()
            if (!keys.add(key)) invalid("Duplicate map field: $key")
            read(key)
        }
        endObject()
        return keys
    }

    private inline fun JsonPull.elements(read: () -> Unit) {
        beginArray()
        while (hasNext()) read()
        endArray()
    }

    private inline fun <T> JsonPull.array(read: () -> T): List<T> = buildList { elements { add(read()) } }

    private fun validate(state: MapState) {
        MapInvariants.check(state).firstOrNull()?.let { invalid(it) }
        for (node in state.nodes.values) for (source in node.sources) {
            if ((source.kind == SourceKind.CANVAS) != (source.pageId == null)) invalid("Source page does not match its kind")
        }
    }

    /** Raw publication may assign an identity before an editor opens the file. Validate on the
     * snapshot, then let the copy plan stream the original entries and verify their digests. */
    fun validateForCopy(zip: ZipFile, cancelled: () -> Boolean) {
        val map = zip.getInputStream(zip.getEntry(ENTRY)).use {
            CanvasCodec.readMapEntry(BundleEntries.cancellable(it, cancelled))
        }
        val layers = zip.getInputStream(zip.getEntry(BundleCopyPlan.MANIFEST)).use { input ->
            val reader = InputStreamReader(BundleEntries.cancellable(input, cancelled), Charsets.UTF_8.newDecoder())
            val p = JsonPull(reader, strict = true, tokenLimit = Int.MAX_VALUE)
            var layers = DrawingLayers()
            p.fields { key -> if (key == "layers") layers = LayerJson.read(p) else p.skipValue() }
            if (p.peek() != JsonPull.Token.END_DOCUMENT) invalid("Trailing manifest data")
            layers
        }
        checkLayers(map.state, layers)
    }

    fun checkEntry(format: String, hasEntry: Boolean) {
        if ((format == CanvasCodec.FORMAT_WITH_MAP) != hasEntry) invalid("Canvas format and map entry disagree")
    }

    fun checkLayers(state: MapState, layers: DrawingLayers) {
        val ids = layers.entries.mapTo(HashSet()) { it.id }
        if (state.roots.any { (state.layers[it] ?: DrawingLayers.DEFAULT) !in ids }) invalid("Unknown map root layer")
    }

    private fun missing(key: String): Nothing = invalid("Missing map field: $key")
    internal fun invalid(reason: String): Nothing = throw XCanvasFormatException(reason)
}
