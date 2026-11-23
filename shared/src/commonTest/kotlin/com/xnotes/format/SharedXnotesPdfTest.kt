package com.xnotes.format

import com.xnotes.core.testkit.assertArrayEquals
import com.xnotes.core.testkit.assertEquals
import com.xnotes.core.testkit.assertFalse
import com.xnotes.core.testkit.assertNotNull
import com.xnotes.core.testkit.assertNull
import com.xnotes.core.testkit.assertTrue
import kotlin.test.Test
import com.xnotes.kio.*
import okio.*
import kotlin.test.AfterTest

/**
 * The bytes of an xnotes PDF, on PDFs built by hand the way PdfBox and other writers lay them out.
 * What these protect is a note that reopens: every case ends by reading the note back out of the file.
 */
class SharedXnotesPdfTest {

    private val fs = KFiles.system
    private val directory = (KFiles.tempDirectory() / Uuids.random()).also { fs.createDirectory(it) }
    @AfterTest fun cleanup() = fs.deleteRecursively(directory)
    private fun temp(bytes: ByteArray) = (directory / Uuids.random()).also { fs.write(it) { write(bytes) } }
    private fun Path.readBytes() = fs.read(this) { readByteArray() }
    private fun Path.length() = fs.metadata(this).size!!
    private fun <T> Path.rw(block: (PositionedFile) -> T): T = openReadWrite(this).use(block)
    private fun appendTail(pdf: Path, note: ByteArray) = pdf.rw { ch -> openRead(temp(note)).use { XnotesPdf.appendTail(ch, it) } }
    private fun readAll(region: FileRegion) = region.sourceAt(0).buffer().use { it.readByteArray() }
    private fun ByteArray.latin() = CharArray(size) { (this[it].toInt() and 255).toChar() }.concatToString()

    /** The note inside [pdf], which has to be ours. */
    private fun noteIn(pdf: Path): ByteArray = pdf.rw { ch ->
        val ours = XnotesPdf.inspect(ch) as XnotesPdf.Inspection.Ours
        readAll(XnotesPdf.embedded(ch, ours)!!)
    }

    private fun ours(pdf: Path): XnotesPdf.Inspection.Ours? = pdf.rw { XnotesPdf.inspect(it) as? XnotesPdf.Inspection.Ours }

    private fun stamp(pdf: Path): Boolean = pdf.rw { XnotesPdf.stamp(it) }

    /** Bytes that read like the inside of a PDF, which a stored `source.pdf` puts in the middle of the note. */
    private fun note(tag: String, size: Int = 600): ByteArray {
        val seed = "PK $tag 1 0 obj << /Length 9 >> stream\nendstream endobj xref trailer startxref 9 %%EOF\n".encodeToByteArray()
        return ByteArray(size) { seed[it % seed.size] }
    }

    // --- PDFs by hand ---

    private fun body(note: ByteArray) = MiniPdf.body(note)

    private fun exported(note: ByteArray, marker: Boolean = true, extraObjects: Int = 0, encrypt: Boolean = false) =
        MiniPdf.exported(note, marker, extraObjects, encrypt)

    private fun endingInAStream(note: ByteArray) = MiniPdf.endingInAStream(note)

    private fun contains(bytes: ByteArray, word: String, from: Int = 0): Boolean {
        val w = word.encodeToByteArray()
        return (from..bytes.size - w.size).any { i -> w.indices.all { bytes[i + it] == w[it] } }
    }

    // --- the marker ---

    @Test fun anExportIsOursOnceItsLengthIsStamped() {
        val note = note("first")
        val pdf = temp(exported(note))
        val before = pdf.readBytes()

        assertNull("an open marker proves nothing yet", ours(pdf))
        assertTrue(stamp(pdf))

        val found = ours(pdf)!!
        assertFalse(found.stale)
        assertArrayEquals(note, noteIn(pdf))
        assertEquals("stamping moves no byte", before.size.toLong(), pdf.length())
        val digits = before.size.toString().padStart(13, '0')
        assertTrue(contains(pdf.readBytes(), "/Len ($digits)"))
    }

    @Test fun aPdfWithoutTheMarkerIsPlain() {
        val pdf = temp(exported(note("plain"), marker = false))

        assertTrue(pdf.rw { XnotesPdf.inspect(it) } is XnotesPdf.Inspection.Plain)
        assertFalse(stamp(pdf))
        assertNull(appendTail(pdf, note("x")))
    }

