package com.xnotes.pen

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.AudioData
import com.xnotes.core.model.AudioItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.platform.AndroidTextMeasurer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DirectBundleStorageHostTest {
    @Test fun strokeSaveSplicesTheTailAndAddingAnImageRewritesTheAssets() = runParkingScenario { f ->
        val audio = File(f.root, "long.aac").apply { writeBytes(ByteArray(2 * 1024 * 1024) { it.toByte() }) }
        val bounds = Rect(0.0, 0.0, 100.0, 50.0)
        val note = Document(mutableListOf(Page(500.0, 500.0))).apply {
            enableReferenceAnchors()
            pages.single().items += AudioItem(AudioData(audio, "aac", 1000, ByteArray(0)), bounds, "Audio", 1, AndroidTextMeasurer())
        }
        f.target.outputStream().use { f.codec.write(note, it) }
        val reads = AtomicInteger()
        f.beforeTargetRead = { reads.incrementAndGet() }
        withContext(Dispatchers.Main) {
            f.editor.openAsync(f.targetUri, "assets.xnote")
            assertEquals(note.identity.documentId, f.editor.state.document.identity.documentId)
            assertEquals("One metadata read and one model decode", 2, reads.get())
            f.addStroke(80.0, recordHistory = true)
            assertTrue(f.editor.flushSplitDocument())
            assertEquals(0L, f.editor.state.lastSaveAssetsMs)
            assertEquals("The provider copied no full candidate", 0, f.writes.get())
            assertEquals(1, f.writeOpens.get())
            val image = File(f.root, "image.png").apply {
                writeBytes(android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+cX30AAAAASUVORK5CYII=", 0))
            }
            val model = f.editor.state.document
            model.pages.single().items += ImageItem(ImageData(image, 1, 1), bounds)
            model.dirty = true
            assertTrue(f.editor.flushSplitDocument())
            assertEquals(1, f.writes.get())
            ZipFile(f.target).use { zip ->
                val assets = zip.entries().asSequence().filter { it.name.startsWith("assets/") }.toList()
                assertTrue(assets.any { zip.getInputStream(it).use { data -> data.readBytes().contentEquals(image.readBytes()) } })
                assertTrue(assets.any { zip.getInputStream(it).use { data -> data.readBytes().contentEquals(audio.readBytes()) } })
            }
        }
    }

    @Test fun unanchoredNotesExternalPdfNotesAndCanvasesEachDecodeOneBody() {
        for (kind in listOf("note", "pdf", "canvas")) runParkingScenario { f ->
            val asset = if (kind == "pdf") f.storage.pdfResources.admit("%PDF-1.7".byteInputStream(), "source.pdf") else null
            val id = if (kind == "canvas") {
                val document = com.xnotes.core.infinite.InfiniteDocument()
                val codec = com.xnotes.format.CanvasCodec(com.xnotes.platform.AndroidImageCodec(), AndroidTextMeasurer())
                f.target.outputStream().use { codec.write(document, it) }
                document.identity
            } else {
                val document = Document(mutableListOf(Page(500.0, 500.0)), pdfFile = asset)
                asset?.let { document.identity = document.identity.copy(pdfResource = it.nameWithoutExtension, externalPdf = true) }
                f.target.outputStream().use { f.codec.write(document, it, embedPdf = false) }
                document.identity
            }
            val reads = AtomicInteger()
            f.beforeTargetRead = { reads.incrementAndGet() }
            withContext(Dispatchers.Main) {
                if (kind == "canvas") assertTrue(f.editor.openCanvasAsync(f.targetUri, "test.xcanvas"))
                else f.editor.openAsync(f.targetUri, "test.xnote")
                assertEquals(id.documentId, f.editor.visibleDocumentId)
                assertEquals(2, reads.get())
                assertEquals(0, f.writes.get())
                if (asset != null) assertEquals(asset, f.editor.state.document.pdfFile)
            }
        }
    }

    @Test fun resolvingManagedPdfUsesItsAddressWithoutReadingItsBytes() = runParkingScenario { f ->
        val store = f.storage.pdfResources
        val file = store.admit("original".byteInputStream(), "original.pdf")
        val id = file.nameWithoutExtension
        file.writeText("private")
        assertEquals(file, store.resolve(id))
        file.writeBytes(byteArrayOf())
        assertThrows(IllegalStateException::class.java) { store.resolve(id) }
    }

    @Test fun splitPairOpenSplicesBothPanesWithoutRewritingAssets() = runParkingScenario { f ->
        val audioA = File(f.root, "left.aac").apply { writeBytes(ByteArray(2 * 1024 * 1024) { it.toByte() }) }
        val audioB = File(f.root, "right.aac").apply { writeBytes(ByteArray(2 * 1024 * 1024) { (it xor 0x5A).toByte() }) }
        val bounds = Rect(0.0, 0.0, 100.0, 50.0)
        val firstNote = Document(mutableListOf(Page(500.0, 500.0))).apply {
            enableReferenceAnchors()
            pages.single().items += AudioItem(AudioData(audioA, "aac", 1000, ByteArray(0)), bounds, "Left", 1, AndroidTextMeasurer())
        }
        val secondNote = Document(mutableListOf(Page(500.0, 500.0))).apply {
            enableReferenceAnchors()
            pages.single().items += AudioItem(AudioData(audioB, "aac", 1000, ByteArray(0)), bounds, "Right", 1, AndroidTextMeasurer())
        }
        val retryUri = android.provider.DocumentsContract.buildDocumentUri(f.authority, "retry").toString()
        f.target.outputStream().use { f.codec.write(firstNote, it) }
        f.retry.outputStream().use { f.codec.write(secondNote, it) }
        withContext(Dispatchers.Main) {
            val cap = requireNotNull(f.editor.prepareNativePairOpen(
                com.xnotes.ui.NativeSplitSource(f.targetUri, "left.xnote"),
                com.xnotes.ui.NativeSplitSource(retryUri, "right.xnote"),
            ))
            try {
                assertTrue(cap.commit())
            } finally {
                cap.close()
            }
            for (pane in listOf(cap.first, cap.second)) {
                val page = pane.state.document.pages.single()
                val stroke = com.xnotes.core.model.Stroke(
                    com.xnotes.core.tools.Tool.PEN,
                    com.xnotes.core.tools.ToolDefaults.configFor(com.xnotes.core.tools.Tool.PEN),
                    listOf(com.xnotes.core.stroke.Sample(60.0, 60.0, 0.5), com.xnotes.core.stroke.Sample(120.0, 120.0, 0.7)),
                )
                page.items += stroke
                pane.history.push(com.xnotes.core.history.AddItem(page, stroke))
                pane.state.document.dirty = true
                assertTrue(pane.flushSplitDocument())
                assertEquals(0L, pane.state.lastSaveAssetsMs)
            }
            assertEquals("Both split panes splice only their tails", 0, f.writes.get())
            assertEquals(2, f.writeOpens.get())
        }
    }
}
