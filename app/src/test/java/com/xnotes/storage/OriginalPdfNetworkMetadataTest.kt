package com.xnotes.storage

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import com.xnotes.core.model.ReferenceAnchors
import com.xnotes.core.geometry.Pt
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.mindmap.MapSourceTarget
import com.xnotes.mindmap.PdfOriginalProjection
import com.xnotes.mindmap.SourceScope
import com.xnotes.platform.AndroidRasterSurface
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real on-disk registry opened read-only; payload bytes are deliberately not parsed as PDFs. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class OriginalPdfNetworkMetadataTest {
    private class Fixture(val root:File) {
        val privateDir=File(root,"private")
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir()=privateDir
        }
        val registry=DocumentRegistry(context)
        val catalog=OriginalPdfCatalog(registry)
        val dbFile=File(privateDir,"document-registry.db")
        fun warm() {privateDir.mkdirs();registry.writableDatabase}
        data class Seed(val id:String,val sha:String,val pages:List<String>,val file:File)
        fun seed(count:Int=2,deleted:Boolean=false,honestDigest:Boolean=false):Seed {
            warm();val id=UUID.randomUUID().toString()
            val bytes="not a decoded PDF; metadata may only stat this file".toByteArray()
            val sha=if(honestDigest) MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
                else UUID.randomUUID().toString().replace("-","").repeat(2)
            val file=File(privateDir,"ai-pdfs/$sha.pdf").apply {parentFile.mkdirs();writeBytes(bytes)}
            val db=registry.writableDatabase
            db.execSQL("INSERT INTO resources(id,name,path,size,digest,version,owned,created) VALUES(?,?,?,?,?,1,1,0)",arrayOf(sha,"Original",file.path,file.length(),sha))
            db.execSQL("INSERT INTO original_pdf_documents VALUES(?,?,?, ?,1,?,1)",arrayOf(id,sha,"Original",count,if(deleted) 1 else 0))
            val pages=List(count) {UUID.randomUUID().toString()}
            pages.forEachIndexed {i,p->db.execSQL("INSERT INTO original_pdf_pages VALUES(?,?,?)",arrayOf(id,i,p))}
            if(!deleted) db.execSQL("INSERT INTO resource_refs VALUES(?,?)",arrayOf(OriginalPdfCatalog.owner(id),sha))
            return Seed(id,sha,pages,file)
        }
        fun target(s:Seed,index:Int=0)=OriginalPdfCatalog.MetadataTarget(s.id,s.pages[index],s.sha,index)
        fun fingerprint():Map<String,String> = if(!privateDir.exists()) emptyMap() else privateDir.walkTopDown().filter {it.isFile}.associate {
            it.relativeTo(privateDir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") {b->"%02x".format(b)}
        }
    }
    private fun scenario(block:(Fixture)->Unit) {
        val f=Fixture(Files.createTempDirectory("original-network-").toFile())
        try {block(f)} finally {f.registry.close();f.root.deleteRecursively()}
    }
    @Test fun coldMissingDatabaseAndAccessDomainProbeCreateNothing()=scenario {f->
        val id=UUID.randomUUID().toString();assertFalse(f.privateDir.exists())
        val domain=f.catalog.identityDomains(setOf(id));val batch=f.catalog.networkMetadata(setOf(id),orphanLimit=2)
        assertTrue(domain.supported);assertTrue(domain.current());assertTrue(domain.reservedOriginalIds.isEmpty())
        assertEquals(OriginalPdfCatalog.MetadataState.ABSENT,batch.files.getValue(id).state)
        val storage=NotebookStorage(f.context)
        OriginalPdfAccess(f.context,storageOverride=storage).identityDomains(setOf(id))
        assertFalse(f.privateDir.exists());storage.registry.close()
    }
    @Test fun oldAndFutureColdSchemasAreNotMigratedOrWritten()=scenario {f->
        f.warm();f.registry.close()
        SQLiteDatabase.openDatabase(f.dbFile.path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            db.execSQL("DROP TABLE original_pdf_pages");db.execSQL("DROP TABLE original_pdf_documents");db.version=2
        }
        val id=UUID.randomUUID().toString();val before=f.fingerprint()
        val old=f.catalog.networkMetadata(setOf(id),orphanLimit=2)
        assertTrue(old.supported);assertEquals(OriginalPdfCatalog.MetadataState.ABSENT,old.files.getValue(id).state)
        assertEquals(before,f.fingerprint());assertFalse(File(f.privateDir,"ai-pdfs").exists())
        SQLiteDatabase.openDatabase(f.dbFile.path,null,SQLiteDatabase.OPEN_READWRITE).use {it.version=4}
        val futureBefore=f.fingerprint();val future=f.catalog.identityDomains(setOf(id))
        assertFalse(future.supported);assertTrue(future.unverifiable);assertEquals(futureBefore,f.fingerprint())
        SQLiteDatabase.openDatabase(f.dbFile.path,null,SQLiteDatabase.OPEN_READONLY).use {assertEquals(4,it.version)}
    }
    @Test fun metadataReadsOnlyBoundedFactsAndPointChecksWithoutParsingOrHashingThePayload()=scenario {f->
        val s=f.seed();val point=f.target(s);val before=f.fingerprint()
        val result=f.catalog.networkMetadata(setOf(s.id),setOf(point))
        val fact=result.files.getValue(s.id);assertEquals(OriginalPdfCatalog.MetadataState.VALID,fact.state)
        assertEquals(s.pages[0],fact.header!!.firstPageId);assertEquals(2,fact.header!!.pageCount)
        assertEquals(OriginalPdfCatalog.ResourcePresence.PRESENT_HINT,fact.presence)
        assertEquals(OriginalPdfCatalog.TargetCheck.MATCHED,result.targetChecks[point]);assertTrue(result.current())
        assertEquals(before,f.fingerprint()) // SHA in the row intentionally differs from actual bytes.
        f.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
    }
    @Test fun deletedOriginalRemainsValidAfterRealResourceCollectionAndMissingFilesRemainKnown()=scenario {f->
        val active=f.seed();active.file.delete()
        val a=f.catalog.networkMetadata(setOf(active.id)).files.getValue(active.id)
        assertEquals(OriginalPdfCatalog.MetadataState.VALID,a.state);assertEquals(OriginalPdfCatalog.ResourcePresence.MISSING,a.presence)
        val gone=f.seed(deleted=true)
        assertEquals(1,f.registry.collect(System.currentTimeMillis()+8L*24*60*60*1000,7L*24*60*60*1000,File(f.privateDir,"ai-pdfs")))
        assertNull(f.registry.resource(gone.sha));assertFalse(gone.file.exists())
        val b=f.catalog.networkMetadata(setOf(gone.id)).files.getValue(gone.id)
        assertEquals(OriginalPdfCatalog.MetadataState.VALID,b.state);assertTrue(b.header!!.deleted)
        assertEquals(OriginalPdfCatalog.ResourcePresence.MISSING,b.presence)
    }
    @Test fun pageAndOrphanBudgetsAreGlobalAndExtraRawRowsAreBoundedCorruption()=scenario {f->
        val a=f.seed(2);val b=f.seed(2)
        val limited=f.catalog.networkMetadata(setOf(a.id,b.id),pageBudget=2)
        assertEquals(1,limited.files.values.count {it.state==OriginalPdfCatalog.MetadataState.VALID})
        assertEquals(1,limited.files.values.count {it.state==OriginalPdfCatalog.MetadataState.LIMITED});assertTrue(limited.limited)
        val orphan=f.catalog.networkMetadata(emptySet(),orphanLimit=1)
        assertEquals(1,orphan.orphans.size);assertTrue(orphan.limited)
        f.registry.writableDatabase.execSQL("INSERT INTO original_pdf_pages VALUES(?,?,?)",arrayOf(a.id,2000000,UUID.randomUUID().toString()))
        val broken=f.catalog.networkMetadata(setOf(a.id),pageBudget=2)
        assertEquals(OriginalPdfCatalog.MetadataState.CORRUPT,broken.files.getValue(a.id).state)
    }
    private fun embeddedNulPage(index:Int)=scenario {f->
        val bad=f.seed();val good=f.seed()
        val invalid=bad.pages[index]+0.toChar()+"suffix"
        f.registry.writableDatabase.execSQL("UPDATE original_pdf_pages SET page_id=? WHERE document_id=? AND ordinal=?",
            arrayOf(invalid,bad.id,index))
        val before=f.fingerprint()
        val result=f.catalog.networkMetadata(setOf(bad.id,good.id))
        assertEquals(OriginalPdfCatalog.MetadataState.VALID,result.files.getValue(good.id).state)
        assertEquals(OriginalPdfCatalog.MetadataState.CORRUPT,result.files.getValue(bad.id).state)
        assertNull(result.files.getValue(bad.id).header)
        assertEquals(before,f.fingerprint())
        assertEquals(setOf(bad.id,good.id),result.reservedOriginalIds)
    }
    @Test fun embeddedNulInTheFirstPageCannotBecomeACanonicalInitialAnchor()=embeddedNulPage(0)
    @Test fun embeddedNulInALaterPageCannotPassTheWholeDirectoryCheck()=embeddedNulPage(1)
    @Test fun oversizedRawTitleIsIsolatedFromAHealthyOriginalAndKeepsItsReservation() {
        for(nulPrefix in listOf(false,true)) scenario {f->
            val bad=f.seed();val good=f.seed()
            val invalid=(if(nulPrefix) "Original"+0.toChar() else "")+"x".repeat(3*1024*1024)
            f.registry.writableDatabase.execSQL("UPDATE original_pdf_documents SET title=? WHERE id=?",arrayOf(invalid,bad.id))
            val before=f.fingerprint();val result=f.catalog.networkMetadata(setOf(bad.id,good.id))
            assertEquals(OriginalPdfCatalog.MetadataState.CORRUPT,result.files.getValue(bad.id).state)
            assertEquals(OriginalPdfCatalog.MetadataState.VALID,result.files.getValue(good.id).state)
            assertEquals(setOf(bad.id,good.id),result.reservedOriginalIds)
            assertEquals(before,f.fingerprint());assertEquals(setOf(bad.sha),f.registry.referenceIds(OriginalPdfCatalog.owner(bad.id)))
        }
    }
    @Test fun aShortTitleWithAnEmbeddedNulPreservesTheExistingTitleContract()=scenario {f->
        val seed=f.seed();val title="A"+0.toChar()+"B"
        f.registry.writableDatabase.execSQL("UPDATE original_pdf_documents SET title=? WHERE id=?",arrayOf(title,seed.id))
        val before=f.fingerprint();val result=f.catalog.networkMetadata(setOf(seed.id)).files.getValue(seed.id)
        assertEquals(OriginalPdfCatalog.MetadataState.VALID,result.state);assertEquals(title,result.header!!.title)
        assertEquals(before,f.fingerprint())
    }
    @Test fun aLegal4096CharacterChineseTitleKeepsTheExistingUtf16Limit()=scenario {f->
        val seed=f.seed();val title="原".repeat(4096)
        f.registry.writableDatabase.execSQL("UPDATE original_pdf_documents SET title=? WHERE id=?",arrayOf(title,seed.id))
        val result=f.catalog.networkMetadata(setOf(seed.id)).files.getValue(seed.id)
        assertEquals(OriginalPdfCatalog.MetadataState.VALID,result.state);assertEquals(title,result.header!!.title)
    }
    @Test fun oversizedResourcePathAndPinTextRemainIsolatedAndAreNeverCleared()=scenario {f->
        val path=f.seed();val pin=f.seed();val good=f.seed();val huge="x".repeat(3*1024*1024)
        f.registry.writableDatabase.execSQL("UPDATE resources SET path=? WHERE id=?",arrayOf("/"+huge,path.sha))
        val digest=UUID.randomUUID().toString().replace("-","").repeat(2)
        f.registry.writableDatabase.execSQL("INSERT INTO resources(id,name,path,size,digest,version,owned,created) VALUES(?,'Unknown protection',?,1,?,1,1,0)",
            arrayOf(huge,File(f.privateDir,"ai-pdfs/unknown.pdf").path,digest))
        f.registry.writableDatabase.execSQL("INSERT INTO resource_refs VALUES(?,?)",arrayOf(OriginalPdfCatalog.owner(pin.id),huge))
        val before=f.fingerprint();val result=f.catalog.networkMetadata(setOf(path.id,pin.id,good.id))
        assertEquals(OriginalPdfCatalog.MetadataState.CORRUPT,result.files.getValue(path.id).state)
        assertEquals(OriginalPdfCatalog.MetadataState.CORRUPT,result.files.getValue(pin.id).state)
        assertEquals(OriginalPdfCatalog.MetadataState.VALID,result.files.getValue(good.id).state)
        assertEquals(setOf(path.id,pin.id,good.id),result.reservedOriginalIds);assertEquals(before,f.fingerprint())
        f.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM resource_refs WHERE owner=?",arrayOf(OriginalPdfCatalog.owner(pin.id))).use {
            assertTrue(it.moveToFirst());assertEquals(2,it.getInt(0))
        }
    }
    @Test fun malformedAndFutureRowsReserveTheirIdentityAndDoNotModifyPins()=scenario {f->
        val bad=f.seed();val future=f.seed()
        f.registry.writableDatabase.execSQL("UPDATE original_pdf_documents SET page_count=4294967298 WHERE id=?",arrayOf(bad.id))
        f.registry.writableDatabase.execSQL("UPDATE original_pdf_documents SET row_schema=99 WHERE id=?",arrayOf(future.id))
        val before=f.fingerprint();val result=f.catalog.networkMetadata(setOf(bad.id,future.id))
        assertEquals(OriginalPdfCatalog.MetadataState.CORRUPT,result.files.getValue(bad.id).state)
        assertEquals(OriginalPdfCatalog.MetadataState.UNSUPPORTED,result.files.getValue(future.id).state)
        assertEquals(setOf(bad.id,future.id),result.reservedOriginalIds);assertEquals(before,f.fingerprint())
        val domains=f.catalog.identityDomains(setOf(bad.id,future.id));assertEquals(setOf(bad.id,future.id),domains.reservedOriginalIds)
    }
    @Test fun descriptorMismatchIsIndependentAndNeverFallsBackToTheOrdinal()=scenario {f->
        val s=f.seed();val good=f.target(s);val wrong=good.copy(pageIndex=1);val absent=good.copy(pageId=UUID.randomUUID().toString())
        val result=f.catalog.networkMetadata(setOf(s.id),setOf(good,wrong,absent))
        assertEquals(OriginalPdfCatalog.TargetCheck.MATCHED,result.targetChecks[good])
        assertEquals(OriginalPdfCatalog.TargetCheck.DESCRIPTOR_MISMATCH,result.targetChecks[wrong])
        assertEquals(OriginalPdfCatalog.TargetCheck.PAGE_NOT_REGISTERED,result.targetChecks[absent])
    }
    @Test fun mutationStartRollbackAndNestedTokensRetireTheOriginalMetadataTicket()=scenario {f->
        val s=f.seed();val before=f.catalog.networkMetadata(setOf(s.id))
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val bad=OriginalPdfCatalog(f.registry) {if(it=="delete-record") {entered.countDown();check(release.await(10,TimeUnit.SECONDS));error("rollback")}}
        val expected=f.catalog.observe(s.id)!!
        val worker=Thread {runCatching {bad.delete(expected)}};worker.start()
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS));assertFalse(before.current());assertTrue(f.catalog.networkEpoch.value.busy)
            val nested=f.catalog.beginNetworkMutation();nested.close();assertTrue(f.catalog.networkEpoch.value.busy)
        } finally {release.countDown();worker.join(10000)}
        assertFalse(worker.isAlive);assertFalse(f.catalog.networkEpoch.value.busy);assertFalse(before.current())
        assertFalse(f.catalog.networkMetadata(setOf(s.id)).files.getValue(s.id).header!!.deleted)
    }
    @Test fun oneMaximumLegalOriginalFitsTheDefaultAggregateBudgetWithoutMaterializingPageUuids()=scenario {f->
        val seed=f.seed(1);val db=f.registry.writableDatabase
        db.execSQL("DELETE FROM original_pdf_pages WHERE document_id=?",arrayOf(seed.id))
        db.execSQL("UPDATE original_pdf_documents SET page_count=? WHERE id=?",arrayOf(ReferenceAnchors.MAX_TARGETS,seed.id))
        db.execSQL("WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i+1 FROM n WHERE i+1<?) "+
            "INSERT INTO original_pdf_pages(document_id,ordinal,page_id) SELECT ?,i,printf('%08x-0000-0000-0000-000000000000',i) FROM n",
            arrayOf(ReferenceAnchors.MAX_TARGETS,seed.id))
        val result=f.catalog.networkMetadata(setOf(seed.id))
        val header=result.files.getValue(seed.id).header!!
        assertEquals(OriginalPdfCatalog.MetadataState.VALID,result.files.getValue(seed.id).state)
        assertEquals(ReferenceAnchors.MAX_TARGETS,header.pageCount);assertEquals("00000000-0000-0000-0000-000000000000",header.firstPageId)
        assertFalse(result.limited)
    }
    @Test fun badPinAndBadRegisteredPathDoNotPoisonOtherMetadataOrClearAnyProtection()=scenario {f->
        val good=f.seed();val extra=f.seed();val outside=f.seed()
        f.registry.writableDatabase.execSQL("INSERT INTO resource_refs VALUES(?,?)",arrayOf(OriginalPdfCatalog.owner(extra.id),good.sha))
        f.registry.writableDatabase.execSQL("UPDATE resources SET path=? WHERE id=?",arrayOf(File(f.root,"external-untouched.pdf").path,outside.sha))
        val before=f.fingerprint();val result=f.catalog.networkMetadata(setOf(good.id,extra.id,outside.id))
        assertEquals(OriginalPdfCatalog.MetadataState.VALID,result.files.getValue(good.id).state)
        assertEquals(OriginalPdfCatalog.MetadataState.CORRUPT,result.files.getValue(extra.id).state)
        assertEquals(OriginalPdfCatalog.MetadataState.CORRUPT,result.files.getValue(outside.id).state)
        assertEquals(before,f.fingerprint())
        val orphans=f.catalog.networkMetadata(emptySet(),orphanLimit=10)
        assertEquals(setOf(good.id),orphans.orphans.map {it.header!!.id}.toSet())
        assertEquals(setOf(extra.id,outside.id),orphans.issues.mapNotNull {it.documentId}.toSet())
    }
    private class Engine(private val count:Int,val closed:AtomicInteger,val onInfo:()->Unit={}):OriginalPdfEngine {
        override val pageCount get()=count
        override fun pageInfo(pdfKey:Int):OriginalPdfPageInfo {onInfo();return OriginalPdfPageInfo(Pt(600.0,800.0),PdfPageGeometry.flipped(800.0))}
        override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):AndroidRasterSurface?=null
        override fun closeAndAwait() {closed.incrementAndGet()}
    }
    private fun actualTarget(seed:Fixture.Seed)=MapSourceTarget(seed.id,SourceScope.PDF_ORIGINAL,seed.pages[0],
        originalPdf=PdfOriginalProjection(seed.sha,0))
    @Test fun actualOriginalAccessRejectsNativeCollisionsAndRetiresAnAlreadyOpenedReader()=scenario {f->
        val seed=f.seed(honestDigest=true);val storage=NotebookStorage(f.context);val opens=AtomicInteger();val closes=AtomicInteger()
        val access=OriginalPdfAccess(f.context,{opens.incrementAndGet();Engine(2,closes)},storage)
        val target=actualTarget(seed);val read=access.open(target);assertTrue(read.current())
        f.registry.register(seed.id,"xnote","collision","content://example/document/native")
        assertFalse(read.current());val before=opens.get()
        assertThrows(IllegalStateException::class.java) {access.open(target)};assertEquals(before,opens.get())
        val candidate=access.candidate(seed.file,"Original")
        try {assertThrows(IllegalStateException::class.java) {f.catalog.retain(candidate)}} finally {candidate.closeAndAwait()}
        read.closeAndAwait();assertEquals(opens.get(),closes.get());storage.registry.close()
    }
    @Test fun collisionIntroducedDuringActualOriginalReadClosesTheEngineAndDoesNotIssueAReader()=scenario {f->
        val seed=f.seed(honestDigest=true);val storage=NotebookStorage(f.context);val opens=AtomicInteger();val closes=AtomicInteger()
        val access=OriginalPdfAccess(f.context,{opens.incrementAndGet();Engine(2,closes) {
            f.registry.register(seed.id,"xcanvas","late collision","content://example/document/native")
        }},storage)
        assertThrows(IllegalStateException::class.java) {access.open(actualTarget(seed))}
        assertEquals(1,opens.get());assertEquals(opens.get(),closes.get())
        f.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases",null).use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
        storage.registry.close()
    }
    @Test fun relinkRemainsBusyDuringTheActualResourceIoAndNestedTokensCannotEndItEarly()=scenario {f->
        val seed=f.seed(honestDigest=true);val storage=NotebookStorage(f.context);val closes=AtomicInteger()
        val access=OriginalPdfAccess(f.context,{Engine(2,closes)},storage)
        val expected=f.catalog.observe(seed.id)!!;val before=f.catalog.networkMetadata(setOf(seed.id))
        val input=File(f.root,"relink-source.pdf").apply {writeBytes(seed.file.readBytes())}
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val blocked=AtomicBoolean()
        val error=AtomicReference<Throwable?>()
        val worker=Thread {
            try {access.relink(input,expected,"Original") {
                if(!expected.current() && blocked.compareAndSet(false,true)) {entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
            }.closeAndAwait()} catch(e:Throwable) {error.set(e)}
        };worker.start()
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS));assertFalse(before.current());assertTrue(f.catalog.networkEpoch.value.busy)
            val second=OriginalPdfCatalog(storage.registry).beginNetworkMutation();second.close()
            assertTrue(f.catalog.networkEpoch.value.busy)
            assertThrows(IllegalStateException::class.java) {f.catalog.networkMetadata(setOf(seed.id))}
        } finally {release.countDown();worker.join(10000)}
        assertFalse(worker.isAlive);error.get()?.let {throw AssertionError("Actual relink failed",it)}
        assertFalse(f.catalog.networkEpoch.value.busy);assertFalse(before.current());assertEquals(1,closes.get())
        storage.registry.close()
    }
    @Test fun failedResourceRelinkAlwaysAnnouncesEndWithoutRevivingTheOldMetadataTicket()=scenario {f->
        val seed=f.seed(honestDigest=true);val storage=NotebookStorage(f.context)
        val access=OriginalPdfAccess(f.context,{error("Wrong bytes must fail before engine construction")},storage)
        val expected=f.catalog.observe(seed.id)!!;val before=f.catalog.networkMetadata(setOf(seed.id))
        val wrong=File(f.root,"wrong.pdf").apply {writeText("different original")}
        assertThrows(IllegalArgumentException::class.java) {access.relink(wrong,expected,"Original")}
        assertFalse(f.catalog.networkEpoch.value.busy);assertFalse(before.current())
        assertEquals(setOf(seed.sha),f.registry.referenceIds(OriginalPdfCatalog.owner(seed.id)))
        storage.registry.close()
    }
    @Test fun externallyOwnedSqlTransactionIsRejectedBeforeStartingAnyNotificationOrIdentityWrite()=scenario {f->
        val s=f.seed();val before=f.catalog.networkMetadata(setOf(s.id));val version=f.catalog.networkEpoch.value
        val db=f.registry.writableDatabase;db.beginTransaction()
        try {
            assertThrows(IllegalStateException::class.java) {f.registry.register(s.id,"xnote","external transaction","content://example/document/ext")}
            assertEquals(version,f.catalog.networkEpoch.value);assertTrue(before.current())
            db.rawQuery("SELECT id FROM documents WHERE id=?",arrayOf(s.id)).use {assertFalse(it.moveToFirst())}
        } finally {db.endTransaction()}
        assertFalse(f.catalog.networkEpoch.value.busy)
    }
    @Test fun nestedRegistryWrappersKeepTheCollisionBusyUntilTheRealOuterCommitOrRollback()=scenario {f->
        val committed=f.seed();val before=f.catalog.networkMetadata(setOf(committed.id))
        f.registry.originalPdfTransaction {
            f.registry.register(committed.id,"xmap","nested","content://example/document/nested")
            assertTrue(f.catalog.networkEpoch.value.busy);assertFalse(before.current())
        }
        assertFalse(f.catalog.networkEpoch.value.busy)
        val rolledBack=f.seed();val second=f.catalog.networkMetadata(setOf(rolledBack.id))
        assertThrows(IllegalStateException::class.java) {f.registry.originalPdfTransaction {
            f.registry.register(rolledBack.id,"xcanvas","nested rollback","content://example/document/rollback")
            assertTrue(f.catalog.networkEpoch.value.busy);error("rollback outer")
        }}
        assertFalse(second.current());assertFalse(f.catalog.networkEpoch.value.busy)
        assertFalse(rolledBack.id in f.catalog.identityDomains(setOf(rolledBack.id)).nativeIdentityIds)
    }
    @Test fun newNativeCollisionAndItsRollbackRetireTicketsButOrdinaryExistingMapsDoNot()=scenario {f->
        val s=f.seed();val batch=f.catalog.networkMetadata(setOf(s.id));val other=UUID.randomUUID().toString()
        f.registry.register(other,"xmap","ordinary","content://example/document/map")
        f.registry.register(other,"xmap","ordinary again","content://example/document/map")
        assertTrue(batch.current())
        f.registry.writableDatabase.execSQL("CREATE TRIGGER reject_native BEFORE INSERT ON documents WHEN NEW.id='${s.id}' BEGIN SELECT RAISE(ABORT,'rollback'); END")
        assertThrows(android.database.SQLException::class.java) {f.registry.register(s.id,"xnote","collision","content://example/document/note")}
        assertFalse(batch.current());assertFalse(f.catalog.networkEpoch.value.busy)
        val domain=f.catalog.identityDomains(setOf(s.id));assertFalse(s.id in domain.nativeIdentityIds)
        f.registry.writableDatabase.execSQL("DROP TRIGGER reject_native")
        f.registry.register(s.id,"xnote","collision","content://example/document/note")
        assertFalse(domain.current());val conflict=f.catalog.networkMetadata(setOf(s.id)).files.getValue(s.id)
        assertTrue(conflict.nativeIdentityPresent)
    }
}
