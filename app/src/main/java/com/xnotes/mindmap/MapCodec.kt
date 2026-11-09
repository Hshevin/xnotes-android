package com.xnotes.mindmap

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.DataOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

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
    private fun extended(doc:MindMapDocument)=doc.organization!=MapOrganization.MIND_MAP || doc.nodes.any {it.fileCardId!=null}
    internal fun toJson(doc: MindMapDocument):JSONObject {
        val expanded=extended(doc)
        return JSONObject()
        .put("format", "xnotes-mind-map").put("version", if(expanded) 4 else 3).put("id", doc.id).put("direction",doc.direction.name).put("relations",MapGraphJson.relations(doc))
        .also {if(expanded) it.put("organization",doc.organization.name)}
        .put("previewAssets",JSONArray().also {array->doc.prunePreviewAssets().previewAssets.values.forEach {asset->
            array.put(JSONObject().put("key",asset.key).put("pngBase64",asset.pngBase64).put("width",asset.width).put("height",asset.height))
        }})
        .put("nodes", JSONArray().also { array -> doc.nodes.forEach { n ->
            array.put(JSONObject().put("id", n.id).put("parent", n.parent ?: JSONObject.NULL)
                .put("kind", n.kind.name).put("text", n.text).put("x", n.x).put("y", n.y)
                .put("width", n.width).put("height", n.height).put("collapsed", n.collapsed)
                .put("free",n.free).put("sources",MapGraphJson.sources(n)).put("primarySource",n.primarySource ?: JSONObject.NULL)
                .also {if(expanded) it.put("fileCardId",n.fileCardId ?: JSONObject.NULL)}
                .put("ink", JSONArray().also { ink -> n.ink.forEach { s ->
                    ink.put(JSONObject().put("width", s.width).put("points", JSONArray().also { points ->
                        s.points.forEach { p -> points.put(JSONObject().put("x", p.x).put("y", p.y)
                            .put("pressure", p.pressure).put("time", p.time)) }
                    }))
                } }))
        } })
    }

    internal fun fromJson(j: JSONObject): MindMapDocument {
        require(j.string("format") == "xnotes-mind-map")
        val version=j.number("version");val versionValue=j.get("version")
        require(version==1.0 || version==2.0 || version in setOf(3.0,4.0) && (versionValue is Int || versionValue is Long))
        val organization=if(version>=4.0) MapOrganization.valueOf(j.string("organization")) else MapOrganization.MIND_MAP
        val array = j.getJSONArray("nodes")
        require(array.length() in (if(organization==MapOrganization.MIND_MAP) 1 else 0)..MindMapDocument.MAX_NODES)
        var count = 0
        val nodes = (0 until array.length()).map { i ->
            val n = array.getJSONObject(i)
            val ink = n.getJSONArray("ink")
            require(ink.length() <= MindMapDocument.MAX_POINTS)
            MapNode(id = n.string("id"), parent = if (n.get("parent") === JSONObject.NULL) null else n.string("parent"),
                kind = NodeKind.valueOf(n.string("kind")), text = n.string("text"),
                width = n.number("width"), height = n.number("height"), x = n.number("x"), y = n.number("y"),
                collapsed = n.get("collapsed").let { require(it is Boolean); it },
                free=if(version>=2.0) MapGraphJson.bool(n,"free") else false,
                sources=if(version>=2.0) MapGraphJson.readSources(n,previews=version>=3.0) else emptyList(),
                primarySource=if(version>=2.0) MapGraphJson.nullable(n,"primarySource") else null,
                fileCardId=if(version>=4.0) MapGraphJson.nullable(n,"fileCardId") else null,
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
        return MindMapDocument(j.string("id"), nodes, if(version>=2.0) MapGraphJson.readRelations(j) else emptyList(),
            if(version>=2.0) MapDirection.valueOf(j.string("direction")) else MapDirection.BOTH,
            if(version>=3.0) readAssets(j.getJSONArray("previewAssets")) else emptyMap(),organization).also { it.validate() }
    }
    private fun readAssets(array:JSONArray):Map<String,MapPreviewAsset> {
        require(array.length()<=MapPreviewAsset.MAX_ASSETS)
        val assets=linkedMapOf<String,MapPreviewAsset>();var bytes=0L;var pixels=0L
        for(i in 0 until array.length()) {
            val j=array.getJSONObject(i);val key=j.string("key");val png=j.string("pngBase64")
            val width=j.integer("width");val height=j.integer("height")
            require(key !in assets && width in 1..MapPreviewAsset.MAX_SIDE && height in 1..MapPreviewAsset.MAX_SIDE)
            require(png.length<=4*((MapPreviewAsset.MAX_BYTES+2)/3) && png.length%4==0)
            bytes+=png.length.toLong()/4*3-png.takeLast(2).count {it=='='}
            pixels+=width.toLong()*height
            require(bytes<=MapPreviewAsset.MAX_TOTAL_BYTES && pixels<=MapPreviewAsset.MAX_TOTAL_PIXELS)
            assets[key]=MapPreviewAsset.fromEncoded(key,png,width,height)
        }
        return assets
    }
    /** Cache identity for what the map renderer sees, never a source-write/conflict authority.
     * Derived observations and retained PNGs are excluded so self/cyclic references converge.
     * A typed ordered stream avoids platform-specific JSONObject ordering and large PNG JSONs.
     */
    fun semanticPreviewVersion(doc:MindMapDocument):String {
        doc.validate()
        val digest=MessageDigest.getInstance("SHA-256")
        val sink=object:java.io.OutputStream() {
            override fun write(b:Int) {}
            override fun write(b:ByteArray,off:Int,len:Int) {}
        }
        val out=DataOutputStream(DigestOutputStream(sink,digest))
        fun text(value:String) {val bytes=value.toByteArray(Charsets.UTF_8);out.writeInt(bytes.size);out.write(bytes)}
        fun optional(value:String?) {out.writeBoolean(value!=null);if(value!=null) text(value)}
        val expanded=extended(doc)
        text(if(expanded) "xmap-render-v2" else "xmap-render-v1");text(doc.id);text(doc.direction.name)
        if(expanded) text(doc.organization.name)
        out.writeInt(doc.nodes.size)
        doc.nodes.forEach {n->
            text(n.id);optional(n.parent);text(n.kind.name);text(n.text)
            out.writeDouble(n.width);out.writeDouble(n.height);out.writeDouble(n.x);out.writeDouble(n.y)
            out.writeBoolean(n.collapsed);out.writeBoolean(n.free);optional(n.primarySource)
            if(expanded) optional(n.fileCardId)
            out.writeInt(n.ink.size);n.ink.forEach {s->out.writeDouble(s.width);out.writeInt(s.points.size);s.points.forEach {p->
                out.writeDouble(p.x);out.writeDouble(p.y);out.writeDouble(p.pressure);out.writeDouble(p.time)
            }}
            out.writeInt(n.sources.size);n.sources.forEach {s->
                text(s.id);text(s.documentId);text(s.fileName);text(s.scope.name);optional(s.pageId);text(s.note)
                out.writeBoolean(s.region!=null);s.region?.let {r->out.writeDouble(r.x);out.writeDouble(r.y);out.writeDouble(r.w);out.writeDouble(r.h)}
            }
        }
        out.writeInt(doc.relations.size);doc.relations.forEach {r->text(r.id);text(r.from);text(r.to);text(r.kind.name);text(r.label)}
        out.flush()
        return (if(expanded) "xmap-render-v2:" else "xmap-render-v1:")+digest.digest().joinToString("") {"%02x".format(it)}
    }
    private fun JSONObject.string(key: String): String = get(key).let { require(it is String); it }
    private fun JSONObject.number(key: String): Double = get(key).let { require(it is Number); it.toDouble().also { d -> require(d.isFinite()) } }
    private fun JSONObject.integer(key:String):Int=get(key).let {require(it is Int || it is Long);(it as Number).toLong().also {n->require(n in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())}.toInt()}
}