    @Test fun whatDoesNotEndLikeAPdfIsUnreadable() {
        val withOurWords = "junk\n%xnotes-tail\nmore junk".encodeToByteArray() + ByteArray(5000) { 7 }
        for (bytes in listOf(ByteArray(0), "hello".encodeToByteArray(), ByteArray(9000) { 7 }, "startxref\n99999\n%%EOF\n".encodeToByteArray(), withOurWords)) {
            assertTrue(temp(bytes).rw { XnotesPdf.inspect(it) } is XnotesPdf.Inspection.Unreadable)
        }
    }

    @Test fun theNoteIsFoundBehindALongTable() {
        val note = note("far")
        val pdf = temp(exported(note, extraObjects = 3000)) // a table far longer than the stretch read from the end
        assertTrue(stamp(pdf))

        assertArrayEquals(note, noteIn(pdf))
    }

    @Test fun aTrailerWrittenTightReadsTheSame() {
        val note = note("tight")
        val w = body(note)
        val xref = w.table(8)
        w.put("trailer<</Size 8/Root 1 0 R/Info 3 0 R % a comment\r/XNotes<</V 1/Obj 7 0 R/Len(0000000000000)>>/ID[(a\\)b)<0A>]>>\r\n")
        w.end(xref)
        val pdf = temp(w.out.toByteArray())

        assertTrue(stamp(pdf))
        assertArrayEquals(note, noteIn(pdf))
    }

    // --- another app ---

    @Test fun bytesAppendedByAnotherAppAreNoticed() {
        val note = note("mine")
        val pdf = temp(exported(note))
        stamp(pdf)
        val own = pdf.length()
        fs.appendingSink(pdf).buffer().use { it.write("\n% touched\n".encodeToByteArray()) }

        val found = pdf.rw { XnotesPdf.inspect(it) } as XnotesPdf.Inspection.Appended
        assertEquals(own, found.ownLength)
        val back = pdf.rw { ch -> readAll(XnotesPdf.embedded(FileRegion(ch, 0L, found.ownLength), found.own)!!) }
        assertArrayEquals(note, back)
    }

    /** An incremental update by another app: one more object, a table, and a trailer pointing back at ours. */
    private fun updateBy(pdf: Path, keepsOurKey: Boolean) {
        val prev = pdf.readBytes().latin().let { it.substring(it.lastIndexOf("startxref") + 9).trim().lines().first().toInt() }
        val base = pdf.length().toInt()
        val w = MiniPdf.Writer()
        w.put("\n")
        val at = base + w.out.size()
        w.put("9 0 obj\n<<\n/Type /Annot\n/Subtype /Highlight\n>>\nendobj\n")
        val xref = base + w.out.size()
        w.put("xref\n9 1\n${at.toString().padStart(10, '0')} 00000 n\r\n")
        w.put("trailer\n<<\n/Root 1 0 R\n/Size 10\n/Prev $prev\n")
        if (keepsOurKey) w.put(pdf.readBytes().latin().let { it.substring(it.lastIndexOf("/XNotes"), it.lastIndexOf(">>\nstartxref")) })
        w.put(">>\n")
        w.end(xref)
        fs.appendingSink(pdf).buffer().use { it.write(w.out.toByteArray()) }
    }

    @Test fun anotherAppsUpdateIsNoticedWhetherOrNotItKeepsOurKey() {
        for (keeps in listOf(false, true)) {
            val note = note("mine")
            val pdf = temp(exported(note))
            stamp(pdf)
            val own = pdf.length()
            updateBy(pdf, keepsOurKey = keeps)

            val found = pdf.rw { XnotesPdf.inspect(it) } as XnotesPdf.Inspection.Appended
            assertEquals(own, found.ownLength)
            assertFalse(found.own.stale)
        }
    }

    @Test fun anUpdateAfterATailLeavesTheTailsNoteReadable() {
        val pdf = temp(exported(note("old")))
        stamp(pdf)
        val newer = note("newer", 900)
        val tail = appendTail(pdf, newer)!!
        updateBy(pdf, keepsOurKey = false)

        val found = pdf.rw { XnotesPdf.inspect(it) } as XnotesPdf.Inspection.Appended
        assertEquals(tail.end, found.ownLength)
        assertTrue(found.own.stale)
        val back = pdf.rw { ch -> readAll(XnotesPdf.embedded(FileRegion(ch, 0L, found.ownLength), found.own)!!) }
        assertArrayEquals(newer, back)
    }

