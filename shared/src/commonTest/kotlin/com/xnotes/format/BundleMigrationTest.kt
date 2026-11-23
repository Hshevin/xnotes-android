package com.xnotes.format

import com.xnotes.core.pal.*
import com.xnotes.kio.*
import okio.*
import kotlin.test.assertFailsWith

import com.xnotes.core.testkit.*
import kotlin.test.Test

class BundleMigrationTest : BundleTestFiles() {
    /** Representative legacy payload deliberately includes fields this version's model does not understand. */
    private fun legacy(dir: AssetFile, canvas: Boolean = false, comment: Boolean = false): AssetFile = dir.child("legacy.zip").also { file ->
        val stroke = """{"kind":"stroke","tool":"pen","config":{"base_width":2.5000,"rgba":[1,2,3,255],"pressure_enabled":true},"samples":[[0.125,-10.5,0.25,17],[9.75,11.0,0.875,24]],"tilt":[[12,13],[14,15]],"transform":[1,0,0,1,3,4],"unknownFutureField":"preserve me"}"""
        val manifest = if (canvas) """{"format":"xcanvas","version":1,"items":[$stroke]}""" else """{"format":"xnote","version":1,"pages":[{"items":[$stroke]}]}"""
        bundleWriter(file.fileSink(), comment = "legacy archive comment".takeIf { comment }).use { zip ->
            for ((name, bytes) in listOf("manifest.json" to manifest.encodeToByteArray(), "assets/source.pdf" to "%PDF-1.7\noriginal bytes\u0000\u0001".encodeToByteArray(), "future/payload.bin" to ByteArray(65537) { it.toByte() })) {
                zip.putDeflated(name,bytes)
            }
        }
    }
    private fun withDirectory(block: (AssetFile) -> Unit) {
        val dir = testDirectory("bundle-migration")
        try { block(dir) } finally { dir.deleteTree() }
    }
    @Test fun pagedAndCanvasPreserveAllPayloadHashesAndRepeatedMigrationIdentity() = withDirectory { dir ->
        for (canvas in listOf(false, true)) {
            val source = legacy(dir, canvas)
            val before = BundleMigration.fingerprints(source)
            val original = source.fileBytes()
            val prepared = BundleMigration.prepare(source, dir)
            assertArrayEquals(original, source.fileBytes())
            assertEquals(before, BundleMigration.fingerprints(prepared.file).filterKeys { it != BundleIdentity.ENTRY })
            BundleMigration.publish(prepared, source)
            val id = readBundleIdentity(source)
            val second = BundleMigration.prepare(source, dir)
            assertEquals(id, second.identity)
            assertEquals(before, BundleMigration.fingerprints(second.file).filterKeys { it != BundleIdentity.ENTRY })
            second.file.remove()
        }
    }
    @Test fun applicationCopyGetsNewIdWithoutChangingPayload() = withDirectory { dir ->
        val source = legacy(dir)
        BundleMigration.publish(BundleMigration.prepare(source, dir), source)
        val original = readBundleIdentity(source)
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
        val source = legacy(dir); val before = source.fileBytes()
        try { BundleMigration.prepare(source, dir, cancelled = { true }); fail("must cancel") } catch (_: IllegalStateException) {}
        assertArrayEquals(before, source.fileBytes()); assertEquals(listOf(source.assetPath().name), dir.fileNames().toList())
    }
    @Test fun concurrentEditPreventsPublicationAndKeepsCandidateForRecovery() = withDirectory { dir ->
        val source = legacy(dir); val candidate = BundleMigration.prepare(source, dir)
        source.appendUtf8("external change")
        val changed = source.fileBytes()
        try { BundleMigration.publish(candidate, source); fail("must refuse") } catch (_: IllegalStateException) {}
        assertArrayEquals(changed, source.fileBytes()); assertTrue(candidate.file.readableFile())
    }
    @Test fun missingManifestDoesNotReplaceFile() = withDirectory { dir ->
        val source = dir.child("broken.zip")
        bundleWriter(source.fileSink()).use { it.putDeflated("other", byteArrayOf()) }
        val before = source.fileBytes()
        try { BundleMigration.prepare(source, dir); fail("must refuse") } catch (_: IllegalArgumentException) {}
        assertArrayEquals(before, source.fileBytes()); assertEquals(1, dir.fileNames().size)
    }
}
