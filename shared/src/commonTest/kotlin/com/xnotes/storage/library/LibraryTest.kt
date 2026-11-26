package com.xnotes.storage.library

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Rgba
import com.xnotes.format.BundlePdf
import com.xnotes.format.JsonPull
import com.xnotes.format.interop.BundleInterop
import com.xnotes.format.interop.interopFixture
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import com.xnotes.storage.lite.LiteOpen
import com.xnotes.storage.lite.LiteStore
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LibraryTest {
    private val fs = KFiles.system
    private val base = KFiles.tempDirectory() / "library-${Uuids.random()}"
    private val root = base / "Documents"
    private val folder = root / "课程"
    private val side = root / ".xnote"
    private val trash = side / "trash"
    private val blue = Rgba(17, 34, 204)
    private var now = 1_700_000_000_123L
    private lateinit var library: Library
    private lateinit var store: LiteStore

    private fun libraryWith(files: FileSystem): Library {
        store = LiteStore(files, root, base / "Private", FakeImageCodec(), FakeTextMeasurer())
        return Library(files, root, store) { now }
    }

    @BeforeTest fun setup() {
        fs.createDirectories(folder)
        library = libraryWith(fs)
    }

    @AfterTest fun cleanup() { fs.deleteRecursively(base) }

    private fun put(path: Path, text: String = "content") = path.also {
        fs.createDirectories(it.parent!!)
        fs.write(it) { writeUtf8(text) }
    }

    private fun fixture(path: Path, fixture: String) = path.also { fs.write(it) { write(interopFixture(fixture)) } }
    private fun bytes(path: Path) = fs.read(path) { readByteArray() }
    private fun contents(path: Path) = fs.read(path) { readUtf8() }
    private fun LibraryResult.path() = assertIs<LibraryResult.Success>(this).path
    private fun fails(reason: LibraryFailure, result: LibraryResult) {
        assertEquals(LibraryResult.Failure(reason), result)
    }

    // Independent field-level reader for Android's JSONObject/JSONArray schema, with no library decoder.
    private fun json(path: Path): Map<*, *> {
        val reader = JsonPull(contents(path), strict = true)
        fun value(): Any = when (reader.peek()) {
            JsonPull.Token.STRING -> reader.nextString()
            JsonPull.Token.NUMBER -> reader.nextInt()
            JsonPull.Token.BEGIN_ARRAY -> buildList { reader.beginArray(); while (reader.hasNext()) add(value()); reader.endArray() }
            JsonPull.Token.BEGIN_OBJECT -> buildMap { reader.beginObject(); while (reader.hasNext()) put(reader.nextName(), value()); reader.endObject() }
            else -> error("Unexpected sidecar JSON value")
        }
        return assertIs<Map<*, *>>(value()).also { assertEquals(JsonPull.Token.END_DOCUMENT, reader.peek()) }
    }

    @Test fun readsAndroidSidecarsAndPrunesOnlyDeadOrInvalidPins() {
        val note = put(folder / "Ink.xnote")
        put(root / "Else.xcanvas")
        put(side / "colors.json", """{"version":1,"colors":{"课程":"#1122cc","Else.xcanvas":"#FF1122CC","bad":"unknown"}}""")
        put(folder / ".xnote" / "colors.json", """{"version":1,"colors":{"Ink.xnote":"#1122cc"}}""")
        put(side / "color-names.json", """{"version":1,"names":{"#1122CC":"  复习  ","#778899":"  "}}""")
        put(side / "pins.json", """{"version":1,"pins":["课程/Ink.xnote","missing.xnote","Else.xcanvas","课程","../out.xnote",".xnote/bad.xnote","课程/Ink.xnote"]}""")
        assertEquals(mapOf("课程" to blue, "Else.xcanvas" to blue), library.colors(root))
        assertEquals(mapOf(blue to "复习"), library.colorNames())
        assertEquals(setOf(note, folder, root / "Else.xcanvas"), library.findByColor(blue).toSet())
        assertEquals(listOf(note, root / "Else.xcanvas"), library.notePins())
        assertEquals(mapOf("version" to 1, "pins" to listOf("课程/Ink.xnote", "Else.xcanvas")), json(side / "pins.json"))
    }

    @Test fun androidReadersSeeExactFieldsColorAndTimestampFromLibrary() {
        val name = """Ink "甲".xnote"""
        val note = put(folder / name)
        assertTrue(library.setColors(folder, mapOf(name to blue)))
        assertTrue(library.setColorName(blue, "  深蓝  "))
        assertTrue(library.setNotePinned(note, true))
        assertEquals(mapOf("version" to 1, "colors" to mapOf(name to "#1122cc")), json(folder / ".xnote" / "colors.json"))
        assertEquals(mapOf("version" to 1, "names" to mapOf("#1122cc" to "深蓝")), json(side / "color-names.json"))
        assertEquals(mapOf("version" to 1, "pins" to listOf("课程/$name")), json(side / "pins.json"))
        val payload = library.trash(listOf(note)).single().path()
        val wrapper = payload.parent!!
        assertEquals(trash, wrapper.parent)
        assertTrue(Regex("1700000000123-[0-9a-f]{6}").matches(wrapper.name))
        assertEquals(mapOf("version" to 1, "name" to name, "path" to listOf("课程"),
            "deleted" to "2023-11-14T22:13:20.123Z", "colour" to "#1122cc"), json(wrapper / "trash.json"))
        assertEquals(emptyMap(), library.colors(folder))
        assertEquals(emptyList(), library.notePins())
        assertTrue(library.setColorName(blue, " "))
        assertEquals(emptyMap(), library.colorNames())

        now = 1_700_000_000_000L
        val plain = library.trash(listOf(put(root / "B.xnote"))).single().path()
        assertEquals(mapOf("version" to 1, "name" to "B.xnote", "path" to emptyList<String>(),
            "deleted" to "2023-11-14T22:13:20Z"), json(plain.parent!! / "trash.json"))
    }

    @Test fun androidTrashRestoresIntoExistingFolderAndSuffixesNameClashes() {
        val wrapper = trash / "1700000000123-baf001"
        put(wrapper / "a.xnote", "old")
        put(wrapper / "trash.json", """{"version":1,"name":"a.xnote","path":["课程"],"deleted":"2023-11-14T22:13:20.123Z","colour":"#1122CC"}""")
        put(folder / "A.xnote", "current")
        put(folder / "a (2).xnote")
        val item = library.listTrash().single()
        assertEquals(TrashItem(wrapper / "a.xnote", false, listOf("课程"), now, blue), item)
        val restored = library.restore(item).path()
        assertEquals(folder / "a (3).xnote", restored)
        assertEquals("old", contents(restored))
        assertEquals("current", contents(folder / "A.xnote"))
        assertEquals(mapOf(restored.name to blue), library.colors(folder))
        assertFalse(fs.exists(wrapper))
        fails(LibraryFailure.NOT_FOUND, library.restore(item))
        assertFalse(library.delete(item))
    }

    @Test fun folderRenameAndMoveCarryNestedPinsAndColorsWithoutTouchingTheDocuments() {
        val item = put(folder / "a.XNOTE", "original")
        val other = put(root / "b.xcanvas")
        assertTrue(library.setColors(root, mapOf(folder.name to blue)))
        assertTrue(library.setColors(folder, mapOf(item.name to blue)))
        assertTrue(library.setNotePinned(other, true))
        assertTrue(library.setNotePinned(item, true))
        val newName = library.rename(item, "draft.xnote").path()
        assertEquals(folder / "draft.XNOTE", newName)
        assertEquals(listOf(other, newName), library.notePins())
        val renamed = library.rename(folder, "2026.甲").path()
        val dest = root / "Projects"
        fs.createDirectory(dest)
        val moved = library.moveInto(listOf(renamed), dest).single().path()
        assertEquals(dest / renamed.name, moved)
        assertEquals(emptyMap(), library.colors(root))
        assertEquals(mapOf("2026.甲" to blue), library.colors(dest))
        assertEquals(mapOf("draft.XNOTE" to blue), library.colors(moved))
        assertEquals(listOf(other, moved / "draft.XNOTE"), library.notePins())
        assertEquals("original", contents(moved / "draft.XNOTE"))
        assertEquals(moved, library.moveInto(listOf(moved), dest).single().path())
        assertTrue(library.setNotePinned(other, false))
        assertEquals(listOf(moved / "draft.XNOTE"), library.notePins())
    }

    @Test fun recursiveCopiesReidentifyEveryBundleAndKeepTheOriginalAndRawInk() {
        val note = fixture(folder / "A.xnote", "note-pdf-annotated.xnote")
        val sub = folder / "sub"
        fs.createDirectory(sub)
        val canvas = fixture(sub / "B.xcanvas", "canvas-mind-map.xcanvas")
        val extra = put(sub / "other.bin", "bytes")
        assertTrue(library.setColors(root, mapOf(folder.name to blue)))
        assertTrue(library.setColors(sub, mapOf(canvas.name to blue)))
        assertTrue(library.setNotePinned(note, true))
        val copied = library.copyInto(listOf(folder), root).single().path()
        assertEquals(root / "课程 copy", copied)
        assertEquals(root / "课程 copy 2", library.copyInto(listOf(folder), root).single().path())
        for (original in listOf(note, canvas)) {
            val copy = copied / original.relativeTo(folder)
            val before = BundleInterop.summarize(original.name, bytes(original))
            val after = BundleInterop.summarize(copy.name, bytes(copy))
            assertNotEquals(before.documentId, after.documentId)
            assertEquals(before.inkDigest, after.inkDigest)
            if (original == note) {
                assertEquals(before.entryContentCrc.getValue(BundlePdf.ENTRY), after.entryContentCrc.getValue(BundlePdf.ENTRY))
                assertTrue(after.pageIds.none { it in before.pageIds })
            }
        }
        assertContentEquals(interopFixture("note-pdf-annotated.xnote"), bytes(note))
        assertContentEquals(interopFixture("canvas-mind-map.xcanvas"), bytes(canvas))
        assertEquals(contents(extra), contents(copied / "sub" / "other.bin"))
        assertEquals(blue, library.colors(copied / "sub")[canvas.name])
        assertEquals(blue, library.colors(root)[copied.name])
        assertEquals(listOf(note), library.notePins())
        assertEquals(folder / "A copy.xnote", library.copyInto(listOf(note), folder).single().path())
    }

    @Test fun aBadBundleCancelsOnlyItsCopyAndRemovesAllStagedChildren() {
        put(folder / "a.txt")
        put(folder / "z.xnote", "broken")
        val other = fixture(root / "good.xcanvas", "canvas-ink-image.xcanvas")
        val dest = root / "destination"
        fs.createDirectory(dest)
        val results = library.copyInto(listOf(folder, other), dest)
        fails(LibraryFailure.IO_ERROR, results.first())
        assertEquals(dest / other.name, results.last().path())
        assertEquals(listOf(dest / other.name), fs.list(dest))
        assertEquals("broken", contents(folder / "z.xnote"))
    }

    @Test fun trashOrdersWrappersAndDropsDescendantPinsThenRestoresFolderAtRoot() {
        val tree = folder / "semester"
        val note = put(tree / "lecture.xnote")
        assertTrue(library.setNotePinned(note, true))
        assertTrue(library.setColors(folder, mapOf(tree.name to blue)))
        val first = library.trash(listOf(tree)).single().path()
        val wrapper = first.parent!!
        now += 1000
        val second = library.trash(listOf(put(root / "other.xnote"))).single().path()
        assertEquals(listOf(second, first), library.listTrash().map(TrashItem::path))
        assertTrue(library.notePins().isEmpty())
        fs.deleteRecursively(folder)
        fs.createDirectory(root / "semester")
        val restored = library.restore(library.listTrash().last()).path()
        assertEquals(root / "semester (2)", restored)
        assertTrue(fs.exists(restored / note.name))
        assertEquals(blue, library.colors(root)[restored.name])
        assertFalse(fs.exists(wrapper))
    }

    @Test fun purgeUsesStrictDayCutoffAndListingCleansEmptyWrappers() {
        val old = library.trash(listOf(put(root / "old.xnote"))).single().path()
        now += 86_400_000
        val fresh = library.trash(listOf(put(root / "fresh.xnote"))).single().path()
        put(trash / "1700000000000-000fff" / "trash.json", "{}")
        fs.createDirectories(trash / "1700000000001-111aaa")
        assertEquals(listOf(fresh, old), library.listTrash().map { it.path })
        assertEquals(2, fs.list(trash).size)
        library.purgeExpired(1)
        assertTrue(fs.exists(old))
        now++
        library.purgeExpired(0)
        assertTrue(fs.exists(old))
        library.purgeExpired(1)
        assertFalse(fs.exists(old))
        assertEquals(1, library.emptyTrash())
        assertEquals(0, library.emptyTrash())
        assertFalse(fs.exists(fresh))
    }

    @Test fun searchMatchesDisplayNameOnlyInRequestedSubtreeSkippingHiddenFilesAndLinks() {
        val first = put(folder / "Ink.XNOTE")
        val second = put(folder / "INK folder" / "pINK.xcanvas")
        put(root / "Ink.xnote")
        put(folder / ".hidden" / "Ink.xnote")
        put(folder / "ink.txt")
        library.trash(listOf(put(folder / "ink-trash.xnote"))).single().path()
        fs.createSymlink(folder / "link", root)
        assertEquals(listOf(first, second), library.search(folder, " iNk "))
        assertEquals(emptyList(), library.search(root, "xcanvas"))
        assertEquals(emptyList(), library.search(root, " "))
        assertEquals(emptyList(), library.search(trash, "ink"))
    }

    @Test fun pendingRecoveryRefusesAllRelocationsOfTheFileOrItsAncestor() {
        val note = fixture(folder / "a.xnote", "note-layers.xnote")
        val session = assertIs<LiteOpen.Opened>(store.open(note)).session
        session.markEdited()
        assertTrue(session.writeRecovery())
        session.close()
        val recovery = store.pendingRecoveries().single()
        val dest = root / "dest"
        fs.createDirectory(dest)
        for (entry in listOf(note, folder)) {
            fails(LibraryFailure.PENDING_RECOVERY, library.rename(entry, "changed"))
            fails(LibraryFailure.PENDING_RECOVERY, library.moveInto(listOf(entry), dest).single())
            fails(LibraryFailure.PENDING_RECOVERY, library.trash(listOf(entry)).single())
            assertTrue(fs.exists(entry))
        }
        assertFalse(fs.exists(trash))
        assertEquals(listOf(recovery), store.pendingRecoveries())
        assertIs<LiteOpen.Opened>(store.restore(recovery)).session.close()
        store.discard(recovery)
        assertEquals(dest / folder.name, library.moveInto(listOf(folder), dest).single().path())
    }

    @Test fun invalidNamesCollisionsMissingAndDescendantTargetsReturnDistinctReasons() {
        val note = put(folder / "original.xnote")
        val other = put(folder / "OTHER.xnote", "keep")
        for (bad in listOf("", " ", ".xnote", ".hidden", "../escape", "a/b", "a\\b")) {
            fails(LibraryFailure.INVALID_NAME, library.rename(note, bad))
        }
        fails(LibraryFailure.NAME_CONFLICT, library.rename(note, "other"))
        fails(LibraryFailure.NOT_FOUND, library.rename(folder / "gone.xnote", "new"))
        fails(LibraryFailure.NOT_FOUND, library.copyInto(listOf(note), root / "gone").single())
        fails(LibraryFailure.NOT_FOUND, library.trash(listOf(root / "absent")).single())
        val sub = folder / "sub"
        fs.createDirectory(sub)
        for (dest in listOf(folder, sub)) {
            fails(LibraryFailure.TARGET_IN_SOURCE, library.moveInto(listOf(folder), dest).single())
            fails(LibraryFailure.TARGET_IN_SOURCE, library.copyInto(listOf(folder), dest).single())
        }
        val same = put(root / note.name.uppercase(), "still here")
        fails(LibraryFailure.NAME_CONFLICT, library.moveInto(listOf(note), root).single())
        assertEquals("still here", contents(same))
        assertEquals("keep", contents(other))
    }

    @Test fun outsideHiddenAndSymbolicPathsCannotAuthorizeOperations() {
        val outside = put(base / "Other" / "external.xnote", "keep")
        val link = root / "outside"
        fs.createSymlink(link, outside.parent!!)
        val note = put(folder / "source.xnote")
        fs.createDirectory(side)
        fs.createSymlink(side / "colors.json", outside)
        assertFalse(library.setColors(root, mapOf(folder.name to blue)))
        assertFalse(library.setNotePinned(link / outside.name, true))
        for (path in listOf(outside, root, side, side / "colors.json", link, link / outside.name)) {
            fails(LibraryFailure.INVALID_NAME, library.trash(listOf(path)).single())
        }
        for (target in listOf(outside.parent!!, side, link)) {
            fails(LibraryFailure.INVALID_NAME, library.moveInto(listOf(note), target).single())
            fails(LibraryFailure.INVALID_NAME, library.copyInto(listOf(note), target).single())
        }
        assertEquals("keep", contents(outside))
        val fake = TrashItem(outside, false, emptyList(), now, null)
        assertFalse(library.delete(fake))
        fails(LibraryFailure.INVALID_NAME, library.restore(fake))
        assertEquals("keep", contents(outside))
    }

    @Test fun hostileTrashMetadataAndSidecarDirectoryLinksCannotEscapeRoot() {
        val outside = put(base / "Else" / "a.xnote", "keep")
        val wrapper = trash / "1700000000123-baf002"
        val payload = put(wrapper / "a.xnote", "old")
        put(wrapper / "trash.json", """{"version":1,"path":["..","Else"]}""")
        val invalid = library.listTrash().single()
        fails(LibraryFailure.INVALID_NAME, library.restore(invalid))
        assertFalse(library.delete(invalid.copy(path = outside)))
        assertEquals("old", contents(payload))
        fs.createSymlink(folder / ".xnote", outside.parent!!)
        assertFalse(library.setColors(folder, mapOf("a.xnote" to blue)))
        assertEquals(listOf(outside), fs.list(outside.parent!!))
    }

    @Test fun failedFinalMoveRollsBackColorsPinsDestinationReservationAndTrashWrapper() {
        val note = put(folder / "original.xnote")
        val target = root / "dest"
        fs.createDirectory(target)
        val failing = object : ForwardingFileSystem(fs) {
            override fun atomicMove(source: Path, target: Path) {
                if (source == note) throw IOException("injected publish failure")
                super.atomicMove(source, target)
            }
        }
        library = libraryWith(failing)
        assertTrue(library.setColors(folder, mapOf(note.name to blue)))
        assertTrue(library.setNotePinned(note, true))
        val originalColors = contents(folder / ".xnote" / "colors.json")
        val originalPins = contents(side / "pins.json")
        fails(LibraryFailure.IO_ERROR, library.moveInto(listOf(note), target).single())
        fails(LibraryFailure.IO_ERROR, library.trash(listOf(note)).single())
        assertEquals(emptyList(), fs.list(target))
        assertEquals(listOf(side / "pins.json"), fs.list(side))
        assertEquals("content", contents(note))
        assertEquals(originalColors, contents(folder / ".xnote" / "colors.json"))
        assertEquals(originalPins, contents(side / "pins.json"))
    }

    @Test fun anUnreadableRecoveryIndexLeavesTheLibraryIntact() {
        val note = put(folder / "original.xnote")
        val index = base / "Private" / "recoveries"
        fs.createDirectories(index)
        val failing = object : ForwardingFileSystem(fs) {
            override fun list(dir: Path): List<Path> {
                if (dir == index) throw IOException("unreadable recovery index")
                return super.list(dir)
            }
        }
        library = libraryWith(failing)
        fails(LibraryFailure.IO_ERROR, library.rename(note, "rename"))
        fails(LibraryFailure.IO_ERROR, library.trash(listOf(folder)).single())
        assertFalse(fs.exists(side))
        assertEquals("content", contents(note))
    }

    @Test fun aMissingOriginalWithRecoveryCannotBeAdoptedByAnUnrelatedCopy() {
        val old = fixture(root / "original.xnote", "note-layers.xnote")
        assertIs<LiteOpen.Opened>(store.open(old)).session.use { session ->
            session.markEdited()
            assertTrue(session.writeRecovery())
        }
        fs.delete(old)
        val other = fixture(folder / "Original.xnote", "note-ink-text-pages.xnote")
        fails(LibraryFailure.PENDING_RECOVERY, library.copyInto(listOf(other), root).single())
        assertEquals(listOf(folder), fs.list(root))
        assertEquals(old, store.pendingRecoveries().single().original)
    }

    @Test fun restorePublishFailureKeepsTheOriginalPayloadAndMetadataInTrash() {
        val file = put(folder / "original.xnote")
        assertTrue(library.setColors(folder, mapOf(file.name to blue)))
        val payload = library.trash(listOf(file)).single().path()
        val stored = library.listTrash().single()
        val marker = payload.parent!! / "trash.json"
        val originalJson = contents(marker)
        library = libraryWith(object : ForwardingFileSystem(fs) {
            override fun atomicMove(source: Path, target: Path) {
                if (source == payload) throw IOException("injected restore failure")
                super.atomicMove(source, target)
            }
        })
        fails(LibraryFailure.IO_ERROR, library.restore(stored))
        assertEquals(listOf(stored), library.listTrash())
        assertEquals(originalJson, contents(marker))
        assertEquals("content", contents(payload))
        assertEquals(emptyMap(), library.colors(folder))
        assertFalse(fs.exists(file))
    }

    @Test fun permanentDeletionCanRetryUnlinkingWithoutShowingAPartlyDeletedFolder() {
        val outside = put(base / "Other" / "external.xnote", "untouched")
        put(folder / "a.xnote")
        put(folder / "b.xnote")
        fs.createSymlink(folder / "external", outside.parent!!)
        library.trash(listOf(folder)).single().path()
        val item = library.listTrash().single()
        var fail = true
        library = libraryWith(object : ForwardingFileSystem(fs) {
            override fun delete(path: Path, mustExist: Boolean) {
                if (fail && path.name == "b.xnote") throw IOException("temporarily busy")
                super.delete(path, mustExist)
            }
        })
        assertTrue(library.delete(item))
        assertEquals(emptyList(), library.listTrash())
        fails(LibraryFailure.NOT_FOUND, library.restore(item))
        fail = false
        assertEquals(0, library.emptyTrash())
        assertEquals(listOf(trash), fs.list(side))
        assertEquals("untouched", contents(outside))
    }
}
