package com.xnotes.mindmap

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapSourceAccessTest {
    @Test fun fileSourcesReadStableIdentityWithoutModifyingOriginalAndRejectDuplicateCopies() {
        val context=RuntimeEnvironment.getApplication()
        val doc=MindMapDocument()
        val file=File.createTempFile("source-",".xmap",context.cacheDir)
        val bytes=MapCodec.encode(doc);file.writeBytes(bytes)
        val source=MapSourceAccess.inspect(context,Uri.fromFile(file))
        assertEquals(doc.id,source.documentId)
        assertEquals(SourceStatus.UNVERIFIED,source.status)
        assertArrayEquals(bytes,file.readBytes())
        val duplicate=File.createTempFile("duplicate-",".xmap",context.cacheDir).apply {writeBytes(bytes)}
        assertThrows(IllegalArgumentException::class.java) { MapSourceAccess.inspect(context,Uri.fromFile(duplicate)) }
        assertArrayEquals(bytes,duplicate.readBytes());assertArrayEquals(bytes,file.readBytes())
    }
}
