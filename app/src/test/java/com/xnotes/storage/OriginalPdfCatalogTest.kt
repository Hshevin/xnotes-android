package com.xnotes.storage

import android.content.Context
import android.content.ContextWrapper
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.mindmap.MapSourceTarget
import com.xnotes.mindmap.PdfOriginalProjection
import com.xnotes.mindmap.SourceScope
import com.xnotes.platform.AndroidRasterSurface
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real SQLite/managed bytes/leases; PDF parser is injected, so these do not assert PDFium pixels. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class OriginalPdfCatalogTest {
    private class Engine(private val count:Int=2,val close:()->Unit={}):OriginalPdfEngine {
        override val pageCount get()=count
        override fun pageInfo(pdfKey:Int)=OriginalPdfPageInfo(Pt(600.0,800.0),PdfPageGeometry.flipped(800.0))
        override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):AndroidRasterSurface?=null
        override fun closeAndAwait()=close()
    }
    private class Fixture(val root:File,val context:Context) {
        val storage=NotebookStorage(context)
        val catalog=OriginalPdfCatalog(storage.registry)
        var factory:(File)->OriginalPdfEngine={Engine()}
        val access=OriginalPdfAccess(context,{factory(it)},storage)
        val input=File(root,"original.pdf").apply {writeText("actual immutable input bytes")}
        fun candidate()=access.candidate(input,"Original")
        fun retain():OriginalPdfCatalog.Observation {
            val c=candidate()
            try {return (catalog.retain(c) as OriginalPdfCatalog.Retention.Retained).observation} finally {c.closeAndAwait()}
        }
        fun target(o:OriginalPdfCatalog.Observation,index:Int=0)=MapSourceTarget(o.record.id,SourceScope.PDF_ORIGINAL,
            o.record.pages[index],originalPdf=PdfOriginalProjection(o.record.sha256,index))
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=Files.createTempDirectory("original-pdf-catalog-").toFile()
        val context=object:ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getApplicationContext():Context=this
        }
        val f=Fixture(root,context)
        try {block(f)} finally {f.storage.registry.close();root.deleteRecursively()}
    }
    @Test fun twoNotesSharingActualBytesGetOneIndependentIdentityAndReopenPreservesAllPages()=scenario {f->
        val a=f.retain();val noteA=java.util.UUID.randomUUID().toString();val noteB=java.util.UUID.randomUUID().toString()
        f.storage.pdfResources.pin("note:$noteA",setOf(a.record.sha256));f.storage.pdfResources.pin("note:$noteB",setOf(a.record.sha256))
        val b=f.retain();assertEquals(a.record,b.record);assertNotEquals(noteA,a.record.id);assertNotEquals(a.record.sha256,a.record.id)
        f.storage.pdfResources.pin("note:$noteA",emptySet());f.storage.pdfResources.pin("note:$noteB",emptySet())
        DocumentRegistry(f.context).use {other->
            val reopened=OriginalPdfCatalog(other).observe(a.record.id)!!
            assertEquals(a.record,reopened.record);assertEquals(2,reopened.record.pages.toSet().size)
            assertEquals(setOf(a.record.sha256),other.referenceIds(OriginalPdfCatalog.owner(a.record.id)))
        }
        f.access.open(f.target(a)).let {read->assertTrue(read.current());read.closeAndAwait()}
    }
    @Test fun cancellingGraphImportDoesNotDropExplicitlyRetainedOriginal()=scenario {f->
        val original=f.retain();assertEquals(original.record,f.catalog.list().records.single().record)
        assertEquals(setOf(original.record.sha256),f.storage.registry.referenceIds(OriginalPdfCatalog.owner(original.record.id)))
    }
    @Test fun deleteOnlyDropsItsOwnPinAndExplicitRestoreKeepsPermanentIds()=scenario {f->
        val original=f.retain();f.storage.pdfResources.pin("note:other",setOf(original.record.sha256))
        val reader=f.access.open(f.target(original));val deleted=f.catalog.delete(original)
        assertFalse(original.current());assertFalse(reader.current());assertTrue(deleted.record.deleted)
        assertEquals(setOf(original.record.sha256),f.storage.registry.referenceIds("note:other"))
        assertTrue(f.storage.registry.referenceIds(OriginalPdfCatalog.owner(original.record.id)).isEmpty())
        val c=f.candidate()
        val offer=(f.catalog.retain(c) as OriginalPdfCatalog.Retention.RestoreRequired).observation
        assertTrue(offer.record.deleted);assertEquals(original.record.pages,offer.record.pages)
        val restored=f.catalog.restore(offer,c)
        assertEquals(original.record.id,restored.record.id);assertEquals(original.record.pages,restored.record.pages)
        assertEquals(original.record.generation+2,restored.record.generation);assertFalse(restored.record.deleted)
        assertFalse(original.current());assertFalse(reader.current());c.closeAndAwait();reader.closeAndAwait()
    }
    @Test fun failedPublicationAtEveryBoundaryLeavesNeitherIdentityNorOwnPin()=scenario {f->
        for(stage in listOf("record","pages","pin")) {
            val bad=OriginalPdfCatalog(f.storage.registry) {if(it==stage) throw IllegalStateException("injected")}
            val c=f.candidate()
            try {assertThrows(IllegalStateException::class.java) {bad.retain(c)}} finally {c.closeAndAwait()}
            assertTrue(f.catalog.list().records.isEmpty())
            f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM resource_refs WHERE owner LIKE 'original-pdf:%'",null).use {
                assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))
            }
        }
    }
    @Test fun deleteAndRestoreFailuresRollbackRowsAndProtectionAndRetireOldRuntimeTickets()=scenario {f->
        for(stage in listOf("delete-record","delete-pin")) {
            val before=f.catalog.list().records.firstOrNull() ?: f.retain()
            val bad=OriginalPdfCatalog(f.storage.registry) {if(it==stage) error("injected")}
            assertThrows(IllegalStateException::class.java) {bad.delete(before)}
            assertFalse(before.current());val current=f.catalog.observe(before.record.id)!!
            assertEquals(before.record,current.record);assertFalse(current.record.deleted)
            assertEquals(setOf(current.record.sha256),f.storage.registry.referenceIds(OriginalPdfCatalog.owner(current.record.id)))
        }
        val deleted=f.catalog.delete(f.catalog.list().records.single())
        for(stage in listOf("restore-record","restore-pin")) {
            val offer=f.catalog.observe(deleted.record.id)!!;val c=f.candidate()
            val bad=OriginalPdfCatalog(f.storage.registry) {if(it==stage) error("injected")}
            try {assertThrows(IllegalStateException::class.java) {bad.restore(offer,c)}} finally {c.closeAndAwait()}
            assertFalse(offer.current());assertEquals(deleted.record,f.catalog.observe(deleted.record.id)!!.record)
            assertTrue(f.storage.registry.referenceIds(OriginalPdfCatalog.owner(deleted.record.id)).isEmpty())
        }
    }
    @Test fun mutationStartRetiresAcrossInstancesBeforeSqliteCommitAndRollback()=scenario {f->
        val original=f.retain();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val second=DocumentRegistry(f.context);val other=OriginalPdfCatalog(second) {stage->if(stage=="delete-record") {entered.countDown();check(release.await(10,TimeUnit.SECONDS));error("rollback")}}
        val thread=Thread {runCatching {other.delete(original)}};thread.start()
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS));assertFalse(original.current())
        } finally {release.countDown();thread.join(10000);second.close()}
        assertFalse(thread.isAlive);assertFalse(f.catalog.observe(original.record.id)!!.record.deleted)
    }
    @Test fun candidateFreezesEpochBeforeSlowEngineOpenSoDeleteRestoreCannotReviveIt()=scenario {f->
        val original=f.retain();var first=true
        f.factory={
            if(first) {
                first=false;val deleted=f.catalog.delete(f.catalog.observe(original.record.id)!!)
                val restore=f.access.candidate(original.record.sha256,"Original")
                try {f.catalog.restore(deleted,restore)} finally {restore.closeAndAwait()}
            }
            Engine()
        }
        assertThrows(IllegalStateException::class.java) {f.candidate()}
        assertFalse(original.current());assertFalse(f.catalog.observe(original.record.id)!!.record.deleted)
    }
    @Test fun wrongHashWrongOrdinalWrongPageUuidAndWrongActualPageCountAreRejected()=scenario {f->
        val original=f.retain();val target=f.target(original)
        assertThrows(IllegalArgumentException::class.java) {f.access.open(target.copy(originalPdf=target.originalPdf!!.copy(sha256="0".repeat(64))))}
        assertThrows(IllegalArgumentException::class.java) {f.access.open(target.copy(originalPdf=target.originalPdf!!.copy(pageIndex=1)))}
        assertThrows(IllegalArgumentException::class.java) {f.access.open(target.copy(pageId=java.util.UUID.randomUUID().toString()))}
        f.factory={Engine(1)};assertThrows(IllegalArgumentException::class.java) {f.access.open(target)}
    }
    @Test fun futureOrIncompleteRecordCannotBeListedDeletedRestoredOrSilentlyRecreated()=scenario {f->
        val original=f.retain();val owner=OriginalPdfCatalog.owner(original.record.id)
        f.storage.registry.writableDatabase.execSQL("UPDATE original_pdf_documents SET row_schema=99 WHERE id=?",arrayOf(original.record.id))
        assertThrows(IllegalStateException::class.java) {f.catalog.list()}
        assertThrows(IllegalStateException::class.java) {f.catalog.delete(original)}
        val c=f.candidate();try {assertThrows(IllegalStateException::class.java) {f.catalog.retain(c)}} finally {c.closeAndAwait()}
        assertEquals(setOf(original.record.sha256),f.storage.registry.referenceIds(owner))
        f.storage.registry.writableDatabase.execSQL("UPDATE original_pdf_documents SET row_schema=1 WHERE id=?",arrayOf(original.record.id))
        f.storage.registry.writableDatabase.execSQL("DELETE FROM original_pdf_pages WHERE document_id=? AND ordinal=1",arrayOf(original.record.id))
        assertThrows(IllegalArgumentException::class.java) {f.catalog.observe(original.record.id)}
        assertThrows(IllegalArgumentException::class.java) {f.catalog.delete(original)}
        assertEquals(setOf(original.record.sha256),f.storage.registry.referenceIds(owner))
    }
    @Test fun rawSqlTypesAndFullWidthIntegersCannotMasqueradeAsSupportedIdentityValues() {
        val cases=listOf(
            "UPDATE original_pdf_documents SET row_schema=4294967297",
            "UPDATE original_pdf_documents SET row_schema=1.5",
            "UPDATE original_pdf_documents SET row_schema='future'",
            "UPDATE original_pdf_documents SET page_count=4294967298",
            "UPDATE original_pdf_documents SET page_count=2.5",
            "UPDATE original_pdf_documents SET generation=1.5",
            "UPDATE original_pdf_documents SET deleted=4294967296",
            "UPDATE original_pdf_pages SET ordinal=4294967296 WHERE ordinal=0"
        )
        for(sql in cases) scenario {f->
            val original=f.retain();val owner=OriginalPdfCatalog.owner(original.record.id)
            f.storage.registry.writableDatabase.execSQL(sql)
            assertThrows(sql,RuntimeException::class.java) {f.catalog.observe(original.record.id)}
            assertThrows(sql,RuntimeException::class.java) {f.catalog.delete(original)}
            assertEquals(setOf(original.record.sha256),f.storage.registry.referenceIds(owner))
            val candidate=f.candidate()
            try {assertThrows(sql,RuntimeException::class.java) {f.catalog.retain(candidate)}} finally {candidate.closeAndAwait()}
            assertEquals(setOf(original.record.sha256),f.storage.registry.referenceIds(owner))
        }
    }
    private fun pinSnapshot(f:Fixture)=f.storage.registry.readableDatabase.rawQuery("SELECT owner,resource_id FROM resource_refs ORDER BY owner,resource_id",null).use {c->
        buildList {while(c.moveToNext()) add(c.getString(0) to c.getString(1))}
    }
    private fun verifyUnknownPinIsPreserved(deleted:Boolean)=scenario {f->
        val first=f.retain()
        val otherFile=File(f.root,"other.pdf").apply {writeText("another immutable actual original")}
        val otherCandidate=f.access.candidate(otherFile,"Other")
        val other=try {(f.catalog.retain(otherCandidate) as OriginalPdfCatalog.Retention.Retained).observation} finally {otherCandidate.closeAndAwait()}
        val fresh=f.catalog.observe(first.record.id)!!
        val expected=if(deleted) f.catalog.delete(fresh) else fresh
        f.storage.registry.writableDatabase.execSQL("INSERT INTO resource_refs(owner,resource_id) VALUES(?,?)",
            arrayOf(OriginalPdfCatalog.owner(first.record.id),other.record.sha256))
        val before=pinSnapshot(f)
        val candidate=f.candidate()
        try {
            assertThrows(IllegalStateException::class.java) {f.catalog.observe(first.record.id)}
            assertThrows(IllegalStateException::class.java) {f.catalog.list()}
            assertThrows(IllegalStateException::class.java) {f.catalog.retain(candidate)}
            assertThrows(IllegalStateException::class.java) {f.catalog.delete(expected)}
            assertThrows(IllegalStateException::class.java) {f.catalog.restore(expected,candidate)}
            assertEquals(before,pinSnapshot(f))
        } finally {candidate.closeAndAwait()}
        assertEquals(before,pinSnapshot(f))
        assertTrue(f.storage.registry.referenceIds(OriginalPdfCatalog.owner(first.record.id)).contains(other.record.sha256))
    }
    @Test fun activeOriginalWithAnExtraUnknownPinCannotDeleteOrRepairAwayThatProtection()=verifyUnknownPinIsPreserved(false)
    @Test fun tombstonedOriginalWithAResidualUnknownPinCannotSilentlyRestoreOrClearIt()=verifyUnknownPinIsPreserved(true)
    @Test fun pageMappingsAreImmutableAndRestoreRequiresTheWholePreviousMapping()=scenario {f->
        val original=f.retain();assertThrows(UnsupportedOperationException::class.java) {(original.record.pages as MutableList<String>).clear()}
        val deleted=f.catalog.delete(original);val c=f.candidate()
        f.storage.registry.writableDatabase.execSQL("UPDATE original_pdf_pages SET page_id=? WHERE document_id=? AND ordinal=0",arrayOf(java.util.UUID.randomUUID().toString(),original.record.id))
        try {assertThrows(IllegalStateException::class.java) {f.catalog.restore(deleted,c)}} finally {c.closeAndAwait()}
        assertTrue(f.storage.registry.referenceIds(OriginalPdfCatalog.owner(original.record.id)).isEmpty())
    }
    @Test fun leaseSurvivesActualCloseWaitAndNativeCloseFailure()=scenario {f->
        val original=f.retain();val started=CountDownLatch(1);val release=CountDownLatch(1)
        f.factory={Engine {started.countDown();check(release.await(10,TimeUnit.SECONDS))}}
        val reader=f.access.open(f.target(original));f.catalog.delete(original)
        val worker=Thread {reader.closeAndAwait()};worker.start()
        try {
            assertTrue(started.await(10,TimeUnit.SECONDS))
            f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases WHERE resource_id=?",arrayOf(original.record.sha256)).use {assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))}
        } finally {release.countDown();worker.join(10000)}
        assertFalse(worker.isAlive)
        f.storage.registry.readableDatabase.rawQuery("SELECT COUNT(*) FROM leases WHERE resource_id=?",arrayOf(original.record.sha256)).use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
    }
    @Test fun activeExplicitRelinkRetainsIdentityAndRejectsDifferentBottomLayer()=scenario {f->
        val original=f.retain();val target=f.target(original)
        val stored=File(f.storage.pdfResources.directory,"${original.record.sha256}.pdf");stored.delete()
        val wrong=File(f.root,"wrong.pdf").apply {writeText("different bottom layer")}
        assertThrows(IllegalArgumentException::class.java) {f.access.relink(wrong,original,"Original")}
        assertFalse(stored.exists())
        val refreshed=f.catalog.observe(original.record.id)!!
        val c=f.access.relink(f.input,refreshed,"Original")
        try {val restored=f.catalog.restore(refreshed,c);assertEquals(original.record.pages,restored.record.pages);assertEquals(original.record.generation+1,restored.record.generation)} finally {c.closeAndAwait()}
        f.access.open(target).closeAndAwait()
    }
}
