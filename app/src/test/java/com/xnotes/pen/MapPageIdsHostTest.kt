package com.xnotes.pen

import android.provider.DocumentsContract
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.BundleHeader
import com.xnotes.map.source.PageIdEnableResult
import com.xnotes.ui.Editor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// New notes have page ids, so open an actual legacy bundle to exercise unanchored notes.
internal suspend fun EditorPageCopyFixture.openLegacyNote(asUnsaved: Boolean = false): Document {
    val original = Document(MutableList(4) { Page(600.0, 1200.0) })
    val before = withContext(Dispatchers.IO) {
        target.outputStream().use { codec.write(original.fixedWriteSnapshot(), it) }
        target.readBytes()
    }
    return withContext(Dispatchers.Main) {
        editor.openAsync(targetUri, "target.xnote")
        editor.view.layout(0, 0, 800, 800)
        val doc = editor.state.document
        assertEquals(original.identity, doc.identity)
        assertFalse(doc.hasReferenceAnchors)
        assertTrue(doc.pages.all { it.referenceIdentity.id == null })
        assertArrayEquals(before, target.readBytes())
        // The unsaved-source cases exercise the chooser before the note has a destination.
        if (asUnsaved) doc.path = null
        doc
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MapPageIdsHostTest {
    @Test fun theFirstCreatedBundleIncludesEveryPageAndTheCover() = runParkingScenario { f ->
        val tree = DocumentsContract.buildTreeDocumentUri(f.authority, "root").toString()
        val setup = Editor.NewNoteSetup(148.0, 210.0, PageStyle(template = "none"), PageStyle(template = "none"))
        val location = requireNotNull(f.editor.createNoteFile(tree, "root", "new note", setup))
        assertNotNull(BundleHeader.read(f.target).anchors)
        val written = f.target.inputStream().use { f.codec.read(it) }
        assertTrue(written.pages.first().cover)
        val ids = written.pages.map { requireNotNull(it.referenceIdentity.id) }
        assertEquals(ids.size, ids.distinct().size)
        withContext(Dispatchers.Main) {
            f.editor.openAsync(location, "new note.xnote")
            assertEquals(ids, f.editor.state.document.pages.map { it.referenceIdentity.id })
        }
    }

    @Test fun legacyActivationReloadsThePublishedPagesAtTheSameReadingPosition() = runParkingScenario { f ->
        val old = f.openLegacyNote()
        withContext(Dispatchers.Main) {
            f.editor.goToPage(2)
            assertEquals(2, f.editor.state.currentPageIndex())
            f.addStroke(42.0, recordHistory = true)
            assertEquals(PageIdEnableResult.ENABLED, f.editor.enablePageIds())
            val loaded = f.editor.state.document
            assertFalse(old.hasReferenceAnchors)
            assertTrue(old.pages.all { it.referenceIdentity.id == null })
            assertNotSame(old, loaded)
            assertEquals(old.identity, loaded.identity)
            assertEquals(2, f.editor.state.currentPageIndex())
            assertEquals(1, loaded.pages.first().items.size)
            val written = f.target.inputStream().use { f.codec.read(it) }
            assertTrue(written.hasReferenceAnchors)
            assertEquals(written.pages.map { requireNotNull(it.referenceIdentity.id) },
                loaded.pages.map { it.referenceIdentity.id })
        }
    }

    @Test fun anEditDuringTheDirtySaveStaysInTheOriginalModel() = runParkingScenario { f ->
        val old = f.openLegacyNote()
        val started = CompletableDeferred<Unit>()
        f.beforeTargetWrite = { started.complete(Unit); f.release.await() }
        withContext(Dispatchers.Main) {
            f.addStroke(1.0)
            val enabling = async { f.editor.enablePageIds() }
            started.await()
            f.addStroke(2.0)
            f.release.countDown()
            assertEquals(PageIdEnableResult.CHANGED, enabling.await())
            assertSame(old, f.editor.state.document)
            assertTrue(old.dirty)
            assertFalse(old.hasReferenceAnchors)
            assertNull(BundleHeader.read(f.target).anchors)
            assertEquals(2, old.pages.first().items.size)
        }
    }

    @Test fun aBorrowedPaneKeepsBothViewsOnOneOwnerAfterActivation() = runParkingScenario { f ->
        val old = f.openLegacyNote()
        withContext(Dispatchers.Main) {
            val owner = f.editor
            val borrower = owner.secondaryPane()
            assertTrue(borrower.openSharedView(owner))
            borrower.view.layout(0, 0, 800, 800)
            owner.goToPage(1)
            borrower.goToPage(3)
            assertEquals(PageIdEnableResult.ENABLED, borrower.enablePageIds())
            assertSame(owner, borrower.documentOwner)
            assertSame(owner.state.document, borrower.state.document)
            assertNotSame(old, owner.state.document)
            assertEquals(1, owner.state.currentPageIndex())
            assertEquals(3, borrower.state.currentPageIndex())
            assertTrue(old.pages.all { it.referenceIdentity.id == null })
        }
    }

    @Test fun aLateEditDuringTheFileReadStopsTheUpgradeBeforePublication() = runParkingScenario { f ->
        val old = f.openLegacyNote()
        val original = f.target.readBytes()
        val reading = CompletableDeferred<Unit>()
        f.beforeTargetRead = { reading.complete(Unit); f.release.await() }
        withContext(Dispatchers.Main) {
            val enabling = async { f.editor.enablePageIds() }
            reading.await()
            f.addStroke(70.0)
            f.release.countDown()
            assertEquals(PageIdEnableResult.CHANGED, enabling.await())
            assertSame(old, f.editor.state.document)
            assertTrue(old.dirty)
            assertFalse(old.hasReferenceAnchors)
        }
        assertArrayEquals(original, f.target.readBytes())
    }

    @Test fun ambiguousDocumentIdentityBlocksEvenThePreparatoryDirtySave() = runParkingScenario { f ->
        val old = f.openLegacyNote()
        val original = f.target.readBytes()
        f.source.writeBytes(original)
        f.storage.registry.registerObserved(BundleHeader.read(f.source), "xnote", "source.xnote", f.sourceUri)
        withContext(Dispatchers.Main) {
            f.addStroke(1.0)
            assertEquals(PageIdEnableResult.CHANGED, f.editor.enablePageIds())
            assertSame(old, f.editor.state.document)
            assertTrue(old.dirty)
        }
        assertArrayEquals(original, f.target.readBytes())
    }

    @Test fun aDifferentActualVersionIsNeverActivated() = runParkingScenario { f ->
        val old = f.openLegacyNote()
        val changed = f.target.inputStream().use { f.codec.read(it) }.apply { pages.removeAt(0) }
        f.target.outputStream().use { f.codec.write(changed.fixedWriteSnapshot(), it) }
        val before = f.target.readBytes()
        withContext(Dispatchers.Main) {
            assertEquals(PageIdEnableResult.CHANGED, f.editor.enablePageIds())
            assertSame(old, f.editor.state.document)
            assertFalse(old.hasReferenceAnchors)
        }
        assertArrayEquals(before, f.target.readBytes())
    }

    @Test fun losingReadAccessAfterVerifiedPublicationCannotReportAnUntouchedFile() = runParkingScenario { f ->
        val old = f.openLegacyNote()
        f.afterTargetWrite = {
            f.beforeTargetRead = {
                if (f.writes.get() >= 1) throw SecurityException("Read grant lost after publication")
            }
        }
        withContext(Dispatchers.Main) {
            assertEquals(PageIdEnableResult.CHANGED, f.editor.enablePageIds())
            assertSame(old, f.editor.state.document)
            assertFalse(old.hasReferenceAnchors)
        }
        assertNotNull(BundleHeader.read(f.target).anchors)
        assertTrue(f.target.isFile)
    }

    @Test fun losingReadAccessAfterThePreparatorySaveKeepsTheWrittenEdit() = runParkingScenario { f ->
        val old = f.openLegacyNote()
        f.afterTargetWrite = {
            f.beforeTargetRead = {
                if (f.writes.get() >= 1) throw SecurityException("Read grant lost after saving the edit")
            }
        }
        withContext(Dispatchers.Main) {
            f.addStroke(1.0)
            assertEquals(PageIdEnableResult.CHANGED, f.editor.enablePageIds())
            assertSame(old, f.editor.state.document)
            assertFalse(old.hasReferenceAnchors)
        }
        val written = f.target.inputStream().use { f.codec.read(it) }
        assertFalse(written.hasReferenceAnchors)
        assertEquals(1, written.pages.first().items.size)
    }

    @Test fun anUnsavedNoteHasNoUpgradeTarget() = runParkingScenario { f ->
        withContext(Dispatchers.Main) {
            assertNull(f.editor.state.document.path)
            assertNotEquals(PageIdEnableResult.ENABLED, f.editor.enablePageIds())
            assertEquals(0, f.writes.get())
        }
    }
}
