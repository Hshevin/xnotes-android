package com.xnotes.format

import com.xnotes.core.pal.*
import com.xnotes.kio.*
import okio.*
import kotlin.test.assertFailsWith

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
import com.xnotes.core.testkit.assertArrayEquals
import com.xnotes.core.testkit.assertEquals
import com.xnotes.core.testkit.assertFalse
import com.xnotes.core.testkit.assertNull
import com.xnotes.core.testkit.assertTrue
import kotlin.test.Test

/**
 * The in-place save: a bundle's manifest is replaced without the assets ahead of it being read or
 * written. These drive exactly the sequence the editor drives, against a real file on disk, because
 * the failure this code can produce is a note that no longer opens.
 */
class SharedZipTailTest : BundleTestFiles() {

    private val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun imageFile(bytes: ByteArray): AssetFile =
        testFile("img", null).apply { setBytes(bytes) }

    /** A note carrying one big image, so there is a real asset prefix to preserve. */
    private fun docWith(strokes: Int, image: AssetFile): Document {
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

    private fun write(doc: Document, to: AssetFile) {
        to.fileSink().use { codec.write(doc, it) }
    }

    /** The editor's in-place path, verbatim enough to be worth testing. */
    private fun splice(file: AssetFile, doc: Document): Long {
        val assets = codec.imageAssets(doc)
        val keepCount = assets.size + if (doc.pdfFile != null) 1 else 0
        val dir = openRead(file.assetPath()).use { ZipTail.read(it) } ?: return -1
        val ordered = dir.entries.sortedBy { it.localOffset }
        val tailStart = ordered[keepCount].localOffset
        val tmp = testFile("tail", ".zip")
        tmp.fileSink().use { out ->
            bundleWriter(out,level=1).use { zos ->
                codec.writeTail(zos, doc, assets)
            }
        }
        return openRead(tmp.assetPath()).use { tail ->
            val tailDir = ZipTail.read(tail)!!
            openReadWrite(file.assetPath()).use { raf ->
                ZipTail.splice(raf, tailStart, ordered.subList(0, keepCount), tail, tailDir)
            }
        }
    }

    private fun entriesOf(file: AssetFile): List<String> {
        val names = mutableListOf<String>()
        bundleReader(file.input()).use { zis ->
            var e = zis.next()
            while (e != null) {
                names += e.name
                e.data.readByteArray()
                e.finish()
                e = zis.next()
            }
        }
        return names
    }

    private fun readBack(file: AssetFile): Document {
        val imageDir = testDirectory("xnotes-splice-img")
        return file.input().use { codec.read(it, imageDir = imageDir) }
    }

    @Test fun aSplicedNoteReadsBackWithTheNewInk() {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val image = imageFile(bytes)
        val file = testFile("note", ".xnote")
        write(docWith(strokes = 2, image = image), file)

        val edited = docWith(strokes = 9, image = image)
        assertTrue(splice(file, edited) > 0)

        val back = readBack(file)
        assertEquals(listOf("assets/image-000.png", BundleIdentity.ENTRY, "manifest.json"), entriesOf(file))
        assertEquals(10, back.pages[0].items.size) // the image plus nine strokes
        assertArrayEquals(bytes, (back.pages[0].items[0] as ImageItem).image.file.fileBytes())
        assertEquals(11.5, (back.pages[0].items[9] as Stroke).samples[1].x, 1e-9) // stroke i=8
    }

    @Test fun aShorterManifestLeavesNoTrailingRubbish() {
        val image = imageFile(ByteArray(4096) { 3 })
        val file = testFile("note", ".xnote")
        write(docWith(strokes = 60, image = image), file)
        val long = KFiles.system.metadata(file.assetPath()).size!!

        val length = splice(file, docWith(strokes = 1, image = image))

        assertTrue("the file should have shrunk", length < long)
        assertEquals(length, KFiles.system.metadata(file.assetPath()).size!!) // truncated to exactly what was written
        assertEquals(2, readBack(file).pages[0].items.size)
    }

    @Test fun theAssetBytesAreNeverTouched() {
        val image = imageFile(ByteArray(8192) { (it % 97).toByte() })
        val file = testFile("note", ".xnote")
        write(docWith(strokes = 3, image = image), file)
        val dir = openRead(file.assetPath()).use { ZipTail.read(it) }!!
        val tailStart = dir.entries.sortedBy { it.localOffset }[1].localOffset
        val before = ByteArray(tailStart.toInt())
        file.input().buffer().use { it.readFully(before) }

        splice(file, docWith(strokes = 40, image = image))

        val after = ByteArray(tailStart.toInt())
        file.input().buffer().use { it.readFully(after) }
        assertArrayEquals(before, after)
    }

    @Test fun splicingTwiceInARowStaysValid() {
        val image = imageFile(ByteArray(2048) { 9 })
        val file = testFile("note", ".xnote")
        write(docWith(strokes = 1, image = image), file)

        splice(file, docWith(strokes = 20, image = image))
        splice(file, docWith(strokes = 5, image = image))

        assertEquals(6, readBack(file).pages[0].items.size)
        assertEquals(listOf("assets/image-000.png", BundleIdentity.ENTRY, "manifest.json"), entriesOf(file))
    }

    @Test fun aSplicedNoteMatchesOneWrittenWhole() {
        val image = imageFile(ByteArray(1024) { 5 })
        val spliced = testFile("spliced", ".xnote")
        val whole = testFile("whole", ".xnote")
        write(docWith(strokes = 2, image = image), spliced)
        val edited = docWith(strokes = 7, image = image)
        splice(spliced, edited)
        write(edited, whole)

        assertEquals(entriesOf(whole), entriesOf(spliced))
        assertEquals(readBack(whole).pages[0].items.size, readBack(spliced).pages[0].items.size)
    }

    @Test fun readRefusesWhatItDidNotWrite() {
        val file = testFile("odd", ".zip")
        // A zip carrying a comment: never something this wrote, so never something to patch blind.
        file.fileSink().use { out ->
            bundleWriter(out,comment="hello").use { zos ->
                zos.putDeflated("a.txt",byteArrayOf(1, 2, 3))
            }
        }
        assertNull(openRead(file.assetPath()).use { ZipTail.read(it) })

        val empty = testFile("empty", ".zip")
        assertNull(openRead(empty.assetPath()).use { ZipTail.read(it) })
    }

    // --- telling one save from another, which is what decides whether autosave forks ---

    private fun directoryOf(file: AssetFile): ZipTail.Directory =
        openRead(file.assetPath()).use { ZipTail.read(it) }!!

    /** A one-entry zip holding [text], its entry stamped as written at [time]. */
    private fun zipOf(text: String, time: Long): AssetFile {
        val file = testFile("save", ".zip")
        file.fileSink().use { out ->
            bundleWriter(out).use { zos ->
                zos.entry("manifest.json",time,null,0) { it.putBytes(text.encodeToByteArray()) }
            }
        }
        return file
    }

    @Test fun moreInkIsADifferentSaveSplicedOrWrittenWhole() {
        val image = imageFile(ByteArray(4096) { 7 })
        val file = testFile("note", ".xnote")
        write(docWith(strokes = 2, image = image), file)
        val known = directoryOf(file)

        splice(file, docWith(strokes = 9, image = image))
        val spliced = directoryOf(file)
        assertFalse(known.sameAs(spliced))
        assertTrue(spliced.sameAs(directoryOf(file))) // reading it again is still that save

        write(docWith(strokes = 4, image = image), file)
        assertFalse(spliced.sameAs(directoryOf(file)))
    }

    @Test fun otherContentOfTheSameSizeIsADifferentSave() {
        val at = 1_790_000_000_000L
        val one = zipOf("ink a", at)
        val other = zipOf("ink b", at)

        assertEquals(KFiles.system.metadata(one.assetPath()).size!!, KFiles.system.metadata(other.assetPath()).size!!) // nothing but the checksum tells them apart
        assertFalse(directoryOf(one).sameAs(directoryOf(other)))
    }

    @Test fun theSameContentSavedAtAnotherTimeIsADifferentSave() {
        val at = 1_790_000_000_000L
        val known = directoryOf(zipOf("ink", at))

        assertTrue(known.sameAs(directoryOf(zipOf("ink", at))))
        assertFalse(known.sameAs(directoryOf(zipOf("ink", at + 60_000L))))
    }
}
