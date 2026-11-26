package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.DrawingLayer
import com.xnotes.core.model.DrawingLayers
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.map.LayoutKind
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
import com.xnotes.map.StateBuilder
import com.xnotes.map.Vec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CanvasMapCodecTest {

    private val codec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun bytesOf(doc: InfiniteDocument) = ByteArrayOutputStream().also { codec.write(doc, it) }.toByteArray()

    private fun read(bytes: ByteArray): InfiniteDocument =
        codec.read(ByteArrayInputStream(bytes), Files.createTempDirectory("map-codec").toFile())

    private fun roundTrip(doc: InfiniteDocument) = read(bytesOf(doc))

    private fun entries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) { out[e.name] = z.readBytes(); z.closeEntry(); e = z.nextEntry }
        }
        return out
    }

    private fun rezip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED }); z.write(data); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifestFormat(bytes: ByteArray): String {
        val p = JsonPull(java.io.InputStreamReader(ByteArrayInputStream(entries(bytes).getValue("manifest.json")), Charsets.UTF_8))
        p.beginObject()
        while (p.hasNext()) if (p.nextName() == "format") return p.nextString() else p.skipValue()
        error("no format")
    }

    private fun stroke(vararg s: Sample) =
        Stroke(Tool.PEN, ToolConfig(3.0, true, 0.4, 0.6, Rgba(10, 20, 30, 255)), s.toList())

    /** Two trees with every kind of field set. */
    private fun richState(): MapState {
        val b = StateBuilder()
        val r = b.root("r", Pt(10.5, -20.25), LayoutKind.ORG)
        val a = b.child(r, "a")
        val a1 = b.child(a, "a1")
        val c = b.child(r, "c")
        val f = b.root("free", Pt(900.0, 100.0), LayoutKind.INDENT)
        b.offset(a, 3.0, -4.5)
        b.relation("rel", a1, f)
        val s = b.build()
        val ink = NodeInk(170.0, 95.0, listOf(stroke(Sample(1.123456, 2.5, 0.31, 0.0), Sample(40.0, 50.25, 0.77, 16.5))))
        val styled = NodeStyle(NodeShape.UNDERLINE, Rgba(200, 0, 0), null, Rgba(0, 0, 200, 128), 4.5, 30.0, true)
        val sources = listOf(
            SourceRef("s1", SourceKind.PDF_PAGE, "doc-uuid", "page-uuid", "MiTA · 3"),
            SourceRef("s2", SourceKind.CANVAS, "canvas-uuid", null, "草稿"),
        )
        return s.copy(
            nodes = s.nodes + (a to s.nodes.getValue(a).copy(title = "监督学习", style = styled, ink = ink, sources = sources, width = 236.5)) +
                (c to s.nodes.getValue(c).copy(title = "")),
            relations = mapOf(RelationId("rel") to Relation(RelationId("rel"), a1, f, "依赖于", RelationArrow.BACKWARD, Rgba(1, 2, 3, 4))),
            layers = mapOf(f to "L2"),
        )
    }

    private fun docWith(state: MapState, collapsed: Set<NodeId> = emptySet()) = InfiniteDocument().apply {
        layers.restore(listOf(DrawingLayer(DrawingLayers.DEFAULT), DrawingLayer("L2", "二")), DrawingLayers.DEFAULT)
        map = MapModel(state, collapsed)
    }

    private fun assertSameMap(expected: MapState, actual: MapState) {
        assertEquals(expected.roots, actual.roots)
        assertEquals(expected.parent, actual.parent)
        assertEquals(expected.children.filterValues { it.isNotEmpty() }, actual.children.filterValues { it.isNotEmpty() })
        assertEquals(expected.rootOrigin, actual.rootOrigin)
        assertEquals(expected.layout, actual.layout)
        assertEquals(expected.offsets, actual.offsets)
        assertEquals(expected.layers, actual.layers)
        assertEquals(expected.relations, actual.relations)
        assertEquals(expected.nodes.keys, actual.nodes.keys)
        for ((id, n) in expected.nodes) {
            val m = actual.nodes.getValue(id)
            assertEquals(n.title, m.title)
            assertEquals(n.style, m.style)
            assertEquals(n.sources, m.sources)
            assertEquals(n.width, m.width)
            assertEquals(n.ink.width, m.ink.width, 0.0)
            assertEquals(n.ink.height, m.ink.height, 0.0)
            assertEquals(n.ink.strokes.size, m.ink.strokes.size)
            n.ink.strokes.zip(m.ink.strokes).forEach { (x, y) ->
                assertEquals(x.samples.size, y.samples.size)
                // node ink reads back exactly like the same stroke as a plain canvas item
                val asItem = roundTrip(InfiniteDocument().apply { add(Stroke(x.tool, x.config, x.samples)) }).items.single() as Stroke
                assertEquals(asItem.config, y.config)
            }
        }
    }

    // ---- format marker ----

    @Test fun plainCanvasKeepsThePlainFormatAndNoMapEntry() {
        val bytes = bytesOf(InfiniteDocument())
        assertEquals("xcanvas", manifestFormat(bytes))
        assertFalse(MapJson.ENTRY in entries(bytes))
    }

    @Test fun canvasWithMapsUsesTheMapFormatAndWritesTheEntryBeforeTheManifest() {
        val bytes = bytesOf(docWith(richState()))
        assertEquals(CanvasCodec.FORMAT_WITH_MAP, manifestFormat(bytes))
        val names = entries(bytes).keys.toList()
        assertTrue(MapJson.ENTRY in names)
        assertEquals("manifest.json", names.last())
        assertTrue(names.indexOf(MapJson.ENTRY) < names.indexOf("manifest.json"))
    }

    @Test fun releasedReadersRefuseACanvasWithMaps() {
        // Release 98 and earlier accept only this exact format string.
        assertFalse(manifestFormat(bytesOf(docWith(richState()))) == "xcanvas")
    }

    @Test fun deletingEveryMapWritesAPlainCanvasAgain() {
        val doc = docWith(richState())
        doc.map = MapModel()
        val bytes = bytesOf(doc)
        assertEquals("xcanvas", manifestFormat(bytes))
        assertFalse(MapJson.ENTRY in entries(bytes))
    }

    @Test fun formatHelperAcceptsBoth() {
        assertTrue(CanvasCodec.isCanvasFormat("xcanvas"))
        assertTrue(CanvasCodec.isCanvasFormat("xcanvas-map"))
        assertFalse(CanvasCodec.isCanvasFormat("xnote"))
        assertFalse(CanvasCodec.isCanvasFormat(null))
    }

    @Test fun peekWorksForCanvasesWithMaps() {
        assertNotNull(codec.peek(ByteArrayInputStream(bytesOf(docWith(richState())))))
    }

    @Test fun legacyFormatValidationAcceptsCanvasesWithMaps() {
        val manifest = entries(bytesOf(docWith(richState()))).getValue("manifest.json")
        bundleFormat(ByteArrayInputStream(manifest))
    }

    // ---- round trip ----

    @Test fun everyFieldSurvivesARoundTrip() {
        val state = richState()
        val back = roundTrip(docWith(state)).map.state
        assertSameMap(state, back)
    }

    @Test fun nodeInkKeepsEveryRawSampleExactly() {
        val state = richState()
        val original = state.nodes.getValue(NodeId("a")).ink.strokes.single()
        val once = roundTrip(docWith(state))
        val back = once.map.state.nodes.getValue(NodeId("a")).ink.strokes.single()
        // exactly as the same stroke comes back as a plain canvas item (same sample encoding)
        val asItem = roundTrip(InfiniteDocument().apply { add(stroke(*original.samples.toTypedArray())) }).items.single() as Stroke
        assertEquals(asItem.samples, back.samples)
        // and saving again never drifts: no re-encoding on later saves
        val twice = roundTrip(once).map.state.nodes.getValue(NodeId("a")).ink.strokes.single()
        assertEquals(back.samples, twice.samples)
    }

    @Test fun nodeInkKeepsItsCutEdges() {
        val cut = stroke(Sample(-10.0, 5.0, 0.5, 0.0), Sample(300.0, 40.0, 0.5, 8.0)).apply {
            cuts = listOf(
                com.xnotes.core.model.CutEdge(0.0, 0.0, -1.0, 0.0),
                com.xnotes.core.model.CutEdge(170.0, 0.0, 1.0, 0.0),
            )
        }
        val s = richState()
        val a = NodeId("a")
        val state = s.copy(nodes = s.nodes + (a to s.nodes.getValue(a).copy(ink = NodeInk(170.0, 95.0, listOf(cut)))))
        val back = roundTrip(docWith(state)).map.state.nodes.getValue(a).ink.strokes.single()
        assertEquals(cut.cuts, back.cuts)
        assertEquals(cut.samples.size, back.samples.size)
    }

    @Test fun aNodeWithoutAWidthWritesNoWidthField() {
        val json = String(entries(bytesOf(docWith(StateBuilder().apply { root("r") }.build()))).getValue(MapJson.ENTRY))
        assertFalse(json.contains("\"width\""))
        assertEquals(null, read(withMapJson(minimal)).map.state.nodes.getValue(NodeId("r")).width)
    }

    @Test fun aBadNodeWidthIsRefused() {
        for (bad in listOf("0", "-3.5", "\"wide\"")) {
            val json = minimal.replace("\"layout\":\"right\"", "\"layout\":\"right\",\"width\":$bad")
            assertThrows(XCanvasFormatException::class.java) { read(withMapJson(json)) }
        }
    }

    @Test fun onlyPrunedCollapsedNodesAreSaved() {
        val state = richState()
        val doc = docWith(state, collapsed = setOf(NodeId("a"), NodeId("c"), NodeId("ghost")))
        val back = roundTrip(doc).map
        assertEquals(setOf(NodeId("a")), back.collapsed)
    }

    @Test fun ordinaryItemsAreUnaffected() {
        val doc = docWith(richState())
        doc.add(stroke(Sample(0.0, 0.0, 0.5), Sample(10.0, 10.0, 0.5)))
        val back = roundTrip(doc)
        assertEquals(1, back.items.size)
        assertTrue(back.items.single() is Stroke)
    }

    @Test fun readingDoesNotMarkTheDocumentDirty() {
        assertFalse(roundTrip(docWith(richState())).dirty)
    }

    // ---- refusals ----

    private fun withMapJson(json: String): ByteArray {
        val e = entries(bytesOf(docWith(richState())))
        e[MapJson.ENTRY] = json.toByteArray()
        val reordered = LinkedHashMap<String, ByteArray>()
        e.filterKeys { it != "manifest.json" }.forEach { (k, v) -> reordered[k] = v }
        reordered["manifest.json"] = e.getValue("manifest.json")
        return rezip(reordered)
    }

    private val minimal = """{"format":"xnotes-map","version":1,"roots":["r"],"nodes":[{"id":"r","style":{"shape":"rounded","border":[0,0,0,255],"fill":null,"text":[0,0,0,255],"borderWidth":2.0,"textSize":18.0,"bold":false},"origin":[0,0],"layout":"right"}],"relations":[]}"""

    @Test fun aMinimalHandWrittenEntryReads() {
        val m = read(withMapJson(minimal)).map
        assertEquals(listOf(NodeId("r")), m.state.roots)
        assertEquals(LayoutKind.RIGHT, m.state.layout[NodeId("r")])
    }

    @Test fun unknownFieldsAreIgnored() {
        val json = minimal.replace("\"version\":1,", "\"version\":1,\"future\":{\"x\":[1,2]},").replace("\"layout\":\"right\"", "\"layout\":\"right\",\"glow\":true")
        assertEquals(listOf(NodeId("r")), read(withMapJson(json)).map.state.roots)
    }

    @Test fun aNewerVersionIsRefused() {
        val e = assertThrows(XCanvasFormatException::class.java) { read(withMapJson(minimal.replace("\"version\":1", "\"version\":2"))) }
        assertTrue(e.message!!.contains("version", ignoreCase = true))
    }

    @Test fun anUnknownEnumIsRefused() {
        assertThrows(XCanvasFormatException::class.java) { read(withMapJson(minimal.replace("\"layout\":\"right\"", "\"layout\":\"spiral\""))) }
    }

    @Test fun brokenStructureIsRefused() {
        val cycle = """{"format":"xnotes-map","version":1,"roots":["r"],"nodes":[{"id":"r","origin":[0,0],"layout":"right","children":["a"]},{"id":"a","children":["r"]}],"relations":[]}"""
        assertThrows(XCanvasFormatException::class.java) { read(withMapJson(cycle)) }
        val dangling = minimal.replace("\"relations\":[]", "\"relations\":[{\"id\":\"q\",\"from\":\"r\",\"to\":\"nope\",\"arrow\":\"none\",\"color\":[0,0,0,255]}]")
        assertThrows(XCanvasFormatException::class.java) { read(withMapJson(dangling)) }
        assertThrows(XCanvasFormatException::class.java) { read(withMapJson("{not json")) }
    }

    @Test fun aRootOnAMissingLayerIsRefused() {
        val json = minimal.replace("\"layout\":\"right\"", "\"layout\":\"right\",\"layer\":\"nowhere\"")
        assertThrows(XCanvasFormatException::class.java) { read(withMapJson(json)) }
    }

    @Test fun theMapFormatWithoutItsEntryIsRefused() {
        val e = entries(bytesOf(docWith(richState())))
        e.remove(MapJson.ENTRY)
        assertThrows(XCanvasFormatException::class.java) { read(rezip(e)) }
    }

    // ---- document hook ----

    @Test fun mapChangesMarkTheDocumentDirty() {
        val b = StateBuilder()
        val r = b.root("r")
        b.child(r, "a")
        val doc = docWith(b.build())
        doc.dirty = false
        assertTrue(doc.map.setCollapsed(r, true))
        assertTrue(doc.dirty)
    }

    @Test fun aReplacedModelStillMarksDirtyAndTheOldOneNoLongerDoes() {
        val b = StateBuilder()
        val r = b.root("r")
        b.child(r, "a")
        val old = MapModel(b.build())
        val doc = InfiniteDocument()
        doc.map = old
        doc.map = MapModel(b.build())
        doc.dirty = false
        old.setCollapsed(r, true)
        assertFalse(doc.dirty)
        doc.map.setCollapsed(r, true)
        assertTrue(doc.dirty)
    }

    @Test fun aWriteSnapshotFreezesTheMap() {
        val b = StateBuilder()
        val r = b.root("r")
        b.child(r, "a")
        val doc = docWith(b.build())
        val snap = doc.snapshotForWrite()
        assertNotSame(doc.map, snap.map)
        assertSame(doc.map.state, snap.map.state)
        doc.map.setCollapsed(r, true)
        assertTrue(snap.map.collapsed.isEmpty())
        assertSameMap(b.build(), read(bytesOf(snap)).map.state)
    }
}
