package com.xnotes.map.source

import android.net.Uri
import android.provider.DocumentsContract
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.snapshot
import com.xnotes.format.BundleIdentity
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.pen.PenTestRunner
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.AppStorageDocumentsProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AndroidSourceCatalogsTest {
    @Test fun readsActualIdentitiesAndPageOrderWithoutEnablingIdsOrRegisteringFiles() {
        val context = PenTestRunner.instance.targetContext
        val catalogs = AndroidSourceCatalogs(context)
        val root = Files.createTempDirectory(AppStorageDocumentsProvider.rootDir(context).toPath(), "map-sources").toFile()
        val images = AndroidImageCodec()
        val text = AndroidTextMeasurer()
        val notes = DocumentCodec(images, text)
        fun documentUri(file: File) = DocumentsContract.buildDocumentUri(
            requireNotNull(AppStorageDocumentsProvider.treeUri(context).authority), "root/${root.name}/${file.name}",
        ).toString()
        fun verify(file: File, expected: SourceCatalog) {
            val bytes = file.readBytes()
            assertEquals(expected, catalogs.read(documentUri(file)))
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(emptyList<String>(), catalogs.locations(expected.documentId))
        }
        try {
            val doc = Document.blank()
            doc.addPage(200.0, 200.0)
            doc.enableReferenceAnchors()
            doc.pages.sortByDescending { it.referenceIdentity.id }
            val anchored = File(root, "Renamed.xcanvas") // The manifest determines its kind.
            anchored.outputStream().use { notes.write(doc.snapshot(), it) }
            verify(anchored, SourceCatalog(doc.identity.documentId, SourceCatalog.Kind.NOTE, anchored.name,
                doc.pages.map { requireNotNull(it.referenceIdentity.id) }))

            val plain = Document.blank()
            val legacy = File(root, "Plain.xnote")
            legacy.outputStream().use { notes.write(plain.snapshot(), it) }
            verify(legacy, SourceCatalog(plain.identity.documentId, SourceCatalog.Kind.NOTE, legacy.name, null))

            val board = InfiniteDocument()
            val canvas = File(root, "Board.xcanvas")
            canvas.outputStream().use { CanvasCodec(images, text).write(board.snapshotForWrite(), it) }
            verify(canvas, SourceCatalog(board.identity.documentId, SourceCatalog.Kind.CANVAS, canvas.name, null))

            val identity = BundleIdentity()
            val other = File(root, "Other.xnote")
            ZipOutputStream(other.outputStream()).use { zip ->
                identity.write(zip)
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("""{"format":"other","version":1}""".toByteArray())
                zip.closeEntry()
            }
            verify(other, SourceCatalog(identity.documentId, SourceCatalog.Kind.OTHER, other.name, null))

            val absent = File(root, "Gone.xnote")
            assertNull(catalogs.read(Uri.fromFile(absent).toString()))
            assertNull(catalogs.read(documentUri(absent)))
            assertFalse(absent.exists())

            val broken = File(root, "Broken.xnote")
            val bytes = "truncated bundle".toByteArray()
            broken.writeBytes(bytes)
            assertThrows(Exception::class.java) { catalogs.read(Uri.fromFile(broken).toString()) }
            assertArrayEquals(bytes, broken.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }
}
