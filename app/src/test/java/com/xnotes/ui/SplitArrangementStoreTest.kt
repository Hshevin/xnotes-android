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
    @Test fun savesNewFilesWithoutTouchingSourcesRecentOrSessionDirectories()=scenario {root,store->
        val recent=File(root,"saved-split.json").apply {writeText("keep recent")}
        val session=File(root,"session").apply {mkdirs()};File(session,"body").writeText("cached body")
        val request=mixedSplitSample();val file=requireNotNull(store.saveNamed(request,"导图 + 笔记"))
        assertEquals(File(root,"splits").canonicalFile,file.canonicalFile.parentFile);assertEquals("xsplit",file.extension)
        assertEquals(SplitOpenRequest.Mixed(request),ready(store,file))
        assertEquals("keep recent",recent.readText());assertEquals("cached body",File(session,"body").readText())
        assertEquals(listOf(file),requireNotNull(file.parentFile).listFiles()!!.toList())
    }
    @Test fun sameSourcesAndTitleSaveIndependentGeometryInsteadOfPathOnlyDeduplication()=scenario {_,store->
        val first=mixedSplitSample();val second=first.copy(layout=first.layout.copy(ratio=.72f),nativePosition=first.nativePosition.copy(y=900.0))
        val a=requireNotNull(store.saveNamed(first,"same"));val b=requireNotNull(store.saveNamed(second,"same"))
        assertNotEquals(a,b);assertEquals(SplitOpenRequest.Mixed(first),ready(store,a));assertEquals(SplitOpenRequest.Mixed(second),ready(store,b))
    }
    @Test fun traversalTitlesCannotChooseTargetsAndExistingFilesAreNeverReplaced()=scenario {root,store->
        val existing=managed(root,"keep.xsplit").apply {writeText("unknown future version")}
        val outside=File(root,"outside.xsplit").apply {writeText("outside")}
        val file=requireNotNull(store.saveNamed(mixedSplitSample(),"../../outside.xsplit\u0000\n:/\\"))
        assertEquals(requireNotNull(existing.parentFile).canonicalFile,file.canonicalFile.parentFile)
        assertEquals("unknown future version",existing.readText());assertEquals("outside",outside.readText())
        assertFalse(file.name.any {it=='/' || it=='\\' || it.code<32})
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
        assertEquals("content://notes/a",((SplitOpenCodec.parse(external.readBytes()) as SplitOpenRequest.Mixed).request.map.uri))
    }
    @Test fun onlyFixedRecentEntryAllowsLegacyFormatAndTypedRecentIsReadOnly()=scenario {root,store->
        val legacy=JSONObject().put("version",1).put("primaryPath","a").put("secondPath","b").toString().toByteArray()
        val explicit=managed(root).apply {writeBytes(legacy)};val recent=File(root,"saved-split.json").apply {writeBytes(legacy)}
        assertEquals(SplitArrangementRead.Invalid,store.readManaged(explicit.toURI().toString()))
        assertTrue((store.readRecent() as SplitArrangementRead.Ready).request is SplitOpenRequest.Native)
        val typed=mixedSplitJson().toString().toByteArray();recent.writeBytes(typed)
        assertTrue((store.readRecent() as SplitArrangementRead.Ready).request is SplitOpenRequest.Mixed)
        assertArrayEquals(typed,recent.readBytes())
    }
    @Test fun symlinkedStoreDirectoryOrRecentCannotEscapeThePrivateRoot()=scenario {root,store->
        val outside=Files.createTempDirectory("mixed-split-outside-").toFile()
        try {
            val existing=File(outside,"existing.xsplit").apply {writeBytes(mixedSplitJson().toString().toByteArray())}
            Files.createSymbolicLink(File(root,"splits").toPath(),outside.toPath())
            assertEquals(SplitArrangementRead.Invalid,store.readManaged(File(root,"splits/existing.xsplit").toURI().toString()))
            assertNull(store.saveNamed(mixedSplitSample(),"do not escape"))
            assertEquals(listOf(existing),outside.listFiles()!!.toList())
            Files.createSymbolicLink(File(root,"saved-split.json").toPath(),existing.toPath())
            assertEquals(SplitArrangementRead.Invalid,store.readRecent())
            assertTrue(File(root,"splits").delete());assertTrue(File(root,"saved-split.json").delete())
            assertTrue(existing.isFile)
        } finally {outside.deleteRecursively()}
    }
    @Test fun failedPublicationDoesNotCleanUnknownFilesOrRewriteRecent()=scenario {root,_->
        File(root,"splits").writeText("directory blocked by existing file")
        val recent=File(root,"saved-split.json").apply {writeText("keep")}
        assertNull(SplitArrangementStore(root).saveNamed(mixedSplitSample(),"name"))
        assertEquals("directory blocked by existing file",File(root,"splits").readText());assertEquals("keep",recent.readText())
        assertEquals(setOf("splits","saved-split.json"),root.listFiles()!!.map {it.name}.toSet())
    }
    @Test fun invalidRequestsAndOversizedFilesLeaveNoPublishedArtifacts()=scenario {root,store->
        val request=mixedSplitSample();assertNull(store.saveNamed(request.copy(layout=request.layout.copy(nativeHome=true)),"bad"))
        val oversized=managed(root).apply {writeBytes(ByteArray(SplitOpenCodec.MAX_BYTES+1))}
        assertEquals(SplitArrangementRead.Invalid,store.readManaged(oversized.toURI().toString()))
        assertEquals(listOf(oversized),requireNotNull(oversized.parentFile).listFiles()!!.toList())
    }
    @Test fun independentStoreInstancesPublishConcurrentSameNameWithoutLostOrPartialFiles()=scenario {root,_->
        val value=mixedSplitSample();val executor=Executors.newFixedThreadPool(4)
        try {
            val results=(0 until 16).map {index->executor.submit(Callable {
                val request=value.copy(nativePosition=value.nativePosition.copy(y=index.toDouble()))
                request to requireNotNull(SplitArrangementStore(root).saveNamed(request,"same"))
            })}.map {it.get(20,TimeUnit.SECONDS)}
            assertEquals(16,results.map {it.second}.toSet().size)
            val reader=SplitArrangementStore(root)
            for((request,file) in results) assertEquals(SplitOpenRequest.Mixed(request),ready(reader,file))
            assertEquals(16,File(root,"splits").listFiles()!!.size)
        } finally {executor.shutdownNow()}
    }
    @Test fun collisionAtTheActualPublishStepPreservesTheUnknownTarget()=scenario {root,_->
        var collision:File?=null
        val ops=object:SplitArrangementStore.FileOps {
            override fun createLink(link:java.nio.file.Path,existing:java.nio.file.Path) {
                if(collision==null) {
                    collision=link.toFile().apply {writeText("arrived before publication")}
                    throw java.nio.file.FileAlreadyExistsException(link.toString())
                }
                Files.createLink(link,existing)
            }
            override fun deleteIfExists(path:java.nio.file.Path) {Files.deleteIfExists(path)}
        }
        val file=SplitArrangementStore(root,ops).saveNamed(mixedSplitSample(),"collision")
        assertEquals("arrived before publication",requireNotNull(collision).readText())
        if(file!=null) assertNotEquals(collision,file)
        assertTrue(File(root,"splits").listFiles()!!.none {it.name.endsWith(".pending")})
    }
    @Test fun failureBeforeTheCreateNewCommitRemovesOnlyItsOwnStage()=scenario {root,_->
        val directory=File(root,"splits").apply {mkdirs()}
        val foreign=File(directory,"foreign.pending").apply {writeText("not ours")}
        val ops=object:SplitArrangementStore.FileOps {
            override fun createLink(link:java.nio.file.Path,existing:java.nio.file.Path) {throw IOException("controlled link failure")}
            override fun deleteIfExists(path:java.nio.file.Path) {Files.deleteIfExists(path)}
        }
        assertNull(SplitArrangementStore(root,ops).saveNamed(mixedSplitSample(),"fail"))
        assertEquals(listOf(foreign),directory.listFiles()!!.toList());assertEquals("not ours",foreign.readText())
    }
    @Test fun cleanupFailureAfterTheCreateNewCommitStillReturnsTheCompletePublishedFile()=scenario {root,_->
        val ops=object:SplitArrangementStore.FileOps {
            override fun createLink(link:java.nio.file.Path,existing:java.nio.file.Path) {Files.createLink(link,existing)}
            override fun deleteIfExists(path:java.nio.file.Path) {throw IOException("controlled stage cleanup failure")}
        }
        val request=mixedSplitSample();val file=requireNotNull(SplitArrangementStore(root,ops).saveNamed(request,"saved"))
        assertEquals(SplitOpenRequest.Mixed(request),ready(SplitArrangementStore(root),file))
        val stage=requireNotNull(file.parentFile).listFiles()!!.single {it!=file}
        assertArrayEquals(file.readBytes(),stage.readBytes());assertTrue(file.isFile)
    }
}
