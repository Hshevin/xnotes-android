package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.openRead
import okio.*
import kotlin.test.assertFailsWith
import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.*
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.stroke.Sample
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.testkit.*
import kotlin.test.Test

class ReferenceCodecTest : BundleTestFiles() {
    private val text=FakeTextMeasurer()
    private fun note()=DocumentCodec(FakeImageCodec(),text)
    private fun canvas()=CanvasCodec(FakeImageCodec(),text)
    private fun ink()=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(1.0,2.0,.7)))
    @Test fun enabledNoteKeepsPageIdentityWhileCanvasUsesOnlyDocumentIdentity() {
        val doc=Document.blank();val p=doc.pages.first();p.items.add(ink())
        val old=Buffer().also {note().write(doc.snapshot(),it)}.bytes()
        assertNull(p.referenceIdentity.id);assertFalse(note().read(old.asSource()).hasReferenceAnchors)
        doc.enableReferenceAnchors()
        val next=Buffer().also {note().write(doc.snapshot(),it)}.bytes()
        val loaded=note().read(next.asSource());assertTrue(loaded.hasReferenceAnchors)
        assertEquals(p.referenceIdentity.id,loaded.pages.first().referenceIdentity.id)
        val board=InfiniteDocument();board.add(ink())
        val bytes=Buffer().also {canvas().write(board.snapshotForWrite(),it)}.bytes()
        val reopened=canvas().read(bytes.asSource());assertEquals(board.identity,reopened.identity)
    }
    private val owner="00000000-0000-0000-0000-000000000001"
    private val page="00000000-0000-0000-0000-000000000002"
    private val item="00000000-0000-0000-0000-000000000003"
    private fun bundle(manifest:String,marker:Boolean=true,extra:Map<String,ByteArray> = emptyMap()):ByteArray = Buffer().also {out->bundleWriter(out).use {z->
        fun entry(name:String,b:ByteArray) {z.putDeflated(name,b)}
        entry("manifest.json",manifest.encodeToByteArray());z.addIdentity(BundleIdentity(owner))
        extra.forEach {(n,b)->entry(n,b)}
        if(marker) entry(BundleAnchors.ENTRY,"""{"format":"xnotes-page-anchors","version":1,"documentId":"$owner"}""".encodeToByteArray())
    }}.bytes()
    private fun rejected(bytes:ByteArray,isCanvas:Boolean=false) {
        try {if(isCanvas) canvas().read(bytes.asSource()) else note().read(bytes.asSource());fail("must not expose partial writable anchored model")} catch(_:IllegalArgumentException) {} catch(_:JsonPullException) {}
    }
    @Test fun markerAfterManifestStillRejectsUnknownMissingDuplicateAndUnmarkedIdentities() {
        rejected(bundle("""{"format":"xnote","version":1,"pages":[{"items":[]}]}"""))
        rejected(bundle("""{"format":"xnote","version":1,"pages":[]}"""))
        rejected(bundle("""{"format":"xnote","version":1,"pages":[{"reference_id":"$page","items":[]},{"reference_id":"$page","items":[]}]}"""))
        rejected(bundle("""{"format":"xnote","version":1,"pages":[{"reference_id":"$page","items":[]}]}""",marker=false))
        rejected(bundle("""{"format":"xcanvas","version":1,"items":[{"kind":"future","reference_id":"$item"}]}"""),true)
    }
    @Test fun failedPageIdentityReadCleansOnlyNewAssetsAndDoesNotDeleteBorrowedPdf() {
        val dir=testDirectory("anchor-read-");val keep=dir.child("keep.pdf").apply {setText("original")}
        try {
            val bytes=bundle("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"reference_id":"bad-page","items":[{"kind":"image","asset":"assets/image-missing.png","reference_id":"$item"}]}]}""",extra=mapOf("assets/source.pdf" to keep.fileBytes(),"assets/image-unused.png" to byteArrayOf(1,2,3)))
            try {note().read(bytes.asSource(),pdfDir=dir,imageDir=dir,pdfStore={input->input.buffer().readByteArray();keep});fail("missing image")} catch(_:IllegalArgumentException) {}
            assertEquals("original",keep.fileText());assertEquals(listOf("keep.pdf"),dir.fileNames())
        } finally {dir.deleteTree()}
    }
    @Test fun canvasRejectsPageMarkersButInvalidTapeDoesNotInvalidateAPage() {
        rejected(bundle("""{"format":"xcanvas","version":1}"""),true)
        val loaded=note().read(bundle("""{"format":"xnote","version":1,"pages":[{"reference_id":"$page","items":[{"kind":"tape"}]}]}""").asSource())
        assertTrue(loaded.pages.single().items.isEmpty());assertEquals(page,loaded.pages.single().referenceIdentity.id)
    }
    @Test fun writerDoesNotAllocateIdsForUnpreparedNewPages() {
        val doc=Document.blank();doc.enableReferenceAnchors();val page=Page(100.0,100.0).also {doc.pages.add(it)}
        try {note().write(doc,Buffer());fail("writer cannot prepare live IDs")} catch(_:IllegalArgumentException) {}
        assertNull(page.referenceIdentity.id)
        val snapshot=doc.snapshot();assertNotNull(page.referenceIdentity.id)
        note().write(snapshot,Buffer())
    }
    @Test fun everySupportedKindAndConnectorBindingSurvivesPageIdentityRoundTrip() {
        val dir=testDirectory("anchor-kinds-")
        try {
            val image=dir.child("input.png").apply {setText("fake encoded image")}
            val sound=dir.child("input.m4a").apply {setText("fake encoded audio")}
            fun items(withText:Boolean):List<CanvasItem> {
                val shape=ShapeItem(ShapeKind.RECTANGLE,Pt(10.0,10.0),Pt(50.0,50.0),Rgba(0,0,0,255))
                val result=mutableListOf<CanvasItem>(
                    ImageItem(ImageData(image,64,48),Rect(0.0,0.0,64.0,48.0)),ink(),
                    AudioItem(AudioData(sound,"m4a",123,byteArrayOf(1,2)),Rect(0.0,0.0,100.0,40.0),"audio",7,text),shape,
                    ConnectorItem(Routing.STRAIGHT,ConnectorEnd(Pt(10.0,10.0),shape,0),ConnectorEnd(Pt(200.0,200.0)),Rgba(0,0,0,255)),
                    TapeItem(TapeShape(TapeMode.STRAIGHT,listOf(Pt(1.0,1.0),Pt(40.0,40.0)),10.0),Rgba(0,0,0,255)))
                if(withText) result+=TextItem(Pt(0.0,0.0),text="文字",measurer=text)
                return result
            }
            val doc=Document.blank();doc.pages.first().items.addAll(items(true));doc.enableReferenceAnchors()
            val bytes=Buffer().also {note().write(doc.snapshot(),it)}.bytes()
            val loaded=note().read(bytes.asSource(),imageDir=dir)
            assertEquals(doc.pages.first().referenceIdentity.id,loaded.pages.first().referenceIdentity.id)
            assertEquals(7,loaded.pages.first().items.size)
            assertSame(loaded.pages.first().items.filterIsInstance<ShapeItem>().single(),loaded.pages.first().items.filterIsInstance<ConnectorItem>().single().start.target)
            val board=InfiniteDocument();board.addAll(items(false))
            val canvasBytes=Buffer().also {canvas().write(board.snapshotForWrite(),it)}.bytes()
            val reopened=canvas().read(canvasBytes.asSource(),imageDir=dir)
            assertEquals(board.identity,reopened.identity);assertEquals(6,reopened.items.size)
            // Missing object assets no longer invalidate a page reference.
            assertEquals(5,note().read(bytes.asSource()).pages.single().items.size)
            assertEquals(4,canvas().read(canvasBytes.asSource()).items.size)
        } finally {dir.deleteTree()}
    }

    @Test fun anchoredVersionMustBeExactIntegerOneRatherThanDoubleRoundedNearOne() {
        for(version in listOf("1.0","1e0","1.0000000000000000000001","01","2","\"1\"")) {
            rejected(bundle("""{"format":"xnote","version":$version,"pages":[{"reference_id":"$page","items":[]}]}"""))
        }
    }
    @Test fun anchoredPdfCannotBeReturnedAsCompleteWhenMissingOrDeliberatelySkipped() {
        val manifest="""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"pdf_page":0,"reference_id":"$page","items":[]}]}"""
        rejected(bundle(manifest))
        rejected(bundle(manifest,extra=mapOf("assets/source.pdf" to "%PDF-1.7 original bytes".encodeToByteArray())))
    }

    @Test fun explicitInkCopyKeepsContentButMintsAllIndependentIdsAndNeverWritesSource() {
        val dir=testDirectory("ink-copy-")
        try {
            val original=dir.child("original.pdf").apply {setText("%PDF-1.7 immutable")}
            val doc=Document.blank().apply {pdfFile=original}
            val p=doc.pages.first();p.width=600.0;p.height=800.0;p.pdfPage=0;p.pdfTurn=1;p.layers.create("Notes")
            p.items.add(ink());doc.enableReferenceAnchors()
            val file=dir.child("source.xnote").apply {fileSink().use {note().write(doc.snapshot(),it)}}
            val hash=BundleMigration.hash(file)
            val copy=file.input().use {note().readInkCopy(it,imageDir=dir)}
            assertEquals(hash,BundleMigration.hash(file));assertNotEquals(doc.identity.documentId,copy.identity.documentId)
            assertNull(copy.pdfFile);assertNull(copy.identity.pdfResource);assertFalse(copy.identity.externalPdf)
            assertTrue(copy.hasReferenceAnchors);assertTrue(copy.dirty);assertNull(copy.pages.first().pdfPage);assertEquals(0,copy.pages.first().pdfTurn)
            assertEquals(p.width,copy.pages.first().width,0.0);assertEquals(p.height,copy.pages.first().height,0.0)
            assertEquals(p.layers.entries,copy.pages.first().layers.entries)
            assertEquals((p.items.first() as Stroke).samples,(copy.pages.first().items.first() as Stroke).samples)
            val oldIds=setOf(p.referenceIdentity.id)
            val newIds=setOf(copy.pages.first().referenceIdentity.id)
            assertTrue(oldIds.intersect(newIds).isEmpty())
            val output=Buffer().also {note().write(copy.snapshot(),it)}.bytes()
            val reopened=note().read(output.asSource(),imageDir=dir)
            assertEquals(copy.identity,reopened.identity);assertEquals(copy.pages.first().referenceIdentity.id,reopened.pages.first().referenceIdentity.id)
            assertEquals(hash,BundleMigration.hash(file));assertEquals("%PDF-1.7 immutable",original.fileText())
            val unknown=bundle("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"reference_id":"$page","items":[{"kind":"future","reference_id":"$item"}]}]}""")
            // The ink-only copy is the rescue path: an unreadable object is left out and counted,
            // never allowed to make the whole recovery fail. The copy's ids stay disjoint.
            val missingImage=bundle("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"reference_id":"$page","items":[{"kind":"image","asset":"assets/image-gone.png","reference_id":"$item"}]}]}""")
            for(partial in listOf(unknown,missingImage)) {
                val salvaged=note().readInkCopy(partial.asSource(),dir)
                assertEquals(1,salvaged.omittedOnCopy);assertEquals(1,salvaged.pages.size);assertTrue(salvaged.pages.first().items.isEmpty())
                assertTrue(salvaged.hasReferenceAnchors);assertNotEquals(page,salvaged.pages.first().referenceIdentity.id)
                val saved=Buffer().also {note().write(salvaged.snapshot(),it)}.bytes()
                assertTrue(note().read(saved.asSource(),imageDir=dir).hasReferenceAnchors)
            }
            assertEquals(0,copy.omittedOnCopy)
        } finally {dir.deleteTree()}
    }
    @Test fun borrowedResolvedPdfSurvivesAnyAnchoredValidationFailure() {
        val dir=testDirectory("resolved-pdf-");val pdf=dir.child("managed.pdf").apply {setText("immutable")}
        try {
            val resource="a".repeat(64);val identity=BundleIdentity(owner,resource,true)
            fun encoded(known:Boolean)=Buffer().also {out->bundleWriter(out).use {z->
                z.addIdentity(identity);z.addMarker(AnchorMetadata(owner))
                z.putDeflated("manifest.json","""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"reference_id":"${if(known) page else "bad-page"}","items":${if(known) "[]" else "[{\"kind\":\"future\",\"reference_id\":\"$item\"}]"}}]}""".encodeToByteArray())
            }}.bytes()
            val codec=note().apply {resolvePdf={assertEquals(resource,it);pdf}}
            val loaded=codec.read(encoded(true).asSource(),pdfDir=dir)
            assertEquals(pdf,loaded.pdfFile);assertTrue(loaded.hasReferenceAnchors)
            try {codec.read(encoded(false).asSource(),pdfDir=dir);fail("partial model")} catch(_:IllegalArgumentException) {}
            assertEquals("immutable",pdf.fileText());assertEquals(listOf("managed.pdf"),dir.fileNames())
        } finally {dir.deleteTree()}
    }

    @Test fun legacyInkRecoveryStaysLegacyUntilStorageActivationAndKeepsLargeModelCompatibility() {
        val bytes=bundle("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"pdf_page":0,"items":[{"kind":"stroke","samples":[[1,2,0.7],[3,4,0.8]]}]}]}""",marker=false)
        val copy=note().readInkCopy(bytes.asSource())
        assertFalse(copy.hasReferenceAnchors);assertNotEquals(owner,copy.identity.documentId)
        assertNull(copy.pages.first().referenceIdentity.id)
        assertNull(copy.pdfFile);assertNull(copy.pages.first().pdfPage)
        val output=Buffer().also {note().write(copy.snapshot(),it)}.bytes()
        assertFalse(note().read(output.asSource()).hasReferenceAnchors)
    }
    @Test fun legacyInkRecoverySalvagesAroundUnreadableObjects() {
        val bytes=bundle("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"pdf_page":0,"items":[{"kind":"stroke","samples":[[1,2,0.7],[3,4,0.8]]},{"kind":"future"},{"kind":"image","asset":"assets/image-gone.png"}]}]}""",marker=false)
        val copy=note().readInkCopy(bytes.asSource())
        assertEquals(2,copy.omittedOnCopy)
        assertEquals(1,copy.pages.first().items.size);assertTrue(copy.pages.first().items.first() is Stroke)
        assertFalse(copy.hasReferenceAnchors)
    }
    @Test fun inkRecoveryStillRejectsAnUnrecognizedFormatVersion() {
        val bytes=bundle("""{"format":"xnote","version":2,"pages":[{"items":[]}]}""",marker=false)
        try {note().readInkCopy(bytes.asSource());fail("unknown version")} catch(_:IllegalArgumentException) {}
    }

    @Test fun referenceOnlyPdfIdentityCannotBeOverriddenByUnexpectedEmbeddedBytes() {
        val dir=testDirectory("ambiguous-pdf-")
        val protected=dir.child("managed.pdf").apply {setText("protected A")}
        val admitted=dir.child("admitted.pdf").apply {setText("different B")}
        try {
            val bytes=Buffer().also {out->bundleWriter(out).use {z->
                z.addIdentity(BundleIdentity(owner,"a".repeat(64),true));z.addMarker(AnchorMetadata(owner))
                z.putDeflated("manifest.json","""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"reference_id":"$page","items":[]}]}""".encodeToByteArray())
                z.putDeflated("assets/source.pdf","different B".encodeToByteArray())
            }}.bytes()
            val codec=note().apply {resolvePdf={protected}}
            try {codec.read(bytes.asSource(),pdfDir=dir,pdfStore={input->assertEquals("different B",input.buffer().readByteArray().decodeToString());admitted});fail("embedded data must not override a reference-only original")} catch(_:IllegalArgumentException) {}
            assertEquals("protected A",protected.fileText());assertEquals("different B",admitted.fileText())
        } finally {dir.deleteTree()}
    }

}
