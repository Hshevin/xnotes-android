package com.xnotes.storage.lite

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.pal.assetPath
import com.xnotes.format.BundleIdentity
import com.xnotes.format.MapJson
import com.xnotes.format.bundleReader
import com.xnotes.format.bundleWriter
import com.xnotes.format.interop.BundleInterop
import com.xnotes.format.interop.interopFixture
import com.xnotes.format.putDeflated
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import com.xnotes.kio.atomicWrite
import com.xnotes.kio.fileIdentity
import okio.Buffer
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.use
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LiteStoreEdgeTest {
    private val fs = KFiles.system
    private val root = KFiles.tempDirectory() / Uuids.random()
    private val docs = (root / "Docs").also { fs.createDirectories(it) }
    private val privateDir = root / "Private"
    private fun store() = LiteStore(fs, docs, privateDir, FakeImageCodec(), FakeTextMeasurer())
    private val store = store()
    private val note = "note-ink-text-pages.xnote"

    @AfterTest fun cleanup() = fs.deleteRecursively(root)

    private fun place(name: String) = (docs / name).also { fs.write(it) { write(interopFixture(name)) } }
    private fun bytes(path: Path) = fs.read(path) { readByteArray() }
    private fun opened(path: Path) = assertIs<LiteOpen.Opened>(store.open(path)).session
    private fun entries(bundle: ByteArray): Map<String, ByteArray> = buildMap {
        bundleReader(Buffer().write(bundle)).use { archive ->
            while (true) {
                val entry = archive.next() ?: break
                put(entry.name, entry.data.readByteArray())
            }
        }
    }

    @Test fun canvasSavesKeepImagesMapsAndRawSamples() {
        for (name in listOf("canvas-ink-image.xcanvas", "canvas-mind-map.xcanvas")) {
            val path = place(name)
            val before = BundleInterop.summarize(name, bytes(path))
            val point = Waypoint("saved view", 23.0, 31.0, 1.5)
            opened(path).use { session ->
                session.canvas!!.waypoints.add(point)
                session.markEdited()
                assertEquals(LiteSave.Saved, session.save())
            }
            val after = BundleInterop.summarize(name, bytes(path))
            assertEquals(before.documentId, after.documentId)
            assertEquals(before.inkDigest, after.inkDigest)
            assertEquals(before.entryContentCrc - "manifest.json", after.entryContentCrc - "manifest.json")
            opened(path).use { assertEquals(point, it.canvas!!.waypoints.last()) }
        }
    }

    @Test fun annotatedPdfBytesAndMarkupsSurviveAnEdit() {
        val path = place("note-pdf-annotated.xnote")
        val before = entries(bytes(path))
        opened(path).use { session ->
            session.note!!.pages.first().width += 10.0
            session.markEdited()
            assertEquals(LiteSave.Saved, session.save())
        }
        val after = entries(bytes(path))
        for (entry in listOf("assets/source.pdf", "markups.xfdf")) assertContentEquals(before.getValue(entry), after.getValue(entry))
    }

    @Test fun identicalReplacementDuringSaveConflictsByInode() {
        val path = place(note)
        val original = bytes(path)
        val identity = fileIdentity(path)
        opened(path).use { session ->
            session.markEdited()
            assertIs<LiteSave.Conflict>(session.save { atomicWrite(fs, path) { it.write(original) } })
            assertNotEquals(identity, fileIdentity(path))
            assertContentEquals(original, bytes(path))
        }
    }

    @Test fun validStageWithOriginalIdAndPageIdsButWrongContentIsRefused() {
        val path = place(note)
        val original = bytes(path)
        opened(path).use { session ->
            val width = session.note!!.pages.first().width + 10.0
            session.note!!.pages.first().width = width
            session.markEdited()
            val failed = assertIs<LiteSave.Failed>(session.save { stage -> fs.write(stage) { write(original) } })
            assertContentEquals(original, bytes(path))
            assertTrue(fs.exists(failed.protectedCopy))
            val restore = assertIs<LiteOpen.Opened>(store.restore(store.pendingRecoveries().single())).session
            restore.use { assertEquals(width, it.note!!.pages.first().width) }
        }
    }

    @Test fun subsequentEditDuringPublicationStaysDirtyAndCanBeSaved() {
        val path = place(note)
        opened(path).use { session ->
            val page = session.note!!.pages.first()
            val captured = page.width + 20.0
            page.width = captured
            session.markEdited()
            assertEquals(LiteSave.Saved, session.save {
                page.width++
                session.markEdited()
            })
            assertTrue(session.dirty)
            opened(path).use { assertEquals(captured, it.note!!.pages.first().width) }
            assertEquals(LiteSave.Saved, session.save())
            assertFalse(session.dirty)
            opened(path).use { assertEquals(captured + 1.0, it.note!!.pages.first().width) }
        }
    }

    @Test fun coldRestoreDoesNotAcquireAuthorityOverAReplacedOriginal() {
        val path = place(note)
        opened(path).use { session ->
            session.markEdited()
            assertTrue(session.writeRecovery())
            assertTrue(session.writeRecovery())
        }
        val restarted = store()
        val pending = restarted.pendingRecoveries().single()
        val foreign = interopFixture("note-layers.xnote")
        atomicWrite(fs, path) { it.write(foreign) }
        val session = assertIs<LiteOpen.Opened>(restarted.restore(pending)).session
        session.use {
            assertIs<LiteSave.Conflict>(it.save())
            assertContentEquals(foreign, bytes(path))
        }
        assertEquals(1, restarted.pendingRecoveries().size)
    }

    @Test fun futureManifestIdentityOrMapVersionAndMixedSplitAreRefusedUntouched() {
        for ((name, target) in listOf(note to "manifest.json", note to BundleIdentity.ENTRY,
                "canvas-mind-map.xcanvas" to MapJson.ENTRY)) {
            val path = place(name)
            val modified = Buffer()
            bundleWriter(modified).use { archive ->
                for ((entry, raw) in entries(bytes(path))) {
                    val data = if (entry != target) raw else raw.decodeToString().replace("\"version\":1", "\"version\":9").encodeToByteArray()
                    if (entry == target) assertFalse(data.contentEquals(raw))
                    archive.putDeflated(entry, data)
                }
            }
            val future = modified.readByteArray()
            fs.write(path) { write(future) }
            assertEquals(LiteOpen.Refused(LiteRefusal.FUTURE_VERSION), store.open(path), target)
            assertContentEquals(future, bytes(path))
        }
        val split = docs / "mixed.xsplit"
        val original = """{"format":"xsplit","version":3}""".encodeToByteArray()
        fs.write(split) { write(original) }
        assertEquals(LiteOpen.Refused(LiteRefusal.UNSUPPORTED_KIND), store.open(split))
        assertContentEquals(original, bytes(split))
    }

    @Test fun cleanSaveDoesNotEvenReplaceTheInode() {
        val path = place(note)
        val identity = fileIdentity(path)
        opened(path).use { assertEquals(LiteSave.Saved, it.save { error("should never stage") }) }
        assertEquals(identity, fileIdentity(path))
    }

    @Test fun privateWriteFailureDoesNotClaimANewCopyOrRemoveThePreviousOne() {
        val path = place(note)
        var denyPrivateWrite = false
        val fault = object : ForwardingFileSystem(fs) {
            override fun atomicMove(source: Path, target: Path) {
                if (target == path || denyPrivateWrite && target.name == "body.xnote") throw IOException("disk full")
                super.atomicMove(source, target)
            }
        }
        val failing = LiteStore(fault, docs, privateDir, FakeImageCodec(), FakeTextMeasurer())
        assertIs<LiteOpen.Opened>(failing.open(path)).session.use { session ->
            session.markEdited()
            val failure = assertIs<LiteSave.Failed>(session.save())
            val recovery = failing.pendingRecoveries().single()
            session.note!!.pages.first().width++
            session.markEdited()
            denyPrivateWrite = true
            assertFalse(session.writeRecovery())
            assertFailsWith<LiteProtectionException> { session.save() }
            assertTrue(session.dirty && fs.exists(failure.protectedCopy))
            assertEquals(listOf(recovery), failing.pendingRecoveries())
            assertContentEquals(interopFixture(note), bytes(path))
            assertEquals(listOf(path), fs.list(docs))
        }
    }

    @Test fun damagedWorkingPdfCannotReplaceTheOriginalOrItsLastGoodRecovery() {
        val path = place("note-pdf-annotated.xnote")
        opened(path).use { session ->
            session.markEdited()
            assertTrue(session.writeRecovery())
            val previous = store.pendingRecoveries().single()
            fs.write(session.note!!.pdfFile!!.assetPath()) { writeUtf8("wrong original") }
            val failed = assertIs<LiteSave.Failed>(session.save())
            assertTrue(fs.exists(failed.protectedCopy))
            assertEquals(listOf(previous), store.pendingRecoveries())
            assertContentEquals(interopFixture(path.name), bytes(path))
        }
    }

    @Test fun outsideDocumentsAndSymlinkedDocumentsAreNotEditable() {
        val outside = root / "outside.xnote"
        val link = docs / "link.xnote"
        fs.write(outside) { write(interopFixture(note)) }
        fs.createSymlink(link, outside)
        for (path in listOf(outside, link)) assertEquals(LiteOpen.Refused(LiteRefusal.UNSUPPORTED_KIND), store.open(path))
        assertContentEquals(interopFixture(note), bytes(outside))
    }

    @Test fun movingTheOriginalFolderDuringSaveLeavesNoStageInEitherLocation() {
        val folder = docs / "notes"
        val moved = docs / "renamed"
        fs.createDirectory(folder)
        val path = folder / note
        fs.write(path) { write(interopFixture(note)) }
        opened(path).use { session ->
            session.markEdited()
            assertIs<LiteSave.Conflict>(session.save { fs.atomicMove(folder, moved) })
            assertEquals(listOf(moved), fs.list(docs))
            assertEquals(listOf(moved / note), fs.list(moved))
            assertContentEquals(interopFixture(note), bytes(moved / note))
        }
    }

    @Test fun corruptAndFutureRecoveryRecordsAreNeverDeletedByDiscoveryOrRestore() {
        val path = place(note)
        opened(path).use { session ->
            session.markEdited()
            assertTrue(session.writeRecovery())
        }
        val entry = store.pendingRecoveries().single()
        val records = listOf("broken".encodeToByteArray() to LiteRefusal.CORRUPT,
            Buffer().writeUtf8("XNotesLite\n").writeInt(99).readByteArray() to LiteRefusal.FUTURE_VERSION)
        for ((raw, reason) in records) {
            fs.write(entry.record) { write(raw) }
            assertTrue(store.pendingRecoveries().isEmpty())
            assertEquals(LiteOpen.Refused(reason), store.restore(entry))
            assertContentEquals(raw, bytes(entry.record))
            assertTrue(fs.exists(entry.record.parent!! / "body.xnote"))
        }
        assertContentEquals(interopFixture(note), bytes(path))
    }
}
