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
        var afterWrite:(()->Unit)?=null
        var duringRead:(()->Unit)?=null
        var event:((WorkingSessionCache.Event,File)->Unit)?=null
        val storage=NotebookStorage(context,targetOverride={object:MigrationPublication.Target {
            override fun read()=object:java.io.FilterInputStream(file.inputStream()) {
                override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                    val count=super.read(bytes,offset,length);duringRead?.invoke();return count
                }
            }
            override fun write(source:File) {writes++;source.copyTo(file,overwrite=true);afterWrite?.invoke()}
        }})
        val notes=DocumentCodec(FakeImageCodec(),FakeTextMeasurer()).apply {resolvePdf={storage.pdfResources.resolve(it)}}
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
            return NativeWorkingSession(target,notes,canvases,storage.pdfResources,onEvent={e,f->event?.invoke(e,f)})
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
        assertThrows(MigrationPublication.RecoveryRequired::class.java) {cold.prepareRecovery(f.storage,File(f.root,"recovery-pdf"),File(f.root,"recovery-assets"))}
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
    @Test fun onlyTheActualPersistedPublicationCanCommitNative4AndIssueItsRequestReceipt()=scenario {f->
        val (doc,source)=f.open();doc.pages.single().items.add(ink(10.0));doc.dirty=true
        f.store.publish(f.store.capture(doc,source,f.view));val request=source.capture(doc)
        val stage=request.prepareForHandoff(f.storage,f.notes,f.canvases,File(f.root,"scratch"),"source")
        doc.pages.single().items.add(ink(200.0));doc.dirty=true
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {publication->
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {f.storage.ensure(f.uri,"source","xnote")}
            assertEquals(0,f.writes)
            val receipt=stage.commit(publication)
            assertEquals(stage.handoffId,receipt.handoffId);assertEquals(stage.candidate.sha256,receipt.version.sha256)
            assertEquals(1,f.writes);assertFalse(f.storage.hasRecovery(f.uri))
            assertEquals(1,f.file.inputStream().use {f.notes.read(it)}.pages.single().items.size)
            f.store.load()!!.use {assertEquals(2,it.body.inputStream().use {f.notes.read(it)}.pages.single().items.size)}
            assertTrue(source.accept(request,receipt));assertTrue(doc.dirty)
            assertThrows(ReferenceContractViolation::class.java) {stage.commit(publication)}
            assertEquals(1,f.writes)
        }
    }
    @Test fun publicationRevalidatesTheLaterSameOwnerHeadBeforeWritingTheFixedNativeCandidate()=scenario {f->
        val (doc,source)=f.open();doc.pages.single().items.add(ink(10.0));doc.dirty=true
        f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {publication->
            doc.pages.single().items.add(ink(300.0));doc.dirty=true
            assertTrue(f.store.publish(f.store.capture(doc,source,f.view)).published)
            val receipt=stage.commit(publication)
            assertEquals(stage.candidate.sha256,receipt.version.sha256);assertEquals(1,f.writes)
            assertEquals(1,f.file.inputStream().use {f.notes.read(it)}.pages.single().items.size)
            f.store.load()!!.use {assertEquals(2,it.body.inputStream().use {f.notes.read(it)}.pages.single().items.size)}
        }
    }
    @Test fun aProviderFailureAfterWritingBRetainsTAndRetryDoesNotRewriteB()=scenario {f->
        val (doc,source)=f.open();doc.pages.single().items.add(ink(20.0));doc.dirty=true
        f.store.publish(f.store.capture(doc,source,f.view));val request=source.capture(doc)
        val stage=request.prepareForHandoff(f.storage,f.notes,f.canvases,File(f.root,"scratch"),"source")
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {publication->
            f.afterWrite={throw java.io.IOException("provider failed after write")}
            assertThrows(MigrationPublication.RecoveryRequired::class.java) {stage.commit(publication)}
            assertEquals(1,f.writes);assertTrue(f.storage.hasRecovery(f.uri))
            assertEquals(stage.candidate.sha256,com.xnotes.format.BundleMigration.hash(f.file))
            f.afterWrite=null
            val receipt=stage.commit(publication)
            assertEquals(1,f.writes);assertFalse(f.storage.hasRecovery(f.uri));assertTrue(source.accept(request,receipt))
        }
    }
    @Test fun closingOrAdvancingTheHeadAfterAckCreationCannotWriteFromThatOldAck()=scenario {f->
        val (doc,source)=f.open();doc.pages.single().items.add(ink(20.0));doc.dirty=true
        f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc);val original=f.file.readBytes()
        val publication=runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}
        val ack=com.xnotes.storage.NativeHandoffAcknowledgement.verify(stage,publication,{})
        doc.pages.single().items.add(ink(400.0));doc.dirty=true
        assertTrue(f.store.publish(f.store.capture(doc,source,f.view)).published)
        assertThrows(ReferenceContractViolation::class.java) {f.storage.commitHandoff(stage.nativeWitness(),ack,"source",{})}
        assertArrayEquals(original,f.file.readBytes());assertEquals(0,f.writes)
        val refreshed=com.xnotes.storage.NativeHandoffAcknowledgement.verify(stage,publication,{})
        publication.close()
        assertThrows(ReferenceContractViolation::class.java) {f.storage.commitHandoff(stage.nativeWitness(),refreshed,"source",{})}
        assertArrayEquals(original,f.file.readBytes());assertEquals(0,f.writes)
    }
    @Test fun anAckCannotAuthorizeAnotherNativeStageEvenWhenBothAreValid()=scenario {f->scenario {other->
        val (doc,source)=f.open();f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
        val (od,os)=other.open();other.store.publish(other.store.capture(od,os,other.view));val wrong=other.stage(os,od)
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {publication->
            val ack=com.xnotes.storage.NativeHandoffAcknowledgement.verify(stage,publication,{})
            assertThrows(ReferenceContractViolation::class.java) {other.storage.commitHandoff(wrong.nativeWitness(),ack,"source",{})}
            assertEquals(0,f.writes);assertEquals(0,other.writes)
        }
    }}
    @Test fun losingPublicationOrSourceDuringTargetHashNeverWrites() {
        for(closePublication in listOf(false,true)) scenario {f->
            val (doc,source)=f.open();doc.pages.single().items.add(ink(30.0));doc.dirty=true
            f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
            val original=f.file.readBytes()
            runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {publication->
                f.duringRead={f.duringRead=null;if(closePublication) publication.close() else source.invalidate()}
                assertThrows(ReferenceContractViolation::class.java) {stage.commit(publication)}
                assertEquals(0,f.writes);assertArrayEquals(original,f.file.readBytes());assertTrue(f.storage.hasRecovery(f.uri))
                f.store.load()!!.use {assertEquals(stage.handoffId,it.metadata.handoff!!.id)}
            }
        }
    }
    @Test fun registrationFailureAfterWritingBRetainsRecoveryAndRetryOnlyRegisters()=scenario {f->
        val (doc,source)=f.open();doc.pages.single().items.add(ink(30.0));doc.dirty=true
        f.store.publish(f.store.capture(doc,source,f.view));val request=source.capture(doc)
        val stage=request.prepareForHandoff(f.storage,f.notes,f.canvases,File(f.root,"scratch"),"source")
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {publication->
            f.afterWrite={f.storage.registry.register(java.util.UUID.randomUUID().toString(),"xnote","other",f.uri)}
            assertThrows(ReferenceContractViolation::class.java) {stage.commit(publication)}
            assertEquals(1,f.writes);assertTrue(f.storage.hasRecovery(f.uri))
            assertEquals(stage.candidate.sha256,com.xnotes.format.BundleMigration.hash(f.file))
            f.storage.registry.register(doc.identity.documentId,"xnote","source",f.uri);f.afterWrite=null
            val receipt=stage.commit(publication)
            assertEquals(1,f.writes);assertFalse(f.storage.hasRecovery(f.uri));assertTrue(source.accept(request,receipt))
        }
    }
    @Test fun acceptedReceiptRebasesOnlyTheWorkingMetadataAndPreservesLaterContent()=scenario {f->
        val (doc,source)=f.open();doc.pages.single().items.add(ink(40.0));doc.dirty=true
        f.store.publish(f.store.capture(doc,source,f.view));val request=source.capture(doc)
        val stage=request.prepareForHandoff(f.storage,f.notes,f.canvases,File(f.root,"scratch"),"source")
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {publication->
            val receipt=stage.commit(publication)
            doc.pages.single().items.add(ink(400.0));doc.dirty=true
            assertTrue(f.store.publish(f.store.capture(doc,source,f.view)).published)
            assertTrue(source.accept(request,receipt))
            val before=f.store.load()!!.use {assertNotNull(it.metadata.handoff);it.body}
            val complete=f.store.capture(doc,source,f.view.copy(scrollY=800.0))
            assertNull(complete.handoff);assertFalse(complete.requiresBody)
            assertTrue(f.store.publish(complete).published)
            f.store.load()!!.use {read->
                assertNull(read.metadata.handoff);assertEquals(receipt.version.sha256,read.metadata.source!!.sha256)
                assertEquals(before,read.body);assertTrue(read.metadata.dirty)
                assertEquals(2,read.body.inputStream().use {f.notes.read(it)}.pages.single().items.size)
            }
            assertEquals(1,f.writes)
            assertTrue(f.store.publish(f.store.capture(doc,source,f.view)).published)
            assertTrue(f.store.publishClear(f.store.reserveClear()).published)
        }
    }
    @Test fun acceptedCompletionInvalidatesAnOldTWriterPastItsLastCheck()=scenario {f->
        val (doc,source)=f.open();doc.pages.single().items.add(ink(40.0));doc.dirty=true
        f.store.publish(f.store.capture(doc,source,f.view));val request=source.capture(doc)
        val stage=request.prepareForHandoff(f.storage,f.notes,f.canvases,File(f.root,"scratch"),"source")
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {publication->
            val receipt=stage.commit(publication);val old=f.store.capture(doc,source,f.view)
            val ready=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
            val executor=Executors.newSingleThreadExecutor();val armed=java.util.concurrent.atomic.AtomicBoolean(true)
            f.event={event,_->if(event==WorkingSessionCache.Event.BEFORE_HEAD_COMMIT && armed.compareAndSet(true,false)) {
                ready.countDown();check(release.await(5,TimeUnit.SECONDS))
            }}
            try {
                val pending=executor.submit<Boolean> {f.store.publish(old).published}
                assertTrue(ready.await(5,TimeUnit.SECONDS));assertTrue(source.accept(request,receipt))
                assertTrue(f.store.publish(f.store.capture(doc,source,f.view)).published)
                release.countDown();assertFalse(pending.get(5,TimeUnit.SECONDS))
                f.store.load()!!.use {assertNull(it.metadata.handoff);assertEquals(receipt.version.sha256,it.metadata.source!!.sha256)}
            } finally {release.countDown();executor.shutdownNow()}
        }
    }
    @Test fun failedCompletionKeepsTAndBlocksClearUntilAnActualRetryPublishes()=scenario {f->
        val (doc,source)=f.open();f.store.publish(f.store.capture(doc,source,f.view));val request=source.capture(doc)
        val stage=request.prepareForHandoff(f.storage,f.notes,f.canvases,File(f.root,"scratch"),"source")
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {publication->
            assertTrue(source.accept(request,stage.commit(publication)))
            f.event={event,_->if(event==WorkingSessionCache.Event.BEFORE_HEAD_REPLACE) throw java.io.IOException("head failed")}
            assertThrows(java.io.IOException::class.java) {f.store.publish(f.store.capture(doc,source,f.view))}
            f.store.load()!!.use {assertNotNull(it.metadata.handoff)}
            assertThrows(WorkingSessionCache.Conflict::class.java) {f.store.publishClear(f.store.reserveClear())}
            f.event=null;assertTrue(f.store.publish(f.store.capture(doc,source,f.view)).published)
            f.store.load()!!.use {assertNull(it.metadata.handoff)}
        }
    }
    @Test fun aCommittedCompletionHeadCanRetryWhenItsCallerNeverReceivedSuccess()=scenario {f->
        val (doc,source)=f.open();f.store.publish(f.store.capture(doc,source,f.view));val request=source.capture(doc)
        val stage=request.prepareForHandoff(f.storage,f.notes,f.canvases,File(f.root,"scratch"),"source")
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {publication->
            assertTrue(source.accept(request,stage.commit(publication)))
            f.event={event,_->if(event==WorkingSessionCache.Event.AFTER_HEAD_COMMIT) throw java.io.IOException("return interrupted")}
            assertThrows(java.io.IOException::class.java) {f.store.publish(f.store.capture(doc,source,f.view))}
            f.store.load()!!.use {assertNull(it.metadata.handoff)}
            assertThrows(WorkingSessionCache.Conflict::class.java) {f.store.publishClear(f.store.reserveClear())}
            f.event=null;assertTrue(f.store.publish(f.store.capture(doc,source,f.view)).published)
            assertTrue(f.store.publishClear(f.store.reserveClear()).published)
        }
    }
    @Test fun lateCompletionCleanupCannotEraseANewerHandoffIntent()=scenario {f->
        val (doc,source)=f.open();f.store.publish(f.store.capture(doc,source,f.view));val request=source.capture(doc)
        val stage=request.prepareForHandoff(f.storage,f.notes,f.canvases,File(f.root,"scratch"),"source")
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {publication->
            assertTrue(source.accept(request,stage.commit(publication)))
            val old=f.store.capture(doc,source,f.view)
            val ready=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
            val executor=Executors.newSingleThreadExecutor();val armed=java.util.concurrent.atomic.AtomicBoolean(true)
            f.event={event,_->if(event==WorkingSessionCache.Event.AFTER_HEAD_COMMIT && armed.compareAndSet(true,false)) {
                ready.countDown();check(release.await(5,TimeUnit.SECONDS))
            }}
            try {
                val pending=executor.submit<Boolean> {f.store.publish(old).published}
                assertTrue(ready.await(5,TimeUnit.SECONDS))
                doc.pages.single().items.add(ink(900.0));doc.dirty=true
                assertTrue(f.store.publish(f.store.capture(doc,source,f.view)).published)
                val next=f.stage(source,doc)
                runBlocking {f.store.persistHandoff(next) {it.capture(doc,source,f.view)}}.use {
                    release.countDown();assertTrue(pending.get(5,TimeUnit.SECONDS));it.verify(next)
                    f.store.load()!!.use {assertEquals(next.handoffId,it.metadata.handoff!!.id)}
                    assertThrows(WorkingSessionCache.Conflict::class.java) {f.store.publishClear(f.store.reserveClear())}
                }
            } finally {release.countDown();executor.shutdownNow()}
        }
    }
    @Test fun actualNoopCanvasStillNeedsItsMatchingAcceptedReceiptToDropT()=scenario {f->
        val blank=com.xnotes.core.infinite.InfiniteDocument()
        f.file.outputStream().use {f.canvases.write(blank,it)};normalizeZipTimes(f.file,f.root)
        val p=f.storage.prepareOpen(f.uri,DocumentKind.CANVAS,f.notes,f.canvases,File(f.root,"pdfs"),File(f.root,"assets"))
        val doc=requireNotNull(p.canvas);doc.path=f.uri;p.protectForAdoption();p.adopt(doc);p.close();val source=p.editSource()
        f.store.publish(f.store.capture(doc,source))
        val request=source.capture(doc)
        f.duringRead={File(f.root,"scratch").listFiles().orEmpty().filter {it.isFile}.forEach {normalizeZipTimes(it,f.root)}}
        val stage=request.prepareForHandoff(f.storage,f.notes,f.canvases,File(f.root,"scratch"),"canvas");f.duringRead=null
        assertEquals(stage.original.sha256,stage.candidate.sha256)
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source)}}.use {publication->
            val receipt=stage.commit(publication);assertEquals(0,f.writes)
            val cold=f.copyStore("noop-cold")
            (cold.prepareRecovery(f.storage,File(f.root,"noop-pdfs"),File(f.root,"noop-assets")) as NativeWorkingSession.Recovery.Bound).use {bound->
                val p=bound.prepared;val restored=p.canvas!!;p.protectForAdoption();p.adopt(restored);bound.claim()
                val next=p.editSource();assertThrows(ReferenceContractViolation::class.java) {next.capture(restored)}
                val write=cold.capture(restored,next);assertTrue(write.requiresBody);assertTrue(cold.publish(write).published)
                next.capture(restored)
            }
            assertTrue(f.store.publish(f.store.capture(doc,source)).published)
            f.store.load()!!.use {assertNotNull(it.metadata.handoff)}
            assertTrue(source.accept(request,receipt));assertTrue(f.store.publish(f.store.capture(doc,source)).published)
            f.store.load()!!.use {assertNull(it.metadata.handoff);assertEquals(receipt.version.sha256,it.metadata.source!!.sha256)}
            assertTrue(f.store.publishClear(f.store.reserveClear()).published)
        }
    }
    @Test fun ownerRetirementAfterCommittedCompletionCannotStrandTheRoot()=scenario {f->
        val (doc,source)=f.open();f.store.publish(f.store.capture(doc,source,f.view));val request=source.capture(doc)
        val stage=request.prepareForHandoff(f.storage,f.notes,f.canvases,File(f.root,"scratch"),"source")
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {publication->
            assertTrue(source.accept(request,stage.commit(publication)))
            var late:NativeWorkingSession.Write?=null
            f.event={event,_->if(event==WorkingSessionCache.Event.AFTER_HEAD_COMMIT) {
                f.event=null;late=f.store.capture(doc,source,f.view.copy(scrollY=900.0));source.invalidate()
            }}
            assertTrue(f.store.publish(f.store.capture(doc,source,f.view)).published)
            f.store.load()!!.use {assertNull(it.metadata.handoff);assertEquals(stage.candidate.sha256,it.metadata.source!!.sha256)}
            assertFalse(f.store.publish(requireNotNull(late)).published)
            assertTrue(f.store.publishClear(f.store.reserveClear()).published)
        }
    }
    @Test fun externalPdfCompletionKeepsPortableWorkingBodyAndOriginalResourcePackaging()=scenario {f->
        val pdf=f.storage.pdfResources.admit("%PDF-handoff-completion".byteInputStream(),"original")
        val original=Document.blank().apply {
            enableReferenceAnchors();pdfFile=pdf;identity=identity.copy(pdfResource=pdf.nameWithoutExtension,externalPdf=true)
        }
        f.file.outputStream().use {f.notes.write(original.snapshot(),it,embedPdf=false)}
        val (doc,source)=f.open();doc.pages.single().items.add(ink(40.0));doc.dirty=true
        f.store.publish(f.store.capture(doc,source,f.view));val request=source.capture(doc)
        val stage=request.prepareForHandoff(f.storage,f.notes,f.canvases,File(f.root,"scratch"),"source")
        doc.pages.single().items.add(ink(400.0));doc.dirty=true
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {publication->
            val receipt=stage.commit(publication)
            val cold=f.copyStore("pdf-cold")
            (cold.prepareRecovery(f.storage,File(f.root,"pdf-pdfs"),File(f.root,"pdf-assets")) as NativeWorkingSession.Recovery.Bound).use {bound->
                val p=bound.prepared;val restored=p.note!!;assertTrue(restored.identity.externalPdf)
                p.protectForAdoption();p.adopt(restored);bound.claim();val next=p.editSource()
                val write=cold.capture(restored,next,f.view);assertTrue(write.requiresBody);assertTrue(cold.publish(write).published)
                next.capture(restored)
            }
            assertTrue(source.accept(request,receipt))
            val write=f.store.capture(doc,source,f.view);assertFalse(write.requiresBody)
            assertTrue(f.store.publish(write).published)
            val native=com.xnotes.format.BundleReferenceInfo.inspect(f.file,true)
            assertTrue(native.header.identity!!.externalPdf);assertEquals(pdf.nameWithoutExtension,native.originalPdfHash)
            f.store.load()!!.use {read->
                assertNull(read.metadata.handoff);val cached=com.xnotes.format.BundleReferenceInfo.inspect(read.body,true)
                assertFalse(cached.header.identity!!.externalPdf);assertEquals(pdf.nameWithoutExtension,cached.embeddedPdfHash)
            }
            f.storage.registry.writableDatabase.execSQL("UPDATE resources SET created=0");f.storage.pdfResources.collect()
            assertTrue(pdf.isFile)
        }
    }
    @Test fun coldActualBRecoveryClaimsOnlyTheAdoptedModelAndRewritesItsCurrentContentBeforeSaving()=scenario {f->
        val (doc,source)=f.open();doc.pages.single().items.add(ink(40.0));doc.dirty=true
        f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
        doc.pages.single().items.add(ink(400.0));doc.dirty=true
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {stage.commit(it)}
        val cold=f.copyStore("cold-b")
        val recovery=cold.prepareRecovery(f.storage,File(f.root,"recover-pdfs"),File(f.root,"recover-assets")) as NativeWorkingSession.Recovery.Bound
        val p=recovery.prepared;val restored=requireNotNull(p.note)
        assertEquals(2,restored.pages.single().items.size)
        assertThrows(IllegalStateException::class.java) {p.editSource()}
        assertThrows(IllegalStateException::class.java) {recovery.claim()}
        p.protectForAdoption();assertThrows(ReferenceContractViolation::class.java) {p.adopt(restored.snapshot())};p.adopt(restored)
        val gated=p.sourceForClaim(cold,recovery.held)
        assertThrows(ReferenceContractViolation::class.java) {gated.observe(restored)}
        assertThrows(ReferenceContractViolation::class.java) {gated.capture(restored)}
        restored.pages.single().items.add(ink(900.0));restored.dirty=true
        recovery.claim();val next=p.editSource();recovery.close()
        next.observe(restored) // Readonly cache observation cannot unlock native capture.
        assertThrows(ReferenceContractViolation::class.java) {next.capture(restored)}
        f.event={event,_->if(event==WorkingSessionCache.Event.BEFORE_HEAD_REPLACE) throw java.io.IOException("first body failed")}
        assertThrows(java.io.IOException::class.java) {cold.publish(cold.capture(restored,next,f.view))}
        assertThrows(ReferenceContractViolation::class.java) {next.capture(restored)}
        f.event=null
        val write=cold.capture(restored,next,f.view);assertTrue(write.requiresBody);assertNull(write.handoff)
        assertTrue(cold.publish(write).published)
        cold.load()!!.use {assertNull(it.metadata.handoff);assertEquals(3,it.body.inputStream().use {f.notes.read(it)}.pages.single().items.size)}
        val request=next.capture(restored);val receipt=request.publish(f.storage,f.notes,f.canvases,File(f.root,"later-save"),"source")
        assertTrue(next.accept(request,receipt));assertEquals(3,f.file.inputStream().use {f.notes.read(it)}.pages.single().items.size)
    }
    @Test fun twoColdPreparationsCannotBothClaimAndAClosedPreparationNeverClaims()=scenario {f->
        val (doc,source)=f.open();doc.pages.single().items.add(ink(40.0));doc.dirty=true
        f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {stage.commit(it)}
        val cold=f.copyStore("claim-race")
        fun prepare()=cold.prepareRecovery(f.storage,File(f.root,"recover-pdfs"),File(f.root,"recover-assets")) as NativeWorkingSession.Recovery.Bound
        val closed=prepare();closed.close();assertThrows(IllegalStateException::class.java) {closed.claim()}
        prepare().use {one->prepare().use {two->
            for(bound in listOf(one,two)) {bound.prepared.protectForAdoption();bound.prepared.adopt(bound.prepared.note!!)}
            one.claim();assertThrows(WorkingSessionCache.Conflict::class.java) {two.claim()}
            assertThrows(IllegalStateException::class.java) {two.prepared.editSource()}
            val next=one.prepared.editSource();val write=cold.capture(one.prepared.note!!,next,f.view)
            assertTrue(write.requiresBody);assertTrue(cold.publish(write).published)
            assertThrows(WorkingSessionCache.Conflict::class.java) {two.claim()}
        }}
    }
    @Test fun unresolvedActualAAndForeignActualSourceCannotBeRebasedOrLoseTheirRecoveryBytes() {
        for(foreign in listOf(false,true)) scenario {f->
            val (doc,source)=f.open();doc.pages.single().items.add(ink(40.0));doc.dirty=true
            f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
            runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.close()
            f.storage.restore(f.uri)
            if(foreign) {doc.pages.single().items.add(ink(800.0));f.file.outputStream().use {f.notes.write(doc.snapshot(),it)}}
            val cold=f.copyStore("unresolved");val directory=File(f.root,"unresolved")
            val before=directory.listFiles()!!.associate {it.name to it.readBytes().toList()};val target=f.file.readBytes()
            assertThrows(ReferenceContractViolation::class.java) {cold.prepareRecovery(f.storage,File(f.root,"recover-pdfs"),File(f.root,"recover-assets"))}
            assertEquals(before,directory.listFiles()!!.associate {it.name to it.readBytes().toList()});assertArrayEquals(target,f.file.readBytes())
            assertEquals(0,f.writes)
        }
    }
    @Test fun anotherAdapterReadAndAReplacedHeadCannotClaimAnEarlierPreparation()=scenario {f->
        val (doc,source)=f.open();doc.pages.single().items.add(ink(40.0));doc.dirty=true
        f.store.publish(f.store.capture(doc,source,f.view));val stage=f.stage(source,doc)
        runBlocking {f.store.persistHandoff(stage) {it.capture(doc,source,f.view)}}.use {stage.commit(it)}
        val cold=f.copyStore("changed-head");val dir=File(f.root,"changed-head")
        val other=NativeWorkingSession(dir,f.notes,f.canvases,f.storage.pdfResources)
        val bound=cold.prepareRecovery(f.storage,File(f.root,"recover-pdfs"),File(f.root,"recover-assets")) as NativeWorkingSession.Recovery.Bound
        bound.use {
            val p=it.prepared;p.protectForAdoption();p.adopt(p.note!!)
            assertThrows(IllegalStateException::class.java) {p.sourceForClaim(other,it.held)}
            other.load()!!.use {wrong->assertThrows(IllegalStateException::class.java) {p.sourceForClaim(cold,wrong)}}
            val generic=WorkingSessionCache(dir,{_,_,_->})
            val metadata=org.json.JSONObject(File(dir,"head.json").readText()).getJSONObject("metadata")
            metadata.getJSONObject("view").put("scrollY",999.0)
            val ticket=generic.reserve(Any(),WorkingSessionCache.Kind.NOTE,doc.identity.documentId,123,metadata)
            assertTrue(generic.publish(ticket) {out->bound.held.body.inputStream().use {input->input.copyTo(out)}}.published)
            assertThrows(WorkingSessionCache.Conflict::class.java) {bound.claim()}
            assertThrows(IllegalStateException::class.java) {p.editSource()}
        }
        (cold.prepareRecovery(f.storage,File(f.root,"recover-pdfs"),File(f.root,"recover-assets")) as NativeWorkingSession.Recovery.Bound).use {
            val p=it.prepared;p.protectForAdoption();p.adopt(p.note!!);it.claim()
            assertTrue(cold.publish(cold.capture(p.note!!,p.editSource(),f.view)).published)
        }
    }
    private fun normalizeZipTimes(file:File,root:File) {
        val temp=File.createTempFile("zip-time-", ".zip",root)
        try {
            java.util.zip.ZipInputStream(file.inputStream()).use {input->java.util.zip.ZipOutputStream(temp.outputStream()).use {output->
                output.setLevel(java.util.zip.Deflater.BEST_SPEED)
                while(true) {
                    val entry=input.nextEntry ?: break;val bytes=input.readBytes()
                    val next=java.util.zip.ZipEntry(entry.name).apply {time=1700000000000L;method=entry.method}
                    if(next.method==java.util.zip.ZipEntry.STORED) {next.size=bytes.size.toLong();next.crc=java.util.zip.CRC32().apply {update(bytes)}.value}
                    output.putNextEntry(next);output.write(bytes);output.closeEntry();input.closeEntry()
                }
            }}
            temp.copyTo(file,overwrite=true)
        } finally {temp.delete()}
    }
}
