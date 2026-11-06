package com.xnotes.mindmap

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

/** Independent, versioned .xmap format. Never decoded as a notebook bundle. */
object MapCodec {
    const val MAX_BYTES = 32 * 1024 * 1024
    fun read(input: InputStream): MindMapDocument = decode(readBytes(input))
    fun readBytes(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= MAX_BYTES)
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
    fun encode(doc: MindMapDocument): ByteArray {
        doc.validate()
        return toJson(doc).toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES - 4096) }
    }
    fun decode(bytes: ByteArray): MindMapDocument {
        require(bytes.size <= MAX_BYTES - 4096)
        return fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
    }
    internal fun toJson(doc: MindMapDocument) = JSONObject()
        .put("format", "xnotes-mind-map").put("version", 1).put("id", doc.id)
        .put("nodes", JSONArray().also { array -> doc.nodes.forEach { n ->
            array.put(JSONObject().put("id", n.id).put("parent", n.parent ?: JSONObject.NULL)
                .put("kind", n.kind.name).put("text", n.text).put("x", n.x).put("y", n.y)
                .put("width", n.width).put("height", n.height).put("collapsed", n.collapsed)
                .put("ink", JSONArray().also { ink -> n.ink.forEach { s ->
                    ink.put(JSONObject().put("width", s.width).put("points", JSONArray().also { points ->
                        s.points.forEach { p -> points.put(JSONObject().put("x", p.x).put("y", p.y)
                            .put("pressure", p.pressure).put("time", p.time)) }
                    }))
                } }))
        } })

    internal fun fromJson(j: JSONObject): MindMapDocument {
        require(j.string("format") == "xnotes-mind-map" && j.number("version") == 1.0)
        val array = j.getJSONArray("nodes")
        require(array.length() in 1..MindMapDocument.MAX_NODES)
        var count = 0
        val nodes = (0 until array.length()).map { i ->
            val n = array.getJSONObject(i)
            val ink = n.getJSONArray("ink")
            require(ink.length() <= MindMapDocument.MAX_POINTS)
            MapNode(id = n.string("id"), parent = if (n.get("parent") === JSONObject.NULL) null else n.string("parent"),
                kind = NodeKind.valueOf(n.string("kind")), text = n.string("text"),
                width = n.number("width"), height = n.number("height"), x = n.number("x"), y = n.number("y"),
                collapsed = n.get("collapsed").let { require(it is Boolean); it },
                ink = (0 until ink.length()).map { k ->
                    val s = ink.getJSONObject(k)
                    val points = s.getJSONArray("points")
                    count += points.length()
                    require(count <= MindMapDocument.MAX_POINTS)
                    MapInk((0 until points.length()).map { p -> points.getJSONObject(p).let {
                        InkPoint(it.number("x"), it.number("y"), it.number("pressure"), it.number("time"))
                    } }, s.number("width"))
                })
        }
        return MindMapDocument(j.string("id"), nodes).also { it.validate() }
    }
    private fun JSONObject.string(key: String): String = get(key).let { require(it is String); it }
    private fun JSONObject.number(key: String): Double = get(key).let { require(it is Number); it.toDouble().also { d -> require(d.isFinite()) } }
}
