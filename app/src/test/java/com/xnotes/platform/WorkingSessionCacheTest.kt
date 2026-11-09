package com.xnotes.platform

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CancellationException

class WorkingSessionCacheTest {
    private val id=UUID.randomUUID().toString()
    private fun dir()=Files.createTempDirectory("working-session").toFile()
    private fun body(kind:WorkingSessionCache.Kind=WorkingSessionCache.Kind.NOTE,id:String=this.id,text:String="content")="${kind.name}:$id:$text"
    private fun cache(dir:File,hook:(WorkingSessionCache.Event,File)->Unit={_,_->})=WorkingSessionCache(dir,
        validateBody={file,kind,docId->require(file.readText().startsWith("${kind.name}:$docId:"))},onEvent=hook)
    private fun request(c:WorkingSessionCache,owner:Any=Any(),revision:Long=0,kind:WorkingSessionCache.Kind=WorkingSessionCache.Kind.NOTE,docId:String=id,meta:JSONObject=JSONObject())=c.reserve(owner,kind,docId,revision,meta)
    private fun write(c:WorkingSessionCache,r:WorkingSessionCache.WriteRequest,text:String=body(r.kind,r.documentId))=c.publish(r) {it.write(text.toByteArray())}
    private fun text(c:WorkingSessionCache)=c.load()!!.use {it.body.readText()}
    private fun rejects(block:()->Unit) {try {block();fail("Expected cache failure")} catch(_:java.io.IOException) {} catch(_:IllegalArgumentException) {}}

