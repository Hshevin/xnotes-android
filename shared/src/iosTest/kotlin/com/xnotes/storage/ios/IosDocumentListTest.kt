package com.xnotes.storage.ios

import com.xnotes.core.util.DocumentKind
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosDocumentListTest {
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
