package com.xnotes.storage

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.DocumentCodec
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ProtectedNoteInkPreservationTest {
    @Test fun completeRecoveryKeepsDenseOldInkWhileOrdinaryReaderStillCompactsIt() {
        val root=Files.createTempDirectory("protected-ink-").toFile()
        try {
            val codec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
            val samples=(0 until 100).joinToString(",") {"[${it/5.0},5,1,${it*4}]"}
            val raw=ByteArrayOutputStream().also {out->ZipOutputStream(out).use {zip->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(("""{"format":"xnote","version":1,"pages":[{"width":100,"height":100,"items":[{"kind":"stroke","tool":"pen","config":{"speed_strength":1},"samples":[$samples]}]}]}""").toByteArray())
                zip.closeEntry()
            }}.toByteArray()
            val restored=codec.readComplete(raw.inputStream(),root,root,preserveInkSamples=true).document
            assertFalse(restored.compactedOnLoad)
            val ink=restored.pages.single().items.single() as Stroke
            assertEquals(100,ink.samples.size)
            val before=ink.samples.toList()
            val bytes=ByteArrayOutputStream().also {codec.write(restored.fixedWriteSnapshot(),it)}.toByteArray()
            val written=codec.readComplete(bytes.inputStream(),root,root,preserveInkSamples=true).document
            val saved=(written.pages.single().items.single() as Stroke).samples
            assertEquals(before.size,saved.size)
            for(index in before.indices) {
                assertEquals(before[index].x,saved[index].x,.00001)
                assertEquals(before[index].y,saved[index].y,.00001)
                assertEquals(before[index].pressure,saved[index].pressure,.00001)
                assertEquals(before[index].t,saved[index].t,.00001)
            }
            val ordinary=codec.read(raw.inputStream())
            assertTrue(ordinary.compactedOnLoad)
            assertTrue((ordinary.pages.single().items.single() as Stroke).samples.size<100)
        } finally {root.deleteRecursively()}
    }
}
