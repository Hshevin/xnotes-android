package com.xnotes.platform.ios

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.CanvasPdfLayout
import com.xnotes.core.model.*
import com.xnotes.core.pdf.TextQuad
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.stroke.Sample
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.format.MiniPdf
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import com.xnotes.nativelibs.Pdfium
import com.xnotes.nativelibs.PdfiumDocument
import com.xnotes.storage.lite.*
import okio.ByteString.Companion.decodeBase64
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import kotlin.math.abs
import kotlin.test.*

class PdfExportTest {
    private val fs = KFiles.system
    private val root = KFiles.tempDirectory() / Uuids.random()
    private val docs = (root / "Documents").also(fs::createDirectories)
    private val store = LiteStore(fs, docs, root / "Private", CgImageCodec, CgTextMeasurer)
    private val exports = (root / "Share").also(fs::createDirectory)
    private val out = exports / "Output.pdf"

    @AfterTest fun cleanup() = fs.deleteRecursively(root)

    private fun session(kind: LiteKind = LiteKind.NOTE) =
        assertIs<LiteOpen.Opened>(store.create(docs, "Paper", kind)).session

    private fun readPdf() = assertNotNull(Pdfium.openFile(out.toString()))
    private fun pageText(index: Int): String =
        PDFDocument(NSURL.fileURLWithPath(out.toString())).pageAtIndex(index.toULong())?.string.orEmpty()

    private fun PdfiumDocument.checkSize(page: Int, width: Double, height: Double) {
        val (actualWidth, actualHeight) = pageSize(page)
        assertEquals(width, actualWidth, 0.01)
        assertEquals(height, actualHeight, 0.01)
    }

    private fun line(tool: Tool, x: Double, y: Double, color: Rgba) = Stroke(tool,
        ToolConfig(baseWidth = 12.0, pressureEnabled = false, highlighterAlpha = 0.5, rgba = color),
        listOf(Sample(x, y, 1.0), Sample(x + 50.0, y, 1.0)), straight = true)

    @Test fun coverAndBodyKeepPageOrderPaperRulingMarginsAndSelectableFlow() {
        val progress = mutableListOf<Pair<Int, Int>>()
        session().use {
            val doc = it.note!!
            doc.dpi = 144
            doc.pages.clear()
            doc.pages += Page(300.0, 200.0, style = PageStyle(pageColor = Rgba(0, 255, 0))).also { p -> p.cover = true }
            doc.pages += Page(420.0, 300.0, style = PageStyle(template = "lines", spacing = 20.0,
                patternColor = Rgba(0, 0, 0)), margins = PageMargins(left = 0.1))
            doc.flow.paragraphs += Paragraph(mutableListOf(Run("Notebook")))
            assertTrue(PdfExport.export(it, out, onProgress = { done, total -> progress += done to total }))
        }
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
        readPdf().use {
            assertEquals(2, it.pageCount)
            it.checkSize(0, 150.0, 100.0)
            it.checkSize(1, 231.0, 150.0)
            assertEquals(Rgba(0, 255, 0).toArgb(), it.render(0, 150, 100)[50 * 150 + 75])
            val ruled = it.render(1, 924, 600)
            assertTrue(Rgba.fromArgb(ruled[40 * 924 + 8]).r < 40)
            assertEquals(-1, ruled[55 * 924 + 8])
        }
        assertTrue(pageText(0).isBlank())
        assertContains(pageText(1), "Notebook")
    }

    /** Two distinct pages, one with a nonzero crop origin and a native rotation. */
    private fun originalPdf() = MiniPdf.Writer().run {
        put("%PDF-1.4\n")
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Count 2 /Kids [3 0 R 4 0 R] >>")
        val font = "/Resources << /Font << /F1 5 0 R >> >>"
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 220 140] /CropBox [10 20 210 120] /Rotate 90 $font /Contents 6 0 R /Annots [10 0 R] >>")
        obj(4, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 120 80] $font /Contents 7 0 R /Annots [8 0 R] >>")
        obj(5, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        stream(6, "", "1 0 0 rg 10 20 80 45 re f 0 g BT /F1 12 Tf 50 80 Td (Rotated) Tj ET".encodeToByteArray())
        stream(7, "", "0 0 1 rg 0 50 45 30 re f 0 g BT /F1 12 Tf 30 30 Td (Original) Tj ET".encodeToByteArray())
        for (id in listOf(8, 10)) obj(id, "<< /Type /Annot /Subtype /Square /Rect [70 45 90 55] /F 4 /AP << /N 9 0 R >> >>")
        stream(9, "/Type /XObject /Subtype /Form /BBox [0 0 20 10] /Resources << >>\n",
            "0 1 0 rg 0 0 20 10 re f".encodeToByteArray())
        val xref = table(11)
        put("trailer\n<< /Size 11 /Root 1 0 R >>\n")
        end(xref)
        out.toByteArray()
    }

