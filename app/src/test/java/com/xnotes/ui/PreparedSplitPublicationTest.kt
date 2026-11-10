package com.xnotes.ui

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

class PreparedSplitPublicationTest {
    private fun scenario(block:(File,SplitArrangementStore)->Unit) {
        val root=Files.createTempDirectory("prepared-split-").toFile()
        try {block(root,SplitArrangementStore(root))} finally {root.deleteRecursively()}
    }
    private fun read(store:SplitArrangementStore,file:File)=store.readManaged(file.toURI().toString())

    @Test fun stagedBytesStayPrivateUntilOneCommitAndCloseCannotRevokePublication()=scenario {root,store->
        val request=mixedSplitSample()
        val prepared=requireNotNull(store.prepareNamed(request,"prepared"))
        val folder=File(root,"splits")
        try {
            assertTrue(folder.listFiles()!!.none {it.extension=="xsplit"})
            assertEquals(1,folder.listFiles()!!.size)
            val file=requireNotNull(prepared.commit())
            assertEquals(SplitArrangementRead.Ready(SplitOpenRequest.Mixed(request)),read(store,file))
            assertNull(prepared.commit())
            prepared.close();prepared.close()
            assertTrue(file.isFile)
            assertEquals(listOf(file.canonicalFile),folder.listFiles()!!.map {it.canonicalFile})
        } finally {prepared.close()}
    }

    @Test fun abandoningPreparationRemovesOnlyItsOwnStageAndNeverPublishes()=scenario {root,store->
        val folder=File(root,"splits").apply {mkdirs()}
        val foreign=File(folder,"foreign.pending").apply {writeText("keep foreign")}
        val prepared=requireNotNull(store.prepareNamed(mixedSplitSample(),"abandoned"))
        assertEquals(2,folder.listFiles()!!.size)
        prepared.close();prepared.close()
        assertNull(prepared.commit());assertEquals(listOf(foreign),folder.listFiles()!!.toList())
        assertEquals("keep foreign",foreign.readText())
    }

    @Test fun cleanupFailureCannotTurnACommittedNamedFileIntoFailure()=scenario {root,_->
        val ops=object:SplitArrangementStore.FileOps {
            override fun createLink(link:Path,existing:Path) {Files.createLink(link,existing)}
            override fun deleteIfExists(path:Path) {throw IOException("controlled stage cleanup failure")}
        }
        val store=SplitArrangementStore(root,ops);val request=mixedSplitSample()
        val prepared=requireNotNull(store.prepareNamed(request,"complete"))
        val file=requireNotNull(prepared.commit());prepared.close()
        assertEquals(SplitArrangementRead.Ready(SplitOpenRequest.Mixed(request)),read(SplitArrangementStore(root),file))
        val stage=requireNotNull(file.parentFile).listFiles()!!.single {it!=file}
        assertArrayEquals(file.readBytes(),stage.readBytes())
    }
}
