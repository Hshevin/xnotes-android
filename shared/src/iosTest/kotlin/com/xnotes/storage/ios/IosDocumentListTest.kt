@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.storage.ios

import com.xnotes.core.util.DocumentKind
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.Foundation.NSURLContentModificationDateKey
import platform.Foundation.NSURLCreationDateKey
import platform.Foundation.dateWithTimeIntervalSince1970
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosDocumentListTest {
    @Test
    fun reportsFileBytesAndBirthTimeIndependentlyOfModificationTime() {
        val root = KFiles.tempDirectory() / Uuids.random()
        val files = KFiles.system
        files.createDirectories(root)
        try {
            val path = root / "ink.xnote"
            files.write(path) { writeUtf8("墨") }
            val birth = 1_700_000_000L
            val modified = birth + 86_400
            val url = NSURL.fileURLWithPath(path.toString())
            assertTrue(url.setResourceValue(NSDate.dateWithTimeIntervalSince1970(birth.toDouble()), NSURLCreationDateKey, null))
            assertTrue(url.setResourceValue(NSDate.dateWithTimeIntervalSince1970(modified + 0.125), NSURLContentModificationDateKey, null))
            val entry = listIosDocuments(root).single()
            assertEquals("ink.xnote", entry.name)
            assertEquals(false, entry.isDir)
            assertEquals(3L, entry.size)
            assertEquals(birth * 1000, entry.createdAtMillis)
            assertEquals(birth * 1000, entry.created)
            assertEquals(modified * 1000 + 125, entry.modifiedAtMillis)
            assertEquals(entry.modifiedAtMillis, entry.modified)
            assertEquals(entry.modified, entry.copy(createdAtMillis = null).created)
            assertNull(entry.childCount)
            assertNull(entry.color)
        } finally {
            files.deleteRecursively(root)
        }
    }

    @Test
    fun countsOnlyVisibleImmediateChildrenWithoutFollowingLinks() {
        val root = KFiles.tempDirectory() / Uuids.random()
        val folder = root / "Projects"
        val files = KFiles.system
        files.createDirectories(folder / "nested")
        try {
            files.createDirectories(root / "empty")
            files.createDirectories(folder / ".private")
            for (name in listOf("draft.XNOTE", "sketch.xcanvas", ".hidden.xnote", "other.txt", "nested/one.xnote")) {
                files.write(folder / name) { writeUtf8("") }
            }
            files.createSymlink(folder / "link.xnote", folder / "draft.XNOTE")
            files.createSymlink(folder / "parent", root)
            files.createSymlink(root / "folder-link", folder)
            val entries = listIosDocuments(root)
            assertEquals(listOf("empty", "Projects"), entries.map { it.name })
            assertEquals(listOf(0, 3), entries.map { it.childCount })
            assertTrue(entries.all { it.isDir && it.size == 0L })
            assertEquals(listOf("nested", "draft.XNOTE", "sketch.xcanvas"), listIosDocuments(folder).map { it.name })
        } finally {
            files.deleteRecursively(root)
        }
    }

    @Test
    fun listsFoldersAndRecognisedNotesWithoutReadingTheirContents() {
        val root = KFiles.tempDirectory() / Uuids.random()
        val files = KFiles.system
        files.createDirectories(root / "Projects")
        try {
            for (name in listOf("B.XNOTE", "a.xcanvas", "unrelated.txt", ".draft.xnote")) {
                files.write(root / name) { writeUtf8("not a bundle") }
            }
            IosStorageLocations.stagingFor(root)
            val entries = listIosDocuments(root)
            assertEquals(listOf("Projects", "a.xcanvas", "B.XNOTE"), entries.map { it.path.name })
            assertEquals(listOf(null, DocumentKind.CANVAS, DocumentKind.NOTE), entries.map { it.kind })
            assertTrue(entries.all { it.modifiedAtMillis != null })
        } finally {
            files.deleteRecursively(root)
        }
    }
}
