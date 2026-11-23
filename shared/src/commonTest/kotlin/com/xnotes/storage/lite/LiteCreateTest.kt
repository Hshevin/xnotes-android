package com.xnotes.storage.lite

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.*

class LiteCreateTest {
    private val fs = KFiles.system
    private val root = KFiles.tempDirectory() / Uuids.random()
    private val docs = (root / "Docs").also(fs::createDirectories)
    private val folder = (docs / "Folder").also(fs::createDirectory)
    private val privateDir = root / "Private"
    private fun store(files: FileSystem = fs) = LiteStore(files, docs, privateDir, FakeImageCodec(), FakeTextMeasurer())
    @AfterTest fun cleanup() = fs.deleteRecursively(root)

    @Test fun newDocumentsAreVerifiedReopenableAndIndependentlyIdentified() {
        val store = store()
        val ids = mutableSetOf<String>()
        val pages = mutableSetOf<String>()
        for (kind in listOf(LiteKind.NOTE, LiteKind.NOTE, LiteKind.CANVAS)) {
            assertIs<LiteOpen.Opened>(store.create(folder, "Untitled", kind)).session.use { session ->
                assertEquals(folder, session.path.parent)
                assertTrue(ids.add(session.documentId)); assertFalse(session.dirty)
                val page = session.note?.pages?.single()
                if (kind == LiteKind.NOTE) {
                    assertNotNull(page)
                    assertEquals(PageSize.A4.pixels(Orientation.PORTRAIT, PageSize.DEFAULT_DPI), page.width to page.height)
                    assertTrue(pages.add(assertNotNull(page.referenceIdentity.id)))
                }
                assertIs<LiteOpen.Opened>(store.open(session.path)).session.use { reopened ->
                    assertEquals(session.documentId, reopened.documentId)
                    assertEquals(page?.referenceIdentity?.id, reopened.note?.pages?.single()?.referenceIdentity?.id)
                }
                session.markEdited(); assertEquals(LiteSave.Saved, session.save())
            }
        }
        assertEquals(setOf("Untitled.xnote", "Untitled (2).xnote", "Untitled.xcanvas"), fs.list(folder).map { it.name }.toSet())
    }

    @Test fun lateCollisionCannotClobberTheOtherWriter() {
        val first = folder / "New.xnote"
        var inject = true
        val files = object : ForwardingFileSystem(fs) {
            override fun metadataOrNull(path: Path): FileMetadata? {
                val found = super.metadataOrNull(path)
                if (path == first && inject && found == null) {
                    inject = false; fs.write(path) { writeUtf8("other writer") }
                }
                return found
            }
        }
        assertIs<LiteOpen.Opened>(store(files).create(folder, "New", LiteKind.NOTE)).session.use {
            assertEquals("New (2).xnote", it.path.name)
        }
        assertEquals("other writer", fs.read(first) { readUtf8() })
    }

    @Test fun failedPublishLeavesNoDestinationStageOrPrivateAssets() {
        val files = object : ForwardingFileSystem(fs) {
            override fun metadataOrNull(path: Path): FileMetadata? {
                if (path == folder / "New.xnote") throw IOException("directory unavailable")
                return super.metadataOrNull(path)
            }
        }
        assertIs<LiteOpen.Refused>(store(files).create(folder, "New", LiteKind.NOTE))
        assertTrue(fs.list(folder).isEmpty()); assertEquals(listOf(folder), fs.list(docs))
        assertFalse(fs.list(privateDir).any { it.name.startsWith("assets-") })
    }

    @Test fun unsafeNamesAndNonDocumentFoldersAreNotTargets() {
        val store = store()
        for (name in listOf("../New", ".hidden", "", "one/two")) assertIs<LiteOpen.Refused>(store.create(folder, name, LiteKind.CANVAS))
        assertIs<LiteOpen.Refused>(store.create(root, "New", LiteKind.NOTE))
        assertEquals(listOf(folder), fs.list(docs)); assertTrue(fs.list(folder).isEmpty())
    }

    @Test fun changedStagedBytesCannotBePublished() {
        var changed = false
        val files = object : ForwardingFileSystem(fs) {
            override fun metadataOrNull(path: Path): FileMetadata? {
                if (!changed && path == folder / "Race.xcanvas") {
                    val stage = fs.list(docs).single { it.name.startsWith(".create-") }
                    fs.write(fs.list(stage).single()) { writeUtf8("changed after verification") }
                    changed = true
                }
                return super.metadataOrNull(path)
            }
        }
        assertIs<LiteOpen.Refused>(store(files).create(folder, "Race", LiteKind.CANVAS))
        assertTrue(changed); assertTrue(fs.list(folder).isEmpty()); assertEquals(listOf(folder), fs.list(docs))
        assertFalse(fs.list(privateDir).any { it.name.startsWith("assets-") })
    }
}
