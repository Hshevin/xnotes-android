package com.xnotes.mindmap

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit
import com.xnotes.core.geometry.Pt
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.platform.AndroidRasterSurface
import com.xnotes.storage.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real catalog/lease IO behind the retained management state; pixels are a controlled engine. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class OriginalPdfLibraryStateTest {
    private class Engine:OriginalPdfEngine {
        override val pageCount=2
        override fun pageInfo(pdfKey:Int)=OriginalPdfPageInfo(Pt(600.0,800.0),PdfPageGeometry.flipped(800.0))
        override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):AndroidRasterSurface?=null
        override fun closeAndAwait() {}
    }
    private class Fixture {
        val root=Files.createTempDirectory("original-library-").toFile()
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        val storage=NotebookStorage(context)
        val access=OriginalPdfAccess(context,engineFactory={Engine()},storageOverride=storage)
        val catalog=OriginalPdfCatalog(storage.registry)
        val state=OriginalPdfLibraryState(context,scope,access,catalog)
        val source=File(root,"original.pdf").apply {writeText("controlled original bytes")}
        fun retained():OriginalPdfCatalog.Observation {
            val candidate=access.candidate(source,"original.pdf")
            return try {(catalog.retain(candidate) as OriginalPdfCatalog.Retention.Retained).observation}
            finally {candidate.closeAndAwait()}
        }
        suspend fun awaitIdle()=withTimeout(10000) {
            while(withContext(Dispatchers.Main) {state.busy}) delay(1)
        }
        suspend fun finish() {
            withContext(Dispatchers.Main) {state.dispose()}
            scope.coroutineContext[Job]?.cancelAndJoin()
            storage.registry.close();root.deleteRecursively()
        }
    }
    private fun scenario(block:suspend (Fixture)->Unit) {
        val f=Fixture();var failure:Throwable?=null
        val runner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val job=runner.launch {
            try {withTimeout(20000) {block(f)}} catch(error:Throwable) {failure=error}
            finally {withContext(NonCancellable) {f.finish()}}
        }
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!job.isCompleted && System.nanoTime()<deadline) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idle();Thread.yield()
            }
            assertTrue("Original library state did not settle",job.isCompleted)
            failure?.let {throw it}
        } finally {runner.cancel()}
    }
    @Test fun deletionIsExplicitAndReopeningListsTheSameTombstoneAndPermanentPages()=scenario {f->
        val retained=withContext(Dispatchers.IO) {f.retained()}
        withContext(Dispatchers.Main) {assertTrue(f.state.open())}
        f.awaitIdle()
        withContext(Dispatchers.Main) {
            assertEquals(retained.record.id,f.state.records.single().record.id)
            assertFalse(f.state.records.single().record.deleted)
            assertTrue(f.state.delete(f.state.records.single()))
        }
        f.awaitIdle()
        withContext(Dispatchers.Main) {
            assertTrue(f.state.records.single().record.deleted)
            f.state.close();assertTrue(f.state.open())
        }
        f.awaitIdle()
        withContext(Dispatchers.Main) {
            assertEquals(retained.record.pages,f.state.records.single().record.pages)
            assertTrue(f.state.records.single().record.deleted)
        }
    }
    @Test fun explicitRestoreFromRetainedBytesKeepsIdentityAndRetiresOldOpenActions()=scenario {f->
        withContext(Dispatchers.IO) {f.retained()}
        withContext(Dispatchers.Main) {f.state.open()};f.awaitIdle()
        lateinit var before:OriginalPdfCatalog.Observation
        lateinit var oldOpen:()->Boolean
        withContext(Dispatchers.Main) {
            before=f.state.records.single();oldOpen=requireNotNull(f.state.captureOpen(before))
            assertTrue(oldOpen());f.state.delete(before)
        }
        f.awaitIdle()
        withContext(Dispatchers.Main) {
            assertFalse(oldOpen())
            assertNotNull(f.state.requestRestore(f.state.records.single()))
        }
        f.awaitIdle()
        withContext(Dispatchers.Main) {
            val restored=f.state.records.single().record
            assertFalse(restored.deleted);assertEquals(before.record.id,restored.id)
            assertEquals(before.record.pages,restored.pages)
            assertTrue(restored.generation>before.record.generation)
            assertFalse(oldOpen());assertNull(f.state.restoreRequest)
        }
    }
    @Test fun cancelledMissingFilePickerCannotRestoreARecordAfterLibraryReopens()=scenario {f->
        val record=withContext(Dispatchers.IO) {f.retained()}
        withContext(Dispatchers.Main) {f.state.open()};f.awaitIdle()
        withContext(Dispatchers.Main) {f.state.delete(f.state.records.single())};f.awaitIdle()
        File(f.context.noBackupFilesDir,"ai-pdfs/${record.record.sha256}.pdf").delete()
        lateinit var request:OriginalPdfLibraryState.RestoreRequest
        withContext(Dispatchers.Main) {request=requireNotNull(f.state.requestRestore(f.state.records.single()))}
        f.awaitIdle()
        withContext(Dispatchers.Main) {
            assertTrue(f.state.needsRestoreFile)
            f.state.close();f.state.open()
        }
        f.awaitIdle()
        withContext(Dispatchers.Main) {
            assertFalse(f.state.receiveRestoreFile(request.id,Uri.fromFile(f.source)))
            assertNull(f.state.restoreRequest);assertTrue(f.state.records.single().record.deleted)
        }
    }
    @Test fun aWrongRelinkFileRetiresItsRequestAndANewExplicitRetryKeepsTheOriginalIdentity()=scenario {f->
        val original=withContext(Dispatchers.IO) {f.retained()}
        withContext(Dispatchers.Main) {f.state.open()};f.awaitIdle()
        withContext(Dispatchers.Main) {f.state.delete(f.state.records.single())};f.awaitIdle()
        File(f.context.noBackupFilesDir,"ai-pdfs/${original.record.sha256}.pdf").delete()
        lateinit var first:OriginalPdfLibraryState.RestoreRequest
        withContext(Dispatchers.Main) {first=requireNotNull(f.state.requestRestore(f.state.records.single()))};f.awaitIdle()
        val wrong=File(f.root,"different.pdf").apply {writeText("different original bytes")}
        withContext(Dispatchers.Main) {assertTrue(f.state.receiveRestoreFile(first.id,Uri.fromFile(wrong)))};f.awaitIdle()
        withContext(Dispatchers.Main) {
            assertNull(f.state.restoreRequest);assertNotNull(f.state.failure)
            assertTrue(f.state.records.single().record.deleted)
            assertEquals(original.record.id,f.state.records.single().record.id)
        }
        lateinit var retry:OriginalPdfLibraryState.RestoreRequest
        withContext(Dispatchers.Main) {retry=requireNotNull(f.state.requestRestore(f.state.records.single()))};f.awaitIdle()
        withContext(Dispatchers.Main) {
            assertTrue(f.state.needsRestoreFile)
            assertFalse(f.state.receiveRestoreFile(first.id,Uri.fromFile(f.source)))
            assertTrue(f.state.receiveRestoreFile(retry.id,Uri.fromFile(f.source)))
        };f.awaitIdle()
        withContext(Dispatchers.Main) {
            val restored=f.state.records.single().record
            assertFalse(restored.deleted);assertEquals(original.record.id,restored.id)
            assertEquals(original.record.pages,restored.pages)
        }
        assertEquals("controlled original bytes",f.source.readText())
        assertEquals("different original bytes",wrong.readText())
    }

}
