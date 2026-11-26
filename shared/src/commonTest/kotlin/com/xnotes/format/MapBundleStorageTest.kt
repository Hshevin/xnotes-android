package com.xnotes.format

import com.xnotes.core.pal.*
import com.xnotes.kio.*
import okio.*
import kotlin.test.assertFailsWith

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
import com.xnotes.core.testkit.*
import kotlin.test.Test

class MapBundleStorageTest : BundleTestFiles() {
    private val folder = testDirectory("map-bundle-")

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
        val tree = SharedMapBundleFixture.state
        val root = tree.roots.single()
        doc.map = MapModel(tree.copy(nodes = mapOf(root to tree.nodes.getValue(root).copy(
            ink = NodeInk(100.0, 50.0, listOf(stroke))))))
        val file = temporaryAsset("bundle-", ".zip", folder)
        file.fileSink().use { codec.write(doc, it) }
        bundleDirectory(file).use { zip ->
            fun json(entry: String) = zip.open(entry).buffer().use { it.readUtf8() }
            val item = json("manifest.json").substringAfter("\"items\":[").removeSuffix("]}")
            val layerField = ""","layer":"${layer.id}"}"""
            assertTrue(item.endsWith(layerField))
            val expected = item.removeSuffix(layerField) + "}"
            assertTrue(json(MapJson.ENTRY).contains("\"strokes\":[$expected]"))
        }
        val decoded = file.input().use { codec.read(it) }
        val nodeInk = decoded.map.state.nodes.getValue(root).ink.strokes.single()
        assertEquals((decoded.items.single() as Stroke).samples, nodeInk.samples)
        assertEquals(stroke.sync, nodeInk.sync)
        assertEquals(stroke.group, nodeInk.group)
        assertTrue(nodeInk.locked)
    }

    @Test fun identityUpgradeIndependentCopyAndPortableExportKeepTheExactMapEntry() {
        val source = temporaryAsset("bundle-", ".zip", folder)
        SharedMapBundleFixture.write(source, identity = null)
        val original = source.fileBytes()
        val upgrade = BundleMigration.prepare(source, folder)
        assertArrayEquals(original, source.fileBytes())
        assertArrayEquals(SharedMapBundleFixture.raw, SharedMapBundleFixture.entry(upgrade.file))
        val info = TestBundleMetadata.read(upgrade.file)
        assertEquals(CanvasCodec.FORMAT_WITH_MAP, info.format)
        assertEquals(DocumentKind.CANVAS, info.kind)
        val copy = BundleMigration.prepareCopy(upgrade.file, folder, BundleIdentity().documentId)
        assertNotEquals(upgrade.identity.documentId, copy.identity.documentId)
        assertArrayEquals(SharedMapBundleFixture.raw, SharedMapBundleFixture.entry(copy.file))
        val portable = temporaryAsset("bundle-", ".zip", folder)
        portable.fileSink().use { BundlePdf.write(copy.file, it, copy.identity, null, false) }
        assertArrayEquals(SharedMapBundleFixture.raw, SharedMapBundleFixture.entry(portable))
        assertEquals(copy.identity, readBundleIdentity(portable))
    }

    @Test fun refusedMapCannotAcquireAnIdentityThroughMigration() {
        val source = temporaryAsset("bundle-", ".zip", folder)
        val valid = SharedMapBundleFixture.raw.decodeToString()
        for (bad in listOf(valid.replace("\"version\":1", "\"version\":2"),
                valid.replace("\"id\":\"r\"", "\"id\":\"r\",\"layer\":\"lost\""))) {
            SharedMapBundleFixture.write(source, identity = null, map = bad.encodeToByteArray())
            val before = source.fileBytes()
            assertFailsWith<XCanvasFormatException> { BundleMigration.prepare(source, folder) }
            assertArrayEquals(before, source.fileBytes())
            assertEquals(listOf(source), folder.fileNames().map(folder::child))
        }
    }

    @Test fun invalidMapsLeaveTheBundleAndOtherReadersAssetsIntact() {
        val codec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())
        val assets = testDirectory("assets-")
        val other = assets.child("other").apply { setText("owned elsewhere") }
        val valid = SharedMapBundleFixture.raw.decodeToString()
        val cases = listOf(
            valid + " trailing", valid.replace("\"version\":1", "\"version\":1.9"),
            valid.replace("\"id\":\"r\"", "\"id\":\"r\",\"id\":\"s\""),
            valid.replace("\"id\":\"r\"", "\"id\":\"r\",\"offset\":[0,1]"),
            valid.replace("1.0e1", "1e9999"),
            valid.replace("\"id\":\"r\"", "\"id\":\"r\",\"ink\":{\"w\":5,\"h\":5,\"strokes\":[{}]}"),
        )
        for (json in cases) {
            val source = temporaryAsset("bundle-", ".zip", folder)
            SharedMapBundleFixture.write(source, map = json.encodeToByteArray())
            val before = source.fileBytes()
            assertFailsWith<XCanvasFormatException> { source.input().use { codec.read(it, assets) } }
            assertArrayEquals(before, source.fileBytes())
            assertEquals(listOf(other), assets.fileNames().map(assets::child))
        }
        val unmarked = temporaryAsset("bundle-", ".zip", folder)
        SharedMapBundleFixture.write(unmarked, format = CanvasCodec.FORMAT)
        assertFailsWith<XCanvasFormatException> { unmarked.input().use { codec.read(it, assets) } }
        assertEquals(listOf(other), assets.fileNames().map(assets::child))
    }
}
