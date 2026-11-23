package com.xnotes.format

import com.xnotes.core.pal.*
import com.xnotes.kio.*
import okio.*
import kotlin.test.assertFailsWith

import com.xnotes.core.geometry.Pt
import com.xnotes.map.LayoutKind
import com.xnotes.map.MapNode
import com.xnotes.map.MapState
import com.xnotes.map.NodeId

/** Noncanonical whitespace, numeric literals and an additive field expose any decode/re-encode. */
internal object SharedMapBundleFixture {
    val raw = """ { "nodes":[{"title":"Ink tree","layout":"right","origin":[1.0e1,-2.500],"id":"r","future":[17]}],
        "roots":["r"], "version":1, "format":"xnotes-map" } """.encodeToByteArray()
    val state: MapState
        get() {
            val r = NodeId("r")
            return MapState(nodes = mapOf(r to MapNode(r, "Ink tree")), roots = listOf(r),
                rootOrigin = mapOf(r to Pt(10.0, -2.5)), layout = mapOf(r to LayoutKind.RIGHT))
        }

    fun write(file: AssetFile, identity: BundleIdentity? = BundleIdentity(), map: ByteArray = raw,
              format: String = CanvasCodec.FORMAT_WITH_MAP) {
        bundleWriter(file.fileSink()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putDeflated(name,bytes)
            }
            // Allocated by a decode before it reaches map.json, including on a failed read.
            entry("assets/image-000.png", byteArrayOf(1, 2))
            identity?.let(zip::addIdentity)
            entry(MapJson.ENTRY, map)
            entry("manifest.json", """{"format":"$format","version":1,"items":[]}""".encodeToByteArray())
        }
    }

    fun entry(file: AssetFile): ByteArray = bundleDirectory(file).use { zip ->
        zip.open(MapJson.ENTRY).buffer().use { it.readByteArray() }
    }
}
