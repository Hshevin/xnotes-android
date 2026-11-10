package com.xnotes.pen

import com.xnotes.core.geometry.Rect
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.storage.NativeReferenceTarget
import com.xnotes.ui.NativeReferencePreparation
import com.xnotes.ui.NativeReferenceResult
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A region open cannot replace its caller with a Note whose drawing payload was only partly read. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PagedRegionOpenCompletenessHostTest {
    private val area=Rect(20.0,40.0,160.0,100.0)
    private fun damage(file:File,mode:String) {
        val entries=java.util.zip.ZipFile(file).use {zip->zip.entries().asSequence().map {entry->
            entry.name to zip.getInputStream(entry).use {it.readBytes()}
        }.toList()}
        val item=if(mode=="unknown") org.json.JSONObject().put("kind","future-shape")
            else org.json.JSONObject().put("kind","image").put("asset","assets/image-missing.png")
                .put("rect",org.json.JSONArray(listOf(0,0,20,20))).put("src_w",20).put("src_h",20)
        val temporary=File(file.parentFile,"${file.name}.partial")
        java.util.zip.ZipOutputStream(temporary.outputStream()).use {zip->
            for((name,bytes) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(if(name=="manifest.json") org.json.JSONObject(bytes.toString(Charsets.UTF_8)).apply {
                    getJSONArray("pages").getJSONObject(1).getJSONArray("items").put(item)
                }.toString().toByteArray(Charsets.UTF_8) else bytes)
                zip.closeEntry()
            }
        }
        temporary.copyTo(file,overwrite=true);temporary.delete()
    }
    @Test fun aPartialInitialRegionReadCannotSaveOrReplaceTheOutgoingDirtyNote() {
        for(mode in listOf("unknown","missing-image")) runParkingScenario {f->
            withContext(Dispatchers.IO) {f.seed();f.seed(f.target)}
            val info=BundleReferenceInfo.inspectStrict(f.target)
            withContext(Dispatchers.IO) {damage(f.target,mode)}
            val raw=f.target.readBytes();val original=f.source.readBytes()
            withContext(Dispatchers.Main) {
                f.editor.openAsync(f.sourceUri,"Reader.xnote");f.addStroke(120.0,recordHistory=true);f.stopDebounce()
                val model=f.editor.state.document;val history=f.editor.history;val revision=model.bodyRevision
                val preparation=f.editor.prepareReference(NativeReferenceTarget(info.header.identity!!.documentId,
                    info.orderedPageIds[1]),area,selectedUri=f.targetUri)
                var adopted=0
                if(preparation is NativeReferencePreparation.Ready) {
                    val result=f.editor.openReference(preparation.request,onOpened={adopted++})
                    assertTrue("$mode must reject the actual incomplete decode: $result",result is NativeReferenceResult.Failed)
                } else assertTrue(preparation is NativeReferencePreparation.Failed)
                assertEquals(0,adopted);assertSame(model,f.editor.state.document);assertSame(history,f.editor.history)
                assertEquals(revision,model.bodyRevision);assertTrue(model.dirty);assertTrue(history.canUndo)
                assertTrue(f.editor.noteOpen);assertFalse(f.editor.canvasOpen)
            }
            assertArrayEquals(raw,f.target.readBytes());assertArrayEquals(original,f.source.readBytes())
            assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
        }
    }
    @Test fun aSameIdentityPartialBAtFinalRegionVerificationCannotReplaceTheOutgoingWorkspace() {
        for(mode in listOf("unknown","missing-image")) runParkingScenario {f->
            withContext(Dispatchers.IO) {f.seed();f.seed(f.target)}
            val info=BundleReferenceInfo.inspectStrict(f.target)
            val original=f.source.readBytes()
            val request=withContext(Dispatchers.Main) {
                f.editor.openAsync(f.sourceUri,"Reader.xnote")
                val preparation=f.editor.prepareReference(NativeReferenceTarget(info.header.identity!!.documentId,
                    info.orderedPageIds[1]),area,selectedUri=f.targetUri)
                assertTrue(preparation is NativeReferencePreparation.Ready)
                (preparation as NativeReferencePreparation.Ready).request
            }
            val entered=CompletableDeferred<Unit>();val reads=AtomicInteger()
            f.beforeTargetRead={if(reads.incrementAndGet()==2) {entered.complete(Unit);check(f.release.await(10,TimeUnit.SECONDS))}}
            coroutineScope {
                val model=f.editor.state.document;val history=f.editor.history
                var adopted=0
                val opening=async(Dispatchers.Main) {f.editor.openReference(request,onOpened={adopted++})}
                entered.await();withContext(Dispatchers.IO) {damage(f.target,mode)}
                val rawB=f.target.readBytes();f.release.countDown()
                val result=opening.await()
                withContext(Dispatchers.Main) {
                    assertTrue("Final B re-read must reject $mode: $result",result is NativeReferenceResult.Failed)
                    assertEquals(0,adopted);assertSame(model,f.editor.state.document);assertSame(history,f.editor.history)
                    assertFalse(model.dirty);assertFalse(history.canUndo);assertTrue(f.editor.noteOpen);assertFalse(f.editor.canvasOpen)
                }
                assertArrayEquals(rawB,f.target.readBytes())
            }
            assertArrayEquals(original,f.source.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.writeOpens.get());assertEquals(0,f.deletes.get())
        }
    }
}
