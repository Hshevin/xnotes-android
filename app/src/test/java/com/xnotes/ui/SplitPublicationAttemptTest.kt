package com.xnotes.ui

import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real private-file ownership at the IO-to-caller cancellation boundary used by Main. */
class SplitPublicationAttemptTest {
    private class GateDispatcher:CoroutineDispatcher() {
        private val tasks=LinkedBlockingQueue<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable) {tasks.add(block)}
        fun next():Runnable=requireNotNull(tasks.poll(5,TimeUnit.SECONDS)) {"Caller continuation did not arrive"}
    }
    private fun scenario(test:(File,SplitArrangementStore)->Unit) {
        val root=Files.createTempDirectory("split-attempt-").toFile()
        try {test(root,SplitArrangementStore(root))} finally {root.deleteRecursively()}
    }

    @Test fun cancellationAfterStageIoBeforeCallerResumeCleansOnlyTheActualOwnedStage()=scenario {root,store->
        val folder=File(root,"splits").apply {mkdirs()}
        val foreign=File(folder,"foreign.pending").apply {writeText("not this attempt")}
        val dispatcher=GateDispatcher();val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val attempt=SplitPublicationAttempt(store)
        var callerResumed=false
        var heldReturn:Runnable?=null
        val task=scope.async {
            try {
                assertTrue(attempt.prepare(mixedSplitSample(),"cancelled"))
                callerResumed=true
                attempt.commit()
            } finally {attempt.close()}
        }
        try {
            dispatcher.next().run() // Begin the real attempt; preparation switches to IO.
            val ioReturn=dispatcher.next().also {heldReturn=it} // IO is done; caller has not resumed.
            assertFalse(task.isCompleted);assertFalse(callerResumed)
            val staged=folder.listFiles()!!.single {it!=foreign}
            assertTrue(staged.name.endsWith(".pending"))
            assertEquals(setOf(foreign,staged),folder.listFiles()!!.toSet())
            assertTrue(folder.listFiles()!!.none {it.extension=="xsplit"})
            task.cancel();ioReturn.run();heldReturn=null
            while(!task.isCompleted) dispatcher.next().run()
            assertTrue(task.isCancelled);assertFalse(callerResumed)
            assertNull(attempt.commit());assertNull(attempt.publishedFile)
            assertEquals(listOf(foreign),folder.listFiles()!!.toList())
            assertEquals("not this attempt",foreign.readText())
        } finally {
            task.cancel()
            heldReturn?.run()
            while(!task.isCompleted) dispatcher.next().run()
            scope.cancel()
            runBlocking {attempt.close()}
        }
    }

    @Test fun oneAttemptNeverOverwritesItsStageAndKeepsPublishedFactsAfterClose()=scenario {root,store->runBlocking {
        val attempt=SplitPublicationAttempt(store);val first=mixedSplitSample()
        try {
            assertTrue(attempt.prepare(first,"first"))
            assertFalse(attempt.prepare(mixedSplitSample(),"must not replace the first stage"))
            assertEquals(1,File(root,"splits").listFiles()!!.size)
            val file=requireNotNull(attempt.commit())
            assertNull(attempt.commit())
            attempt.close()
            assertNull(attempt.commit());assertFalse(attempt.prepare(mixedSplitSample(),"closed"))
            assertEquals(file,attempt.publishedFile)
            assertEquals(SplitArrangementRead.Ready(SplitOpenRequest.Mixed(first)),store.readManaged(file.toURI().toString()))
            assertEquals(listOf(file.canonicalFile),File(root,"splits").listFiles()!!.map {it.canonicalFile})
        } finally {attempt.close()}
    }}
}
