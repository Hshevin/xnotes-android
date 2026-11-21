package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.map.LayoutKind
import com.xnotes.map.MapNode
import com.xnotes.map.MapState
import com.xnotes.map.NodeId
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Noncanonical whitespace, numeric literals and an additive field expose any decode/re-encode. */
internal object MapBundleFixture {
    val raw = """ { "nodes":[{"title":"Ink tree","layout":"right","origin":[1.0e1,-2.500],"id":"r","future":[17]}],
        "roots":["r"], "version":1, "format":"xnotes-map" } """.toByteArray()
    val state: MapState
        get() {
            val r = NodeId("r")
            return MapState(nodes = mapOf(r to MapNode(r, "Ink tree")), roots = listOf(r),
                rootOrigin = mapOf(r to Pt(10.0, -2.5)), layout = mapOf(r to LayoutKind.RIGHT))
        }

    fun write(file: File, identity: BundleIdentity? = BundleIdentity(), map: ByteArray = raw,
              format: String = CanvasCodec.FORMAT_WITH_MAP) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            // Allocated by a decode before it reaches map.json, including on a failed read.
            entry("assets/image-000.png", byteArrayOf(1, 2))
            identity?.write(zip)
            entry(MapJson.ENTRY, map)
            entry("manifest.json", """{"format":"$format","version":1,"items":[]}""".toByteArray())
        }
    }

    fun entry(file: File): ByteArray = ZipFile(file).use { zip ->
        zip.getInputStream(requireNotNull(zip.getEntry(MapJson.ENTRY))).use { it.readBytes() }
    }
}
