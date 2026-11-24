package com.xnotes.storage.lite

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.Document
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.format.DocumentCodec
import com.xnotes.format.MiniPdf
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import com.xnotes.nativelibs.Pdfium
import okio.FileHandle
import okio.ForwardingFileSystem
import okio.Path
import okio.use
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFDocumentOwnerPasswordOption
import platform.PDFKit.PDFDocumentUserPasswordOption
import kotlin.test.*

class LitePdfImportTest {
    private val fs = KFiles.system
    private val root = KFiles.tempDirectory() / Uuids.random()
    private val docs = root / "Documents"
    private val folder = (docs / "Class").also(fs::createDirectories)
    private val privateDir = root / "Private"
    private val store = LiteStore(fs, docs, privateDir, FakeImageCodec(), FakeTextMeasurer())
    private val pick = root / "Selected.pdf"
    private val target = folder / "Lecture.xnote"

    @AfterTest fun cleanup() = fs.deleteRecursively(root)

    private fun pdf(vararg sizes: String): ByteArray = MiniPdf.Writer().run {
        put("%PDF-1.4\n")
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        val kids = sizes.indices.joinToString(" ") { "${it + 3} 0 R" }
        obj(2, "<< /Type /Pages /Count ${sizes.size} /Kids [$kids] >>")
        sizes.forEachIndexed { index, size ->
            obj(index + 3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $size] /Resources << >> >>")
        }
        val count = sizes.size + 3
        val xref = table(count)
        put("trailer\n<< /Size $count /Root 1 0 R >>\n")
        end(xref)
        out.toByteArray()
    }

    private fun imported() = assertIs<LiteOpen.Opened>(store.importPdf(folder, "Lecture", pick)).session
    private fun bytes(file: Path) = fs.read(file) { readByteArray() }

    @Test fun importedNoteKeepsPageGeometryAnchorsAndOriginalPdfThroughSaveAndReopen() {
        val original = pdf("72.75 144", "360 216")
        fs.write(pick) { write(original) }
        fun checkNote(doc: Document): List<String> {
            assertEquals(PageSize.DEFAULT_DPI, doc.dpi)
            assertEquals(listOf(151.5625 to 300.0, 750.0 to 450.0), doc.pages.map { it.width to it.height })
            assertEquals(listOf(0, 1), doc.pages.map { it.pdfPage })
            assertTrue(doc.hasReferenceAnchors)
            val embedded = assertNotNull(doc.pdfFile)
            assertNotEquals(pick, embedded)
            assertContentEquals(original, bytes(embedded))
            assertNotNull(Pdfium.openFile(embedded.toString())).use { assertEquals(doc.pages.size, it.pageCount) }
            return doc.pages.map { assertNotNull(it.referenceIdentity.id) }.also { assertEquals(2, it.distinct().size) }
        }
        val ids = imported().use { session ->
            assertEquals(target, session.path)
            assertFalse(session.dirty)
            val note = assertNotNull(session.note)
            val pageIds = checkNote(note)
            assertContentEquals(original, bytes(pick))
            fs.delete(pick) // From here on only the imported note can supply the original.
            note.bookmarks += Bookmark(1, "Review")
            session.markEdited()
            assertEquals(LiteSave.Saved, session.save())
            session.documentId to pageIds
        }
        fun checkSaved(note: Document) {
            assertEquals(ids, note.identity.documentId to checkNote(note))
            assertEquals(listOf(Bookmark(1, "Review")), note.bookmarks)
        }
        assertIs<LiteOpen.Opened>(store.open(target)).session.use { checkSaved(assertNotNull(it.note)) }
        val assets = (root / "Decoded").also(fs::createDirectory)
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        fs.source(target).use { checkSaved(codec.readComplete(it, assets, assets).document) }
        assertTrue(fs.list(privateDir).isEmpty())
    }

    @Test fun importingTwiceUsesSiblingNameAndIndependentDocumentAndPageIds() {
        val original = pdf("144 72", "216 360")
        fs.write(pick) { write(original) }
        val first = imported().use { it.documentId to it.note!!.pages.map { page -> page.referenceIdentity.id } }
        val saved = bytes(target)
        imported().use {
            assertEquals(folder / "Lecture (2).xnote", it.path)
            assertNotEquals(first.first, it.documentId)
            assertTrue(it.note!!.pages.all { page -> page.referenceIdentity.id !in first.second })
        }
        assertContentEquals(saved, bytes(target))
        assertContentEquals(original, bytes(pick))
    }

    @Test fun tinyPagesInheritA4OrThePreviousSizeAndShowNoPdfPageAsOnAndroid() {
        fs.write(pick) { write(pdf("0.5 72", "216 72", "100 0.25")) }
        imported().use {
            val pages = it.note!!.pages
            val a4 = PageSize.A4.pixels(Orientation.PORTRAIT, PageSize.DEFAULT_DPI)
            assertEquals(listOf(a4, 450.0 to 150.0, 450.0 to 150.0), pages.map { page -> page.width to page.height })
            assertEquals(listOf(null, 1, null), pages.map { page -> page.pdfPage })
        }
    }

    private fun checkRefused(path: Path) {
        val before = bytes(path)
        assertEquals(LiteOpen.Refused(LiteRefusal.PDF_UNREADABLE), store.importPdf(folder, "Lecture", path))
        assertContentEquals(before, bytes(path))
        assertTrue(fs.list(folder).isEmpty())
        assertEquals(listOf(folder), fs.list(docs))
        assertTrue(fs.list(privateDir).isEmpty())
    }

    @Test fun zeroPageAndCorruptPdfsLeaveNoDestinationStageOrPrivateAssets() {
        for (bad in listOf(pdf(), "%PDF-1.4\ntruncated".encodeToByteArray())) {
            fs.write(pick) { write(bad) }
            checkRefused(pick)
        }
    }

    @Test fun publicationRejectsAnotherValidPdfEmbeddedAfterMeasurement() {
        val selected = pdf("216 144")
        fs.write(pick) { write(selected) }
        var replaced = false
        val files = object : ForwardingFileSystem(fs) {
            override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
                if (file.parent?.parent == docs && mustCreate) {
                    // The bundle writer is about to embed the PDF it has already measured.
                    val snapshot = fs.listRecursively(privateDir).single { fs.metadata(it).isRegularFile }
                    fs.write(snapshot) { write(pdf("72 360")) }
                    replaced = true
                }
                return super.openReadWrite(file, mustCreate, mustExist)
            }
        }
        val importing = LiteStore(files, docs, privateDir, FakeImageCodec(), FakeTextMeasurer())
        assertEquals(LiteOpen.Refused(LiteRefusal.CORRUPT), importing.importPdf(folder, "Lecture", pick))
        assertTrue(replaced)
        assertContentEquals(selected, bytes(pick))
        assertEquals(listOf(folder), fs.list(docs))
        assertTrue(fs.list(folder).isEmpty())
        assertTrue(fs.list(privateDir).isEmpty())
    }

    @Test fun passwordProtectedPdfIsRefusedWithoutChangingIt() {
        fs.write(pick) { write(pdf("216 144")) }
        val locked = root / "Locked.pdf"
        val document = PDFDocument(NSURL.fileURLWithPath(pick.toString()))
        assertTrue(document.writeToURL(NSURL.fileURLWithPath(locked.toString()), withOptions = mapOf(
            PDFDocumentOwnerPasswordOption to "owner", PDFDocumentUserPasswordOption to "reader",
        )))
        assertNotNull(Pdfium.openFile(locked.toString(), "reader")).use { assertEquals(1, it.pageCount) }
        checkRefused(locked)
    }
}
