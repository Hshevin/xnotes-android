package com.xnotes.ui

import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Host filesystem coverage for platforms that deny hard links; Android coverage remains separate. */
class SplitPublicationFallbackTest {
    private fun ops(unsupported:Boolean=false)=object:SplitArrangementStore.FileOps {
        override fun createLink(link:Path,existing:Path) {
            if(unsupported) throw UnsupportedOperationException("hard links unavailable")
            throw AccessDeniedException(link.toString())
        }
        override fun deleteIfExists(path:Path) {Files.deleteIfExists(path)}
    }
    private fun scenario(block:(File)->Unit) {
        val root=Files.createTempDirectory("split-link-refused-").toFile()
        try {block(root)} finally {root.deleteRecursively()}
    }
    @Test fun refusedLinksPublishBothDirectAndPreparedWithoutReplacingExistingBytes()=scenario {root->
        val folder=File(root,"splits").apply {mkdirs()}
        val existing=File(folder,"same.xsplit").apply {writeText("unknown future version")}
        val store=SplitArrangementStore(root,ops());val request=mixedSplitSample()
        val direct=requireNotNull(store.saveNamed(request,"same"))
        val prepared=requireNotNull(store.prepareNamed(request,"same"))
        val committed=try {requireNotNull(prepared.commit())} finally {prepared.close()}
        assertEquals("same_1.xsplit",direct.name);assertEquals("same_2.xsplit",committed.name)
        for(file in listOf(direct,committed))
            assertEquals(SplitArrangementRead.Ready(SplitOpenRequest.Mixed(request)),store.readManaged(file.toURI().toString()))
        assertEquals("unknown future version",existing.readText())
        assertEquals(setOf(existing,direct,committed).map {it.canonicalFile}.toSet(),folder.listFiles()!!.map {it.canonicalFile}.toSet())
    }
    @Test fun unsupportedHardLinksAlsoPublishCompleteBytes()=scenario {root->
        val store=SplitArrangementStore(root,ops(unsupported=true));val request=mixedSplitSample()
        val file=requireNotNull(store.saveNamed(request,"unsupported"))
        assertEquals(SplitArrangementRead.Ready(SplitOpenRequest.Mixed(request)),store.readManaged(file.toURI().toString()))
        assertEquals(listOf(file),file.parentFile!!.listFiles()!!.toList())
    }
    @Test fun failedRenameRemovesOnlyItsReservationAndPreservesForeignFiles()=scenario {root->
        val foreign=File(root,"foreign.pending").apply {writeText("keep")}
        val target=File(root,"failed.xsplit")
        try {ops().publishNew(target.toPath(),File(root,"missing-stage").toPath());fail("missing stage must fail")}
        catch(_:java.nio.file.NoSuchFileException) {}
        assertFalse(target.exists());assertEquals(listOf(foreign),root.listFiles()!!.toList())
        assertEquals("keep",foreign.readText())
    }
    @Test fun concurrentRefusedLinksReserveDifferentNamesAndKeepEveryRequest()=scenario {root->
        val executor=Executors.newFixedThreadPool(4)
        try {
            val base=mixedSplitSample()
            val results=(0 until 16).map {index->executor.submit(Callable {
                val request=base.copy(nativePosition=base.nativePosition.copy(y=index.toDouble()))
                request to requireNotNull(SplitArrangementStore(root,ops()).saveNamed(request,"same"))
            })}.map {it.get(20,TimeUnit.SECONDS)}
            assertEquals(16,results.map {it.second}.toSet().size)
            for((request,file) in results)
                assertEquals(SplitArrangementRead.Ready(SplitOpenRequest.Mixed(request)),SplitArrangementStore(root).readManaged(file.toURI().toString()))
            assertEquals(16,File(root,"splits").listFiles()!!.size)
        } finally {executor.shutdownNow()}
    }
}