    @Test fun importedPagesKeepVectorTextCropRotationAndMarkupAfterReordering() {
        val source = root / "Original.pdf"
        val bytes = originalPdf()
        fs.write(source) { write(bytes) }
        val original = assertNotNull(Pdfium.open(bytes))
        original.use { reference ->
            assertIs<LiteOpen.Opened>(store.importPdf(docs, "Lecture", source)).session.use {
                val doc = it.note!!
                doc.pages.reverse()
                doc.pages.first().markups = listOf(TextMarkup("review", MarkupType.HIGHLIGHT, Rgba(255, 0, 0), 0.5,
                    listOf(TextQuad(70f, 10f, 115f, 25f, 0)), "", null, 0, 0))
                assertTrue(PdfExport.export(it, out))
            }
            readPdf().use { pdf ->
                assertEquals(2, pdf.pageCount)
                pdf.checkSize(0, 120.0, 80.0)
                pdf.checkSize(1, 100.0, 200.0)
                val first = pdf.render(0, 120, 80)
                assertEquals(Rgba(0, 0, 255).toArgb(), first[10 * 120 + 12])
                assertEquals(Rgba(0, 255, 0).toArgb(), first[30 * 120 + 80], "original PDF annotation appearance")
                val markup = Rgba.fromArgb(first[18 * 120 + 90])
                assertTrue(markup.r == 255 && markup.g in 120..135 && markup.b in 120..135, "$markup")
                val rotated = pdf.render(1, 100, 200)
                val original = reference.render(0, 100, 200)
                for (y in listOf(10, 70, 100, 180)) for (x in listOf(10, 30, 90)) {
                    assertEquals(original[y * 100 + x], rotated[y * 100 + x], "source sample ($x,$y)")
                }
            }
        }
        assertContains(pageText(0), "Original")
        assertContains(pageText(1), "Rotated")
        assertContentEquals(bytes, fs.read(source) { readByteArray() })
    }

    @Test fun turningAPdfAndInsertingSpaceMovesItsSourceAndAnnotationsTogether() {
        val source = root / "Turned.pdf"
        val bytes = originalPdf()
        fs.write(source) { write(bytes) }
        assertIs<LiteOpen.Opened>(store.importPdf(docs, "Turned", source)).session.use {
            val doc = it.note!!
            val page = doc.pages.last()
            PageTurn.turn(page, doc, 1)
            val pxPerPt = doc.dpi / 72.0
            page.rooms = listOf(Room(40 * pxPerPt, 20 * pxPerPt, color = Rgba(255, 255, 0)))
            page.height += 20 * pxPerPt
            page.margins = PageMargins(left = 0.25)
            assertTrue(PdfExport.export(it, out))
        }
        readPdf().use { pdf ->
            pdf.checkSize(1, 100.0, 140.0)
            val actual = pdf.render(1, 100, 140)
            val original = assertNotNull(Pdfium.open(bytes)).use { it.render(1, 120, 80) }
            for ((x, y) in listOf(15 to 15, 115 to 75, 80 to 30)) {
                val newX = 20 + 79 - y
                val newY = x + if (x < 40) 0 else 20
                assertEquals(original[y * 120 + x], actual[newY * 100 + newX], "source ($x,$y)")
            }
            assertEquals(Rgba(255, 255, 0).toArgb(), actual[48 * 100 + 60])
            assertEquals(-1, actual[25 * 100 + 5])
        }
    }

    @Test fun pageObjectsPaintImagesShapesAndSelectableTextBoxes() {
        session().use {
            val doc = it.note!!
            doc.dpi = 72
            doc.pages.clear()
            val page = Page(200.0, 150.0)
            doc.pages += page
            val image = root / "Item.png"
            fs.write(image) {
                write("iVBORw0KGgoAAAANSUhEUgAAAAQAAAACCAIAAADwyuo0AAAAEUlEQVR4nGP4z8AARwxI7P8AZ7IH+SY4448AAAAASUVORK5CYII=".decodeBase64()!!)
            }
            page.items += ImageItem(ImageData(image, 4, 2), Rect(0.0, 0.0, 40.0, 20.0))
            page.items += ShapeItem(ShapeKind.RECTANGLE, Pt(60.0, 5.0), Pt(110.0, 25.0),
                Rgba(0, 0, 0), fillRgba = Rgba(0, 255, 0))
            page.items += TextItem(Pt(10.0, 40.0), width = 170.0, text = "Caption", pointSize = 12.0, measurer = CgTextMeasurer)
            assertTrue(PdfExport.export(it, out))
        }
        val pixels = readPdf().use { it.render(0, 200, 150) }
        assertEquals(Rgba(255, 0, 0).toArgb(), pixels[2 * 200 + 3])
        assertEquals(Rgba(0, 0, 255).toArgb(), pixels[18 * 200 + 3])
        assertEquals(Rgba(0, 255, 0).toArgb(), pixels[15 * 200 + 80])
        assertContains(pageText(0), "Caption")
    }

