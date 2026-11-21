package com.xnotes.storage

import com.xnotes.format.BundleHeader
import com.xnotes.format.BundleIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class DocumentRegistrySameFileTest {
    private val id = UUID.randomUUID().toString()
    private val picker = "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fa.xcanvas"
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Fa.xcanvas"
    private val copy = tree.replace("a.xcanvas", "copy.xcanvas")

    private fun registry(result: Boolean?) = DocumentRegistry(RuntimeEnvironment.getApplication(), SameFileProbe { a, b ->
        if (setOf(a, b) == setOf(picker, tree)) result else false
    })

    @Test fun pickerThenTreeKeepsOneTreeRowAndPreservesLocationProtection(): Unit = registry(true).use { db ->
        db.register(id, "xcanvas", "created", picker)
        val before = db.identityObservation(id)
        db.register(id, "xcanvas", "listed", tree)
        assertFalse(before.current())
        assertEquals(listOf(tree), db.knownLocations())
        assertEquals(tree, db.resolve(id))
        assertFalse(db.hasCompetingLocation(id, picker))
        assertEquals(ReferenceExpectation(id, false), db.expectationAt(picker))
        assertThrows(ReferenceContractViolation::class.java) {
            db.verifyCreation(UUID.randomUUID().toString(), "xcanvas", picker)
        }
    }

    @Test fun treeThenPickerKeepsTheTreeAndRepeatedObservationsAreStable() = registry(true).use { db ->
        db.register(id, "xcanvas", "listed", tree)
        db.register(id, "xcanvas", "opened", picker)
        val ticket = db.identityObservation(id)
        db.register(id, "xcanvas", "reopened", picker)
        assertTrue(ticket.current())
        assertEquals(listOf(tree), db.knownLocations())
        assertEquals(tree, db.resolve(id))
    }

    @Test fun retiredGrantRemainsAnIdentityExpectationWithoutProvingTheFileIsStillTheSame() {
        registry(true).use { db ->
            db.register(id, "xcanvas", "created", picker)
            db.register(id, "xcanvas", "listed", tree)
        }
        registry(null).use { db ->
            assertEquals(id, db.idAt(picker))
            assertEquals(tree, db.resolve(id))
            assertThrows(ReferenceContractViolation::class.java) {
                db.verifyCreation(id, "xcanvas", picker, allowRegisteredHere = true)
            }
            db.register(id, "xcanvas", "observed again", picker)
            assertEquals(setOf(picker, tree), db.locations(id).toSet())
        }
    }

    @Test fun explicitReassignmentThroughAnAliasKeepsTheOtherPhysicalCopyWithItsOriginalUuid() = registry(true).use { db ->
        db.register(id, "xcanvas", "created", picker)
        db.register(id, "xcanvas", "listed", tree)
        db.register(id, "xcanvas", "copied", copy)
        val newId = UUID.randomUUID().toString()
        db.register(newId, "xcanvas", "recovered copy", picker)
        assertEquals(copy, db.resolve(id))
        assertEquals(tree, db.resolve(newId))
        assertEquals(newId, db.idAt(picker))
        assertEquals(newId, db.idAt(tree))
    }

    private fun assertDuplicate(result: Boolean?): Unit = registry(result).use { db ->
        db.register(id, "xcanvas", "same name", picker)
        db.register(id, "xcanvas", "same name", tree)
        assertEquals(setOf(picker, tree), db.locations(id).toSet())
        assertTrue(db.hasCompetingLocation(id, picker))
        assertThrows(IllegalStateException::class.java) { db.resolve(id) }
        assertThrows(ReferenceContractViolation::class.java) {
            db.verifyCreation(id, "xcanvas", tree, allowRegisteredHere = true)
        }
    }

    @Test fun differentFilesWithOneUuidRemainADuplicate() = assertDuplicate(false)
    @Test fun unknownPhysicalIdentityRemainsADuplicate() = assertDuplicate(null)

    @Test fun persistedAliasesAreReconciledBeforeResolutionWithoutRemovingACopyOrTouchingOtherDocuments() {
        val other = UUID.randomUUID().toString()
        val elsewhere = "content://other/document/another"
        registry(null).use { old ->
            old.register(id, "xcanvas", "created", picker)
            old.register(id, "xcanvas", "listed", tree)
            old.register(id, "xcanvas", "copied", copy)
            old.register(other, "xnote", "other", elsewhere, pageReferences = true)
            assertEquals(4, old.knownLocations().size)
            // The exact schema installed when the duplicate was originally reported.
            old.writableDatabase.execSQL("DROP TABLE location_alias_expectations")
            old.writableDatabase.version = 3
        }
        registry(true).use { db ->
            val ticket = db.identityObservation(id)
            val otherTicket = db.identityObservation(other)
            assertEquals(4, db.readableDatabase.version)
            assertEquals(4, db.knownLocations().size)
            assertThrows(IllegalStateException::class.java) { db.resolve(id) }
            assertEquals(setOf(copy, tree, elsewhere), db.knownLocations().toSet())
            assertEquals(ReferenceExpectation(other, true), db.expectationAt(elsewhere))
            assertFalse(ticket.current())
            assertTrue(otherTicket.current())
            assertEquals(listOf(elsewhere), db.locations(other))
        }
    }

    @Test fun aCreationRetryAndUniqueObservationAcceptOnlyTheProvenAlias() = registry(true).use { db ->
        val file = File.createTempFile("same-file", ".zip", RuntimeEnvironment.getApplication().cacheDir)
        val header = try {
            ZipOutputStream(file.outputStream()).use { BundleIdentity(id).write(it) }
            BundleHeader.read(file)
        } finally { file.delete() }

        db.registerCreated(id, "xcanvas", "created", picker, pageReferences = false)
        db.registerObserved(header, "xcanvas", "listed", tree, uniqueSource = true)
        db.registerCreated(id, "xcanvas", "retry", picker, pageReferences = false)
        assertEquals(listOf(tree), db.knownLocations())
        assertThrows(IllegalArgumentException::class.java) {
            db.registerObserved(header, "xcanvas", "real copy", copy, uniqueSource = true)
        }
        assertEquals(listOf(tree), db.knownLocations())
        assertEquals("retry", db.nameForId(id))
    }
}
