package com.xnotes.ui

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SplitArrangementStoreTest {
    private fun scenario(block:(File,SplitArrangementStore)->Unit) {
        val directory=Files.createTempDirectory("mixed-split-store-").toFile()
        try {block(directory,SplitArrangementStore(directory))} finally {directory.deleteRecursively()}
    }
    private fun managed(root:File,name:String="arrangement.xsplit")=File(File(root,"splits").apply {mkdirs()},name)
    private fun ready(store:SplitArrangementStore,file:File)=(store.readManaged(file.toURI().toString()) as SplitArrangementRead.Ready).request

    @Test fun missingAndCorruptHaveDifferentOutcomesWithoutChangingExistingBytes()=scenario {root,store->
        val missing=managed(root);assertEquals(SplitArrangementRead.Absent,store.readManaged(missing.toURI().toString()))
        assertEquals(SplitArrangementRead.Absent,store.readRecent())
        val bytes="future or damaged data".toByteArray();missing.writeBytes(bytes)
        assertEquals(SplitArrangementRead.Invalid,store.readManaged(missing.toURI().toString()));assertArrayEquals(bytes,missing.readBytes())
        val recent=File(root,"saved-split.json");recent.writeBytes(bytes)
        assertEquals(SplitArrangementRead.Invalid,store.readRecent());assertArrayEquals(bytes,recent.readBytes())
    }
    @Test fun explicitManagedReadsRejectExternalNestedUrisAndEscapingSymlinks()=scenario {root,store->
        val external=File(root,"external.xsplit").apply {writeBytes(mixedSplitJson().toString().toByteArray())}
        val nested=File(File(root,"splits/nested").apply {mkdirs()},"inside.xsplit").apply {writeBytes(external.readBytes())}
        for(uri in listOf(external.toURI().toString(),nested.toURI().toString(),"content://files/splits/a.xsplit",
            "file://remote${managed(root).absolutePath}",managed(root).toURI().toString()+"?query=1",
            managed(root).toURI().toString()+"#fragment",File(root,"splits").toURI().toString()))
            assertEquals(uri,SplitArrangementRead.Invalid,store.readManaged(uri))
        val link=managed(root,"escape.xsplit");Files.createSymbolicLink(link.toPath(),external.toPath())
        assertEquals(SplitArrangementRead.Invalid,store.readManaged(link.toURI().toString()))
        assertEquals(SplitOpenRequest.LegacyMapSplit,SplitOpenCodec.parse(external.readBytes()))
    }
    @Test fun onlyFixedRecentEntryAllowsLegacyFormatAndTypedRecentIsReadOnly()=scenario {root,store->
        val legacy=JSONObject().put("version",1).put("primaryPath","a").put("secondPath","b").toString().toByteArray()
        val explicit=managed(root).apply {writeBytes(legacy)};val recent=File(root,"saved-split.json").apply {writeBytes(legacy)}
        assertEquals(SplitArrangementRead.Invalid,store.readManaged(explicit.toURI().toString()))
        assertTrue((store.readRecent() as SplitArrangementRead.Ready).request is SplitOpenRequest.Native)
        val typed=mixedSplitJson().toString().toByteArray();recent.writeBytes(typed)
        assertTrue((store.readRecent() as SplitArrangementRead.Ready).request ===SplitOpenRequest.LegacyMapSplit)
        assertArrayEquals(typed,recent.readBytes())
    }
    @Test fun symlinkedStoreDirectoryOrRecentCannotEscapeThePrivateRoot()=scenario {root,store->
        val outside=Files.createTempDirectory("mixed-split-outside-").toFile()
        try {
            val existing=File(outside,"existing.xsplit").apply {writeBytes(mixedSplitJson().toString().toByteArray())}
            Files.createSymbolicLink(File(root,"splits").toPath(),outside.toPath())
            assertEquals(SplitArrangementRead.Invalid,store.readManaged(File(root,"splits/existing.xsplit").toURI().toString()))
            assertEquals(listOf(existing),outside.listFiles()!!.toList())
            Files.createSymbolicLink(File(root,"saved-split.json").toPath(),existing.toPath())
            assertEquals(SplitArrangementRead.Invalid,store.readRecent())
            assertTrue(File(root,"splits").delete());assertTrue(File(root,"saved-split.json").delete())
            assertTrue(existing.isFile)
        } finally {outside.deleteRecursively()}
    }
}
