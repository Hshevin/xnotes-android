package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.SyncMark
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.util.DocumentKind
import com.xnotes.map.MapModel
import com.xnotes.map.NodeInk
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MapBundleStorageTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun speedInkWritesIdenticalStrokeFieldsOnTheCanvasAndInANode() {
        val codec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())
        val doc = InfiniteDocument()
        val layer = doc.layers.create("drawing")
        val stroke = Stroke(Tool.SPEED, ToolDefaults.configFor(Tool.SPEED),
            listOf(Sample(1.23456, 2.0, .4567, 12.5, .789), Sample(4.0, 7.0, .5, 30.0, .75)),
            speedScale = 2.5, straight = true, smoothScale = 1.25)
        stroke.locked = true
        stroke.group = 3
        stroke.sync = SyncMark(7, 28)
        doc.add(stroke)
        val tree = MapBundleFixture.state
        val root = tree.roots.single()
        doc.map = MapModel(tree.copy(nodes = mapOf(root to tree.nodes.getValue(root).copy(
            ink = NodeInk(100.0, 50.0, listOf(stroke))))))
        val file = temp.newFile()
        file.outputStream().use { codec.write(doc, it) }
        ZipFile(file).use { zip ->
            fun json(entry: String) = zip.getInputStream(zip.getEntry(entry)).bufferedReader().use { it.readText() }
            val item = json("manifest.json").substringAfter("\"items\":[").removeSuffix("]}")
            val layerField = ""","layer":"${layer.id}"}"""
            assertTrue(item.endsWith(layerField))
            val expected = item.removeSuffix(layerField) + "}"
            assertTrue(json(MapJson.ENTRY).contains("\"strokes\":[$expected]"))
        }
        val decoded = file.inputStream().use { codec.read(it) }
        val nodeInk = decoded.map.state.nodes.getValue(root).ink.strokes.single()
        assertEquals((decoded.items.single() as Stroke).samples, nodeInk.samples)
        assertEquals(stroke.sync, nodeInk.sync)
        assertEquals(stroke.group, nodeInk.group)
        assertTrue(nodeInk.locked)
    }

    @Test fun identityUpgradeIndependentCopyAndPortableExportKeepTheExactMapEntry() {
        val source = temp.newFile()
        MapBundleFixture.write(source, identity = null)
        val original = source.readBytes()
        val upgrade = BundleMigration.prepare(source, temp.root)
        assertArrayEquals(original, source.readBytes())
        assertArrayEquals(MapBundleFixture.raw, MapBundleFixture.entry(upgrade.file))
        val info = BundleReferenceInfo.inspectStrict(upgrade.file)
        assertEquals(CanvasCodec.FORMAT_WITH_MAP, info.format)
        assertEquals(DocumentKind.CANVAS, info.kind)
        val copy = BundleMigration.prepareCopy(upgrade.file, temp.root, BundleIdentity().documentId)
        assertNotEquals(upgrade.identity.documentId, copy.identity.documentId)
        assertArrayEquals(MapBundleFixture.raw, MapBundleFixture.entry(copy.file))
        val portable = temp.newFile()
        portable.outputStream().use { BundlePdf.write(copy.file, it, copy.identity, null, false) }
        assertArrayEquals(MapBundleFixture.raw, MapBundleFixture.entry(portable))
        assertEquals(copy.identity, BundleIdentity.read(portable))
    }

    @Test fun refusedMapCannotAcquireAnIdentityThroughMigration() {
        val source = temp.newFile()
        val valid = String(MapBundleFixture.raw, Charsets.UTF_8)
        for (bad in listOf(valid.replace("\"version\":1", "\"version\":2"),
                valid.replace("\"id\":\"r\"", "\"id\":\"r\",\"layer\":\"lost\""))) {
            MapBundleFixture.write(source, identity = null, map = bad.toByteArray())
            val before = source.readBytes()
            assertThrows(XCanvasFormatException::class.java) { BundleMigration.prepare(source, temp.root) }
            assertArrayEquals(before, source.readBytes())
            assertEquals(listOf(source), temp.root.listFiles()!!.toList())
        }
    }

    @Test fun invalidMapsLeaveTheBundleAndOtherReadersAssetsIntact() {
        val codec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())
        val assets = temp.newFolder()
        val other = assets.resolve("other").apply { writeText("owned elsewhere") }
        val valid = String(MapBundleFixture.raw, Charsets.UTF_8)
        val cases = listOf(
            valid + " trailing", valid.replace("\"version\":1", "\"version\":1.9"),
            valid.replace("\"id\":\"r\"", "\"id\":\"r\",\"id\":\"s\""),
            valid.replace("\"id\":\"r\"", "\"id\":\"r\",\"offset\":[0,1]"),
            valid.replace("1.0e1", "1e9999"),
            valid.replace("\"id\":\"r\"", "\"id\":\"r\",\"ink\":{\"w\":5,\"h\":5,\"strokes\":[{}]}"),
        )
        for (json in cases) {
            val source = temp.newFile()
            MapBundleFixture.write(source, map = json.toByteArray())
            val before = source.readBytes()
            assertThrows(XCanvasFormatException::class.java) { source.inputStream().use { codec.read(it, assets) } }
            assertArrayEquals(before, source.readBytes())
            assertEquals(listOf(other), assets.listFiles()!!.toList())
        }
        val unmarked = temp.newFile()
        MapBundleFixture.write(unmarked, format = CanvasCodec.FORMAT)
        assertThrows(XCanvasFormatException::class.java) { unmarked.inputStream().use { codec.read(it, assets) } }
        assertEquals(listOf(other), assets.listFiles()!!.toList())
    }
}