    @Test fun transparentPenAndMultiplyHighlighterRetainTheirDistinctCompositing() {
        session().use {
            val doc = it.note!!
            doc.dpi = 72
            doc.pages.clear()
            val page = Page(100.0, 80.0, style = PageStyle(pageColor = Rgba(200, 200, 200)))
            doc.pages += page
            page.items += line(Tool.HIGHLIGHTER, 20.0, 20.0, Rgba(255, 255, 0))
            page.items += line(Tool.PEN, 20.0, 50.0, Rgba(255, 0, 0, 128))
            assertTrue(PdfExport.export(it, out))
        }
        val pixels = readPdf().use { it.render(0, 100, 80) }
        fun check(y: Int, expected: Rgba) {
            val color = Rgba.fromArgb(pixels[y * 100 + 45])
            assertTrue(color.toList().zip(expected.toList()).all { (a, b) -> abs(a - b) <= 3 }, "row $y: $color")
        }
        check(20, Rgba(200, 200, 100))
        check(50, Rgba(228, 100, 100))
        check(72, Rgba(200, 200, 200))
    }

    @Test fun canvasFitsVisibleDrawingAtNegativeCoordinatesOnOnePage() {
        session(LiteKind.CANVAS).use {
            val doc = it.canvas!!
            doc.background = doc.background.copy(pattern = PagePattern.NONE)
            val ink = line(Tool.PEN, -450.0, -750.0, Rgba(0, 0, 0))
            doc.add(ink)
            val layout = CanvasPdfLayout.of(ink.paintBounds(), doc.dpi)
            val hidden = doc.layers.create("Hidden")
            doc.add(line(Tool.PEN, 2500.0, 9000.0, Rgba(255, 0, 0)))
            doc.layers.update(hidden.id) { layer -> layer.copy(visible = false) }
            assertTrue(PdfExport.export(it, out))
            readPdf().use { pdf ->
                assertEquals(1, pdf.pageCount)
                pdf.checkSize(0, layout.widthPoints, layout.heightPoints)
                val pixels = pdf.render(0, 120, 60)
                assertEquals(Rgba(0, 0, 0).toArgb(), pixels[30 * 120 + 60])
                assertEquals(-1, pixels[0])
            }
        }
    }

    @Test fun cancellationAfterAPageOrBeforeRenamePreservesDestinationAndCleansStage() {
        session().use { session ->
            session.note!!.pages += Page(240.0, 360.0)
            for (stopAfter in 1..2) {
                if (stopAfter == 2) fs.write(out) { writeUtf8("previous") }
                var pages = 0
                assertFalse(PdfExport.export(session, out, { done, _ ->
                    pages = done
                    if (done > 0 && stopAfter == 1) assertFalse(fs.exists(out))
                }, { pages == stopAfter }))
                if (stopAfter == 1) assertTrue(fs.list(exports).isEmpty())
                else {
                    assertEquals(listOf(out), fs.list(exports))
                    assertEquals("previous", fs.read(out) { readUtf8() })
                }
            }
        }
    }

    @Test fun unreadableSourceAndFailedRenameLeaveNoPartialFile() {
        session().use {
            val page = it.note!!.pages.first()
            page.pdfPage = 0
            assertFails { PdfExport.export(it, out) }
            assertTrue(fs.list(exports).isEmpty())
            page.pdfPage = null
            val original = fs.read(it.path) { readByteArray() }
            assertFails { PdfExport.export(it, it.path) }
            assertContentEquals(original, fs.read(it.path) { readByteArray() })

            val missing = ImageItem(ImageData(root / "missing.png", 4, 2), Rect(10.0, 10.0, 40.0, 20.0))
            page.items += missing
            assertFails { PdfExport.export(it, out) }
            assertTrue(fs.list(exports).isEmpty())
            page.items.remove(missing)

            fs.createDirectory(out)
            assertFails { PdfExport.export(it, out) }
            assertEquals(listOf(out), fs.list(exports))
            assertTrue(fs.metadata(out).isDirectory)
        }
    }
}
