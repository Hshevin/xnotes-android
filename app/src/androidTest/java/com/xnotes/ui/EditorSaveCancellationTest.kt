package com.xnotes.ui

import android.view.ContextThemeWrapper
import com.xnotes.core.model.snapshot
import com.xnotes.R
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.pen.PenTestRunner
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.storage.MigrationPublication
import com.xnotes.storage.NotebookStorage
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real Editor bookkeeping around a backend cancellation; no GPU/pen acceptance is implied. */
class EditorSaveCancellationTest {
    @Test fun explicitSaveCancellationRestoresDirtySpinnerAndFailureCallback() = scenario(0)
    @Test fun noteAutosaveCancellationRestoresDirtyAndFailureCallback() = scenario(1)
    @Test fun canvasAutosaveCancellationRestoresDirtyAndCompletionCallback() = scenario(2)
    @Test fun cancelledCanvasCloseKeepsItsPaneAndUnsavedContentOpen() = scenario(3)
    @Test fun closingDuringInFlightCanvasSaveWaitsForItsFailureAndKeepsPaneOpen() = scenario(4)

    private fun scenario(mode:Int) = runBlocking {
        val base=PenTestRunner.instance.targetContext
        val root=File(base.cacheDir,"save-cancel-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextThemeWrapper(base,R.style.Theme_Xnotes) {
            override fun getApplicationContext()=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val current=File(root,"current");val uri="content://save-cancellation/document/source"
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(if(mode==4) 1 else 0)
        val writes=AtomicInteger();val before=AtomicReference<ByteArray>()
        val storage=NotebookStorage(context,targetOverride={object:MigrationPublication.Target {
            override fun read()=current.inputStream()
            override fun write(source:File) {
                writes.incrementAndGet();entered.countDown()
                check(release.await(15,java.util.concurrent.TimeUnit.SECONDS)) {"test did not release in-flight write"}
                throw CancellationException("provider cancelled write")
            }
        }})
        val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        val previous=singleton.get(null)
        var editor:Editor?=null
        val callbacks=AtomicInteger();val result=AtomicReference<Boolean?>()
        try {
            val job=withContext(Dispatchers.Main) {
                singleton.set(null,storage)
                val e=Editor(context);editor=e
                val image=AndroidImageCodec();val text=AndroidTextMeasurer()
                if(mode>=2) {
                    e.newCanvas();val doc=e.infinite.document;doc.path=uri
                    current.outputStream().use {CanvasCodec(image,text).write(doc.snapshotForWrite(),it)}
                    before.set(current.readBytes());doc.add(stroke());doc.dirty=true
                    if(mode==3) {
                        Editor::class.java.getDeclaredField("canvasAutosaveUri").apply {isAccessible=true}.set(e,uri)
                        e.goHome()
                    } else Editor::class.java.declaredMethods.single {it.name=="startCanvasWrite"}.apply {isAccessible=true}
                        .invoke(e,uri,doc,"source.xcanvas",{ok:Boolean->callbacks.incrementAndGet();result.set(ok);Unit})
                } else {
                    e.newNote();val doc=e.state.document;doc.path=uri
                    current.outputStream().use {DocumentCodec(image,text).write(doc.snapshot(),it)}
                    before.set(current.readBytes());doc.pages.first().items.add(stroke());doc.dirty=true
                    if(mode==0) e.saveToThen(uri) {ok->callbacks.incrementAndGet();result.set(ok)}
                    else Editor::class.java.declaredMethods.single {it.name=="startNoteWrite"}.apply {isAccessible=true}
                        .invoke(e,uri,doc.snapshot(),"source.xnote",System.nanoTime(),{ok:Boolean->callbacks.incrementAndGet();result.set(ok);Unit})
                }
                Editor::class.java.getDeclaredField(if(mode>=2) "canvasWriteJob" else "noteWriteJob")
                    .apply {isAccessible=true}.get(e) as? Job
            }
            val activeJob=job ?: withTimeout(15000) {
                var pending:Job?=null
                while(pending==null) {
                    kotlinx.coroutines.delay(1)
                    pending=withContext(Dispatchers.Main) {Editor::class.java.getDeclaredField("canvasWriteJob").apply {isAccessible=true}.get(requireNotNull(editor)) as? Job}
                }
                requireNotNull(pending)
            }
            if(mode==4) {
                assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS))
                withContext(Dispatchers.Main) {
                    val e=requireNotNull(editor)
                    assertFalse(e.infinite.document.dirty);assertTrue(activeJob.isActive)
                    Editor::class.java.getDeclaredField("canvasAutosaveUri").apply {isAccessible=true}.set(e,uri)
                    e.goHome()
                    assertTrue(e.canvasOpen) // dirty=false is an in-flight snapshot, not a successful save.
                }
                release.countDown()
            }
            withTimeout(15000) {activeJob.join()}
            if(mode==4) withTimeout(15000) {
                var after:Job?=activeJob
                while(after===activeJob || after==null) {
                    kotlinx.coroutines.delay(1)
                    after=withContext(Dispatchers.Main) {Editor::class.java.getDeclaredField("canvasWriteJob").apply {isAccessible=true}.get(requireNotNull(editor)) as? Job}
                }
                requireNotNull(after).join()
            }
            withContext(Dispatchers.Main) {
                val e=requireNotNull(editor)
                assertTrue(activeJob.isCancelled)
                assertTrue(if(mode>=2) e.infinite.document.dirty else e.state.document.dirty)
                assertFalse(e.savingNote)
                if(mode>=3) {assertTrue(e.canvasOpen);assertTrue(e.noteOpen)}
                if(mode!=3) {assertEquals(1,callbacks.get());assertEquals(false,result.get())}
                assertTrue(storage.hasRecovery(uri));assertEquals(1,writes.get())
                assertArrayEquals(before.get(),current.readBytes())
            }
        } finally {
            release.countDown()
            withContext(Dispatchers.Main) {
                editor?.let {e->
                    (Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(e) as CoroutineScope).cancel()
                    e.goHomeAll()
                }
                singleton.set(null,previous)
            }
            storage.registry.close();root.deleteRecursively()
        }
    }
    private fun stroke()=com.xnotes.core.model.Stroke(com.xnotes.core.tools.Tool.PEN,
        com.xnotes.core.tools.ToolDefaults.configFor(com.xnotes.core.tools.Tool.PEN)).apply {
        addSample(com.xnotes.core.stroke.Sample(10.0,20.0,.5))
        addSample(com.xnotes.core.stroke.Sample(30.0,40.0,.8))
    }

}
