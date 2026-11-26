package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.*
import com.xnotes.core.pdf.TextQuad
import com.xnotes.core.stroke.Sample
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.map.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipFile
import org.junit.Test

class AndroidBundleGoldens {
    private val image = FakeImageCodec().apply { probeWidth = 1; probeHeight = 1 }
    private val notes = DocumentCodec(image, FakeTextMeasurer())
    private val canvases = CanvasCodec(image, FakeTextMeasurer())
    private fun id(number: Int) = "12633c19-8823-481a-b949-${number.toString().padStart(12, '0')}"
    private fun stroke(offset: Double) = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(
        Sample(offset + 0.25, -3.5, 0.125), Sample(offset + 2.75, 9.0, 0.875)))

    @Test fun verifyLegacyRoundTrip() {
        val output = File(requireNotNull(System.getenv("XNOTES_GOLDEN_DIR")))
        val assets = Files.createTempDirectory("legacy-resave").toFile()
        try {
            val bundles = output.listFiles()!!.filter { it.extension in setOf("xnote", "xcanvas") }
            check(bundles.size == 5)
            for (bundle in bundles) {
                val copy = File(assets, bundle.name)
                bundle.inputStream().use { source -> copy.outputStream().use { destination ->
                    if (bundle.extension == "xnote") notes.write(notes.read(source, assets, assets).snapshot(), destination)
                    else canvases.write(canvases.read(source, assets).snapshotForWrite(), destination)
                } }
                ZipFile(bundle).use { before -> ZipFile(copy).use { after ->
                    check(before.entries().asSequence().map { it.name }.toList() == after.entries().asSequence().map { it.name }.toList())
                    for (entry in before.entries()) {
                        val next = after.getEntry(entry.name)
                        check(entry.crc == next.crc && entry.method == next.method) {
                            "${bundle.name}: ${entry.name}: ${before.getInputStream(entry).reader().readText()} != ${after.getInputStream(next).reader().readText()}"
                        }
                    }
                } }
            }
        } finally { assets.deleteRecursively() }
    }

    @Test fun generate() {
        val output = File(requireNotNull(System.getenv("XNOTES_GOLDEN_DIR")))
        require(output.isDirectory && output.list()!!.isEmpty())
        val assets = Files.createTempDirectory("golden-assets").toFile()
        try {
            fun note(name: String, number: Int, count: Int, content: (Document) -> Unit) {
                val document = Document.blank(count)
                document.identity = BundleIdentity(id(number))
                document.pages.forEachIndexed { index, page -> page.referenceIdentity.restore(id(100 * number + index)) }
                document.enableReferenceAnchors()
                document.created = 1_750_000_000_000
                content(document)
                val file = File(output, "$name.xnote")
                file.outputStream().use { notes.write(document.snapshot(), it) }
                val loaded = file.inputStream().use { notes.read(it, assets, assets) }
                record(file, "note", loaded.identity.documentId, loaded.pages.map { requireNotNull(it.referenceIdentity.id) })
            }
            fun canvas(name: String, number: Int, content: (InfiniteDocument) -> Unit) {
                val document = InfiniteDocument()
                document.identity = BundleIdentity(id(number))
                document.created = 1_750_000_000_000
                content(document)
                val file = File(output, "$name.xcanvas")
                file.outputStream().use { canvases.write(document.snapshotForWrite(), it) }
                val loaded = file.inputStream().use { canvases.read(it, assets) }
                record(file, "canvas", loaded.identity.documentId, emptyList())
            }
            note("note-ink-text-pages", 1, 2) { document ->
                document.pages.forEachIndexed { index, page -> page.items.add(stroke(index * 10.0)) }
                document.flow.paragraphs.clear()
                document.flow.paragraphs += Paragraph(mutableListOf(Run("ページ <一> & two")))
            }
            note("note-pdf-annotated", 2, 1) { document ->
                document.pdfFile = File(assets, "source.pdf").apply { writeBytes(MiniPdf.exported(byteArrayOf(), marker = false)) }
                document.pages.single().apply {
                    pdfPage = 0
                    markups = listOf(TextMarkup(id(21), MarkupType.HIGHLIGHT, Rgba(120,120,120), 0.5,
                        listOf(TextQuad(1f,2f,12f,20f,0)), "source", "margin", 1_750_000_000_000, 1_750_000_000_000))
                }
            }
            note("note-layers", 3, 1) { document ->
                document.pages.single().apply {
                    items.add(stroke(0.0))
                    layers.restore(listOf(DrawingLayer(DrawingLayers.DEFAULT), DrawingLayer("upper", "訂正", false, true)), "upper")
                    items.add(stroke(200.0))
                }
            }
            canvas("canvas-ink-image", 4) { document ->
                document.add(stroke(-5.0))
                val png = File(assets, "one.png").apply { writeBytes(Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")) }
                val target = ImageItem(ImageData(png, 1, 1), Rect(0.0, 0.0, 20.0, 20.0))
                document.add(target)
                document.add(ConnectorItem(Routing.STRAIGHT, ConnectorEnd(Pt(20.0, 10.0), target, 5),
                    ConnectorEnd(Pt(88.0, 10.0)), Rgba(0,0,0)))
            }
            canvas("canvas-mind-map", 5) { document ->
                val root = NodeId("root")
                document.map = MapModel(MapState(nodes = mapOf(root to MapNode(root, "枝", ink = NodeInk(80.0, 64.0, listOf(stroke(1.0))))),
                    roots = listOf(root), rootOrigin = mapOf(root to Pt(-2.0, 8.0)), layout = mapOf(root to LayoutKind.RIGHT)))
            }
        } finally { assets.deleteRecursively() }
    }

    private fun record(file: File, kind: String, documentId: String, pages: List<String>) {
        val lines = mutableListOf("kind=$kind", "documentId=$documentId", "pageIds=${pages.joinToString(",")}")
        val digest = MessageDigest.getInstance("SHA-256")
        ZipFile(file).use { archive ->
            lines += "entries=${archive.size()}"
            archive.entries().asSequence().forEach { entry ->
                val data = archive.getInputStream(entry).use { it.readBytes() }
                val crc = CRC32().apply { update(data) }.value
                check(crc == entry.crc)
                lines += "crc:${entry.name}=$crc"
                if (entry.name == "manifest.json" || entry.name == "map.json") {
                    val text = data.toString(Charsets.UTF_8)
                    val parser = JsonPull(text.reader(), strict = true)
                    fun visit() {
                        when (parser.peek()) {
                            JsonPull.Token.BEGIN_OBJECT -> {
                                parser.beginObject()
                                while (parser.hasNext()) {
                                    if (parser.nextName() == "samples") {
                                        check(parser.peek() == JsonPull.Token.BEGIN_ARRAY)
                                        val start = parser.position.toInt()
                                        parser.skipValue()
                                        digest.update(text.substring(start, parser.position.toInt()).toByteArray())
                                    } else visit()
                                }
                                parser.endObject()
                            }
                            JsonPull.Token.BEGIN_ARRAY -> { parser.beginArray(); while (parser.hasNext()) visit(); parser.endArray() }
                            else -> parser.skipValue()
                        }
                    }
                    visit()
                }
            }
        }
        lines += "inkDigest=${digest.digest().joinToString("") { "%02x".format(it) }}"
        File(file.parentFile, file.nameWithoutExtension + ".summary.txt").writeText(lines.sorted().joinToString("\n", postfix = "\n"))
    }
}
