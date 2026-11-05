package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.*
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class LayerCodecTest {
    private fun stroke() = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(1.0, 2.0, 0.7)))
    @Test fun pagedLayersAndInkRoundTrip() {
        val doc = Document.blank()
        val page = doc.pages.first()
        page.items.add(stroke())
        val layer = page.layers.create("订正")
        page.items.add(stroke())
        page.layers.update(layer.id) { it.copy(visible = false, locked = true) }
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val bytes = ByteArrayOutputStream().also { codec.write(doc.snapshot(), it) }.toByteArray()
        val loaded = codec.read(bytes.inputStream()).pages.first()
        assertEquals(page.layers.entries, loaded.layers.entries)
        assertEquals(layer.id, loaded.layers.activeId)
        assertEquals(page.items.map { it.layerId }, loaded.items.map { it.layerId })
        assertEquals(1, loaded.visibleItems.size)
        assertEquals((page.items.last() as Stroke).samples, (loaded.items.last() as Stroke).samples)
    }
    @Test fun canvasLayersRoundTrip() {
        val doc = InfiniteDocument()
        doc.add(stroke())
        val layer = doc.layers.create("Draft")
        doc.add(stroke())
        doc.layers.move(layer.id, -1)
        val codec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())
        val bytes = ByteArrayOutputStream().also { codec.write(doc.snapshotForWrite(), it) }.toByteArray()
        val loaded = codec.read(bytes.inputStream())
        assertEquals(doc.layers.entries, loaded.layers.entries)
        assertEquals(doc.items.map { it.layerId }, loaded.items.map { it.layerId })
    }

    @Test fun pagedSnapshotFreezesMembershipAcrossMovesAndLayerDeletion() {
        val doc = Document.blank()
        val page = doc.pages.first()
        val ink = stroke().also { page.items.add(it) }
        val saved = doc.snapshot()
        val next = page.layers.create("Later")
        ink.layerId = next.id
        page.layers.delete(DrawingLayers.DEFAULT)
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val bytes = ByteArrayOutputStream().also { codec.write(saved, it) }.toByteArray()
        val loaded = codec.read(bytes.inputStream()).pages.first()
        assertEquals(DrawingLayers.DEFAULT, loaded.items.first().layerId)
        assertEquals(1, loaded.layers.entries.size)
    }

    @Test fun canvasSnapshotFreezesMembershipAcrossMoves() {
        val doc = InfiniteDocument()
        val ink = stroke().also(doc::add)
        val saved = doc.snapshotForWrite()
        val next = doc.layers.create("Later")
        ink.layerId = next.id
        val codec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())
        val bytes = ByteArrayOutputStream().also { codec.write(saved, it) }.toByteArray()
        val loaded = codec.read(bytes.inputStream())
        assertEquals(DrawingLayers.DEFAULT, loaded.items.first().layerId)
        assertEquals(1, loaded.layers.entries.size)
    }

    @Test fun pdfPayloadStaysByteIdenticalWithLayers() {
        val pdf = java.io.File.createTempFile("layer-pdf", ".pdf").apply { writeBytes(byteArrayOf(37, 80, 68, 70, 0, -1, 42)); deleteOnExit() }
        val doc = Document.blank().apply { pdfFile = pdf }
        val page = doc.pages.first().apply { pdfPage = 0 }
        page.layers.create("Annotations")
        page.items.add(stroke())
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val bytes = ByteArrayOutputStream().also { codec.write(doc.snapshot(), it) }.toByteArray()
        val directory = java.nio.file.Files.createTempDirectory("layer-pdf-read").toFile()
        val loaded = codec.read(bytes.inputStream(), pdfDir = directory)
        try { assertArrayEquals(pdf.readBytes(), loaded.pdfFile!!.readBytes()) }
        finally { directory.deleteRecursively() }
    }
}
