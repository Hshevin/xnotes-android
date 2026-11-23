package com.xnotes.core.util

import com.xnotes.core.pal.asAssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import com.xnotes.kio.openRead
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableCacheAndCopyTest {
    private val fs = KFiles.system
    private val dir: Path = KFiles.tempDirectory() / Uuids.random()

    @BeforeTest fun createDirectory() = fs.createDirectories(dir)
    @AfterTest fun removeDirectory() = fs.deleteRecursively(dir)

    @Test fun stagedRecoveryRecognizesUnchangedPartialCompleteAndForeignBytes() {
        val target = dir / "target"
        val staged = dir / "staged"
        val original = "original"
        val updated = "new content!"
        fs.write(target) { writeUtf8(original) }
        val oldHash = openRead(target).use { StagedCopy.endHash(it) }
        assertFalse(copyOver(staged, 8) { error("missing source must not open destination") })
        fs.write(staged) { writeUtf8(updated) }
        fun state() = openRead(target).use { dest -> openRead(staged).use { source ->
            StagedCopy.of(dest, source, original.length.toLong(), oldHash)
        } }
        assertEquals(StagedCopy.REDO, state())
        fs.write(target) { writeUtf8(updated.take(4)) }
        assertEquals(StagedCopy.REDO, state())
        fs.write(target) { writeUtf8("else") }
        assertEquals(StagedCopy.LEAVE, state())
        assertTrue(copyOver(staged, 2) { fs.sink(target) })
        assertEquals(updated, fs.read(target) { readUtf8() })
        assertEquals(StagedCopy.DONE, state())
    }

    @Test fun panesShareGenerationEvenWhenCacheDirectoryDoesNotYetExist() {
        val path = (dir / "cache").asAssetFile()
        val pane = ThumbnailCacheFiles(path, 3)
        val other = ThumbnailCacheFiles(path, 3)
        val old = pane.useTheme("light")
        pane.store("note", old) { fs.write(it.assetPath()) { writeUtf8("pixels") } }
        assertEquals("pixels", other.load("note", old) { fs.read(it.assetPath()) { readUtf8() } })
        val current = other.useTheme("dark")
        pane.store("note", old) { error("stale render") }
        assertNull(pane.load("note", current) { it })
        assertFalse(pane.isCurrent(old))
    }
}
