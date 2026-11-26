package com.xnotes.format

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class BundleMigrationTest {
    /** Representative legacy payload deliberately includes fields this version's model does not understand. */
    private fun legacy(dir: File, canvas: Boolean = false, comment: Boolean = false): File = File(dir, "legacy.zip").also { file ->
        val stroke = """{"kind":"stroke","tool":"pen","config":{"base_width":2.5000,"rgba":[1,2,3,255],"pressure_enabled":true},"samples":[[0.125,-10.5,0.25,17],[9.75,11.0,0.875,24]],"tilt":[[12,13],[14,15]],"transform":[1,0,0,1,3,4],"unknownFutureField":"preserve me"}"""
        val manifest = if (canvas) """{"format":"xcanvas","version":1,"items":[$stroke]}""" else """{"format":"xnote","version":1,"pages":[{"items":[$stroke]}]}"""
        ZipOutputStream(file.outputStream()).use { zip ->
            if (comment) zip.setComment("legacy archive comment")
            for ((name, bytes) in listOf("manifest.json" to manifest.toByteArray(), "assets/source.pdf" to "%PDF-1.7\noriginal bytes\u0000\u0001".toByteArray(), "future/payload.bin" to ByteArray(65537) { it.toByte() })) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }
    private fun withDirectory(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("bundle-migration").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    @Test fun pagedAndCanvasPreserveAllPayloadHashesAndRepeatedMigrationIdentity() = withDirectory { dir ->
        for (canvas in listOf(false, true)) {
            val source = legacy(dir, canvas)
            val before = BundleMigration.fingerprints(source)
            val original = source.readBytes()
            val prepared = BundleMigration.prepare(source, dir)
            assertArrayEquals(original, source.readBytes())
            assertEquals(before, BundleMigration.fingerprints(prepared.file).filterKeys { it != BundleIdentity.ENTRY })
            BundleMigration.publish(prepared, source)
            val id = BundleIdentity.read(source)
            val second = BundleMigration.prepare(source, dir)
            assertEquals(id, second.identity)
            assertEquals(before, BundleMigration.fingerprints(second.file).filterKeys { it != BundleIdentity.ENTRY })
            second.file.delete()
        }
    }
    @Test fun applicationCopyGetsNewIdWithoutChangingPayload() = withDirectory { dir ->
        val source = legacy(dir)
        BundleMigration.publish(BundleMigration.prepare(source, dir), source)
        val original = BundleIdentity.read(source)
        val copy = BundleMigration.prepare(source, dir, copy = true)
        assertNotEquals(original!!.documentId, copy.identity.documentId)
        assertEquals(BundleMigration.fingerprints(source).filterKeys { it != BundleIdentity.ENTRY }, BundleMigration.fingerprints(copy.file).filterKeys { it != BundleIdentity.ENTRY })
    }
    @Test fun commentedArchiveFallbackPreservesPayload() = withDirectory { dir ->
        val source = legacy(dir, comment = true)
        val candidate = BundleMigration.prepare(source, dir)
        assertEquals(BundleMigration.fingerprints(source), BundleMigration.fingerprints(candidate.file).filterKeys { it != BundleIdentity.ENTRY })
    }
    @Test fun cancellationLeavesOriginalAndNoPartialCandidate() = withDirectory { dir ->
        val source = legacy(dir); val before = source.readBytes()
        try { BundleMigration.prepare(source, dir, cancelled = { true }); fail("must cancel") } catch (_: IllegalStateException) {}
        assertArrayEquals(before, source.readBytes()); assertEquals(listOf(source.name), dir.list()!!.toList())
    }
    @Test fun concurrentEditPreventsPublicationAndKeepsCandidateForRecovery() = withDirectory { dir ->
        val source = legacy(dir); val candidate = BundleMigration.prepare(source, dir)
        source.appendText("external change")
        val changed = source.readBytes()
        try { BundleMigration.publish(candidate, source); fail("must refuse") } catch (_: IllegalStateException) {}
        assertArrayEquals(changed, source.readBytes()); assertTrue(candidate.file.isFile)
    }
    @Test fun missingManifestDoesNotReplaceFile() = withDirectory { dir ->
        val source = File(dir, "broken.zip")
        ZipOutputStream(source.outputStream()).use { it.putNextEntry(ZipEntry("other")); it.closeEntry() }
        val before = source.readBytes()
        try { BundleMigration.prepare(source, dir); fail("must refuse") } catch (_: IllegalArgumentException) {}
        assertArrayEquals(before, source.readBytes()); assertEquals(1, dir.list()!!.size)
    }
}