    @Test fun roundTripAndMetadataAreBoundToOneImmutableBody() {
        val root=dir();val c=cache(root);val owner=Any();val meta=JSONObject().put("path","A").put("dirty",true)
        val r=request(c,owner,meta=meta);meta.put("path","B")
        val result=write(c,r);assertTrue(result.published);assertSame(r,result.request);assertTrue(c.isCurrent(result.request))
        cache(root).load()!!.use {
            assertEquals(id,it.documentId);assertEquals(WorkingSessionCache.Kind.NOTE,it.kind)
            assertEquals("A",JSONObject(it.metadataJson).getString("path"));assertEquals(body(),it.body.readText())
        }
    }
    @Test fun previousHeadGuardCanRejectWritesAndClearBeforeAnyNewBytesAreCreated() {
        val root=dir();val c=cache(root);val owner=Any()
        try {
            write(c,request(c,owner,meta=JSONObject().put("handoff","pending")))
            val before=root.listFiles()!!.associate {it.name to it.readBytes().toList()}
            var calls=0
            val guard:(WorkingSessionCache.Previous?)->Unit={old->
                calls++;assertNotNull(old);assertEquals(id,old!!.documentId)
                assertEquals("pending",JSONObject(old.metadataJson).getString("handoff"))
                assertEquals(0L,old.contentVersion);assertEquals(64,old.sha256.length);assertEquals(64,old.headHash.length)
                throw WorkingSessionCache.Conflict()
            }
            val next=request(c,owner,1)
            assertThrows(WorkingSessionCache.Conflict::class.java) {c.publishChecked(next,validatePrevious=guard) {fail("guard precedes encoding")}}
            assertEquals(before,root.listFiles()!!.associate {it.name to it.readBytes().toList()})
            assertThrows(WorkingSessionCache.Conflict::class.java) {c.publishClearChecked(c.reserveClear(),validatePrevious=guard)}
            assertEquals(2,calls);assertEquals(before,root.listFiles()!!.associate {it.name to it.readBytes().toList()})
        } finally {root.deleteRecursively()}
    }
    @Test fun previousHeadGuardRunsOutsideTheReservationLockAndANewerReservationWins() {
        val root=dir();val c=cache(root);val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val owner=Any();write(c,request(c,owner));val r=request(c,owner,1)
            var newest:WorkingSessionCache.WriteRequest?=null
            val result=c.publishChecked(r,validatePrevious={
                newest=executor.submit<WorkingSessionCache.WriteRequest> {request(c,owner,2)}.get(5,TimeUnit.SECONDS)
            }) {fail("superseded before body encoding")}
            assertFalse(result.published);assertTrue(c.isCurrent(requireNotNull(newest)));assertEquals(body(),text(c))
        } finally {executor.shutdownNow();root.deleteRecursively()}
    }
    @Test fun intentInvalidationAfterTheLastCheckPreventsBothWriteAndClearCommit() {
        for(clear in listOf(false,true)) {
            val root=dir();val ready=CountDownLatch(1);val release=CountDownLatch(1);var armed=false
            val c=cache(root) {event,_->if(armed && event==WorkingSessionCache.Event.BEFORE_HEAD_COMMIT) {
                ready.countDown();check(release.await(5,TimeUnit.SECONDS))
            }}
            val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val owner=Any();write(c,request(c,owner));val before=File(root,"head.json").readBytes();armed=true
                val future=if(clear) {
                    val r=c.reserveClear();executor.submit<WorkingSessionCache.Publication> {c.publishClear(r)}
                } else {
                    val r=request(c,owner,1);executor.submit<WorkingSessionCache.Publication> {write(c,r,body(text="new"))}
                }
                assertTrue(ready.await(5,TimeUnit.SECONDS));c.invalidateReservations();release.countDown()
                assertFalse(future.get(5,TimeUnit.SECONDS).published)
                assertArrayEquals(before,File(root,"head.json").readBytes());assertEquals(body(),text(c))
            } finally {release.countDown();executor.shutdownNow();root.deleteRecursively()}
        }
    }
    @Test fun forcedBodyNeverReusesAnEarlierSameRevisionProof() {
        val root=dir();val c=cache(root);val owner=Any()
        try {
            write(c,request(c,owner));assertFalse(request(c,owner).requiresBody)
            val fresh=c.reserve(owner,WorkingSessionCache.Kind.NOTE,id,0,JSONObject(),forceBody=true)
            assertTrue(fresh.requiresBody);assertTrue(write(c,fresh,body(text="fresh capture")).published)
            assertEquals(body(text="fresh capture"),text(c));assertFalse(request(c,owner).requiresBody)
        } finally {root.deleteRecursively()}
    }
    @Test fun currentHeadReadProofRequiresItsOwnIssuerAnOpenLeaseAndTheUnchangedHead() {
        val root=dir();val c=cache(root);val owner=Any()
        try {
            write(c,request(c,owner));val read=c.load()!!
            c.verifyRead(read)
            assertThrows(WorkingSessionCache.Invalid::class.java) {cache(root).verifyRead(read)}
            val view=request(c,owner,meta=JSONObject().put("view",2));assertTrue(write(c,view).published)
            assertEquals(body(),read.body.readText())
            assertThrows(WorkingSessionCache.Conflict::class.java) {c.verifyRead(read)}
            read.close();assertThrows(WorkingSessionCache.Invalid::class.java) {c.verifyRead(read)}
        } finally {root.deleteRecursively()}
    }
    @Test fun freshReadersRejectAmbiguousEnvelopesWithoutCollectingOrReplacingRecoveryData() {
        val invalid=listOf(
            """{"version":1,"state":"cleared","metadata":{"version":99}}""",
            """{"version":1,"state":"active","st\u0061te":"cleared"}""",
            """{"version":1,"state":"cleared"}{}""",
            """{"version":1.0,"state":"cleared"}""",
            """{"version":1,"state":"cleared","body":"future"}""",
        )
        for(raw in invalid) {
            val root=dir()
            try {
                File(root,"head.json").writeText(raw)
                File(root,"body-${UUID.randomUUID()}.zip").writeText(body())
                val before=root.listFiles()!!.associate {it.name to it.readBytes().toList()}
                val c=cache(root)
                assertThrows(WorkingSessionCache.Invalid::class.java) {c.load()}
                assertThrows(WorkingSessionCache.Invalid::class.java) {write(c,request(c))}
                assertThrows(WorkingSessionCache.Invalid::class.java) {c.publishClear(c.reserveClear())}
                assertEquals(before,root.listFiles()!!.associate {it.name to it.readBytes().toList()})
            } finally {root.deleteRecursively()}
        }
    }
    @Test fun nestedEscapedDuplicatesAndExcessDepthAreRejectedBeforeJSONObjectCanCollapseThem() {
        for(bad in listOf("""{"a":0,"\u0061":1}""", "[".repeat(63)+"0"+"]".repeat(63))) {
            val root=dir()
            try {
                val c=cache(root);write(c,request(c))
                val head=File(root,"head.json")
                val json=JSONObject(head.readText());json.put("metadata",JSONObject())
                head.writeText(json.toString().replace("\"metadata\":{}","\"metadata\":{\"nested\":$bad}"))
                val before=root.listFiles()!!.associate {it.name to it.readBytes().toList()}
                assertThrows(WorkingSessionCache.Invalid::class.java) {cache(root).load()}
                assertEquals(before,root.listFiles()!!.associate {it.name to it.readBytes().toList()})
            } finally {root.deleteRecursively()}
        }
        val root=dir()
        try {
            val c=cache(root);write(c,request(c))
            val head=File(root,"head.json");val json=JSONObject(head.readText())
            val nested="[".repeat(62)+"0"+"]".repeat(62)
            json.put("metadata",JSONObject());head.writeText(json.toString().replace("\"metadata\":{}","\"metadata\":{\"nested\":$nested}"))
            // Root + metadata + 62 arrays = exactly 64 containers, read on first observation.
            val fresh=dir()
            try {
                root.listFiles()!!.forEach {it.copyTo(File(fresh,it.name))}
                cache(fresh).load()!!.use {assertEquals(body(),it.body.readText())}
            } finally {fresh.deleteRecursively()}
        } finally {root.deleteRecursively()}
    }
    @Test fun viewOnlyReusesOnlyTheSameOwnerIdentityKindAndContentVersion() {
        val c=cache(dir());val owner=Any();write(c,request(c,owner))
        val view=request(c,owner,meta=JSONObject().put("zoom",2));assertFalse(view.requiresBody)
        c.publish(view) {fail("Must not encode a view-only update")};assertEquals(body(),text(c))
        assertTrue(request(c,Any()).requiresBody)
        assertTrue(request(c,owner,revision=1).requiresBody)
        assertTrue(request(c,owner,docId=UUID.randomUUID().toString()).requiresBody)
        assertTrue(request(c,owner,kind=WorkingSessionCache.Kind.CANVAS).requiresBody)
    }
    @Test fun failedBodyAndFailedHeadRetainOldBodyAndRequireTheFailedRevisionAgain() {
        val root=dir();var failHead=false
        val c=cache(root) {e,_->if(failHead && e==WorkingSessionCache.Event.BEFORE_HEAD_REPLACE) throw java.io.IOException("head blocked")}
        val owner=Any();write(c,request(c,owner),body(text="old"))
        val bad=request(c,owner,1);rejects {c.publish(bad) {it.write("partial".toByteArray());throw java.io.IOException("body blocked")}}
        assertEquals(body(text="old"),text(c));assertTrue(request(c,owner,1).requiresBody)
        failHead=true;rejects {write(c,request(c,owner,1),body(text="new"))};failHead=false
        assertEquals(body(text="old"),text(c));assertTrue(request(c,owner,1).requiresBody)
        write(c,request(c,owner,1),body(text="new"));assertEquals(body(text="new"),text(c))
    }
    @Test fun invalidBodyAndCancellationDoNotPublishMetadata() {
        val c=cache(dir());write(c,request(c),body(text="old"))
        rejects {write(c,request(c),"not the requested document")};assertEquals(body(text="old"),text(c))
        try {c.publish(request(c),checkActive={throw CancellationException()}) {fail("must not encode")};fail("must cancel")} catch(_:CancellationException) {}
        assertEquals(body(text="old"),text(c))
    }
    @Test fun latestReservationWinsEvenWhenOldIoFinishesLastAndReserveDoesNotWaitForIo() {
        val root=dir();val c=cache(root);val other=cache(root);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val old=request(c);val oldResult=AtomicReference<WorkingSessionCache.Publication>();val error=AtomicReference<Throwable>()
        val thread=Thread {try {oldResult.set(c.publish(old) {entered.countDown();check(release.await(5,TimeUnit.SECONDS));it.write(body(text="old").toByteArray())})} catch(e:Throwable) {error.set(e)}}
        thread.start();assertTrue(entered.await(5,TimeUnit.SECONDS))
        val latest=request(other);write(other,latest,body(text="latest"));release.countDown();thread.join(5000)
        assertFalse(thread.isAlive);assertNull(error.get());assertFalse(oldResult.get().published)
        assertEquals(body(text="latest"),text(c));assertFalse(c.isCurrent(old))
    }
    @Test fun validatorMayReserveANewerRequestWithoutDeadlockOrStaleCommit() {
        val root=dir();lateinit var c:WorkingSessionCache;var invalidate=false
        c=WorkingSessionCache(root,validateBody={file,kind,docId->
            require(file.readText().startsWith("${kind.name}:$docId:"))
            if(invalidate) {invalidate=false;request(c)}
        })
        write(c,request(c),body(text="old"));invalidate=true
        val r=request(c);assertFalse(write(c,r,body(text="new")).published)
        assertEquals(body(text="old"),text(c))
    }
    @Test fun clearingInvalidatesOldRequestsAndTombstoneSurvivesNewInstance() {
        val root=dir();val c=cache(root);write(c,request(c))
        val old=request(c);val clear=c.reserveClear();val result=c.publishClear(clear)
        assertTrue(result.published);assertSame(clear,result.request);assertNull(cache(root).load())
        assertFalse(write(c,old).published);assertNull(c.load())
        val newer=request(c);assertFalse(c.publishClear(clear).published);write(c,newer);assertNotNull(c.load()?.use {it.documentId})
    }
    @Test fun failedClearStillRejectsOlderWriterAndKeepsPreviousCompleteSession() {
        val root=dir();var fail=false;val c=cache(root) {e,_->if(fail && e==WorkingSessionCache.Event.BEFORE_HEAD_REPLACE) throw java.io.IOException("clear failed")}
        write(c,request(c),body(text="old"));val old=request(c);val clear=c.reserveClear();fail=true
        rejects {c.publishClear(clear)};fail=false;assertFalse(write(c,old).published)
        assertEquals(body(text="old"),text(c))
    }
    @Test fun corruptOrFutureHeadCannotBePublishedOverOrCleared() {
        for(raw in listOf("broken json","{\"version\":99,\"state\":\"cleared\"}")) {
            val root=dir();val c=cache(root);write(c,request(c));val head=File(root,"head.json");head.writeText(raw)
            val before=root.listFiles()!!.associate {it.name to it.readBytes().toList()}
            rejects {c.load()};rejects {write(c,request(c))};rejects {c.publishClear(c.reserveClear())}
            assertEquals(before,root.listFiles()!!.associate {it.name to it.readBytes().toList()})
        }
    }
    @Test fun bodyTamperingOrMissingBodyDoesNotBecomeAnEmptySession() {
        for(delete in listOf(false,true)) {
            val root=dir();val c=cache(root);write(c,request(c));val file=c.load()!!.use {it.body}
            if(delete) file.delete() else file.appendText("tampered")
            val head=File(root,"head.json").readText();rejects {c.load()};rejects {write(c,request(c))}
            rejects {c.publishClear(c.reserveClear())};assertEquals(head,File(root,"head.json").readText())
        }
    }
    @Test fun headCannotEscapeItsOwnedDirectoryOrPointAtASymlink() {
        val root=dir();val c=cache(root);write(c,request(c));val head=File(root,"head.json")
        val json=JSONObject(head.readText());json.put("body","../outside.zip");head.writeText(json.toString());rejects {c.load()}
        val other=dir();val target=File(other,"private").also {it.writeText(body())}
        val c2=cache(dir());write(c2,request(c2));val inside=c2.load()!!.use {it.body};inside.delete();Files.createSymbolicLink(inside.toPath(),target.toPath())
        rejects {c2.load()};assertEquals(body(),target.readText())
    }
    @Test fun externalHeadChangeDuringEncodingIsNotOverwritten() {
        val root=dir();val c=cache(root);write(c,request(c));val head=File(root,"head.json")
        rejects {c.publish(request(c)) {it.write(body().toByteArray());head.writeText("foreign state")}}
        assertEquals("foreign state",head.readText())
    }
    @Test fun bodyValidationCannotMutateCandidateAndStillPublish() {
        val root=dir();var tamper=false
        val c=WorkingSessionCache(root,validateBody={file,_,_->if(tamper && file.name.endsWith(".pending")) file.appendText("changed")})
        write(c,request(c),body(text="old"));tamper=true
        rejects {write(c,request(c),body(text="new"))};tamper=false
        assertEquals(body(text="old"),text(c))
    }
    @Test fun loadLeaseKeepsOldBodyAliveAcrossPublishAndClearUntilReleased() {
        val root=dir();val c=cache(root);write(c,request(c),body(text="old"));val lease=c.load()!!;val old=lease.body
        write(c,request(c),body(text="new"));assertTrue(old.isFile);assertEquals(body(text="old"),old.readText())
        c.publishClear(c.reserveClear());assertTrue(old.isFile);lease.close();assertFalse(old.exists());lease.close()
    }
    @Test fun cleanupFailureDoesNotTurnACommittedSessionIntoAFailure() {
        val root=dir();var fail=false;val c=cache(root) {e,_->if(fail && e==WorkingSessionCache.Event.BEFORE_DELETE) throw java.io.IOException("busy")}
        write(c,request(c),body(text="old"));val old=c.load()!!.use {it.body};fail=true
        val result=write(c,request(c),body(text="new"));assertTrue(result.published);assertEquals(body(text="new"),text(c));assertTrue(old.exists())
        fail=false;c.load()!!.close();assertFalse(old.exists())
    }
    @Test fun invalidRequestsAreRejectedBeforeSupersedingAValidRequest() {
        val c=cache(dir());val r=request(c)
        rejects {request(c,docId="bad-id")};rejects {request(c,revision=-1)}
        rejects {request(c,meta=JSONObject().put("oversized","x".repeat(65536)))}
        assertTrue(c.isCurrent(r));assertTrue(write(c,r).published)
    }
    @Test fun aBlockedValidatorDoesNotBlockAReservationInAnotherThread() {
        val root=dir();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val c=WorkingSessionCache(root,validateBody={file,_,_->if(file.name.endsWith(".pending")) {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}})
        val old=request(c);val result=AtomicReference<WorkingSessionCache.Publication>();val error=AtomicReference<Throwable>()
        val writer=Thread {try {result.set(write(c,old))} catch(e:Throwable) {error.set(e)}}.apply {start()}
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        val reserve=java.util.concurrent.FutureTask {request(c)};Thread(reserve).start()
        val latest=try {reserve.get(1,TimeUnit.SECONDS)} finally {release.countDown()}
        writer.join(5000);assertFalse(writer.isAlive);assertNull(error.get());assertFalse(result.get().published)
        assertTrue(c.isCurrent(latest));assertTrue(write(c,latest).published)
    }
    @Test fun aLoadLeaseIsAcquiredBeforeValidatorRunsAndSurvivesConcurrentPublication() {
        val root=dir();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val c=WorkingSessionCache(root,validateBody={_,_,_->if(Thread.currentThread().name=="blocked-session-load") {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}})
        write(c,request(c),body(text="old"));val old=c.load()!!.use {it.body}
        val result=AtomicReference<WorkingSessionCache.Read>();val error=AtomicReference<Throwable>()
        val reader=Thread({try {result.set(c.load())} catch(e:Throwable) {error.set(e)}},"blocked-session-load").apply {start()}
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        try {write(c,request(c),body(text="new"));assertTrue(old.exists())} finally {release.countDown()}
        reader.join(5000);assertFalse(reader.isAlive);assertNull(error.get())
        assertEquals(body(text="old"),result.get().body.readText());result.get().close();assertFalse(old.exists())
        assertEquals(body(text="new"),text(c))
    }
    @Test fun failedLoadValidationReleasesItsLease() {
        val root=dir();var invalid=false;val c=WorkingSessionCache(root,validateBody={_,_,_->require(!invalid)})
        write(c,request(c));val old=c.load()!!.use {it.body};invalid=true;rejects {c.load()};invalid=false
        write(c,request(c),body(text="new"));assertFalse(old.exists())
    }
    @Test fun largeBodyViewRefreshKeepsOneBodyWithoutReencodingAndEncoderMayCloseItsStream() {
        val root=dir();val c=cache(root);val owner=Any();val large=body(text="z".repeat(2*1024*1024))
        c.publish(request(c,owner)) {out->out.use {it.write(large.toByteArray())}}
        val old=c.load()!!.use {it.body}
        val r=request(c,owner,meta=JSONObject().put("scrollY",1234));assertFalse(r.requiresBody)
        c.publish(r) {fail("No second body encoding")}
        c.load()!!.use {assertEquals(old,it.body);assertEquals(large,it.body.readText());assertEquals(1234,JSONObject(it.metadataJson).getInt("scrollY"))}
        assertEquals(1,root.listFiles()!!.count {it.extension=="zip"})
    }
    @Test fun noteAndCanvasUseOneHeadAndCanonicalDirectoryCoordinator() {
        val root=dir();val a=cache(root);val alias=cache(File(root,"child/.."))
        val old=request(a);val canvas=request(alias,kind=WorkingSessionCache.Kind.CANVAS)
        assertTrue(write(alias,canvas).published);assertFalse(write(a,old).published)
        a.load()!!.use {assertEquals(WorkingSessionCache.Kind.CANVAS,it.kind);assertEquals(body(WorkingSessionCache.Kind.CANVAS),it.body.readText())}
    }

    @Test fun changedHeadCandidateCannotReplaceThePreviousCompleteHead() {
        val root=dir();var tamper=false
        val c=cache(root) {event,file->if(tamper && event==WorkingSessionCache.Event.BEFORE_HEAD_REPLACE) file.writeText("broken json")}
        write(c,request(c),body(text="old"));val before=File(root,"head.json").readBytes().toList()
        tamper=true;rejects {write(c,request(c),body(text="new"))}
        assertEquals(before,File(root,"head.json").readBytes().toList());tamper=false;assertEquals(body(text="old"),text(c))
        tamper=true;rejects {c.publishClear(c.reserveClear())}
        assertEquals(before,File(root,"head.json").readBytes().toList());tamper=false;assertEquals(body(text="old"),text(c))
    }

    @Test fun validForeignHeadBeforePublishIsNotAcceptedAsANewBaseline() {
        for(clear in listOf(false,true)) {
            val root=dir();val c=cache(root);write(c,request(c),body(text="old"))
            val old=c.load()!!.use {it.body};val head=File(root,"head.json")
            val r=request(c,revision=1);val cr=if(clear) c.reserveClear() else null
            val foreign=JSONObject(head.readText()).put("metadata",JSONObject().put("path","foreign")).toString();head.writeText(foreign)
            rejects {if(cr!=null) c.publishClear(cr) else write(c,r,body(text="new"))}
            assertEquals(foreign,head.readText());assertTrue(old.exists())
        }
    }
    @Test fun loadedAndClearedHeadProofsAlsoRejectUnknownChanges() {
        val source=dir();val original=cache(source);write(original,request(original))
        val root=dir();source.listFiles()!!.forEach {it.copyTo(File(root,it.name))}
        val loaded=cache(root);loaded.load()!!.close() // This coordinator has never published a body.
        val head=File(root,"head.json");val foreign=JSONObject(head.readText()).put("extra","foreign").toString();head.writeText(foreign)
        rejects {write(loaded,request(loaded))};assertEquals(foreign,head.readText())
        val clearedRoot=dir();val cleared=cache(clearedRoot);cleared.publishClear(cleared.reserveClear())
        val clearedHead=File(clearedRoot,"head.json");val changed=JSONObject(clearedHead.readText()).put("extra",true).toString();clearedHead.writeText(changed)
        rejects {cleared.publishClear(cleared.reserveClear())};assertEquals(changed,clearedHead.readText())
    }

    @Test fun foreignValidHeadDuringFailedPublicationDoesNotAuthorizeDeletingOldBodies() {
        for(tombstone in listOf(false,true)) {
            val root=dir();var foreign:ByteArray?=null
            val c=cache(root) {e,_->if(e==WorkingSessionCache.Event.BEFORE_HEAD_REPLACE) foreign?.let {File(root,"head.json").writeBytes(it)}}
            write(c,request(c),body(text="old"));val old=c.load()!!.use {it.body}
            if(tombstone) foreign="{\"version\":1,\"state\":\"cleared\"}".toByteArray()
            else {
                val anotherRoot=dir();val another=cache(anotherRoot);write(another,request(another),body(text="foreign"))
                another.load()!!.use {it.body.copyTo(File(root,it.body.name))}
                foreign=File(anotherRoot,"head.json").readBytes()
            }
            rejects {write(c,request(c),body(text="new"))}
            assertTrue(old.isFile);assertEquals(body(text="old"),old.readText())
            assertArrayEquals(foreign,File(root,"head.json").readBytes())
        }
    }

    @Test fun malformedUtf8HeadIsRejectedWithoutReplacingItsBytes() {
        val root=dir();val c=cache(root);write(c,request(c,meta=JSONObject().put("path","marker")))
        val file=File(root,"head.json");val bytes=file.readBytes();val at=String(bytes,Charsets.UTF_8).indexOf("marker")
        assertTrue(at>0);bytes[at]=0x80.toByte();file.writeBytes(bytes)
        // Seed another coordinator so this checks decoding rather than an in-memory SHA conflict.
        val otherRoot=dir();root.listFiles()!!.forEach {it.copyTo(File(otherRoot,it.name))}
        val other=cache(otherRoot);rejects {other.load()}
        assertArrayEquals(bytes,File(otherRoot,"head.json").readBytes())
    }
    @Test fun corruptionOfCurrentBodyDoesNotAuthorizeCollectingAnOlderReadLease() {
        val root=dir();val c=cache(root);write(c,request(c),body(text="old"));val old=c.load()!!
        write(c,request(c),body(text="new"));val current=c.load()!!
        current.body.appendText("corrupted");current.close();old.close()
        assertTrue(old.body.isFile);assertEquals(body(text="old"),old.body.readText());rejects {c.load()}
    }

}
