package com.xnotes.mindmap

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.storage.OriginalPdfAccess
import com.xnotes.storage.OriginalPdfCatalog
import com.xnotes.storage.OriginalPdfEngine
import com.xnotes.storage.OriginalPdfPageInfo
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Original facts use actual SQLite/catalog identities; no PDF body or engine is read by facts. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkOriginalPdfFactsTest {
    private fun id()=UUID.randomUUID().toString()
    private fun scenario(block:suspend(MapBacklinkLibraryTest.Fixture)->Unit)=MapBacklinkLibraryTest().scenario(block=block)
    private suspend fun retain(f:MapBacklinkLibraryTest.Fixture,opens:AtomicInteger=AtomicInteger()):OriginalPdfCatalog.Observation=withContext(Dispatchers.IO) {
        val raw=File(f.root,"raw-original.pdf").apply {writeText("immutable original bytes")}
        val access=OriginalPdfAccess(f.context,{opens.incrementAndGet();object:OriginalPdfEngine {
            override val pageCount=2
            override fun pageInfo(pdfKey:Int)=OriginalPdfPageInfo(Pt(600.0,800.0),PdfPageGeometry.flipped(800.0))
            override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):com.xnotes.platform.AndroidRasterSurface?=error("Facts cannot render a PDF")
            override fun closeAndAwait() {}
        }},f.storage)
        val candidate=access.candidate(raw,"Misleading annotation.xnote")
        try {(OriginalPdfCatalog(f.storage.registry).retain(candidate) as OriginalPdfCatalog.Retention.Retained).observation}
        finally {candidate.closeAndAwait()}
    }
    private fun target(row:OriginalPdfCatalog.Observation,index:Int=1,region:Rect?=Rect(20.0,30.0,40.0,50.0))=
        MapSourceTarget(row.record.id,SourceScope.PDF_ORIGINAL,row.record.pages[index],originalPdf=
            PdfOriginalProjection(row.record.sha256,index,userRegion=region))
    @Test fun actualCatalogKindAndCanonicalCardTargetDoNotFollowTheAnnotationNameOrNativeUuid()=scenario {f->
        val opens=AtomicInteger();val original=retain(f,opens);val native=id()
        val note=File(f.notes,"Same title.pdf").apply {writeText("not read")}
        f.storage.registry.register(native,"xnote",note.name,f.uri(note).toString())
        val t=target(original);val bytes=File(f.root,"raw-original.pdf").readBytes()
        val facts=NetworkFileFactsReader(f.context).read(setOf(original.record.id,native),null,targets=setOf(t))
        assertTrue(facts.current());assertEquals(NetworkFileKind.PDF_ORIGINAL,facts.files.getValue(original.record.id).kind)
        assertEquals(NetworkFileKind.NOTE,facts.files.getValue(native).kind)
        val file=facts.files.getValue(original.record.id)
        assertTrue(file.metadataChecked);assertNull(file.availability);assertNull(file.problem)
        assertEquals(MapSourceTarget(original.record.id,SourceScope.PDF_ORIGINAL,original.record.pages.first(),
            originalPdf=PdfOriginalProjection(original.record.sha256,0)),file.cardTarget)
        assertNull(facts.targetStates.getValue(t).statusOverride)
        assertEquals(1,opens.get());assertEquals(0,f.reads.get());assertEquals(0,f.writes.get())
        assertArrayEquals(bytes,File(f.root,"raw-original.pdf").readBytes())
    }
    @Test fun deletedOrMissingOriginalsRetainTheirKindAndAnActualOrdinaryFolderExcludesTheirDomain()=scenario {f->
        val original=retain(f);val t=target(original);val reader=NetworkFileFactsReader(f.context)
        val folder=NetworkFolder(f.tree.toString(),"Ordinary notes")
        val present=reader.read(setOf(original.record.id),folder,targets=setOf(t))
        assertEquals(NetworkFolderMatch.OUTSIDE,present.files.getValue(original.record.id).folder)
        val managed=File(f.storage.pdfResources.directory,"${original.record.sha256}.pdf");assertTrue(managed.delete())
        val missing=reader.read(setOf(original.record.id),null,targets=setOf(t))
        assertEquals(NetworkFileKind.PDF_ORIGINAL,missing.files.getValue(original.record.id).kind)
        assertNull(missing.files.getValue(original.record.id).problem)
        assertEquals(SourceStatus.UNAVAILABLE,missing.targetStates.getValue(t).statusOverride)
        withContext(Dispatchers.IO) {OriginalPdfCatalog(f.storage.registry).delete(original)}
        val deleted=reader.read(setOf(original.record.id),null,targets=setOf(t))
        assertEquals(NetworkFileKind.PDF_ORIGINAL,deleted.files.getValue(original.record.id).kind)
        assertNull(deleted.files.getValue(original.record.id).problem)
        assertEquals(SourceStatus.TRASHED,deleted.targetStates.getValue(t).statusOverride)
        assertEquals(0,f.reads.get());assertEquals(0,f.writes.get())
    }
    @Test fun aBadPointDoesNotPoisonAValidPointOfTheSameOriginalAndNoMissingOriginalFallsBackToANote()=scenario {f->
        val original=retain(f);val good=target(original)
        val bad=good.copy(pageId=id());val nativeOnly=id()
        val note=File(f.notes,"Only a note.xnote").apply {writeText("untouched")}
        f.storage.registry.register(nativeOnly,"xnote",note.name,f.uri(note).toString())
        val absent=good.copy(documentId=nativeOnly)
        val facts=NetworkFileFactsReader(f.context).read(setOf(original.record.id,nativeOnly),null,targets=setOf(good,bad,absent))
        assertEquals(NetworkFileKind.PDF_ORIGINAL,facts.files.getValue(original.record.id).kind)
        assertNull(facts.targetStates.getValue(good).problem)
        assertEquals(BacklinkProblem.IDENTITY,facts.targetStates.getValue(bad).problem)
        assertEquals(SourceStatus.UNAVAILABLE,facts.targetStates.getValue(bad).statusOverride)
        assertNull(facts.files.getValue(nativeOnly).kind);assertEquals(BacklinkProblem.IDENTITY,facts.files.getValue(nativeOnly).problem)
        assertEquals(BacklinkProblem.IDENTITY,facts.targetStates.getValue(absent).problem)
        assertEquals(0,f.reads.get());assertEquals(0,f.writes.get())
    }
    @Test fun theExactTargetSelectorKeepsTheEntireMixedConceptAndSeparatesAvailabilityFromType()=scenario {f->
        val original=retain(f);val good=target(original);val bad=good.copy(pageId=id())
        withContext(Dispatchers.IO) {OriginalPdfCatalog(f.storage.registry).delete(original)}
        val facts=NetworkFileFactsReader(f.context).read(setOf(original.record.id),null,targets=setOf(good,bad))
        val graph=id();val uri=f.uri(File(f.notes,"proof.xmap"));val location=BacklinkLocation(uri,setOf(uri.toString()),MapSaves.key(uri),graph,"Graph")
        fun row(t:MapSourceTarget,s:String)=MapBacklinkRow(location,"c",s,t,"Body",NodeKind.TEXT,"Title","Keep remark",2,
            SourceStatus.CURRENT,null,BacklinkOrigin.STORED)
        val concept=NetworkConceptProjection(NetworkConceptKey(location.key,graph,"c"),location,"Body",NodeKind.TEXT,listOf(row(good,"g"),row(bad,"b")))
        val result=NetworkFilterSelector.select(listOf(concept),NetworkFilter(kinds=setOf(NetworkFileKind.PDF_ORIGINAL)),facts)
        assertEquals(listOf(concept),result.concepts);assertEquals(setOf(original.record.id),result.matched)
        assertEquals(2,result.concepts.single().members.size)
        val onlyBad=concept.copy(members=listOf(row(bad,"bad")))
        val rejected=NetworkFilterSelector.select(listOf(onlyBad),NetworkFilter(kinds=setOf(NetworkFileKind.PDF_ORIGINAL)),facts)
        assertTrue(rejected.concepts.isEmpty());assertEquals(setOf(original.record.id),rejected.unknown)
    }
    @Test fun aCatalogChangeDuringTheActualFolderQueryRetiresTheWholeOldFactsBatch()=scenario {f->
        val original=retain(f);val t=target(original);val native=id()
        val provider=NetworkFileFactsReaderTest.Provider(f.context,File(f.root,"slow-folder").apply {mkdirs()})
        val file=provider.add("note","Native.xnote")
        f.storage.registry.register(native,"xnote",file.name,provider.uri("note").toString())
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val queries=AtomicInteger()
        provider.beforeQuery={if(queries.incrementAndGet()==1) {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}}
        try {coroutineScope {
            val old=async(Dispatchers.IO) {NetworkFileFactsReader(f.context).read(setOf(native,original.record.id),provider.folder(),targets=setOf(t))}
            assertTrue(withContext(Dispatchers.IO) {entered.await(5,TimeUnit.SECONDS)})
            withContext(Dispatchers.IO) {OriginalPdfCatalog(f.storage.registry).delete(original)}
            release.countDown();val facts=old.await()
            assertFalse(facts.current())
            provider.beforeQuery=null
            val latest=NetworkFileFactsReader(f.context).read(setOf(original.record.id),null,targets=setOf(t))
            assertTrue(latest.current());assertEquals(SourceStatus.TRASHED,latest.targetStates.getValue(t).statusOverride)
            assertFalse(facts.current())
        }} finally {release.countDown();provider.beforeQuery=null}
        assertEquals(0,provider.reads.get());assertEquals(0,provider.writes.get());assertEquals(0,f.writes.get())
    }

    @Test fun unsupportedDatabaseCapabilityCannotOfferANativeFileCardThatActualNavigationMustRefuse()=scenario {f->
        val native=id();val file=File(f.notes,"Native.xnote").apply {writeText("untouched native payload")}
        f.storage.registry.register(native,"xnote",file.name,f.uri(file).toString())
        val db=f.storage.registry.writableDatabase;db.version=db.version+1
        val target=MapSourceTarget(native,SourceScope.FILE)
        val facts=NetworkFileFactsReader(f.context).read(setOf(native),null,targets=setOf(target))
        assertNull(facts.files.getValue(native).kind);assertNull(facts.files.getValue(native).cardTarget)
        assertNotNull(facts.files.getValue(native).problem)
        assertEquals(SourceStatus.UNAVAILABLE,facts.targetStates.getValue(target).statusOverride)
        assertTrue(facts.issues.isNotEmpty());assertEquals(0,f.reads.get());assertEquals(0,f.writes.get())
    }
    @Test fun oneFutureOriginalRowDoesNotEraseAnotherNativeFilesVerifiedFormat()=scenario {f->
        val original=retain(f);val target=target(original);val native=id()
        val file=File(f.notes,"Native.xnote").apply {writeText("untouched native payload")}
        f.storage.registry.register(native,"xnote",file.name,f.uri(file).toString())
        f.storage.registry.writableDatabase.execSQL("UPDATE original_pdf_documents SET row_schema=99 WHERE id=?",arrayOf(original.record.id))
        val facts=NetworkFileFactsReader(f.context).read(setOf(native,original.record.id),null,targets=setOf(target))
        assertNull(facts.files.getValue(original.record.id).kind);assertNull(facts.files.getValue(original.record.id).cardTarget)
        assertEquals(SourceStatus.UNAVAILABLE,facts.targetStates.getValue(target).statusOverride)
        assertEquals(NetworkFileKind.NOTE,facts.files.getValue(native).kind)
        assertEquals(MapSourceTarget(native,SourceScope.FILE),facts.files.getValue(native).cardTarget)
        assertEquals(0,f.reads.get());assertEquals(0,f.writes.get())
    }

    @Test fun anInitializedLegacySchemaWithoutAnOriginalCatalogKeepsNativeFormatAndCardTargets()=scenario {f->
        val native=id();val file=File(f.notes,"Native.xnote").apply {writeText("untouched legacy native payload")}
        f.storage.registry.register(native,"xnote",file.name,f.uri(file).toString())
        val db=f.storage.registry.writableDatabase
        db.execSQL("DROP TABLE original_pdf_pages");db.execSQL("DROP TABLE original_pdf_documents");db.version=2
        val target=MapSourceTarget(native,SourceScope.FILE)
        val facts=NetworkFileFactsReader(f.context).read(setOf(native),null,targets=setOf(target))
        assertEquals(NetworkFileKind.NOTE,facts.files.getValue(native).kind)
        assertEquals(target,facts.files.getValue(native).cardTarget);assertNull(facts.files.getValue(native).problem)
        assertTrue(facts.targetStates.isEmpty());assertTrue(facts.current());assertEquals(2,db.version)
        assertEquals(0,f.reads.get());assertEquals(0,f.writes.get())
    }

    @Test fun aLegalCombinedProjectionCanCarryMoreThanFiveThousandDistinctExactOriginalFrames()=scenario {f->
        val original=retain(f);val baseTarget=target(original)
        val targets=(0..5000).mapTo(linkedSetOf()) {n->baseTarget.copy(originalPdf=baseTarget.originalPdf!!.copy(
            userRegion=Rect(n*.001,1.0,1.0,1.0)))}
        withContext(Dispatchers.IO) {OriginalPdfCatalog(f.storage.registry).delete(original)}
        val facts=NetworkFileFactsReader(f.context).read(setOf(original.record.id),null,targets=targets)
        assertTrue(facts.current());assertEquals(5001,facts.targetStates.size)
        assertTrue(facts.targetStates.values.all {it.statusOverride==SourceStatus.TRASHED && it.problem==null})
        assertEquals(NetworkFileKind.PDF_ORIGINAL,facts.files.getValue(original.record.id).kind)
        assertEquals(0,f.reads.get());assertEquals(0,f.writes.get())
    }

}
