package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The bytes of an xnotes PDF, on PDFs built by hand the way PdfBox and other writers lay them out.
 * What these protect is a note that reopens: every case ends by reading the note back out of the file.
 */
class XnotesPdfTest {

    private val latin = Charsets.ISO_8859_1

    private fun temp(bytes: ByteArray): File =
        File.createTempFile("xnotes", ".pdf").apply { writeBytes(bytes); deleteOnExit() }

    private fun <T> File.rw(block: (FileChannel) -> T): T = RandomAccessFile(this, "rw").use { block(it.channel) }

    private fun zipFile(bytes: ByteArray): File =
        File.createTempFile("note", ".xnote").apply { writeBytes(bytes); deleteOnExit() }

    private fun appendTail(pdf: File, note: ByteArray): XnotesPdf.Tail? =
        pdf.rw { ch -> FileInputStream(zipFile(note)).use { XnotesPdf.appendTail(ch, it.channel) } }

    private fun readAll(w: FileWindow): ByteArray {
        val buf = ByteBuffer.allocate(w.size().toInt())
        while (buf.hasRemaining()) if (w.read(buf, buf.position().toLong()) < 0) break
        return buf.array()
    }

    /** The note inside [pdf], which has to be ours. */
    private fun noteIn(pdf: File): ByteArray = pdf.rw { ch ->
        val ours = XnotesPdf.inspect(ch) as XnotesPdf.Inspection.Ours
        readAll(XnotesPdf.embedded(ch, ours)!!)
    }

    private fun ours(pdf: File): XnotesPdf.Inspection.Ours? = pdf.rw { XnotesPdf.inspect(it) as? XnotesPdf.Inspection.Ours }

    private fun stamp(pdf: File): Boolean = pdf.rw { XnotesPdf.stamp(it) }

    /** Bytes that read like the inside of a PDF, which a stored `source.pdf` puts in the middle of the note. */
    private fun note(tag: String, size: Int = 600): ByteArray {
        val seed = "PK $tag 1 0 obj << /Length 9 >> stream\nendstream endobj xref trailer startxref 9 %%EOF\n".toByteArray(latin)
        return ByteArray(size) { seed[it % seed.size] }
    }

    // --- PDFs by hand ---

    private fun body(note: ByteArray) = MiniPdf.body(note)

    private fun exported(note: ByteArray, marker: Boolean = true, extraObjects: Int = 0, encrypt: Boolean = false) =
        MiniPdf.exported(note, marker, extraObjects, encrypt)

    private fun endingInAStream(note: ByteArray) = MiniPdf.endingInAStream(note)

    private fun contains(bytes: ByteArray, word: String, from: Int = 0): Boolean {
        val w = word.toByteArray(latin)
        return (from..bytes.size - w.size).any { i -> w.indices.all { bytes[i + it] == w[it] } }
    }

    @Test fun theEndingTellsOneSaveFromAnother() {
        val pdf = temp(exported(note("exported")))
        stamp(pdf)
        val fresh = pdf.rw { XnotesPdf.ending(it) }
        assertTrue(pdf.setLastModified(pdf.lastModified() - 3_600_000L))
        assertArrayEquals("a new modified time alone changes nothing", fresh, pdf.rw { XnotesPdf.ending(it) })

        var tail = appendTail(pdf, note("one", 800))!!
        val one = pdf.rw { XnotesPdf.ending(it) }
        assertFalse(fresh.contentEquals(one))

        // The same length of note with other bytes in it is another save.
        tail = pdf.rw { ch -> FileInputStream(zipFile(note("two", 800))).use { XnotesPdf.rewriteTail(ch, tail, it.channel) } }!!
        assertFalse(one.contentEquals(pdf.rw { XnotesPdf.ending(it) }))
        assertEquals(pdf.length(), tail.end)
    }

    @Test fun readingAndWritingGoThroughTheTwoHalvesOfADescriptor() {
        val pdf = temp(exported(note("exported")))
        stamp(pdf)
        val newer = note("through two channels", 2600)

        RandomAccessFile(pdf, "rw").use { raf ->
            // What Android gives for a provider's file: a channel that only reads and one that only writes.
            val ch = ReadWriteChannel(FileInputStream(raf.fd).channel, FileOutputStream(raf.fd).channel)
            val tail = FileInputStream(zipFile(newer)).use { XnotesPdf.appendTail(ch, it.channel) }!!
            assertEquals(ch.size(), tail.end)
            val ours = XnotesPdf.inspect(ch) as XnotesPdf.Inspection.Ours
            assertTrue(ours.stale)
            assertArrayEquals(newer, readAll(XnotesPdf.embedded(ch, ours)!!))
        }
        assertArrayEquals(newer, noteIn(pdf))
    }

    @Test fun anEncryptedPdfTakesNoTail() {
        val pdf = temp(exported(note("locked"), encrypt = true))
        stamp(pdf)
        val before = pdf.readBytes()

        assertNull(appendTail(pdf, note("x")))
        assertArrayEquals(before, pdf.readBytes())
    }

    // --- the note spliced in place, as the editor saves ---

