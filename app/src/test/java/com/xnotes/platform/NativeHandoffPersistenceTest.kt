package com.xnotes.platform

import android.content.ContextWrapper
import com.xnotes.canvas.ViewOverrides
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Document
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.snapshot
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.PreparedNativeOpen
import com.xnotes.storage.MigrationPublication
import com.xnotes.storage.ReferenceContractViolation
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeHandoffPersistenceTest {
    private class PausedDispatcher:CoroutineDispatcher() {
        val tasks=java.util.concurrent.LinkedBlockingQueue<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable) {tasks.put(block)}
        fun next() {requireNotNull(tasks.poll(5,TimeUnit.SECONDS)) {"Continuation did not arrive"}.run()}
        fun awaitQueued() {val next=requireNotNull(tasks.poll(5,TimeUnit.SECONDS)) {"Continuation did not arrive"};tasks.put(next)}
    }
    private class Fixture(val root:File) {
        val uri="content://handoff/document/source"
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        }
        val file=File(root,"source");val directory=File(root,"working");var writes=0
        var event:((WorkingSessionCache.Event,File)->Unit)?=null
        val storage=NotebookStorage(context,targetOverride={object:MigrationPublication.Target {
            override fun read()=file.inputStream()
            override fun write(source:File) {writes++;source.copyTo(file,overwrite=true)}
        }})
        val notes=DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
        val canvases=CanvasCodec(FakeImageCodec(),FakeTextMeasurer())
        val store=NativeWorkingSession(directory,notes,canvases,storage.pdfResources,onEvent={e,f->event?.invoke(e,f)})
        val view=NativeWorkingSession.NoteView(1.0,10.0,20.0,false,ViewOverrides())
        init {file.outputStream().use {notes.write(Document.blank().apply {enableReferenceAnchors()}.snapshot(),it)}}
        fun open():Pair<Document,PreparedNativeOpen.EditSource> {
            val p=storage.prepareOpen(uri,DocumentKind.NOTE,notes,canvases,File(root,"pdfs").apply {mkdirs()},File(root,"assets").apply {mkdirs()})
            val doc=requireNotNull(p.note);doc.path=uri;p.protectForAdoption();p.adopt(doc);p.close()
            return doc to p.editSource()
        }
        fun stage(source:PreparedNativeOpen.EditSource,doc:Document)=source.capture(doc).prepareForHandoff(storage,notes,canvases,File(root,"scratch"),"source")
        fun copyStore(name:String):NativeWorkingSession {
            val target=File(root,name);directory.copyRecursively(target)
            return NativeWorkingSession(target,notes,canvases,storage.pdfResources)
        }
        fun bytes()=directory.listFiles().orEmpty().associate {it.name to it.readBytes().toList()}
    }
    private fun ink(x:Double)=Stroke(Tool.PEN,ToolConfig(),listOf(Sample(x,20.0,.25),Sample(x+10,30.0,.75)))
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("native-handoff-").toFile();val f=Fixture(root)
        try {block(f)} finally {f.storage.registry.close();root.deleteRecursively()}
    }
    @Test fun postStageCurrentContentAndHandoffShareOneDurableHeadWhileTheTargetStaysA()=scenario {f->
        val (doc,source)=f.open();doc.pages.single().items.add(ink(10.0));doc.dirty=true
        f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc);val original=f.file.readBytes()
        doc.pages.single().items.add(ink(100.0));doc.dirty=true
        val publication=runBlocking {f.store.persistHandoff(stage) {permit->permit.capture(doc,source,f.view)}}
        try {
            publication.verify(stage);assertEquals(stage.handoffId,publication.handoff.id)
            f.store.load()!!.use {read->
                assertEquals(publication.handoff,read.metadata.handoff);assertTrue(read.metadata.dirty)
                assertEquals(stage.original.sha256,read.metadata.source!!.sha256)
                val saved=read.body.inputStream().use {f.notes.read(it)}
                assertEquals(2,saved.pages.single().items.size)
            }
            assertArrayEquals(original,f.file.readBytes());assertEquals(0,f.writes)
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {stage.commit()}
        } finally {publication.close()}
        assertThrows(IllegalStateException::class.java) {publication.verify(stage)}
    }
    @Test fun anOldWriteCannotReplaceAFreshCaptureAndTheOwnerCanLaterPersistItsIntent()=scenario {f->
        val (doc,source)=f.open();f.store.publish(f.store.capture(doc,source,f.view))
        val old=f.store.capture(doc,source,f.view);val stage=f.stage(source,doc);val before=f.bytes()
        assertThrows(WorkingSessionCache.Conflict::class.java) {runBlocking {f.store.persistHandoff(stage) {_ ->old}}}
        assertEquals(before,f.bytes());assertFalse(f.store.publish(old).published);assertEquals(0,f.writes)
        val fresh=f.store.capture(doc,source,f.view);assertTrue(fresh.requiresBody);assertNotNull(fresh.handoff)
        assertTrue(f.store.publish(fresh).published)
        assertThrows(WorkingSessionCache.Conflict::class.java) {f.store.publishClear(f.store.reserveClear())}
    }
    @Test fun permitIsOneUseAndFalsePublicationDoesNotIssueAPersistedHandoff()=scenario {f->
        val (doc,source)=f.open();f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
        val before=f.bytes()
        assertThrows(IllegalStateException::class.java) {runBlocking {f.store.persistHandoff(stage) {permit->
            val first=permit.capture(doc,source,f.view);permit.capture(doc,source,f.view);first
        }}}
        assertEquals(before,f.bytes())
        assertThrows(WorkingSessionCache.Conflict::class.java) {runBlocking {f.store.persistHandoff(stage) {permit->
            val old=permit.capture(doc,source,f.view)
            f.store.capture(doc,source,f.view.copy(scrollY=300.0));old
        }}}
        assertEquals(before,f.bytes());assertEquals(0,f.writes)
    }
    @Test fun aSupersedingSameOwnerWriteCanProvideTheActualDurableHeadProof()=scenario {f->
        val (doc,source)=f.open();f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
        val executor=Executors.newSingleThreadExecutor()
        try {
            val publication=runBlocking {f.store.persistHandoff(stage) {permit->
                val old=permit.capture(doc,source,f.view)
                doc.pages.single().items.add(ink(200.0));doc.dirty=true
                val fresh=f.store.capture(doc,source,f.view.copy(scrollY=600.0))
                assertTrue(executor.submit<Boolean> {f.store.publish(fresh).published}.get(5,TimeUnit.SECONDS));old
            }}
            publication.use {it.verify(stage)}
            f.store.load()!!.use {read->
                assertEquals(600.0,read.metadata.noteView!!.scrollY,0.0)
                assertEquals(1,read.body.inputStream().use {f.notes.read(it)}.pages.single().items.size)
            }
            assertEquals(0,f.writes)
        } finally {executor.shutdownNow()}
    }
    @Test fun anotherActualSameUuidOwnerAndOtherAdaptersCannotEraseTheIntent()=scenario {f->
        val (doc,source)=f.open();val (other,otherSource)=f.open()
        f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.close()
        val second=NativeWorkingSession(f.directory,f.notes,f.canvases,f.storage.pdfResources);val before=f.bytes()
        assertThrows(ReferenceContractViolation::class.java) {second.capture(other,otherSource,f.view)}
        assertThrows(WorkingSessionCache.Conflict::class.java) {second.publishClear(second.reserveClear())}
        assertEquals(before,f.bytes());assertEquals(0,f.writes)
    }
    @Test fun coldHandoffRemainsReadableButCannotBeClearedOverwrittenOrAutomaticallyRebased()=scenario {f->
        val (doc,source)=f.open();f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.close()
        val cold=f.copyStore("cold");val dir=File(f.root,"cold")
        val before=dir.listFiles()!!.associate {it.name to it.readBytes().toList()}
        cold.load()!!.use {assertEquals(stage.handoffId,it.metadata.handoff!!.id)}
        assertThrows(WorkingSessionCache.Conflict::class.java) {cold.publishClear(cold.reserveClear())}
        assertThrows(WorkingSessionCache.Conflict::class.java) {cold.publish(cold.capture(doc,source,f.view))}
        assertThrows(WorkingSessionCache.Conflict::class.java) {cold.prepareRecovery(f.storage,File(f.root,"recovery-pdf"),File(f.root,"recovery-assets"))}
        assertEquals(before,dir.listFiles()!!.associate {it.name to it.readBytes().toList()});assertEquals(0,f.writes)
    }
    @Test fun anotherOwnerPublishedBetweenIoPreflightAndMainRegistrationCannotBeOverwritten()=scenario {f->
        val (doc,source)=f.open();val (other,otherSource)=f.open()
        f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
        var captured=false
        val paused=PausedDispatcher();val task=CoroutineScope(paused).async {
            f.store.persistHandoff(stage) {captured=true;it.capture(doc,source,f.view)}
        }
        paused.next();paused.awaitQueued() // IO preflight has finished; Main has not resumed.
        other.pages.single().items.add(ink(700.0));other.dirty=true
        assertTrue(f.store.publish(f.store.capture(other,otherSource,f.view)).published)
        val before=f.bytes()
        val currentBody=org.json.JSONObject(File(f.directory,"head.json").readText()).getString("body")
        while(!task.isCompleted) paused.next()
        assertThrows(WorkingSessionCache.Conflict::class.java) {runBlocking {task.await()}.close()}
        assertFalse(captured)
        val after=f.bytes()
        assertEquals(before["head.json"],after["head.json"]);assertEquals(before[currentBody],after[currentBody])
        f.store.load()!!.use {read->
            assertNull(read.metadata.handoff)
            val saved=read.body.inputStream().use {f.notes.read(it)}.pages.single().items.single() as Stroke
            assertEquals(700.0,saved.xAt(0),.001)
        }
        // The preflight's superseded body may now be collected after its lease is released.
        assertEquals(0,f.writes)
        assertTrue(f.store.publish(f.store.capture(other,otherSource,f.view.copy(scrollY=777.0))).published)
    }
    @Test fun cancellationAfterIoCreatesThePublicationReleasesItsReadBeforeDispatch()=scenario {f->
        val (doc,source)=f.open();f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
        val paused=PausedDispatcher();val task=CoroutineScope(paused).async {
            f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}
        }
        paused.next();paused.next();paused.awaitQueued() // C+T and the private proof exist on IO.
        val body=f.store.load()!!.use {assertNotNull(it.metadata.handoff);it.body}
        task.cancel()
        while(!task.isCompleted) paused.next()
        assertTrue(task.isCancelled)
        doc.pages.single().items.add(ink(800.0));doc.dirty=true
        assertTrue(f.store.publish(f.store.capture(doc,source,f.view)).published)
        assertFalse("Cancelled dispatch leaked its held read lease",body.exists());assertEquals(0,f.writes)
    }
    @Test fun aPriorAttemptHeadCannotAcknowledgeANewerCaptureThatNeverPublished()=scenario {f->
        val (doc,source)=f.open();f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.close()
        doc.pages.single().items.add(ink(900.0));doc.dirty=true;val before=f.bytes()
        assertThrows(WorkingSessionCache.Conflict::class.java) {runBlocking {f.store.persistHandoff(stage) {permit->
            val current=permit.capture(doc,source,f.view)
            f.store.capture(doc,source,f.view.copy(scrollY=999.0));current
        }}.close()}
        assertEquals(before,f.bytes());assertEquals(0,f.writes)
    }
    @Test fun emptyAndClearedCachesNeedAnOrdinaryOwnerCheckpointBeforeHandoff() {
        for(cleared in listOf(false,true)) scenario {f->
            val (doc,source)=f.open()
            if(cleared) {
                f.store.publish(f.store.capture(doc,source,f.view));f.store.publishClear(f.store.reserveClear())
            }
            val stage=f.stage(source,doc);val before=f.bytes();var called=false
            assertThrows(WorkingSessionCache.Conflict::class.java) {runBlocking {f.store.persistHandoff(stage) {
                called=true;it.capture(doc,source,f.view)
            }}.close()}
            assertFalse(called);assertEquals(before,f.bytes());assertEquals(0,f.writes)
            assertTrue(f.store.publish(f.store.capture(doc,source,f.view)).published)
            runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.close()
            assertEquals(0,f.writes)
        }
    }
    @Test fun registrationInvalidatesAnOldWriterAlreadyPastItsLastCheck()=scenario {f->
        val (doc,source)=f.open();f.store.publish(f.store.capture(doc,source,f.view))
        val old=f.store.capture(doc,source,f.view);val stage=f.stage(source,doc)
        val ready=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val armed=java.util.concurrent.atomic.AtomicBoolean(true);val executor=Executors.newSingleThreadExecutor()
        f.event={event,_->if(event==WorkingSessionCache.Event.BEFORE_HEAD_COMMIT && armed.compareAndSet(true,false)) {
            ready.countDown();check(release.await(5,TimeUnit.SECONDS))
        }}
        try {
            val pending=executor.submit<Boolean> {f.store.publish(old).published}
            assertTrue(ready.await(5,TimeUnit.SECONDS))
            runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {publication->
                release.countDown();assertFalse(pending.get(5,TimeUnit.SECONDS));publication.verify(stage)
                f.store.load()!!.use {assertEquals(publication.handoff,it.metadata.handoff)}
            }
            assertEquals(0,f.writes)
        } finally {release.countDown();executor.shutdownNow()}
    }
    @Test fun malformedFutureAndUnboundHandoffsPreserveAllColdRecoveryBytes()=scenario {f->
        val (doc,source)=f.open();f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.close()
        val changes=listOf<(org.json.JSONObject)->Unit>(
            {it.put("version",3)},
            {it.getJSONObject("handoff").put("id",it.getJSONObject("handoff").getString("id").uppercase())},
            {it.getJSONObject("handoff").put("recordDigest","0".repeat(64))},
            {it.getJSONObject("handoff").put("extra",true)},
            {it.put("binding","unbound");it.remove("source")},
            {it.put("version",1)},
        )
        for((index,change) in changes.withIndex()) {
            val directory=File(f.root,"bad-$index");f.directory.copyRecursively(directory)
            val head=File(directory,"head.json");val json=org.json.JSONObject(head.readText())
            change(json.getJSONObject("metadata"));head.writeText(json.toString())
            val cold=NativeWorkingSession(directory,f.notes,f.canvases,f.storage.pdfResources)
            val before=directory.listFiles()!!.associate {it.name to it.readBytes().toList()}
            assertThrows(WorkingSessionCache.Invalid::class.java) {cold.load()}
            assertThrows(WorkingSessionCache.Invalid::class.java) {cold.publishClear(cold.reserveClear())}
            assertThrows(WorkingSessionCache.Invalid::class.java) {cold.publish(cold.capture(doc,source,f.view))}
            assertEquals(before,directory.listFiles()!!.associate {it.name to it.readBytes().toList()})
        }
    }
    @Test fun schemaOneWithoutAHandoffRemainsReadableAndRecoverable()=scenario {f->
        val (doc,source)=f.open();f.store.publish(f.store.capture(doc,source,f.view))
        val directory=File(f.root,"schema-one");f.directory.copyRecursively(directory)
        val head=File(directory,"head.json");val json=org.json.JSONObject(head.readText())
        json.getJSONObject("metadata").put("version",1);head.writeText(json.toString())
        val cold=NativeWorkingSession(directory,f.notes,f.canvases,f.storage.pdfResources)
        cold.load()!!.use {assertNull(it.metadata.handoff);assertEquals(doc.identity.documentId,it.documentId)}
        val recovery=cold.prepareRecovery(f.storage,File(f.root,"legacy-pdf"),File(f.root,"legacy-assets")) as NativeWorkingSession.Recovery.Bound
        recovery.use {assertEquals(doc.identity.documentId,it.prepared.note!!.identity.documentId)}
    }
    @Test fun changedPdfContextCannotIssueAPublicationProofOrWriteTheOriginal()=scenario {f->
        val (doc,source)=f.open();f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
        val original=f.file.readBytes();doc.pdfFile=File(f.root,"changed.pdf").apply {writeText("%PDF-new background")};doc.dirty=true
        assertThrows(ReferenceContractViolation::class.java) {runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.close()}
        assertArrayEquals(original,f.file.readBytes());assertEquals(0,f.writes)
        f.store.load()!!.use {assertNotNull(it.metadata.handoff)} // Preserve the data, without a normal-source grant.
    }
}
