package com.xnotes.mindmap

import com.xnotes.core.geometry.Pt
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.storage.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual graphs, catalog epochs and immutable source payloads; no network read opens a PDF engine. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkOriginalPdfControllerTest {
    private fun id()=UUID.randomUUID().toString()
    private fun scenario(block:suspend(MapBacklinkLibraryTest.Fixture)->Unit)=MapBacklinkLibraryTest().scenario(block=block)
    private fun access(f:MapBacklinkLibraryTest.Fixture)=OriginalPdfAccess(f.context,{object:OriginalPdfEngine {
        override val pageCount=1
        override fun pageInfo(pdfKey:Int)=OriginalPdfPageInfo(Pt(600.0,800.0),PdfPageGeometry.flipped(800.0))
        override fun renderRegion(pdfKey:Int,pxPerPt:Double,leftPx:Int,topPx:Int,width:Int,height:Int):com.xnotes.platform.AndroidRasterSurface?=error("Network metadata cannot render")
        override fun closeAndAwait() {}
    }},f.storage)
    private suspend fun retain(f:MapBacklinkLibraryTest.Fixture,name:String):OriginalPdfCatalog.Observation=withContext(Dispatchers.IO) {
        val raw=File(f.root,"$name.pdf").apply {writeText("immutable $name")};val candidate=access(f).candidate(raw,name)
        try {(OriginalPdfCatalog(f.storage.registry).retain(candidate) as OriginalPdfCatalog.Retention.Retained).observation}
        finally {candidate.closeAndAwait()}
    }
    private fun source(observed:OriginalPdfCatalog.Observation)=MapSource(documentId=observed.record.id,fileName=observed.record.title,
        scope=SourceScope.PDF_ORIGINAL,pageId=observed.record.pages.first(),originalPdf=PdfOriginalProjection(observed.record.sha256,0),
        status=SourceStatus.CURRENT,note="Keep the exact original remark")
    private fun asset():MapPreviewAsset {
        val out=ByteArrayOutputStream();val data=DataOutputStream(out)
        data.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(name:String,body:ByteArray) {val type=name.toByteArray();val crc=CRC32().apply {update(type);update(body)}
            data.writeInt(body.size);data.write(type);data.write(body);data.writeInt(crc.value.toInt())}
        val header=ByteArrayOutputStream();DataOutputStream(header).use {it.writeInt(1);it.writeInt(1);it.write(byteArrayOf(8,6,0,0,0))}
        chunk("IHDR",header.toByteArray());val compressed=ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use {it.write(byteArrayOf(0,-1,-1,-1,-1))}
        chunk("IDAT",compressed.toByteArray());chunk("IEND",byteArrayOf());return MapPreviewAsset.fromPng(out.toByteArray())
    }
    private suspend fun await(check:()->Boolean)=withTimeout(10000) {while(!withContext(Dispatchers.Main) {check()}) delay(1)}
    private suspend fun controller(f:MapBacklinkLibraryTest.Fixture,query:NetworkQuery,budget:LocalNetworkBudget=LocalNetworkBudget(),
        block:suspend(LocalFileNetworkController)->Unit) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val controller=withContext(Dispatchers.Main) {LocalFileNetworkController(f.context,scope,query,budget).also {assertTrue(it.refresh())}}
        try {await {!controller.loading && !controller.filtering && controller.report!=null};block(controller)}
        finally {withContext(Dispatchers.Main) {controller.close()};scope.cancel()}
    }
    @Test fun defaultDeleteAndRestoreRetireActionsImmediatelyAndReprojectWithoutChangingSavedSourcesOrPng()=scenario {f->
        val original=retain(f,"Original");val png=asset();val raw=source(original)
        val item=raw.copy(previewKey=png.key,lastPreview=MapLastPreview(png.key,raw.target,"original-pdf-v1:previous",12))
        val other=MapSource(documentId=id(),fileName="Other source",status=SourceStatus.STALE)
        val doc=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null),MapNode(id="c",parent="r",sources=listOf(item,other))),previewAssets=mapOf(png.key to png))
        val file=f.graph("default-original.xmap",doc);val bytes=file.readBytes();val journals=f.journalSnapshot()
        controller(f,NetworkQuery(original.record.id,"Original",listOf(f.tree.toString()))) {c->
            val old=withContext(Dispatchers.Main) {
                val e=c.report!!.concepts.single().members.first {it.fileId==original.record.id}
                assertEquals(SourceStatus.CURRENT,e.row.status)
                requireNotNull(c.captureMetadataGuard()) to requireNotNull(c.captureFocus(e,e))
            }
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val catalog=OriginalPdfCatalog(f.storage.registry) {phase->if(phase=="delete-record") {entered.countDown();check(release.await(5,TimeUnit.SECONDS))}}
            val deleting=CoroutineScope(Dispatchers.IO).async {catalog.delete(original)}
            try {
                assertTrue(withContext(Dispatchers.IO) {entered.await(5,TimeUnit.SECONDS)})
                withContext(Dispatchers.Main) {assertFalse(old.first());assertFalse(old.second.current());assertNull(c.captureMetadataGuard())}
            } finally {release.countDown()}
            val deleted=deleting.await()
            await {!c.filtering && c.report!!.concepts.single().members.any {it.fileId==original.record.id && it.row.status==SourceStatus.TRASHED}}
            withContext(Dispatchers.Main) {
                val members=c.report!!.concepts.single().members
                val shown=members.first {it.fileId==original.record.id}.row
                assertEquals(item.target,shown.target);assertEquals(item.note,shown.sourceNote);assertEquals(png.key,shown.preview!!.assetKey)
                assertNull(shown.blocked);assertEquals(SourceStatus.STALE,members.first {it.fileId==other.documentId}.row.status)
                assertNotNull(c.captureFocus(members.first(),members.last()))
                assertTrue(c.applyFilter(NetworkFilter(kinds=setOf(NetworkFileKind.PDF_ORIGINAL))))
            }
            await {!c.filtering}
            assertEquals(2,withContext(Dispatchers.Main) {c.report!!.concepts.single().members.size})
            withContext(Dispatchers.IO) {
                val candidate=access(f).candidate(File(f.root,"Original.pdf"),"Original")
                try {catalog.restore(deleted,candidate)} finally {candidate.closeAndAwait()}
            }
            await {!c.filtering && c.report!!.concepts.single().members.any {it.fileId==original.record.id && it.row.status==SourceStatus.CURRENT}}
            withContext(Dispatchers.Main) {assertFalse(old.first());assertFalse(old.second.current());assertNotNull(c.captureMetadataGuard())}
        }
        assertArrayEquals(bytes,file.readBytes());assertEquals(doc,MapCodec.decode(bytes));assertEquals(journals,f.journalSnapshot())
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }
    @Test fun aBadPointAndADirectoryExcludedOriginalRemainContextOfTheOtherValidSource()=scenario {f->
        val original=retain(f,"Original");val good=source(original);val bad=good.copy(id=id(),pageId=id())
        val noteId=id();val noteFile=File(f.notes,"Native.xnote").apply {writeText("never read by metadata")}
        f.storage.registry.register(noteId,"xnote",noteFile.name,f.uri(noteFile).toString())
        // Keep the provider inventory restricted to the actual graph, not this deliberately fake note.
        val note=MapSource(documentId=noteId,fileName="Native",status=SourceStatus.CURRENT)
        val file=f.graph("mixed-original.xmap",MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,sources=listOf(note,good,bad)))))
        val bytes=file.readBytes()
        controller(f,NetworkQuery(noteId,"Native",listOf(f.tree.toString()))) {c->
            withContext(Dispatchers.Main) {
                assertEquals(3,c.report!!.concepts.single().members.size)
                assertEquals(BacklinkProblem.IDENTITY,c.report!!.targetStates.getValue(bad.target).problem)
                assertTrue(c.applyFilter(NetworkFilter(kinds=setOf(NetworkFileKind.PDF_ORIGINAL))))
            }
            await {!c.filtering}
            withContext(Dispatchers.Main) {
                assertEquals(3,c.report!!.concepts.single().members.size)
                assertTrue(c.applyFilter(NetworkFilter(folder=NetworkFolder(f.tree.toString(),"Notes"))))
            }
            await {!c.filtering}
            withContext(Dispatchers.Main) {
                assertEquals(setOf(noteId),c.report!!.matchedFiles)
                assertEquals(3,c.report!!.concepts.single().members.size)
                val goodRow=c.report!!.concepts.single().members.first {it.row.sourceId==good.id}.row
                assertEquals(SourceStatus.CURRENT,goodRow.status);assertNull(goodRow.blocked)
            }
        }
        assertArrayEquals(bytes,file.readBytes());assertEquals(0,f.writes.get())
    }
    @Test fun originalOrphansUseTheRemainingSceneBudgetAfterRealEvidenceAndExposeCanonicalChoiceTargets()=scenario {f->
        val referenced=retain(f,"Referenced");val orphan=retain(f,"Orphan")
        val other=MapSource(documentId=id(),fileName="Other")
        f.graph("evidence-first.xmap",MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,sources=listOf(source(referenced),other)))))
        controller(f,NetworkQuery.library("Library",listOf(f.tree.toString())),LocalNetworkBudget(maxSceneNodes=4)) {c->
            withContext(Dispatchers.Main) {
                assertEquals(1,c.report!!.concepts.size);assertEquals(4,c.report!!.files.size+c.report!!.concepts.size)
                assertTrue(c.report!!.limited);assertTrue(c.report!!.files.none {it.documentId==orphan.record.id})
                assertTrue(c.report!!.issues.none {it.problem==BacklinkProblem.IDENTITY})
                val choice=c.fileChoices.single {it.documentId==orphan.record.id}
                assertEquals(NetworkFileKind.PDF_ORIGINAL,choice.kind)
                assertEquals(MapSourceTarget(orphan.record.id,SourceScope.PDF_ORIGINAL,orphan.record.pages.first(),
                    originalPdf=PdfOriginalProjection(orphan.record.sha256,0)),choice.cardTarget)
                assertTrue(c.showCollection(setOf(orphan.record.id)))
            }
            await {!c.loading && !c.filtering}
            withContext(Dispatchers.Main) {
                assertEquals(setOf(orphan.record.id),c.centreFiles)
                assertEquals(NetworkFileKind.PDF_ORIGINAL,c.report!!.files.single().kind)
                assertNotNull(c.captureMetadataGuard())
            }
        }
        assertEquals(0,f.writes.get())
    }
    @Test fun twoLegalGraphsCanExpandPastFiveThousandExactTargetsWithoutLosingADeletedOriginalPrefix()=scenario {f->
        val original=retain(f,"Original");val prefix=source(original);val bridge=MapSource(documentId=id(),fileName="Expansion bridge")
        fun fresh()=MapSource(documentId=id())
        val direct=listOf(MapNode(id="r",parent=null),MapNode(id="d000",parent="r",sources=listOf(prefix,bridge)))+
            (1..38).map {n->MapNode(id="d"+n.toString().padStart(3,'0'),parent="r",
                sources=listOf(prefix.copy(id=id()))+List(127) {fresh()})}
        val deeper=listOf(MapNode(id="r",parent=null))+(0..1).map {n->
            MapNode(id="e"+n.toString().padStart(3,'0'),parent="r",sources=listOf(bridge.copy(id=id()))+List(127) {fresh()})}
        val directDoc=MindMapDocument(nodes=direct);val deeperDoc=MindMapDocument(nodes=deeper)
        assertTrue(directDoc.nodes.sumOf {it.sources.size}<=5000);assertTrue(deeperDoc.nodes.sumOf {it.sources.size}<=5000)
        assertTrue((direct+deeper).flatMap {it.sources}.map {it.target}.toSet().size>5000)
        val a=f.graph("direct-batch.xmap",directDoc);val b=f.graph("deeper-batch.xmap",deeperDoc)
        val beforeA=a.readBytes();val beforeB=b.readBytes()
        withContext(Dispatchers.IO) {OriginalPdfCatalog(f.storage.registry).delete(original)}
        controller(f,NetworkQuery(original.record.id,"Original",listOf(f.tree.toString()))) {c->
            withContext(Dispatchers.Main) {
                assertEquals(SourceStatus.TRASHED,c.report!!.concepts.first {it.key.nodeId=="d000"}.members.first {it.row.sourceId==prefix.id}.row.status)
                assertTrue(c.setExpanded(bridge.documentId,true))
            }
            await {!c.loading && !c.filtering && c.catalog.size>5000}
            withContext(Dispatchers.Main) {
                val shown=c.report!!.concepts.first {it.key.nodeId=="d000"}.members.first {it.row.sourceId==prefix.id}.row
                assertEquals(SourceStatus.TRASHED,shown.status)
                assertEquals(SourceStatus.TRASHED,c.report!!.targetStates.getValue(prefix.target).statusOverride)
                assertEquals(NetworkFileKind.PDF_ORIGINAL,c.report!!.files.first {it.documentId==original.record.id}.kind)
                assertTrue(c.report!!.limited) // Scene limits apply after the complete bounded facts batch.
            }
        }
        assertArrayEquals(beforeA,a.readBytes());assertArrayEquals(beforeB,b.readBytes())
        assertEquals(0,f.writes.get());assertEquals(0,f.deletes.get())
    }

}
