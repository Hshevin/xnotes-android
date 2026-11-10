package com.xnotes.ui

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.FileTime
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LastPresentationStoreTest {
    @Test fun aReadbackFailureAfterASuccessfulMoveCannotRevokeTheWrittenFact()=scenario {root,_->
        seed(root)
        val value=coldMarkerSample()
        val ops=object:Ops() {
            var moved=false
            override fun replace(stage:Path,target:Path) {
                super.replace(stage,target)
                moved=true
            }
            override fun openRead(path:Path):InputStream {
                if(moved && path.toFile().canonicalFile==head(root).canonicalFile)
                    throw IOException("Readback failed after a successful move")
                return super.openRead(path)
            }
        }
        val attempt=prepared(LastPresentationStore(root,ops),value)
        try {
            assertEquals(LastPresentationWrite.Written,attempt.commit {true})
            assertTrue(attempt.written)
        } finally {attempt.close()}
        assertTrue(attempt.written)
        assertEquals(value,LastPresentationCodec.parse(head(root).readBytes()))
    }

    @Test fun aCurrentStateInvalidatedDuringFinalReadCannotBeClaimed()=scenario {root,_->
        val previous=seed(root).readBytes()
        var eligible=true
        val ops=object:Ops() {
            var armed=false
            override fun openRead(path:Path):InputStream {
                if(armed) eligible=false
                return super.openRead(path)
            }
        }
        val attempt=prepared(LastPresentationStore(root,ops),coldMarkerSample())
        ops.armed=true
        try {
            assertEquals(LastPresentationWrite.Changed,attempt.commit {eligible})
            assertFalse(attempt.written)
            assertArrayEquals(previous,head(root).readBytes())
        } finally {attempt.close()}
    }
    private open class Ops:LastPresentationStore.FileOps {
        val reads=CopyOnWriteArrayList<Path>()
        val replacements=CopyOnWriteArrayList<Path>()
        @Volatile var beforeReplace:((Path,Path)->Unit)?=null
        @Volatile var failCleanup=false
        override fun openRead(path:Path):InputStream {reads.add(path);return Files.newInputStream(path,READ,NOFOLLOW_LINKS)}
        override fun replace(stage:Path,target:Path) {
            beforeReplace?.invoke(stage,target)
            Files.move(stage,target,ATOMIC_MOVE,REPLACE_EXISTING);replacements.add(target)
        }
        override fun deleteIfExists(path:Path) {
            if(failCleanup) throw IOException("controlled cleanup failure")
            Files.deleteIfExists(path)
        }
    }
    private class GateDispatcher:CoroutineDispatcher() {
        private val tasks=LinkedBlockingQueue<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable) {tasks.add(block)}
        fun next():Runnable=requireNotNull(tasks.poll(5,TimeUnit.SECONDS)) {"Caller continuation did not arrive"}
    }
    private fun scenario(block:suspend(File,LastPresentationStore)->Unit)=runBlocking {
        val root=Files.createTempDirectory("last-presentation-").toFile()
        try {withTimeout(20000) {block(root,LastPresentationStore(root))}} finally {root.deleteRecursively()}
    }
    private fun head(root:File)=File(root,"last-presentation.json")
    private fun seed(root:File,state:String="home")=head(root).apply {writeText(coldMarkerJson(state).toString())}
    private fun future(root:File,version:Int=99)=head(root).apply {writeText("{\"format\":\"xnotes-last-presentation\",\"version\":$version,\"state\":\"home\"}")}
    private fun stages(root:File)=root.listFiles()!!.filter {it.name.endsWith(".pending") && it.name!="foreign.pending"}
    private suspend fun prepared(store:LastPresentationStore,value:LastPresentation):LastPresentationAttempt {
        val attempt=store.begin(store.reserve(),value)
        assertEquals(LastPresentationPrepare.Ready,attempt.prepare())
        return attempt
    }
    private fun assertNoNamedFiles(root:File) {assertTrue(root.listFiles()!!.none {it.extension=="xsplit"})}

    @Test fun absentInvalidAndHomeAreDifferentAndObservingNeverRewritesFiles()=scenario {root,store->
        assertEquals(LastPresentationRead.Absent,store.observe().read)
        val invalid=future(root);val bytes=invalid.readBytes()
        assertEquals(LastPresentationRead.Invalid,store.observe().read);assertArrayEquals(bytes,invalid.readBytes())
        seed(root)
        assertEquals(LastPresentationRead.Ready(LastPresentation.Home),store.observe().read)
    }

    @Test fun prepareOwnsOnlyAStageAndHomeCommitWritesATombstoneRatherThanDeleting()=scenario {root,store->
        val attempt=prepared(store,LastPresentation.Home)
        try {
            assertFalse(head(root).exists());assertEquals(1,stages(root).size)
            assertEquals(LastPresentationWrite.Written,attempt.commit {true});assertTrue(attempt.written)
            assertTrue(head(root).isFile);assertEquals(LastPresentationRead.Ready(LastPresentation.Home),store.observe().read)
            assertEquals(LastPresentationWrite.Changed,attempt.commit {true})
        } finally {attempt.close()}
        assertTrue(stages(root).isEmpty());assertTrue(head(root).isFile)
    }

    @Test fun completeMixedCanDescribeAnUnsavedNoteWithoutOpeningAnySourceUri()=scenario {root,_->
        val ops=Ops();val store=LastPresentationStore(root,ops);val value=coldMarkerSample()
        val recent=File(root,"saved-split.json").apply {writeText("keep recent")}
        val source=File(root,"source.xnote").apply {writeText("not a writable source")}
        val actual=value.copy(value=value.value.copy(map=value.value.map.copy(uri=source.toURI().toString())))
        val attempt=prepared(store,actual)
        try {assertEquals(LastPresentationWrite.Written,attempt.commit {true})} finally {attempt.close()}
        assertEquals(LastPresentationRead.Ready(actual),store.observe().read)
        assertEquals("keep recent",recent.readText());assertEquals("not a writable source",source.readText())
        assertTrue(ops.reads.none {it.toFile().canonicalFile==source.canonicalFile || it.toFile().canonicalFile==recent.canonicalFile})
        assertTrue(ops.replacements.all {it.toFile().canonicalFile==head(root).canonicalFile});assertNoNamedFiles(root)
    }

    @Test fun defaultAttemptsPreserveBadFutureDataButBoundExplicitResetCanWriteHome()=scenario {root,store->
        val file=future(root);val bytes=file.readBytes();val observed=store.observe()
        val automatic=store.begin(store.reserve(),LastPresentation.Home)
        try {
            assertEquals(LastPresentationPrepare.PreservedInvalid,automatic.prepare())
            assertNotEquals(LastPresentationWrite.Written,automatic.commit {true})
        } finally {automatic.close()}
        assertArrayEquals(bytes,file.readBytes())
        val reset=store.beginReset(store.reserve(),observed)
        try {
            assertEquals(LastPresentationPrepare.Ready,reset.prepare())
            assertEquals(LastPresentationWrite.Written,reset.commit {true})
        } finally {reset.close()}
        assertEquals(LastPresentationRead.Ready(LastPresentation.Home),store.observe().read)
    }

    @Test fun anOldResetObservationCannotOverwriteEitherGoodBOrDifferentBadC()=scenario {root,store->
        for(good in listOf(true,false)) {
            future(root,99);val observed=store.observe()
            val replacement=if(good) seed(root) else future(root,98)
            val bytes=replacement.readBytes();val reset=store.beginReset(store.reserve(),observed)
            try {
                assertEquals(LastPresentationPrepare.Changed,reset.prepare())
                assertNotEquals(LastPresentationWrite.Written,reset.commit {true})
            } finally {reset.close()}
            assertArrayEquals(bytes,head(root).readBytes())
        }
    }

    @Test fun resetRefusesObservationsFromAnotherRootAndObservationsOfValidHeads()=scenario {root,store->
        val other=Files.createTempDirectory("other-last-root-").toFile()
        try {
            future(other);future(root)
            val wrong=store.beginReset(store.reserve(),LastPresentationStore(other).observe())
            try {assertEquals(LastPresentationPrepare.Changed,wrong.prepare())} finally {wrong.close()}
            seed(root);val valid=store.observe();val invalidReset=store.beginReset(store.reserve(),valid)
            try {assertNotEquals(LastPresentationPrepare.Ready,invalidReset.prepare())} finally {invalidReset.close()}
            assertEquals(LastPresentationRead.Ready(LastPresentation.Home),store.observe().read)
        } finally {other.deleteRecursively()}
    }

    @Test fun anOversizedOrUnfingerprintableHeadIsNeverAnAutomaticOrResetWritePermit()=scenario {root,store->
        val file=head(root).apply {writeBytes(ByteArray(LastPresentationCodec.MAX_BYTES+1) {7})}
        val observed=store.observe();assertEquals(LastPresentationRead.Invalid,observed.read)
        val bytes=file.readBytes()
        for(attempt in listOf(store.begin(store.reserve(),LastPresentation.Home),store.beginReset(store.reserve(),observed))) {
            try {assertNotEquals(LastPresentationPrepare.Ready,attempt.prepare());assertFalse(attempt.written)} finally {attempt.close()}
        }
        assertArrayEquals(bytes,file.readBytes());assertTrue(stages(root).isEmpty())
    }

    @Test fun aSymlinkOrDirectoryCannotReplaceTheFixedPrivateMarkerBoundary()=scenario {root,store->
        val outside=Files.createTempFile("outside-marker-",".json").toFile().apply {writeText("outside")}
        try {
            Files.createSymbolicLink(head(root).toPath(),outside.toPath())
            val observed=store.observe();assertEquals(LastPresentationRead.Invalid,observed.read)
            for(attempt in listOf(store.begin(store.reserve(),LastPresentation.Home),store.beginReset(store.reserve(),observed))) {
                try {assertNotEquals(LastPresentationPrepare.Ready,attempt.prepare())} finally {attempt.close()}
            }
            assertEquals("outside",outside.readText());assertTrue(Files.isSymbolicLink(head(root).toPath()))
            assertTrue(head(root).delete());assertTrue(head(root).mkdir())
            val directory=store.begin(store.reserve(),LastPresentation.Home)
            try {assertNotEquals(LastPresentationPrepare.Ready,directory.prepare())} finally {directory.close()}
            assertTrue(head(root).isDirectory)
        } finally {outside.delete()}
    }

    @Test fun storesWithTheSameCanonicalRootShareEpochAndRejectAnOlderPreparedAttempt()=scenario {root,first->
        val old=prepared(first,LastPresentation.Home)
        val second=LastPresentationStore(File(root,"."));val ticket=second.reserve()
        try {
            assertEquals(LastPresentationWrite.Changed,old.commit {true});assertFalse(head(root).exists())
            val newest=second.begin(ticket,coldMarkerSample())
            try {
                assertEquals(LastPresentationPrepare.Ready,newest.prepare())
                assertEquals(LastPresentationWrite.Written,newest.commit {true})
            } finally {newest.close()}
        } finally {old.close()}
    }

    @Test fun currentCallbackCannotReserveANewTicketAndStillClaimTheOldAttempt()=scenario {root,store->
        val attempt=prepared(store,LastPresentation.Home)
        try {
            assertEquals(LastPresentationWrite.Changed,attempt.commit {store.reserve();true})
            assertFalse(attempt.written);assertFalse(head(root).exists())
        } finally {attempt.close()}
    }

    @Test fun equalSizeAndMtimeDoNotHideAChangedHeadFromTheFinalShaGate()=scenario {root,store->
        val file=seed(root);val stamp=Files.getLastModifiedTime(file.toPath())
        val original=file.readBytes();val attempt=prepared(store,coldMarkerSample())
        try {
            val changed=String(original,Charsets.UTF_8).replace("\"home\"","\"made\"").toByteArray()
            assertEquals(original.size,changed.size);assertFalse(original.contentEquals(changed))
            file.writeBytes(changed);Files.setLastModifiedTime(file.toPath(),FileTime.fromMillis(stamp.toMillis()))
            assertEquals(LastPresentationWrite.Changed,attempt.commit {true})
            assertArrayEquals(changed,file.readBytes());assertFalse(attempt.written)
        } finally {attempt.close()}
    }

    @Test fun changedStageBytesAreRejectedBeforeTheExistingHeadCanBeReplaced()=scenario {root,store->
        val file=seed(root);val bytes=file.readBytes();val attempt=prepared(store,coldMarkerSample())
        try {
            stages(root).single().writeText("not the encoded candidate")
            assertEquals(LastPresentationWrite.Changed,attempt.commit {true})
            assertArrayEquals(bytes,file.readBytes());assertFalse(attempt.written)
        } finally {attempt.close()}
        assertTrue(stages(root).isEmpty())
    }

    @Test fun replacingTheStagePathCannotPublishOrDeleteTheForeignReplacement()=scenario {root,store->
        val file=seed(root);val bytes=file.readBytes();val attempt=prepared(store,coldMarkerSample())
        val stage=stages(root).single()
        assertTrue(stage.delete());stage.writeText("foreign replacement")
        try {
            assertEquals(LastPresentationWrite.Changed,attempt.commit {true});assertFalse(attempt.written)
        } finally {attempt.close()}
        assertArrayEquals(bytes,file.readBytes());assertEquals("foreign replacement",stage.readText())
    }

    @Test fun aStageSymlinkCannotBeFollowedOrCleanedAsIfItWereStillOwned()=scenario {root,store->
        val outside=Files.createTempFile("outside-stage-",".json").toFile().apply {writeText("outside source")}
        val attempt=prepared(store,LastPresentation.Home);val stage=stages(root).single()
        try {
            assertTrue(stage.delete());Files.createSymbolicLink(stage.toPath(),outside.toPath())
            assertEquals(LastPresentationWrite.Changed,attempt.commit {true})
            attempt.close();assertTrue(Files.isSymbolicLink(stage.toPath()))
            assertEquals("outside source",outside.readText());assertFalse(head(root).exists())
        } finally {attempt.close();outside.delete()}
    }

    @Test fun aSameByteStageReplacementIsStillForeignAndMustNotBePublishedOrDeleted()=scenario {root,store->
        val file=seed(root);val oldHead=file.readBytes();val attempt=prepared(store,coldMarkerSample())
        val stage=stages(root).single();val candidate=stage.readBytes()
        try {
            // Keep the unlinked original alive so the filesystem cannot reuse its inode.
            Files.newByteChannel(stage.toPath(),READ).use {
                assertTrue(stage.delete());stage.writeBytes(candidate)
                assertEquals(LastPresentationWrite.Changed,attempt.commit {true});assertFalse(attempt.written)
                attempt.close()
                assertArrayEquals(oldHead,file.readBytes());assertArrayEquals(candidate,stage.readBytes())
            }
        } finally {attempt.close()}
    }

    @Test fun anUnreadableHeadCannotMintOrReuseAnExplicitResetPermit()=scenario {root,_->
        val file=future(root);val oldHead=file.readBytes();val foreign=File(root,"foreign.pending").apply {writeText("foreign")}
        val ops=object:Ops() {
            @Volatile var denyHead=true
            override fun openRead(path:Path):InputStream {
                if(denyHead && path.toFile().canonicalFile==file.canonicalFile) throw IOException("controlled unreadable marker")
                return super.openRead(path)
            }
        }
        val store=LastPresentationStore(root,ops)
        val unreadable=store.observe();assertEquals(LastPresentationRead.Invalid,unreadable.read)
        suspend fun refused(attempt:LastPresentationAttempt) {
            try {
                assertNotEquals(LastPresentationPrepare.Ready,attempt.prepare())
                assertNotEquals(LastPresentationWrite.Written,attempt.commit {true});assertFalse(attempt.written)
            } finally {attempt.close()}
            assertArrayEquals(oldHead,file.readBytes());assertEquals("foreign",foreign.readText());assertTrue(stages(root).isEmpty())
        }
        refused(store.begin(store.reserve(),LastPresentation.Home))
        refused(store.beginReset(store.reserve(),unreadable))
        ops.denyHead=false
        val readableBad=store.observe();assertEquals(LastPresentationRead.Invalid,readableBad.read)
        ops.denyHead=true
        refused(store.beginReset(store.reserve(),readableBad))
        ops.denyHead=false
        refused(store.beginReset(store.reserve(),unreadable))
    }

    @Test fun aFailedReplaceKeepsOldHeadAndForeignStages()=scenario {root,_->
        val file=seed(root);val bytes=file.readBytes();val foreign=File(root,"foreign.pending").apply {writeText("keep")}
        val ops=Ops().apply {beforeReplace={_,_->throw IOException("controlled replace failure")}}
        val store=LastPresentationStore(root,ops);val attempt=prepared(store,coldMarkerSample())
        try {assertEquals(LastPresentationWrite.Failed,attempt.commit {true});assertFalse(attempt.written)} finally {attempt.close()}
        assertArrayEquals(bytes,file.readBytes());assertEquals("keep",foreign.readText());assertTrue(stages(root).isEmpty())
    }

    @Test fun postCommitCleanupFailureCannotRevokeTheWrittenFact()=scenario {root,_->
        val ops=Ops().apply {failCleanup=true};val store=LastPresentationStore(root,ops)
        val attempt=prepared(store,LastPresentation.Home)
        assertEquals(LastPresentationWrite.Written,attempt.commit {true});attempt.close()
        assertTrue(attempt.written);assertEquals(LastPresentationRead.Ready(LastPresentation.Home),store.observe().read)
        assertTrue(head(root).isFile)
    }

    @Test fun aMoveThatSucceededBeforeThrowingIsRecognizedAsTheActualWrittenCandidate()=scenario {root,_->
        seed(root)
        val ops=object:Ops() {
            override fun replace(stage:Path,target:Path) {
                super.replace(stage,target)
                throw IOException("controlled failure after the actual atomic move")
            }
        }
        val store=LastPresentationStore(root,ops);val value=coldMarkerSample()
        val attempt=prepared(store,value)
        try {
            assertEquals(LastPresentationWrite.Written,attempt.commit {true})
            assertTrue(attempt.written);assertEquals(LastPresentationRead.Ready(value),store.observe().read)
        } finally {attempt.close()}
        assertTrue(head(root).isFile);assertTrue(stages(root).isEmpty())
    }

    @Test fun claimedIoDoesNotBlockReservationOrAllowAReentrantLaterCommitToReverseTheOrder()=scenario {root,_->
        val ops=Ops();val store=LastPresentationStore(root,ops);val attempt=prepared(store,LastPresentation.Home)
        val executor=Executors.newFixedThreadPool(2)
        var nested:LastPresentationAttempt?=null
        var nestedWrite:LastPresentationWrite?=null
        try {
            ops.beforeReplace={_,_->
                val ticket=executor.submit(Callable {store.reserve()}).get(2,TimeUnit.SECONDS)
                val next=store.begin(ticket,coldMarkerSample());nested=next
                val readiness=executor.submit(Callable {runBlocking {next.prepare()}}).get(2,TimeUnit.SECONDS)
                assertTrue(readiness==LastPresentationPrepare.Ready || readiness==LastPresentationPrepare.Changed)
                nestedWrite=next.commit {true}
            }
            assertEquals(LastPresentationWrite.Written,attempt.commit {true})
            assertEquals(LastPresentationWrite.Changed,nestedWrite)
            assertEquals(LastPresentationRead.Ready(LastPresentation.Home),store.observe().read)
            ops.beforeReplace=null
            val value=coldMarkerSample();val finalAttempt=prepared(store,value)
            try {assertEquals(LastPresentationWrite.Written,finalAttempt.commit {true})} finally {finalAttempt.close()}
            assertEquals(LastPresentationRead.Ready(value),store.observe().read)
        } finally {
            ops.beforeReplace=null;attempt.close();nested?.close();executor.shutdownNow()
        }
    }

    @Test fun cancellationAtTheCompletedPrepareIoReturnCleansOnlyItsStage()=scenario {root,store->
        val foreign=File(root,"foreign.pending").apply {writeText("keep foreign")}
        val attempt=store.begin(store.reserve(),LastPresentation.Home)
        val dispatcher=GateDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        var held:Runnable?=null;var resumed=false
        val task=scope.async {
            try {assertEquals(LastPresentationPrepare.Ready,attempt.prepare());resumed=true;attempt.commit {true}}
            finally {attempt.close()}
        }
        try {
            dispatcher.next().run();val returned=dispatcher.next().also {held=it}
            assertEquals(1,stages(root).size);assertFalse(head(root).exists());assertFalse(resumed)
            task.cancel();returned.run();held=null
            while(!task.isCompleted) dispatcher.next().run()
            assertTrue(task.isCancelled);assertFalse(resumed);assertFalse(attempt.written)
            assertTrue(stages(root).isEmpty());assertEquals("keep foreign",foreign.readText())
            assertFalse(head(root).exists())
        } finally {
            task.cancel();held?.run()
            while(!task.isCompleted) dispatcher.next().run()
            scope.cancel();attempt.close()
        }
    }

    @Test fun cancellationAfterCommitAndRepeatedCloseNeverRollBackTheTombstone()=scenario {root,store->
        val attempt=prepared(store,LastPresentation.Home)
        assertEquals(LastPresentationWrite.Written,attempt.commit {true})
        val dispatcher=GateDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val task=scope.async {try {kotlinx.coroutines.awaitCancellation()} finally {attempt.close()}}
        try {
            dispatcher.next().run();task.cancel()
            while(!task.isCompleted) dispatcher.next().run()
            attempt.close();assertTrue(attempt.written)
            assertEquals(LastPresentationRead.Ready(LastPresentation.Home),store.observe().read)
            assertEquals(LastPresentationWrite.Changed,attempt.commit {true})
            assertTrue(stages(root).isEmpty())
        } finally {scope.cancel();attempt.close()}
    }
}