    private val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun docWith(strokes: Int, image: File): Document {
        val doc = Document.blank(count = 1)
        doc.pages[0].items.add(ImageItem(ImageData(image, 8, 8), Rect(0.0, 0.0, 8.0, 8.0)))
        repeat(strokes) { i ->
            doc.pages[0].items.add(
                Stroke(
                    Tool.PEN,
                    ToolDefaults.configFor(Tool.PEN),
                    mutableListOf(Sample(i + 1.5, 2.25, 1.0), Sample(i + 3.5, 4.75, 0.5)),
                ),
            )
        }
        return doc
    }

    private fun bundle(doc: Document): ByteArray = ByteArrayOutputStream().also { codec.write(doc, it) }.toByteArray()

    /** The editor's in-place save, on the note inside [pdf]: its manifest replaced, then the tail closed again. */
    private fun splice(pdf: File, tail: XnotesPdf.Tail, doc: Document): XnotesPdf.Tail = pdf.rw { ch ->
        val assets = codec.imageAssets(doc)
        val ordered = ZipTail.read(tail.window(ch))!!.entries.sortedBy { it.localOffset }
        val window = XnotesPdf.openTail(ch, tail)
        val tailStart = ordered[assets.size].localOffset
        val tmp = File.createTempFile("tail", ".zip").apply { deleteOnExit() }
        FileOutputStream(tmp).use { out ->
            ZipOutputStream(out).use { zos ->
                zos.setLevel(Deflater.BEST_SPEED)
                codec.writeTail(zos, doc, assets)
            }
        }
        val length = FileInputStream(tmp).use { t ->
            ZipTail.splice(window, tailStart, ordered.subList(0, assets.size), t.channel, ZipTail.read(t.channel)!!)
        }
        assertTrue(length > 0)
        XnotesPdf.closeTail(ch, tail, length)!!
    }

    private fun readBack(bytes: ByteArray): Document {
        val imageDir = Files.createTempDirectory("xnotes-pdf-img").toFile()
        return codec.read(ByteArrayInputStream(bytes), imageDir = imageDir)
    }

    @Test fun aSaveSplicesTheNativeMetadataAndManifestInsideTheTail() {
        val pixels = ByteArray(4096) { (it % 251).toByte() }
        val image = File.createTempFile("img", null).apply { writeBytes(pixels); deleteOnExit() }
        val pdf = temp(exported(bundle(docWith(strokes = 1, image = image))))
        stamp(pdf)
        var tail = appendTail(pdf, bundle(docWith(strokes = 2, image = image)))!!
        val assetsEnd = pdf.rw { ch -> tail.zipStart + ZipTail.read(tail.window(ch))!!.entries.sortedBy { it.localOffset }[1].localOffset }
        val untouched = pdf.readBytes().copyOfRange(0, assetsEnd.toInt())

        for (strokes in listOf(40, 3, 12)) {
            tail = splice(pdf, tail, docWith(strokes = strokes, image = image))

            assertEquals(pdf.length(), tail.end)
            assertArrayEquals("nothing ahead of the manifest moves", untouched, pdf.readBytes().copyOfRange(0, assetsEnd.toInt()))
            assertTrue(ours(pdf)!!.stale)
            val inside = noteIn(pdf)
            assertEquals(tail.zipLength, inside.size.toLong())
            val back = readBack(inside)
            assertEquals(strokes + 1, back.pages[0].items.size)
            assertArrayEquals(pixels, (back.pages[0].items[0] as ImageItem).image.file.readBytes())
            // Taken out of the PDF, the note is an ordinary bundle with nothing odd about its offsets.
            val names = ArrayList<String>()
            ZipInputStream(ByteArrayInputStream(inside)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    names += e.name
                    e = zis.nextEntry
                }
            }
            assertEquals(listOf("assets/image-000.png", BundleIdentity.ENTRY, "manifest.json"), names)
        }
    }

    @Test fun theCodecReadsTheNoteThroughTheWindow() {
        val image = File.createTempFile("img", null).apply { writeBytes(ByteArray(2048) { 5 }); deleteOnExit() }
        val pdf = temp(exported(bundle(docWith(strokes = 6, image = image))))
        stamp(pdf)

        pdf.rw { ch ->
            val window = XnotesPdf.embedded(ch, XnotesPdf.inspect(ch) as XnotesPdf.Inspection.Ours)!!
            assertEquals(1, codec.peek(window)!!.pages)
            val imageDir = Files.createTempDirectory("xnotes-pdf-img").toFile()
            val doc = codec.read(Channels.newInputStream(window.position(0L)), imageDir = imageDir)
            assertEquals(7, doc.pages[0].items.size)
        }
    }

    // --- the window ---

    @Test fun aWindowReadsAndWritesOnlyItsStretch() {
        val file = temp(ByteArray(100) { it.toByte() })
        file.rw { ch ->
            val w = FileWindow(ch, 40L, 20L)
            assertEquals(20L, w.size())
            val buf = ByteBuffer.allocate(64)
            assertEquals(20, w.read(buf, 0L))
            assertEquals(40.toByte(), buf.get(0))
            assertEquals(-1, w.read(ByteBuffer.allocate(4), 20L))

            w.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 18L) // one byte past its end: it grows
            assertEquals(21L, w.size())
            w.truncate(5L) // and cutting it cuts the file there
            assertEquals(5L, w.size())
        }
        assertEquals(45L, file.length())
    }
}
