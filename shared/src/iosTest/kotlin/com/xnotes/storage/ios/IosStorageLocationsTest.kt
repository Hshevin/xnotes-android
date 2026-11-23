package com.xnotes.storage.ios

import com.xnotes.kio.KFiles
import com.xnotes.kio.atomicWrite
import com.xnotes.kio.fileIdentity
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W2-3 contract (docs/movinkpad/process/ipados-wave2-contracts-2026-11-24.md, "W2-3"): where the iPad app keeps
 * what. Notes the user owns go in Documents (shown in the Files app); everything private to the app stays out of it.
 */
class IosStorageLocationsTest {

    private val fs = KFiles.system

    @Test
    fun notesLiveInTheDocumentsDirectory() {
        val docs = IosStorageLocations.documents
        assertTrue(docs.toString().endsWith("/Documents"), docs.toString())
        assertTrue(fs.metadataOrNull(docs)?.isDirectory == true)
    }

    @Test
    fun privateAndNoBackupDataStayOutOfDocuments() {
        val docs = IosStorageLocations.documents.toString()
        for (dir in listOf(IosStorageLocations.private, IosStorageLocations.noBackup, IosStorageLocations.cache)) {
            val text = dir.toString()
            assertFalse(text.startsWith(docs), "$text is visible in the Files app")
            assertTrue("/Library/" in text, text)
            assertTrue(fs.metadataOrNull(dir)?.isDirectory == true, "$text should be created on first use")
        }
        assertNotEquals(IosStorageLocations.private, IosStorageLocations.noBackup)
    }

    @Test
    fun noBackupIsExcludedFromDeviceBackup() {
        assertTrue(IosStorageLocations.isExcludedFromBackup(IosStorageLocations.noBackup))
        assertFalse(IosStorageLocations.isExcludedFromBackup(IosStorageLocations.documents))
    }

    @Test
    fun stagingForAtomicPublishSitsOnTheSameVolumeAsDocuments() {
        // An atomic rename only works within one file system, so staged notes must share the documents volume.
        val staged = IosStorageLocations.stagingFor(IosStorageLocations.documents)
        fs.createDirectories(staged)
        val probe = staged / "probe-${com.xnotes.kio.Uuids.random()}"
        val target = IosStorageLocations.documents / ".probe-${com.xnotes.kio.Uuids.random()}"
        try {
            atomicWrite(fs, probe) { it.writeUtf8("x") }
            val id = assertNotNull(fileIdentity(probe))
            fs.atomicMove(probe, target)
            assertEquals(id, fileIdentity(target))
        } finally {
            fs.delete(probe, mustExist = false)
            fs.delete(target, mustExist = false)
        }
        val insideDocuments = staged.toString().startsWith(IosStorageLocations.documents.toString() + "/")
        assertTrue(!insideDocuments || staged.name.startsWith("."), "staging inside Documents must be hidden (dot-named)")
    }

    @Test
    fun pathsAreAbsolute() {
        for (p in listOf(IosStorageLocations.documents, IosStorageLocations.private, IosStorageLocations.noBackup)) {
            assertTrue(p.isAbsolute, p.toString())
            assertNotEquals("/".toPath(), p)
        }
    }
}
