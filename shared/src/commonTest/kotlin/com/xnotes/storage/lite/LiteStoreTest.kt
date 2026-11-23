package com.xnotes.storage.lite

import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.ImageSize
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.FontSpec
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.LineMetrics
import com.xnotes.core.pal.TextFlags
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.interop.BundleInterop
import com.xnotes.format.interop.interopFixture
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W3-C contract (docs/movinkpad/process/ipados-wave3-contracts-2026-11-24.md, "W3-C"): the lean save protocol the
 * iPad uses for notes in its own Documents folder. Runs on the JVM and on the iOS simulator.
 */
class LiteStoreTest {

    private val fs = KFiles.system
    private lateinit var root: Path
    private lateinit var documents: Path
    private lateinit var private: Path
    private lateinit var store: LiteStore

    private val images = object : ImageCodec {
        override fun probeFile(path: String): ImageSize? = null
    }
    private val text = object : TextMeasurer {
        override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags) =
            Rect(0.0, 0.0, wrapWidth, (1 + text.count { it == '\n' }) * lineHeight(font))
        override fun lineHeight(font: FontSpec) = font.pointSize * 1.3
        override fun metrics(font: FontSpec) = LineMetrics(font.pointSize, font.pointSize * 0.3)
        override fun advances(text: String, font: FontSpec) = DoubleArray(text.length) { font.pointSize * 0.6 }
    }

    @BeforeTest
    fun setUp() {
        root = KFiles.tempDirectory() / "lite-${Uuids.random()}"
        documents = root / "Documents"
        private = root / "Library" / "Private"
        fs.createDirectories(documents)
        fs.createDirectories(private)
        store = LiteStore(fs, documents, private, images, text)
    }

    @AfterTest
    fun tearDown() {
        fs.deleteRecursively(root)
    }

    private fun place(fixture: String, name: String = fixture): Path {
        val path = documents / name
        fs.write(path) { write(interopFixture(fixture)) }
        return path
    }

    private fun bytes(path: Path) = fs.read(path) { readByteArray() }

    private fun summary(path: Path) = BundleInterop.summarize(path.name, bytes(path))

    private fun opened(path: Path): LiteSession = assertIs<LiteOpen.Opened>(store.open(path)).session

    private fun addInk(session: LiteSession) {
        val note = assertNotNull(session.note)
        note.pages[0].items.add(
            Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN), listOf(Sample(10.0, 10.0, 0.5), Sample(40.0, 30.0, 0.6, 12.0))),
        )
        session.markEdited()
    }

    private fun noStagingLeft() {
        val leftovers = fs.listRecursively(root).filter { fs.metadata(it).isRegularFile }
            .filter { it.name.startsWith(".") || "staging" in it.toString() }.toList()
        assertTrue(leftovers.isEmpty(), "staging leftovers: $leftovers")
    }

    @Test
    fun opensANoteWithItsIdentityAndClean() {
        val path = place("note-ink-text-pages.xnote")
        val before = summary(path)
        val s = opened(path)
        assertEquals(LiteKind.NOTE, s.kind)
        assertEquals(before.documentId, s.documentId)
        assertFalse(s.dirty)
        assertContentEquals(interopFixture("note-ink-text-pages.xnote"), bytes(path), "opening must not touch the file")
    }

    @Test
    fun opensACanvas() {
        val s = opened(place("canvas-ink-image.xcanvas"))
        assertEquals(LiteKind.CANVAS, s.kind)
        assertNotNull(s.canvas)
    }

    @Test
    fun aSavedEditSurvivesReopeningWithTheSameIdentity() {
        val path = place("note-ink-text-pages.xnote")
        val before = summary(path)
        val s = opened(path)
        val count = s.note!!.pages[0].items.size
        addInk(s)
        assertTrue(s.dirty)
        assertIs<LiteSave.Saved>(s.save())
        assertFalse(s.dirty)
        s.close()

        val after = summary(path)
        assertEquals(before.documentId, after.documentId)
        assertEquals(before.pageIds, after.pageIds)
        val again = opened(path)
        assertEquals(count + 1, again.note!!.pages[0].items.size)
        noStagingLeft()
    }

    @Test
    fun saveWithoutEditsDoesNotRewriteTheFile() {
        val path = place("note-layers.xnote")
        val s = opened(path)
        assertIs<LiteSave.Saved>(s.save())
        assertContentEquals(interopFixture("note-layers.xnote"), bytes(path))
    }

    @Test
    fun aFailureBeforePublishingKeepsTheOriginalAndAProtectedCopy() {
        val path = place("note-ink-text-pages.xnote")
        val s = opened(path)
        addInk(s)
        val result = s.save(beforePublish = { throw IllegalStateException("disk full") })
        val failed = assertIs<LiteSave.Failed>(result)
        assertContentEquals(interopFixture("note-ink-text-pages.xnote"), bytes(path))
        assertTrue(s.dirty, "the edit is still unsaved")
        val copy = failed.protectedCopy
        assertTrue(copy.toString().startsWith(private.toString()), "protected copies stay out of Documents: $copy")
        assertEquals(summary(path).documentId, BundleInterop.summarize(copy.name, bytes(copy)).documentId)
        noStagingLeft()
    }

    @Test
    fun aStagedFileThatDoesNotVerifyIsNeverPublished() {
        val path = place("note-ink-text-pages.xnote")
        val s = opened(path)
        addInk(s)
        val result = s.save(beforePublish = { staged -> fs.write(staged) { writeUtf8("truncated") } })
        assertIs<LiteSave.Failed>(result)
        assertContentEquals(interopFixture("note-ink-text-pages.xnote"), bytes(path))
        noStagingLeft()
    }

    @Test
    fun aFileChangedElsewhereIsNotOverwritten() {
        val path = place("note-ink-text-pages.xnote")
        val s = opened(path)
        addInk(s)
        // Replaced from the Files app (or another window) while open.
        val foreign = interopFixture("note-layers.xnote")
        fs.write(path) { write(foreign) }
        val conflict = assertIs<LiteSave.Conflict>(s.save())
        assertContentEquals(foreign, bytes(path))
        assertTrue(fs.exists(conflict.protectedCopy))
        assertTrue(conflict.protectedCopy.toString().startsWith(private.toString()))
    }

    @Test
    fun recoveryIsWrittenOnlyWhenDirtyAndOnlyOutsideDocuments() {
        val path = place("note-ink-text-pages.xnote")
        val s = opened(path)
        assertFalse(s.writeRecovery(), "nothing to recover")
        assertTrue(store.pendingRecoveries().isEmpty())

        addInk(s)
        assertTrue(s.writeRecovery())
        val pending = store.pendingRecoveries().single()
        assertEquals(path, pending.original)
        assertEquals(s.documentId, pending.documentId)
        assertTrue(pending.record.toString().startsWith(private.toString()))
        assertEquals(listOf(path.name), fs.list(documents).map { it.name }, "Documents only holds the note")

        assertIs<LiteSave.Saved>(s.save())
        assertTrue(store.pendingRecoveries().isEmpty(), "a successful save clears the recovery record")
    }

    @Test
    fun aRecoveryRestoresTheUnsavedEditAsDirty() {
        val path = place("note-ink-text-pages.xnote")
        val s = opened(path)
        val count = s.note!!.pages[0].items.size
        addInk(s)
        s.writeRecovery()
        s.abandonForTest() // the app is killed in the background

        val restored = assertIs<LiteOpen.Opened>(store.restore(store.pendingRecoveries().single())).session
        assertEquals(path, restored.path)
        assertTrue(restored.dirty)
        assertEquals(count + 1, restored.note!!.pages[0].items.size)
        assertContentEquals(interopFixture("note-ink-text-pages.xnote"), bytes(path), "restoring does not write the original")

        store.discard(store.pendingRecoveries().single())
        assertTrue(store.pendingRecoveries().isEmpty())
    }

    @Test
    fun brokenOrUnsupportedFilesAreRefusedAndLeftAlone() {
        val corrupt = documents / "broken.xnote"
        fs.write(corrupt) { writeUtf8("not a bundle") }
        assertEquals(LiteRefusal.CORRUPT, assertIs<LiteOpen.Refused>(store.open(corrupt)).reason)
        assertEquals("not a bundle", fs.read(corrupt) { readUtf8() })

        val legacyMap = documents / "old.xmap"
        fs.write(legacyMap) { writeUtf8("{}") }
        assertEquals(LiteRefusal.UNSUPPORTED_KIND, assertIs<LiteOpen.Refused>(store.open(legacyMap)).reason)

        assertEquals(LiteRefusal.NOT_FOUND, assertIs<LiteOpen.Refused>(store.open(documents / "absent.xnote")).reason)
        assertTrue(fs.exists(legacyMap))
    }
}
