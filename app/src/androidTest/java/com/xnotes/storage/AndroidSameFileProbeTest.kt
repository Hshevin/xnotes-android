package com.xnotes.storage

import android.system.Os
import androidx.core.content.FileProvider
import com.xnotes.pen.PenTestRunner
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AndroidSameFileProbeTest {
    @Test fun differentContentUrisForOneInodeMatchButAByteIdenticalCopyDoesNot() {
        val context = PenTestRunner.instance.targetContext
        val dir = File(context.cacheDir, "share/same-file-${UUID.randomUUID()}")
        check(dir.mkdirs())
        try {
            val source = File(dir, "original.xcanvas").apply { writeText("same bytes") }
            val alias = File(dir, "alias.xcanvas")
            Os.link(source.path, alias.path)
            val copy = source.copyTo(File(dir, "copy.xcanvas"))
            fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
            val probe = AndroidSameFileProbe(context.contentResolver)
            assertNotEquals(uri(source), uri(alias))
            assertEquals(true, probe.sameFile(uri(source), uri(alias)))
            assertArrayEquals(source.readBytes(), copy.readBytes())
            assertEquals(false, probe.sameFile(uri(source), uri(copy)))
            assertNull(probe.sameFile(uri(source), uri(File(dir, "missing.xcanvas"))))
        } finally {
            dir.deleteRecursively()
        }
    }
}
