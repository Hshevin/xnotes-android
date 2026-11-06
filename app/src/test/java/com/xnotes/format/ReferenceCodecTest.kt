package com.xnotes.format

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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ReferenceCodecTest {
    private val text=FakeTextMeasurer()
    private fun note()=DocumentCodec(FakeImageCodec(),text)
    private fun canvas()=CanvasCodec(FakeImageCodec(),text)
    private fun ink()=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(1.0,2.0,.7)))
    @Test fun enabledNoteAndCanvasRoundTripIdsAndLegacyModeDoesNotAllocateOrWriteThem() {
        val doc=Document.blank();val p=doc.pages.first();p.items.add(ink())
        val old=ByteArrayOutputStream().also {note().write(doc.snapshot(),it)}.toByteArray()
        assertNull(p.referenceIdentity.id);assertFalse(note().read(old.inputStream()).hasReferenceAnchors)
        doc.enableReferenceAnchors()
        val next=ByteArrayOutputStream().also {note().write(doc.snapshot(),it)}.toByteArray()
        val loaded=note().read(next.inputStream());assertTrue(loaded.hasReferenceAnchors)
        assertEquals(p.referenceIdentity.id,loaded.pages.first().referenceIdentity.id)
        assertEquals(p.items.first().referenceIdentity.id,loaded.pages.first().items.first().referenceIdentity.id)
        val board=InfiniteDocument();board.add(ink());board.enableReferenceAnchors()
        val bytes=ByteArrayOutputStream().also {canvas().write(board.snapshotForWrite(),it)}.toByteArray()
        val reopened=canvas().read(bytes.inputStream());assertTrue(reopened.hasReferenceAnchors)
        assertEquals(board.items.first().referenceIdentity.id,reopened.items.first().referenceIdentity.id)
    }
    private val owner="00000000-0000-0000-0000-000000000001"
    private val page="00000000-0000-0000-0000-000000000002"
    private val item="00000000-0000-0000-0000-000000000003"
    private fun bundle(manifest:String,marker:Boolean=true,extra:Map<String,ByteArray> = emptyMap()):ByteArray = ByteArrayOutputStream().also {out->ZipOutputStream(out).use {z->
        fun entry(name:String,b:ByteArray) {z.putNextEntry(ZipEntry(name));z.write(b);z.closeEntry()}
        entry("manifest.json",manifest.toByteArray());BundleIdentity(owner).write(z)
        extra.forEach {(n,b)->entry(n,b)}
        if(marker) entry(BundleAnchors.ENTRY,"""{"format":"xnotes-object-anchors","version":1,"documentId":"$owner"}""".toByteArray())
    }}.toByteArray()
    private fun rejected(bytes:ByteArray,isCanvas:Boolean=false) {
        try {if(isCanvas) canvas().read(bytes.inputStream()) else note().read(bytes.inputStream());fail("must not expose partial writable anchored model")} catch(_:IllegalArgumentException) {} catch(_:JsonPullException) {}
    }
    @Test fun markerAfterManifestStillRejectsUnknownMissingDuplicateAndUnmarkedIdentities() {
        rejected(bundle("""{"format":"xnote","version":1,"pages":[{"reference_id":"$page","items":[{"kind":"future","reference_id":"$item"}]}]}"""))
        rejected(bundle("""{"format":"xnote","version":1,"pages":[{"items":[]}]}"""))
        rejected(bundle("""{"format":"xnote","version":1,"pages":[]}"""))
        rejected(bundle("""{"format":"xnote","version":1,"pages":[{"reference_id":"$page","items":[]},{"reference_id":"$page","items":[]}]}"""))
        rejected(bundle("""{"format":"xnote","version":1,"pages":[{"reference_id":"$page","items":[]}]}""",marker=false))
        rejected(bundle("""{"format":"xcanvas","version":1,"items":[{"kind":"future","reference_id":"$item"}]}"""),true)
    }
    @Test fun failedAnchoredReadCleansOnlyNewAssetsAndDoesNotDeleteBorrowedPdf() {
        val dir=Files.createTempDirectory("anchor-read-").toFile();val keep=File(dir,"keep.pdf").apply {writeText("original")}
        try {
            val bytes=bundle("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"reference_id":"$page","items":[{"kind":"image","asset":"assets/image-missing.png","reference_id":"$item"}]}]}""",extra=mapOf("assets/source.pdf" to keep.readBytes(),"assets/image-unused.png" to byteArrayOf(1,2,3)))
            try {note().read(bytes.inputStream(),pdfDir=dir,imageDir=dir,pdfStore={input->input.readBytes();keep});fail("missing image")} catch(_:IllegalArgumentException) {}
            assertEquals("original",keep.readText());assertEquals(listOf("keep.pdf"),dir.list()!!.toList())
        } finally {dir.deleteRecursively()}
    }
    @Test fun anchoredMissingContainersAndInvalidTapeCannotBecomeEmptyWritableDocuments() {
        rejected(bundle("""{"format":"xcanvas","version":1}"""),true)
        rejected(bundle("""{"format":"xnote","version":1,"pages":[{"reference_id":"$page","items":[{"kind":"tape","reference_id":"$item"}]}]}"""))
    }
    @Test fun writerDoesNotAllocateIdsForUnpreparedLiveMembers() {
        val doc=Document.blank();doc.enableReferenceAnchors();val item=ink().also {doc.pages.first().items.add(it)}
        try {note().write(doc,ByteArrayOutputStream());fail("writer cannot prepare live IDs")} catch(_:IllegalArgumentException) {}
        assertNull(item.referenceIdentity.id)
        val snapshot=doc.snapshot();assertNotNull(item.referenceIdentity.id)
        note().write(snapshot,ByteArrayOutputStream())
    }
    @Test fun everySupportedKindKeepsIdentityIncludingPendingAssetsAndConnectorBindings() {
        val dir=Files.createTempDirectory("anchor-kinds-").toFile()
        try {
            val image=File(dir,"input.png").apply {writeText("fake encoded image")}
            val sound=File(dir,"input.m4a").apply {writeText("fake encoded audio")}
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
            val bytes=ByteArrayOutputStream().also {note().write(doc.snapshot(),it)}.toByteArray()
            val loaded=note().read(bytes.inputStream(),imageDir=dir)
            assertEquals(doc.pages.first().items.map {it.referenceIdentity.id},loaded.pages.first().items.map {it.referenceIdentity.id})
            assertEquals(7,loaded.pages.first().items.size)
            assertSame(loaded.pages.first().items.filterIsInstance<ShapeItem>().single(),loaded.pages.first().items.filterIsInstance<ConnectorItem>().single().start.target)
            val board=InfiniteDocument();board.addAll(items(false));board.enableReferenceAnchors()
            val canvasBytes=ByteArrayOutputStream().also {canvas().write(board.snapshotForWrite(),it)}.toByteArray()
            val reopened=canvas().read(canvasBytes.inputStream(),imageDir=dir)
            assertEquals(board.items.map {it.referenceIdentity.id},reopened.items.map {it.referenceIdentity.id});assertEquals(6,reopened.items.size)
            // Deliberately skipping assets must not yield a writable partial anchored model.
            rejected(bytes);rejected(canvasBytes,true)
        } finally {dir.deleteRecursively()}
    }

    @Test fun anchoredVersionMustBeExactIntegerOneRatherThanDoubleRoundedNearOne() {
        for(version in listOf("1.0","1e0","1.0000000000000000000001","01","2","\"1\"")) {
            rejected(bundle("""{"format":"xcanvas","version":$version,"items":[]}"""),true)
        }
    }
    @Test fun anchoredPdfCannotBeReturnedAsCompleteWhenMissingOrDeliberatelySkipped() {
        val manifest="""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"pdf_page":0,"reference_id":"$page","items":[]}]}"""
        rejected(bundle(manifest))
        rejected(bundle(manifest,extra=mapOf("assets/source.pdf" to "%PDF-1.7 original bytes".toByteArray())))
    }

    @Test fun explicitInkCopyKeepsContentButMintsAllIndependentIdsAndNeverWritesSource() {
        val dir=Files.createTempDirectory("ink-copy-").toFile()
        try {
            val original=File(dir,"original.pdf").apply {writeText("%PDF-1.7 immutable")}
            val doc=Document.blank().apply {pdfFile=original}
            val p=doc.pages.first();p.width=600.0;p.height=800.0;p.pdfPage=0;p.pdfTurn=1;p.layers.create("Notes")
            p.items.add(ink());doc.enableReferenceAnchors()
            val file=File(dir,"source.xnote").apply {outputStream().use {note().write(doc.snapshot(),it)}}
            val hash=BundleMigration.hash(file)
            val copy=file.inputStream().use {note().readInkCopy(it,imageDir=dir)}
            assertEquals(hash,BundleMigration.hash(file));assertNotEquals(doc.identity.documentId,copy.identity.documentId)
            assertNull(copy.pdfFile);assertNull(copy.identity.pdfResource);assertFalse(copy.identity.externalPdf)
            assertTrue(copy.hasReferenceAnchors);assertTrue(copy.dirty);assertNull(copy.pages.first().pdfPage);assertEquals(0,copy.pages.first().pdfTurn)
            assertEquals(p.width,copy.pages.first().width,0.0);assertEquals(p.height,copy.pages.first().height,0.0)
            assertEquals(p.layers.entries,copy.pages.first().layers.entries)
            assertEquals((p.items.first() as Stroke).samples,(copy.pages.first().items.first() as Stroke).samples)
            val oldIds=(listOf(p.referenceIdentity.id)+p.items.map {it.referenceIdentity.id}).toSet()
            val newIds=(listOf(copy.pages.first().referenceIdentity.id)+copy.pages.first().items.map {it.referenceIdentity.id}).toSet()
            assertTrue(oldIds.intersect(newIds).isEmpty())
            val output=ByteArrayOutputStream().also {note().write(copy.snapshot(),it)}.toByteArray()
            val reopened=note().read(output.inputStream(),imageDir=dir)
            assertEquals(copy.identity,reopened.identity);assertEquals(copy.pages.first().referenceIdentity.id,reopened.pages.first().referenceIdentity.id)
            assertEquals(hash,BundleMigration.hash(file));assertEquals("%PDF-1.7 immutable",original.readText())
            val unknown=bundle("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"reference_id":"$page","items":[{"kind":"future","reference_id":"$item"}]}]}""")
            try {note().readInkCopy(unknown.inputStream(),dir);fail("copy cannot lose unknown content")} catch(_:IllegalArgumentException) {}
            val missingImage=bundle("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"reference_id":"$page","items":[{"kind":"image","asset":"assets/image-gone.png","reference_id":"$item"}]}]}""")
            try {note().readInkCopy(missingImage.inputStream(),dir);fail("copy cannot lose an image")} catch(_:IllegalArgumentException) {}
        } finally {dir.deleteRecursively()}
    }
    @Test fun borrowedResolvedPdfSurvivesAnyAnchoredValidationFailure() {
        val dir=Files.createTempDirectory("resolved-pdf-").toFile();val pdf=File(dir,"managed.pdf").apply {writeText("immutable")}
        try {
            val resource="a".repeat(64);val identity=BundleIdentity(owner,resource,true)
            fun encoded(known:Boolean)=ByteArrayOutputStream().also {out->ZipOutputStream(out).use {z->
                identity.write(z);AnchorMetadata(owner).write(z)
                z.putNextEntry(ZipEntry("manifest.json"));z.write("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"reference_id":"$page","items":${if(known) "[]" else "[{\"kind\":\"future\",\"reference_id\":\"$item\"}]"}}]}""".toByteArray());z.closeEntry()
            }}.toByteArray()
            val codec=note().apply {resolvePdf={assertEquals(resource,it);pdf}}
            val loaded=codec.read(encoded(true).inputStream(),pdfDir=dir)
            assertEquals(pdf,loaded.pdfFile);assertTrue(loaded.hasReferenceAnchors)
            try {codec.read(encoded(false).inputStream(),pdfDir=dir);fail("partial model")} catch(_:IllegalArgumentException) {}
            assertEquals("immutable",pdf.readText());assertEquals(listOf("managed.pdf"),dir.list()!!.toList())
        } finally {dir.deleteRecursively()}
    }

    @Test fun legacyInkRecoveryStaysLegacyUntilStorageActivationAndKeepsLargeModelCompatibility() {
        val bytes=bundle("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"pdf_page":0,"items":[{"kind":"stroke","samples":[[1,2,0.7],[3,4,0.8]]}]}]}""",marker=false)
        val copy=note().readInkCopy(bytes.inputStream())
        assertFalse(copy.hasReferenceAnchors);assertNotEquals(owner,copy.identity.documentId)
        assertNull(copy.pages.first().referenceIdentity.id);assertNull(copy.pages.first().items.first().referenceIdentity.id)
        assertNull(copy.pdfFile);assertNull(copy.pages.first().pdfPage)
        val output=ByteArrayOutputStream().also {note().write(copy.snapshot(),it)}.toByteArray()
        assertFalse(note().read(output.inputStream()).hasReferenceAnchors)
    }

    @Test fun referenceOnlyPdfIdentityCannotBeOverriddenByUnexpectedEmbeddedBytes() {
        val dir=Files.createTempDirectory("ambiguous-pdf-").toFile()
        val protected=File(dir,"managed.pdf").apply {writeText("protected A")}
        val admitted=File(dir,"admitted.pdf").apply {writeText("different B")}
        try {
            val bytes=ByteArrayOutputStream().also {out->ZipOutputStream(out).use {z->
                BundleIdentity(owner,"a".repeat(64),true).write(z);AnchorMetadata(owner).write(z)
                z.putNextEntry(ZipEntry("manifest.json"));z.write("""{"format":"xnote","version":1,"has_pdf":true,"pages":[{"reference_id":"$page","items":[]}]}""".toByteArray());z.closeEntry()
                z.putNextEntry(ZipEntry("assets/source.pdf"));z.write("different B".toByteArray());z.closeEntry()
            }}.toByteArray()
            val codec=note().apply {resolvePdf={protected}}
            try {codec.read(bytes.inputStream(),pdfDir=dir,pdfStore={input->assertEquals("different B",input.readBytes().toString(Charsets.UTF_8));admitted});fail("embedded data must not override a reference-only original")} catch(_:IllegalArgumentException) {}
            assertEquals("protected A",protected.readText());assertEquals("different B",admitted.readText())
        } finally {dir.deleteRecursively()}
    }

}
