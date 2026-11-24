package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.openRead
import okio.*
import kotlin.test.assertFailsWith
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.CutEdge
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Room
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.testkit.assertEquals
import com.xnotes.core.testkit.assertFalse
import com.xnotes.core.testkit.assertNull
import com.xnotes.core.testkit.assertTrue
import kotlin.test.Test

class RoomCodecTest : BundleTestFiles() {

    private val notes = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
    private val canvases = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun imageFile(): AssetFile = testFile("img", null).apply { setBytes(byteArrayOf(1, 2, 3, 4)) }

    private fun stroke() = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(10.0, 10.0, 1.0), Sample(10.0, 90.0, 1.0)))

    private fun write(doc: Document): ByteArray = Buffer().also { notes.write(doc, it) }.bytes()

    private fun read(bytes: ByteArray): Document =
        notes.read(Buffer().write(bytes), imageDir = testDirectory("rooms-img"))

    private fun manifestOf(bundle: ByteArray): String {
        bundleReader(Buffer().write(bundle)).use { zis ->
            var entry = zis.next()
            while (entry != null) {
                if (entry.name == "manifest.json") return requireNotNull(entry).data.readByteArray().decodeToString()
                entry = zis.next()
            }
        }
        return ""
    }

    @Test fun roomsCutsAndPartsComeBack() {
        val doc = Document()
        val page = Page(200.0, 400.0, rooms = listOf(Room(100.0, 50.0), Room(20.0, 10.0, sideways = true)))
        val ink = stroke().also { it.cuts = listOf(CutEdge(10.0, 40.0, 0.0, 1.0)) }
        val pic = ImageItem(ImageData(imageFile(), 8, 8), Rect(0.0, 0.0, 8.0, 4.0), part = Rect.ltrb(0.0, 0.5, 1.0, 1.0))
        page.items += ink
        page.items += pic
        doc.pages += page
        val back = read(write(doc)).pages.single()
        assertEquals(page.rooms, back.rooms)
        assertEquals(listOf(CutEdge(10.0, 40.0, 0.0, 1.0)), (back.items[0] as Stroke).cuts)
        assertEquals(Rect.ltrb(0.0, 0.5, 1.0, 1.0), (back.items[1] as ImageItem).part)
    }

    @Test fun roomAndMarginColoursComeBack() {
        val doc = Document()
        doc.margins = PageMargins(left = 0.1, color = Rgba(20, 30, 40))
        val page = Page(200.0, 400.0, margins = PageMargins(color = Rgba(1, 2, 3)), rooms = listOf(Room(100.0, 50.0, color = Rgba(200, 100, 50)), Room(300.0, 20.0)))
        doc.pages += page
        val back = read(write(doc))
        assertEquals(page.rooms, back.pages.single().rooms)
        assertEquals(PageMargins(color = Rgba(1, 2, 3)), back.pages.single().margins)
        assertEquals(PageMargins(left = 0.1, color = Rgba(20, 30, 40)), back.margins)
    }

    @Test fun defaultColoursWriteNothing() {
        val doc = Document()
        doc.pages += Page(200.0, 400.0, margins = PageMargins(top = 0.2), rooms = listOf(Room(100.0, 50.0)))
        assertFalse(manifestOf(write(doc)).contains("color"))
    }

    @Test fun nothingNewIsWrittenWithoutRoom() {
        val doc = Document()
        doc.pages += Page(200.0, 400.0).also { it.items += stroke() }
        val manifest = manifestOf(write(doc))
        assertFalse(manifest.contains("rooms"))
        assertFalse(manifest.contains("cuts"))
        assertFalse(manifest.contains("part"))
    }

    @Test fun brokenRoomIsLeftOut() {
        val manifest = """
            {"format":"xnote","version":1,"dpi":300,"has_pdf":false,"bookmarks":[],
             "pages":[{"width":200,"height":400,"pdf_page":null,"items":[],
               "rooms":[{"at":10,"size":-5},{"at":50,"size":20},{"at":55,"size":5},{"size":3},"x",{"at":300,"size":900}]}]}
        """.trimIndent()
        val out = Buffer()
        bundleWriter(out).use { zos ->
            zos.putDeflated("manifest.json",manifest.encodeToByteArray())
        }
        val page = read(out.bytes()).pages.single()
        assertEquals(listOf(Room(50.0, 20.0)), page.rooms)
    }

    @Test fun cutInkAndSlicedPicturesKeepTheirEdgesOnACanvas() {
        val doc = InfiniteDocument()
        doc.add(stroke().also { it.cuts = listOf(CutEdge(10.0, 40.0, 0.0, -1.0)) })
        doc.add(ImageItem(ImageData(imageFile(), 8, 8), Rect(0.0, 0.0, 8.0, 4.0), part = Rect.ltrb(0.25, 0.0, 1.0, 1.0)))
        doc.add(ImageItem(ImageData(imageFile(), 8, 8), Rect(0.0, 0.0, 8.0, 8.0)))
        val bytes = Buffer().also { canvases.write(doc, it) }.bytes()
        val back = canvases.read(Buffer().write(bytes), imageDir = testDirectory("rooms-canvas"))
        assertEquals(listOf(CutEdge(10.0, 40.0, 0.0, -1.0)), (back.items[0] as Stroke).cuts)
        assertEquals(Rect.ltrb(0.25, 0.0, 1.0, 1.0), (back.items[1] as ImageItem).part)
        assertNull((back.items[2] as ImageItem).part)
        assertTrue((back.items[0] as Stroke).contains(Pt(10.0, 60.0)))
    }
}
