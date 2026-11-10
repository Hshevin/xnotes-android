package com.xnotes.ui

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SplitRecentWriterTest {
    private class Gate {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fun block() {entered.countDown();check(release.await(10,TimeUnit.SECONDS)) {"Controlled IO was not released"}}
        fun awaitEntry() {assertTrue("Writer did not reach controlled IO",entered.await(2,TimeUnit.SECONDS))}
        fun open() {release.countDown()}
    }
    private open class Ops:SplitRecentWriter.FileOps {
        @Volatile var onRead:((Int,Path)->InputStream?)?=null
        @Volatile var onReplace:((Path,Path)->Unit)?=null
        private val reads=AtomicInteger()
        val committed=CopyOnWriteArrayList<SplitOpenRequest>()
        override fun openRead(path:Path):InputStream = onRead?.invoke(reads.incrementAndGet(),path)
            ?: Files.newInputStream(path,READ,NOFOLLOW_LINKS)
        override fun replace(stage:Path,target:Path) {
            onReplace?.invoke(stage,target)
            val request=requireNotNull(SplitOpenCodec.parse(Files.readAllBytes(stage)))
            Files.move(stage,target,ATOMIC_MOVE,REPLACE_EXISTING);committed+=request
        }
    }
    private fun scenario(block:suspend (File)->Unit)=runBlocking {
        val root=Files.createTempDirectory("split-recent-").toFile()
        try {withTimeout(15000) {block(root)}} finally {root.deleteRecursively()}
    }
    private fun request(y:Double)=SplitOpenRequest.Mixed(mixedSplitSample().let {it.copy(nativePosition=it.nativePosition.copy(y=y))})
    private fun seed(root:File,request:SplitOpenRequest.Mixed)=File(root,"saved-split.json").apply {
        writeBytes(SplitOpenCodec.encodeMixed(request.request))
    }
    private suspend fun loaded(writer:SplitRecentWriter)=withTimeout(2500) {writer.state.first {it.loaded}}
    private suspend fun write(writer:SplitRecentWriter,ticket:SplitRecentWriter.Ticket,request:SplitOpenRequest)=
        withTimeout(2500) {writer.submit(ticket,request).await()}
    private fun read(root:File)=SplitArrangementStore(root).readRecent()

    @Test fun canonicalAliasesAndIndependentEditorsShareReservationOrder()=scenario {root->
        val first=SplitRecentWriter.forFilesDir(root)
        val second=SplitRecentWriter.forFilesDir(File(root,"."))
        val old=first.reserve();val newest=second.reserve()
        val value=request(2.0)
        assertEquals(SplitRecentWrite.Written,write(second,newest,value))
        assertEquals(SplitRecentWrite.Superseded,write(first,old,request(1.0)))
        assertEquals(SplitArrangementRead.Ready(value),read(root))
    }

    @Test fun aNewReservationDiscardsAnOldStageBeforeItsCommitClaim()=scenario {root->
        seed(root,request(0.0))
        val ops=Ops();val writer=SplitRecentWriter.forFilesDir(root,ops);loaded(writer)
        val gate=Gate();ops.onRead={_,_->gate.block();null}
        val old=writer.submit(writer.reserve(),request(1.0))
        try {
            gate.awaitEntry()
            val newest=writer.reserve()
            gate.open();ops.onRead=null
            assertEquals(SplitRecentWrite.Superseded,withTimeout(2500) {old.await()})
            val value=request(2.0)
            assertEquals(SplitRecentWrite.Written,write(writer,newest,value))
            assertEquals(listOf(value),ops.committed.toList())
            assertEquals(SplitArrangementRead.Ready(value),read(root))
        } finally {gate.open()}
    }

    @Test fun aClaimedPrefixFinishesBeforeTheNewerRequestAndCannotOverwriteItLater()=scenario {root->
        val ops=Ops();val gate=Gate();val writer=SplitRecentWriter.forFilesDir(root,ops)
        val firstValue=request(1.0);val secondValue=request(2.0)
        ops.onReplace={_,_->gate.block()}
        val first=writer.submit(writer.reserve(),firstValue)
        try {
            gate.awaitEntry()
            first.cancel() // The accepted IO belongs to the writer, not to its awaiter.
            val second=writer.submit(writer.reserve(),secondValue)
            assertFalse(second.isCompleted)
            ops.onReplace=null;gate.open()
            assertEquals(SplitRecentWrite.Written,withTimeout(2500) {second.await()})
            assertTrue(first.isCancelled)
            assertEquals(listOf(firstValue,secondValue),ops.committed.toList())
            assertEquals(SplitArrangementRead.Ready(secondValue),read(root))
        } finally {gate.open()}
    }

    @Test fun lateInitializationCannotReplaceTheStateOfAnAlreadyPublishedRequest()=scenario {root->
        val previous=request(0.0);seed(root,previous)
        val gate=Gate();val ops=Ops()
        ops.onRead={index,path->if(index==1) {
            val bytes=Files.readAllBytes(path);gate.block();ByteArrayInputStream(bytes)
        } else null}
        val writer=SplitRecentWriter.forFilesDir(root,ops)
        try {
            gate.awaitEntry()
            val value=request(5.0)
            assertEquals(SplitRecentWrite.Written,write(writer,writer.reserve(),value))
            assertEquals(SplitArrangementRead.Ready(value),loaded(writer).read)
            gate.open()
            withTimeout(2500) {writer.initialization.await()}
            assertEquals(SplitArrangementRead.Ready(value),loaded(writer).read)
            assertEquals(SplitArrangementRead.Ready(value),read(root))
        } finally {gate.open()}
    }

    @Test fun automaticUpdatesPreserveBadFutureBytesUntilAnExplicitSaveReplacesThem()=scenario {root->
        val recent=File(root,"saved-split.json").apply {writeText("{\"format\":\"xnotes-split\",\"version\":99}")}
        val bytes=recent.readBytes();val writer=SplitRecentWriter.forFilesDir(root)
        assertEquals(SplitRecentWrite.PreservedInvalid,write(writer,writer.reserve(),request(1.0)))
        assertArrayEquals(bytes,recent.readBytes())
        val value=request(2.0)
        assertEquals(SplitRecentWrite.Written,write(writer,writer.reserve(explicit=true),value))
        assertEquals(SplitArrangementRead.Ready(value),read(root))
    }

    @Test fun invalidRecentIsRecheckedAtTheFinalIoRatherThanTrustedFromInitialState()=scenario {root->
        val recent=seed(root,request(0.0));val ops=Ops();val writer=SplitRecentWriter.forFilesDir(root,ops);loaded(writer)
        ops.onRead={_,_->recent.writeText("future bytes arrived after startup");null}
        assertEquals(SplitRecentWrite.PreservedInvalid,write(writer,writer.reserve(),request(1.0)))
        assertEquals("future bytes arrived after startup",recent.readText());assertTrue(ops.committed.isEmpty())
    }

    @Test fun failedRecentReplaceKeepsTheNamedFileAndOldRecentIntact()=scenario {root->
        val previous=request(0.0);val recent=seed(root,previous);val bytes=recent.readBytes()
        val value=request(1.0)
        val named=requireNotNull(SplitArrangementStore(root).saveNamed(value.request,"saved despite recent failure"))
        val ops=Ops().apply {onReplace={_,_->throw IOException("controlled recent failure")}}
        val writer=SplitRecentWriter.forFilesDir(root,ops)
        assertEquals(SplitRecentWrite.Failed,write(writer,writer.reserve(explicit=true),value))
        assertArrayEquals(bytes,recent.readBytes());assertTrue(named.isFile)
        assertEquals(SplitArrangementRead.Ready(value),SplitArrangementStore(root).readManaged(named.toURI().toString()))
        assertTrue(root.listFiles()!!.none {it.name.endsWith(".pending")})
    }

    @Test fun nativeAndMixedRequestsUseTheSameCheckedRecentTarget()=scenario {root->
        val writer=SplitRecentWriter.forFilesDir(root)
        val first=NativeSplitSource("content://notes/one",expectedId=UUID.randomUUID().toString())
        val second=NativeSplitSource("content://notes/two",expectedId=UUID.randomUUID().toString())
        val native=SplitOpenRequest.Native(NativeSplitOpenRequest(first,second,NativeSplitOpenLayout(ratio=.3f,focused=Pane.SECONDARY)))
        assertEquals(SplitRecentWrite.Written,write(writer,writer.reserve(),native))
        val restored=((read(root) as SplitArrangementRead.Ready).request as SplitOpenRequest.Native).request
        assertEquals(first,restored.first);assertEquals(second,restored.second)
        assertEquals(.3f,restored.layout.ratio,0f);assertEquals(Pane.SECONDARY,restored.layout.focused)
        val mixed=request(4.0)
        assertEquals(SplitRecentWrite.Written,write(writer,writer.reserve(),mixed))
        assertEquals(SplitArrangementRead.Ready(mixed),read(root))
    }
}
