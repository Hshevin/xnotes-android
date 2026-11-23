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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The in-place save: a bundle's manifest is replaced without the assets ahead of it being read or
 * written. These drive exactly the sequence the editor drives, against a real file on disk, because
 * the failure this code can produce is a note that no longer opens.
 */
class ZipTailTest {

    private val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun imageFile(bytes: ByteArray): File =
        File.createTempFile("img", null).apply { writeBytes(bytes); deleteOnExit() }

    /** A note carrying one big image, so there is a real asset prefix to preserve. */
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

    private fun write(doc: Document, to: File) {
        FileOutputStream(to).use { codec.write(doc, it) }
    }

    private fun directoryOf(file: File): ZipTail.Directory =
        FileInputStream(file).use { ZipTail.read(it.channel) }!!

    @Test fun aFileThatOnlyGotANewModifiedTimeIsTheSameSave() {
        val file = File.createTempFile("note", ".xnote").apply { deleteOnExit() }
        write(docWith(strokes = 3, image = imageFile(ByteArray(4096) { 7 })), file)
        val known = directoryOf(file)

        // Storage whose modified time settles late looks like this: the stamp moves, the bytes do not.
        assertTrue(file.setLastModified(file.lastModified() - 3_600_000L))

        assertTrue(known.sameAs(directoryOf(file)))
    }

}