    @Test fun aFileCutShortIsNotOurs() {
        val bytes = exported(note("cut"))
        val pdf = temp(bytes)
        stamp(pdf)
        val stamped = pdf.readBytes()
        // The same file with a byte gone from its middle: the marker's length no longer fits it.
        val damaged = temp(stamped.copyOfRange(0, 40) + stamped.copyOfRange(41, stamped.size))

        assertTrue(damaged.rw { XnotesPdf.inspect(it) } is XnotesPdf.Inspection.Unreadable)
    }

    // --- tails ---

    @Test fun aTailCarriesTheNewerNoteAndLeavesTheExportUntouched() {
        val pdf = temp(exported(note("old")))
        stamp(pdf)
        val export = pdf.readBytes()
        val newer = note("newer", 2500)

        val tail = appendTail(pdf, newer)!!

        val now = pdf.readBytes()
        assertEquals(export.size.toLong(), tail.start)
        assertEquals(now.size.toLong(), tail.end)
        assertArrayEquals(export, now.copyOfRange(0, export.size))
        assertTrue(now.latin().endsWith("%%EOF\n"))
        assertTrue(ours(pdf)!!.stale)
        assertArrayEquals(newer, noteIn(pdf))
        assertArrayEquals(newer, pdf.rw { readAll(tail.window(it)) })
    }

    @Test fun aTailIsCutAndWrittenAgainLongerOrShorter() {
        val pdf = temp(exported(note("old")))
        stamp(pdf)
        val export = pdf.length()
        var tail = appendTail(pdf, note("one", 2000))!!

        for (size in listOf(5000, 300, 2000)) {
            val next = note("size $size", size)
            tail = pdf.rw { ch -> openRead(temp(next)).use { XnotesPdf.rewriteTail(ch, tail, it) } }!!

            assertEquals(export, tail.start)
            assertEquals(pdf.length(), tail.end)
            assertTrue(pdf.readBytes().latin().endsWith("%%EOF\n"))
            assertTrue(ours(pdf)!!.stale)
            assertArrayEquals(next, noteIn(pdf))
        }
    }

    @Test fun aLaterSessionAppendsAfterAnEarlierTail() {
        val pdf = temp(exported(note("old")))
        stamp(pdf)
        val first = appendTail(pdf, note("first session", 1200))!!
        val second = appendTail(pdf, note("second session", 700))!!

        assertEquals("the earlier tail is not cut", first.end, second.start)
        assertArrayEquals(note("second session", 700), noteIn(pdf))
        // What the first session left is still a whole file of ours underneath.
        val under = pdf.rw { ch ->
            val w = FileRegion(ch, 0L, first.end)
            readAll(XnotesPdf.embedded(w, XnotesPdf.inspect(w) as XnotesPdf.Inspection.Ours)!!)
        }
        assertArrayEquals(note("first session", 1200), under)
    }

    @Test fun aTailIsATableLikeTheExportItFollows() {
        val pdf = temp(exported(note("old")))
        stamp(pdf)
        val tail = appendTail(pdf, note("newer"))!!

        val after = pdf.readBytes()
        assertTrue(contains(after, "trailer", tail.start.toInt() + 700))
        assertFalse("a chain that mixes tables and streams is one some readers refuse", contains(after, "/XRef", tail.start.toInt()))
    }

    @Test fun onlyAFileThatIsOursTakesATail() {
        val unstamped = temp(exported(note("open marker")))
        val stream = temp(endingInAStream(note("stream")))
        val touched = temp(exported(note("touched"))).also { stamp(it); fs.appendingSink(it).buffer().use { output -> output.writeByte(10) } }

        for (pdf in listOf(unstamped, stream, touched)) {
            val before = pdf.readBytes()
            assertNull(appendTail(pdf, note("x")))
            assertArrayEquals(before, pdf.readBytes())
        }
        assertTrue(stream.rw { XnotesPdf.inspect(it) } is XnotesPdf.Inspection.Plain)
    }

