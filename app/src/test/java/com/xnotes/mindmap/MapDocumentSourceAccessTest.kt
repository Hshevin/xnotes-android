package com.xnotes.mindmap

import android.net.Uri
import android.os.Looper
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.BundleIdentity
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.storage.NativeReferenceIssue
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual DocumentsProvider/file aliases, JSON, recovery journals, directory leases and saves.
 * No target MapSession is manufactured or replaced by this access layer. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class MapDocumentSourceAccessTest {
    private class QueueDispatcher:CoroutineDispatcher() {
        private val queue=ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable) {queue.add(block)}
        fun pending()=queue.isNotEmpty()
        fun next()=queue.poll()?.let {it.run();true} ?: false
    }
    private fun scenario(block:suspend(MapBacklinkLibraryTest.Fixture)->Unit)=MapBacklinkLibraryTest().scenario(block=block)
    private suspend fun registered(f:MapBacklinkLibraryTest.Fixture,name:String="source.xmap",document:MindMapDocument=MindMapDocument()):Pair<File,MindMapDocument> =
        withContext(Dispatchers.IO) {
            f.graph(name,document).also {file->f.storage.registry.registerMapObserved(document.id,name,f.uri(file).toString())} to document
        }
    private suspend fun ready(f:MapBacklinkLibraryTest.Fixture,id:String):MapDocumentSourceAccess.Held = withContext(Dispatchers.Main) {
        val result=MapDocumentSourceAccess(f.context,f.storage).acquire(MapSourceTarget(id,SourceScope.FILE))
        assertTrue("Expected actual Map source: $result",result is MapDocumentSourceAccess.Result.Ready)
        (result as MapDocumentSourceAccess.Result.Ready).held
    }
    private suspend fun await(check:()->Boolean) = withTimeout(10000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
    @Test fun actualJsonWithAWrongSuffixOpensThroughTheBrokerAndSuccessfulAdoptionTransfersOnlyItsLease()=scenario {f->
        val (file,doc)=registered(f,"actual-map.xnote");val bytes=file.readBytes()
        val held=ready(f,doc.id)
        withContext(Dispatchers.Main) {
            assertEquals(doc.id,held.documentId);assertTrue(held.current());assertEquals(doc,held.lease.session.document)
            val actor=MapDocuments.observations(f.context).single {it.key==MapSaves.key(f.uri(file))}
            assertSame(actor.session,held.lease.session)
            assertTrue(held.finish {lease->assertSame(held.lease,lease);f.leases+=lease;true})
            held.closeUnadopted().join();assertTrue(held.lease.alive);assertFalse(held.current())
        }
        assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun registeredNativeIsNotMapButUnknownIdsPermanentPageContractsAndReservedOriginalsAreRefused()=scenario {f->
        val access=MapDocumentSourceAccess(f.context,f.storage)
        val note=Document(mutableListOf(Page(500.0,400.0))).apply {enableReferenceAnchors()}
        val file=File(f.notes,"native.xnote")
        withContext(Dispatchers.IO) {
            file.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(note.fixedWriteSnapshot(),it)}
            f.storage.registry.register(note.identity.documentId,"xnote",file.name,f.uri(file).toString(),true)
        }
        val reads=f.reads.get()
        withContext(Dispatchers.Main) {
            assertSame(MapDocumentSourceAccess.Result.NotMap,access.acquire(MapSourceTarget(note.identity.documentId,SourceScope.FILE)))
            assertEquals(reads,f.reads.get())
            assertTrue(access.acquire(MapSourceTarget(UUID.randomUUID().toString(),SourceScope.FILE)) is MapDocumentSourceAccess.Result.Failed)
        }
        val (_,map)=registered(f)
        withContext(Dispatchers.IO) {f.storage.registry.writableDatabase.execSQL("INSERT INTO page_reference_contracts VALUES(?)",arrayOf(map.id))}
        withContext(Dispatchers.Main) {
            assertEquals(MapDocumentSourceAccess.Result.Failed(NativeReferenceIssue.UNSUPPORTED),access.acquire(MapSourceTarget(map.id,SourceScope.FILE)))
        }
        val (_,other)=registered(f,"other.xmap")
        withContext(Dispatchers.IO) {
            f.storage.registry.writableDatabase.execSQL("INSERT INTO original_pdf_documents VALUES(?,?,?,?,1,0,99)",
                arrayOf(other.id,"a".repeat(64),"Reserved even when malformed",1))
        }
        withContext(Dispatchers.Main) {
            assertEquals(MapDocumentSourceAccess.Result.Failed(NativeReferenceIssue.UNSUPPORTED),access.acquire(MapSourceTarget(other.id,SourceScope.FILE)))
        }
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun twoCanonicalFilesWithTheSameUuidAreRejectedWhileFileAliasesShareTheActualActor()=scenario {f->
        val (file,doc)=registered(f);val copy=File(f.notes,"copy.xmap").apply {writeBytes(file.readBytes())}
        withContext(Dispatchers.IO) {f.storage.registry.registerMapObserved(doc.id,copy.name,f.uri(copy).toString())}
        withContext(Dispatchers.Main) {
            assertEquals(MapDocumentSourceAccess.Result.Failed(NativeReferenceIssue.DUPLICATE),
                MapDocumentSourceAccess(f.context,f.storage).acquire(MapSourceTarget(doc.id,SourceScope.FILE)))
        }
        // This is a separate identity whose two known URI spellings resolve to one actual file.
        val graph=MindMapDocument();val aliasFile=f.graph("alias.xmap",graph)
        val one=Uri.fromFile(aliasFile);val two=Uri.fromFile(File(aliasFile.parentFile,"./${aliasFile.name}"))
        assertNotEquals(one.toString(),two.toString());assertEquals(MapSaves.key(one),MapSaves.key(two))
        withContext(Dispatchers.IO) {
            f.storage.registry.registerMapObserved(graph.id,aliasFile.name,one.toString())
            f.storage.registry.registerMapObserved(graph.id,aliasFile.name,two.toString())
        }
        val owner=f.acquire(one)
        val held=ready(f,graph.id)
        withContext(Dispatchers.Main) {
            assertSame(owner.session,held.lease.session);assertTrue(owner.hasOtherOwners)
            held.closeUnadopted().join();held.closeUnadopted().join()
            assertTrue(owner.alive);assertFalse(owner.hasOtherOwners)
        }
        assertEquals(0,f.writes.get())
    }
    @Test fun existingDirtyActorKeepsItsLatestBodyHistoryAndAutosaveWhenAnUnadoptedSourceLeaseIsCancelled()=scenario {f->
        val (file,doc)=registered(f);val owner=f.acquire(f.uri(file));val held=ready(f,doc.id)
        val debounce=MapSession::class.java.getDeclaredField("debounce").apply {isAccessible=true}
        val job=withContext(Dispatchers.Main) {
            assertSame(owner.session,held.lease.session)
            assertTrue(owner.session.operate {it.update(it.root.id) {node->node.copy(text="Normal pending autosave")}})
            val pending=debounce.get(owner.session) as Job
            assertTrue(held.current()) // Actual body changes do not change actor identity.
            held.closeUnadopted().join();assertTrue(owner.alive);assertTrue(owner.session.canUndo)
            assertSame(pending,debounce.get(owner.session));assertFalse(pending.isCancelled)
            pending
        }
        val expected=withContext(Dispatchers.Main) {
            Triple(owner.session.document!!,owner.session.contentRevision,owner.session.interactionEpoch)
        }
        withTimeout(10000) {
            while(withContext(Dispatchers.Main) {owner.session.dirty || owner.session.saving}) {
                withContext(Dispatchers.Main) {shadowOf(Looper.getMainLooper()).idleFor(25,java.util.concurrent.TimeUnit.MILLISECONDS)}
                delay(1)
            }
        }
        // The consumed debounce normally cancels itself in saveAttempt(); the lease must keep
        // it active until it fires, as asserted above, and allow its real accepted save to finish.
        assertTrue(job.isCompleted);assertTrue(f.writes.get()>0)
        withContext(Dispatchers.Main) {
            assertSame(held.lease.session,owner.session);assertSame(expected.first,owner.session.document)
            assertEquals(expected.second,owner.session.contentRevision);assertEquals(expected.third,owner.session.interactionEpoch)
            assertTrue(owner.alive);assertTrue(owner.session.canUndo)
            assertFalse(owner.session.dirty);assertFalse(owner.session.saveFailed);assertFalse(owner.session.conflict)
        }
        assertEquals(expected.first,MapCodec.decode(file.readBytes()))
        assertEquals("Normal pending autosave",MapCodec.decode(file.readBytes()).root.text)
    }
    @Test fun finalRecheckRejectsChangedActualBytesWithoutAdoptingOrWritingTheDestination()=scenario {f->
        val (file,doc)=registered(f);val held=ready(f,doc.id)
        val changed=MapCodec.encode(doc.update(doc.root.id) {it.copy(text="External same-UUID change")})
        withContext(Dispatchers.IO) {file.writeBytes(changed)}
        var called=false
        withContext(Dispatchers.Main) {
            assertFalse(held.finish {called=true;true});assertFalse(called)
            assertEquals(NativeReferenceIssue.CHANGED,held.failure);assertFalse(held.lease.alive)
            held.closeUnadopted().join()
        }
        assertArrayEquals(changed,file.readBytes());assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun actualUuidAndNativeZipCannotBeHiddenByAnIndex()=scenario {f->
        val (file,doc)=registered(f);val other=MindMapDocument()
        file.writeBytes(MapCodec.encode(other))
        withContext(Dispatchers.Main) {
            assertEquals(MapDocumentSourceAccess.Result.Failed(NativeReferenceIssue.CHANGED),
                MapDocumentSourceAccess(f.context,f.storage).acquire(MapSourceTarget(doc.id,SourceScope.FILE)))
        }
        val native=Document(mutableListOf(Page(500.0,400.0))).apply {identity=BundleIdentity(doc.id);enableReferenceAnchors()}
        file.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(native.fixedWriteSnapshot(),it)}
        val bytes=file.readBytes()
        withContext(Dispatchers.Main) {
            assertEquals(MapDocumentSourceAccess.Result.Failed(NativeReferenceIssue.UNSUPPORTED),
                MapDocumentSourceAccess(f.context,f.storage).acquire(MapSourceTarget(doc.id,SourceScope.FILE)))
        }
        assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get())
    }
    @Test fun finalMainAdoptionHoldsTheSameSerializationLockUntilAnAcceptedSaveCanProceed()=scenario {f->
        val (file,doc)=registered(f);val bytes=file.readBytes();val held=ready(f,doc.id)
        lateinit var save:Deferred<MapSaveOutcome>
        withContext(Dispatchers.Main) {
            assertTrue(held.finish {lease->
                assertFalse(MapSaves.lockFor(lease.uri).tryLock())
                assertTrue(lease.session.operate {it.update(it.root.id) {node->node.copy(text="Accepted while final lock is held")}})
                save=lease.session.saveAttempt();assertTrue(lease.session.saving)
                assertArrayEquals(bytes,file.readBytes());f.leases+=lease;true
            })
        }
        assertTrue(save.await() is MapSaveOutcome.Written)
        assertEquals("Accepted while final lock is held",MapCodec.decode(file.readBytes()).root.text)
        assertTrue(f.writes.get()>0) // This is the accepted user edit save, after navigation adoption.
    }
    @Test fun missingAndCorruptTargetFilesFailWithoutChangingTheExistingLiveActorOrItsEpoch()=scenario {f->
        val (file,doc)=registered(f);val owner=f.acquire(f.uri(file))
        val original=withContext(Dispatchers.Main) {
            assertTrue(owner.session.operate {it.update(it.root.id) {node->node.copy(text="Retain this dirty body")}})
            owner.session.cancelPendingAutosave()
            owner.session.document!! to owner.session.interactionEpoch
        }
        for(bytes in listOf(byteArrayOf(),"{bad JSON".toByteArray(),MapCodec.encode(MindMapDocument()))) {
            file.writeBytes(bytes)
            withContext(Dispatchers.Main) {
                assertTrue(MapDocumentSourceAccess(f.context,f.storage).acquire(MapSourceTarget(doc.id,SourceScope.FILE)) is MapDocumentSourceAccess.Result.Failed)
                assertSame(original.first,owner.session.document);assertEquals(original.second,owner.session.interactionEpoch)
                assertTrue(owner.session.dirty);assertTrue(owner.session.canUndo);assertTrue(owner.alive)
            }
            assertArrayEquals(bytes,file.readBytes())
        }
        // Explicit fixture cleanup restores the original physical identity before normal owner release.
        file.writeBytes(MapCodec.encode(doc));assertEquals(0,f.writes.get())
    }
    private suspend fun recovery(f:MapBacklinkLibraryTest.Fixture,file:File,document:MindMapDocument)=withContext(Dispatchers.IO) {
        val target=object:MapTarget {
            override fun read()=file.readBytes()
            override fun write(bytes:ByteArray):Unit=throw IOException("Controlled publication failure leaves a real recovery head")
        }
        val store=MapFiles.store(f.context,f.uri(file),target)
        store.open();assertThrows(IOException::class.java) {store.save(document)};assertTrue(store.journalReady)
    }
    @Test fun anExistingRecoveryConflictKeepsTheSameActorBodyAndConflictUntilExplicitUserResolution()=scenario {f->
        val (file,doc)=registered(f)
        val recovered=doc.update(doc.root.id) {it.copy(text="Recovered local body")}
        recovery(f,file,recovered)
        val external=MapCodec.encode(doc.update(doc.root.id) {it.copy(text="Actual external body")})
        file.writeBytes(external)
        val owner=f.acquire(f.uri(file));val held=ready(f,doc.id)
        withContext(Dispatchers.Main) {
            assertTrue(owner.session.conflict);assertTrue(owner.session.recovered);assertTrue(owner.session.dirty)
            assertEquals(recovered,owner.session.document);assertSame(owner.session,held.lease.session)
            assertTrue(held.finish {lease->
                assertSame(owner.session,lease.session);assertTrue(lease.session.conflict)
                assertEquals(recovered,lease.session.document);f.leases+=lease;true
            })
        }
        assertArrayEquals(external,file.readBytes());assertEquals(0,f.writes.get())
        // Explicit fixture resolution follows the same user action; source navigation did not resolve it.
        withContext(Dispatchers.Main) {owner.session.useFileVersion()}
        await {!owner.session.replacingDocument && !owner.session.saving}
        assertArrayEquals(external,file.readBytes())
    }
    @Test fun cancellingAfterLeaseCreationWaitsForLoadingThenProtectsItsRecoveredDirtyActorInsteadOfSavingIt()=scenario {f->
        val (file,doc)=registered(f);val bytes=file.readBytes()
        val recovered=doc.update(doc.root.id) {it.copy(text="Do not implicitly write this recovered body")}
        recovery(f,file,recovered)
        val recoveryBytes=f.journalSnapshot();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        var completed:MapDocumentSourceAccess.Result?=null
        f.beforeOpenRead={
            if(Thread.currentThread().stackTrace.any {it.className.startsWith(MapSession::class.java.name+"$"+"open")}) {
                entered.countDown();check(release.await(5,TimeUnit.SECONDS))
            }
        }
        val pending=scope.launch {completed=MapDocumentSourceAccess(f.context,f.storage).acquire(MapSourceTarget(doc.id,SourceScope.FILE))}
        try {
            assertTrue(withContext(Dispatchers.IO) {entered.await(5,TimeUnit.SECONDS)})
            withContext(Dispatchers.Main) {pending.cancel();assertFalse(pending.isCompleted)}
            release.countDown();pending.join();assertNull(completed)
            assertArrayEquals(bytes,file.readBytes());assertEquals(recoveryBytes,f.journalSnapshot());assertEquals(0,f.writes.get())
            val actual=withContext(Dispatchers.Main) {MapDocuments.observations(f.context).single {it.key==MapSaves.key(f.uri(file))}.session}
            withContext(Dispatchers.Main) {assertTrue(actual.dirty);assertTrue(actual.recovered);assertEquals(recovered,actual.document)}
            f.beforeOpenRead=null
            val reclaimed=f.acquire(f.uri(file));assertSame(actual,reclaimed.session)
            // A later explicit user choice can retire this protected actor; cancellation cannot.
            withContext(Dispatchers.Main) {reclaimed.session.useFileVersion()}
            await {!reclaimed.session.replacingDocument && !reclaimed.session.saving}
        } finally {
            release.countDown();f.beforeOpenRead=null
            withContext(NonCancellable) {pending.cancelAndJoin()};scope.cancel()
        }
        assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get())
    }
    private fun finalMainRegistryMutation(alias:Boolean)=scenario {f->
        val doc=MindMapDocument();val file=f.graph("final-main-source.xmap",doc);val uri=Uri.fromFile(file)
        withContext(Dispatchers.IO) {f.storage.registry.registerMapObserved(doc.id,file.name,uri.toString())}
        val owner=f.acquire(uri)
        val model=withContext(Dispatchers.Main) {
            assertTrue(owner.session.operate {it.update(it.root.id) {node->node.copy(text="Keep the actual dirty owner")}})
            owner.session.cancelPendingAutosave();owner.session.document!!
        }
        val held=ready(f,doc.id);val bytes=file.readBytes();val revision=owner.session.contentRevision
        val queue=QueueDispatcher();val scope=CoroutineScope(SupervisorJob()+queue);val adopted=AtomicInteger()
        val pending=scope.async {held.finish {lease->adopted.incrementAndGet();f.leases+=lease;true}}
        try {
            // Run the start on Main, then stop at the continuation of the completed final IO.
            // The actual source lock remains held while registration mutates independently.
            withContext(Dispatchers.Main) {assertTrue(queue.next())}
            withTimeout(10000) {while(!queue.pending()) delay(1)}
            withContext(Dispatchers.IO) {
                if(alias) {
                    val spelling=Uri.fromFile(File(file.parentFile,"./${file.name}")).toString()
                    val target=object:MapTarget {
                        override fun read()=file.readBytes()
                        override fun write(bytes:ByteArray):Unit=throw IOException("The new alias owns a real pending recovery head")
                    }
                    val journal=MapStore(File(f.context.noBackupFilesDir,"mind-map-recovery"),spelling,target)
                    journal.open()
                    assertThrows(IOException::class.java) {journal.save(doc.update(doc.root.id) {it.copy(text="Unknown alias dirty body")})}
                    assertTrue(journal.journalReady)
                    f.storage.registry.registerMapObserved(doc.id,"new alias",spelling)
                } else {
                    val duplicate=f.graph("final-main-second.xmap",doc)
                    f.storage.registry.registerMapObserved(doc.id,duplicate.name,Uri.fromFile(duplicate).toString())
                }
            }
            val journals=f.journalSnapshot()
            withContext(Dispatchers.Main) {while(queue.next()) {}}
            assertFalse(pending.await());assertEquals(0,adopted.get())
            withContext(Dispatchers.Main) {
                assertEquals(NativeReferenceIssue.CHANGED,held.failure);assertFalse(held.lease.alive)
                assertTrue(owner.alive);assertSame(model,owner.session.document);assertTrue(owner.session.canUndo)
                assertEquals(revision,owner.session.contentRevision);assertTrue(owner.session.dirty)
            }
            assertEquals(journals,f.journalSnapshot());assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get())
        } finally {
            withContext(NonCancellable+Dispatchers.Main) {
                scope.cancel();held.closeUnadopted().join()
            }
        }
    }
    @Test fun aSecondCanonicalPositionRegisteredAfterSuccessfulFinalIoCannotReachMainAdoption()=finalMainRegistryMutation(false)
    @Test fun aNewCanonicalAliasWithAnUnknownDirtyJournalAfterSuccessfulFinalIoCannotReachMainAdoption()=finalMainRegistryMutation(true)
    @Test fun anUnknownAliasRecoveryJournalIsRetainedAndCannotCancelAnExistingOwnersAutosave()=scenario {f->
        val doc=MindMapDocument();val file=f.graph("alias-recovery.xmap",doc)
        val uri=Uri.fromFile(file);val alias=Uri.fromFile(File(file.parentFile,"./${file.name}"))
        withContext(Dispatchers.IO) {f.storage.registry.registerMapObserved(doc.id,file.name,uri.toString())}
        val owner=f.acquire(uri)
        val bad=withContext(Dispatchers.IO) {
            f.storage.registry.registerMapObserved(doc.id,file.name,alias.toString())
            val key=MessageDigest.getInstance("SHA-256").digest(alias.toString().toByteArray()).joinToString("") {"%02x".format(it)}
            File(f.context.noBackupFilesDir,"mind-map-recovery/$key.json").apply {parentFile!!.mkdirs();writeText("corrupt alias recovery must remain")}
        }
        val before=bad.readBytes();val source=file.readBytes()
        withContext(Dispatchers.Main) {
            assertTrue(owner.session.operate {it.update(it.root.id) {node->node.copy(text="Owned dirty body")}})
            val field=MapSession::class.java.getDeclaredField("debounce").apply {isAccessible=true}
            val pending=field.get(owner.session) as Job
            assertTrue(MapDocumentSourceAccess(f.context,f.storage).acquire(MapSourceTarget(doc.id,SourceScope.FILE)) is MapDocumentSourceAccess.Result.Failed)
            assertSame(pending,field.get(owner.session));assertFalse(pending.isCancelled)
            owner.session.cancelPendingAutosave() // Explicit test cleanup, after proving access did not cancel it.
        }
        assertArrayEquals(before,bad.readBytes());assertArrayEquals(source,file.readBytes());assertEquals(0,f.writes.get())
        // Do not let fixture shutdown mistake the intentionally unread alias journal for an owned draft.
        bad.delete()
    }
}
