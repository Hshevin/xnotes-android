package com.xnotes.storage

import com.xnotes.core.util.DocumentKind
import com.xnotes.format.*
import okio.Buffer
import okio.ForwardingSource
import okio.ByteString.Companion.toByteString
import kotlin.test.*

class MigrationJournalTest : BundleTestFiles() {
    @Test fun stageReadsBackBothFilesBeforePublishingTheHandoffMarker() {
        val directory = testDirectory("journal-")
        val journal = MigrationJournal(directory)
        val old = testFile("old-", ".zip").apply { setText("original") }
        val next = testFile("next-", ".zip").apply { setText("candidate") }
        val oldHash = old.fileBytes().toByteString().sha256().hex()
        val newHash = next.fileBytes().toByteString().sha256().hex()
        val handoff = "00000000-0000-0000-0000-000000000001"
        assertFailsWith<ReferenceContractViolation> {
            journal.stage(old, next, PublicationPurpose.SAVE_NOTE, oldHash, oldHash, handoff) {}
        }
        assertFalse("pending" in directory.fileNames())
        val digest = journal.stage(old, next, PublicationPurpose.SAVE_NOTE, newHash, oldHash, handoff) {}
        assertEquals("original",directory.child("original.zip").fileText())
        assertEquals("candidate",directory.child("candidate.zip").fileText())
        val marker = directory.child("pending").fileBytes()
        assertEquals("4\n$oldHash\n$newHash\nsave-note\n$handoff",marker.decodeToString())
        assertEquals(marker.toByteString().sha256().hex(),digest)
        assertEquals(MigrationJournal.Record(oldHash,newHash,digest,PublicationPurpose.SAVE_NOTE,handoff),
            MigrationJournal.parse(MigrationJournal.readMarker(marker.asSource())))
        assertFailsWith<IllegalStateException> { journal.stage(next, old, null, null, null, null) {} }
        assertContentEquals(marker,directory.child("pending").fileBytes())
    }

    @Test fun oversizedMarkerReadsOnlyThePrefixUnlessExplicitlyKeepingLegacy() {
        for (typed in listOf(false,true)) for (keep in listOf(false,true)) {
            val bytes = ((if (typed) "03" else "2") + "\n" + "a".repeat(70000)).encodeToByteArray()
            var count = 0L
            var closed = false
            val input = object : ForwardingSource(bytes.asSource()) {
                override fun read(sink: Buffer, byteCount: Long) = super.read(sink,byteCount).also { if (it > 0) count += it }
                override fun close() { closed = true; super.close() }
            }
            if (keep && !typed) {
                val marker = MigrationJournal.readMarker(input,true)
                assertEquals(bytes.size.toLong(),count)
                assertEquals(257,marker.bytes.size)
                assertEquals(bytes.toByteString().sha256().hex(),marker.digest)
            } else {
                assertFailsWith<IllegalArgumentException> { MigrationJournal.readMarker(input,keep) }
                assertEquals(257L,count)
            }
            assertTrue(closed)
        }
    }

    @Test fun aCreationIntentIssuesOnlyOnePermitFromItsOriginalOwner() {
        val issuer = NativeCreationIssuer()
        val other = NativeCreationIssuer()
        val intent = issuer.begin(DocumentKind.NOTE)
        assertFailsWith<ReferenceContractViolation> { issuer.permit(intent,"first") }
        issuer.consume(intent)
        assertFailsWith<ReferenceContractViolation> { other.permit(intent,"first") }
        val permit = issuer.permit(intent,"first")
        assertEquals(intent.documentId,permit.documentId)
        assertFailsWith<ReferenceContractViolation> { issuer.permit(intent,"second") }
        assertFailsWith<ReferenceContractViolation> { other.consume(permit) }
        issuer.consume(permit)
        assertFailsWith<ReferenceContractViolation> { issuer.consume(permit) }
    }
}
