package com.xnotes.storage

import android.net.Uri
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
            val copy = source.copyTo(File(dir, "copy.xcanvas"))
            fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
            val probe = AndroidSameFileProbe(context.contentResolver)
            // App processes may not create hard links on Android 15 (SELinux EACCES), so the
            // second location is the same inode reached through a different URI scheme.
            val alias = Uri.fromFile(source).toString()
            assertNotEquals(uri(source), alias)
            assertEquals(true, probe.sameFile(uri(source), alias))
            assertArrayEquals(source.readBytes(), copy.readBytes())
            assertEquals(false, probe.sameFile(uri(source), uri(copy)))
            assertNull(probe.sameFile(uri(source), uri(File(dir, "missing.xcanvas"))))
        } finally {
            dir.deleteRecursively()
        }
    }
}