    @Test fun anotherAppsUpdateWrittenAsAStreamIsNoticed() {
        val note = note("mine")
        val pdf = temp(exported(note))
        stamp(pdf)
        val own = pdf.length()
        val prev = pdf.readBytes().latin().let { it.substring(it.lastIndexOf("startxref") + 9).trim().lines().first().toInt() }
        val w = MiniPdf.Writer()
        w.put("\n")
        val xref = own.toInt() + w.out.size()
        w.put("9 0 obj\n<<\n/Type /XRef\n/Size 10\n/W [1 2 0]\n/Index [9 1]\n/Root 1 0 R\n/Prev $prev\n/Filter /FlateDecode\n/Length 5\n>>\nstream\r\n")
        w.out.write(byteArrayOf(120, 1, 3, 0, 0)) // rows this never has to read
        w.put("\r\nendstream\nendobj\n")
        w.end(xref)
        fs.appendingSink(pdf).buffer().use { it.write(w.out.toByteArray()) }

        val found = pdf.rw { XnotesPdf.inspect(it) } as XnotesPdf.Inspection.Appended
        assertEquals(own, found.ownLength)
        val back = pdf.rw { ch -> readAll(XnotesPdf.embedded(FileRegion(ch, 0L, found.ownLength), found.own)!!) }
        assertArrayEquals(note, back)
    }

    // --- a write that stopped part way ---

    private fun torn(pdf: Path): XnotesPdf.Inspection.Torn = pdf.rw { XnotesPdf.inspect(it) } as XnotesPdf.Inspection.Torn

    private fun noteUnder(pdf: Path, found: XnotesPdf.Inspection.Torn): ByteArray =
        pdf.rw { ch -> readAll(XnotesPdf.embedded(FileRegion(ch, 0L, found.ownLength), found.own)!!) }

    @Test fun aTailCutShortAnywhereLeavesTheNoteUnderItReadable() {
        val older = note("exported")
        val whole = temp(exported(older))
        stamp(whole)
        val export = whole.length()
        val tail = appendTail(whole, note("never finished", 9000))!!
        val full = whole.readBytes()

        // In its first line, in its head, in the note, in the closing bytes, and one byte short of done.
        for (cut in listOf(export + 5, export + 20, tail.zipStart + 4000, tail.zipStart + tail.zipLength + 30, tail.end - 1)) {
            val pdf = temp(full.copyOfRange(0, cut.toInt()))

            val found = torn(pdf)
            assertEquals("cut at $cut", export, found.ownLength)
            assertFalse(found.own.stale)
            assertArrayEquals(older, noteUnder(pdf, found))

            // Cut back to what is whole, it is ours again and takes the next save.
            pdf.rw { it.truncate(found.ownLength) }
            assertFalse(ours(pdf)!!.stale)
            assertNotNull(appendTail(pdf, note("saved after all")))
            assertArrayEquals(note("saved after all"), noteIn(pdf))
        }
    }

    @Test fun aLaterTailCutShortFallsBackToTheTailBeforeIt() {
        val pdf = temp(exported(note("exported")))
        stamp(pdf)
        val first = appendTail(pdf, note("first session", 1500))!!
        val second = appendTail(pdf, note("second session", 2500))!!
        pdf.rw { it.truncate(second.zipStart + 700) }

        val found = torn(pdf)
        assertEquals(first.end, found.ownLength)
        assertTrue(found.own.stale)
        assertArrayEquals(note("first session", 1500), noteUnder(pdf, found))
    }

    @Test fun aTailOpenForSplicingHasNoEndUntilItIsClosed() {
        val older = note("exported")
        val pdf = temp(exported(older))
        stamp(pdf)
        val tail = appendTail(pdf, note("saved", 3000))!!

        pdf.rw { XnotesPdf.openTail(it, tail) }

        // A crash here must not leave a file that reads as whole around a half-spliced note.
        val found = torn(pdf)
        assertEquals(tail.start, found.ownLength)
        assertArrayEquals(older, noteUnder(pdf, found))

        val closed = pdf.rw { XnotesPdf.closeTail(it, tail, tail.zipLength) }!!
        assertEquals(pdf.length(), closed.end)
        assertArrayEquals(note("saved", 3000), noteIn(pdf))
    }

    @Test fun aRewriteThatFailsLeavesTheFileAsItWasBeforeTheTailOrTorn() {
        val older = note("exported")
        val pdf = temp(exported(older))
        stamp(pdf)
        val export = pdf.length()
        val tail = appendTail(pdf, note("saved", 3000))!!
        val next = temp(note("too short a source", 2000))

        // A source that runs dry part way, as a failing read would.
        val threw = pdf.rw { ch ->
            openRead(next).use { zip ->
                runCatching { XnotesPdf.rewriteTail(ch, tail, FileRegion(zip, 0L, 2500L)) }.isFailure
            }
        }

        assertTrue(threw)
        val found = torn(pdf)
        assertEquals(export, found.ownLength)
        assertArrayEquals(older, noteUnder(pdf, found))
    }

}
