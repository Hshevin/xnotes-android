package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.*
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Page references cannot impose identity or count requirements on individual drawing objects. */
class PageReferenceContractTest {
    private val codec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
    private val owner="00000000-0000-0000-0000-000000000001"
    private val page="00000000-0000-0000-0000-000000000002"
    private fun entries(bytes:ByteArray):Map<String,ByteArray> = buildMap {
        ZipInputStream(bytes.inputStream()).use {z->
            var e=z.nextEntry
            while(e!=null) {put(e.name,z.readBytes());z.closeEntry();e=z.nextEntry}
        }
    }
    private fun bundle(items:String,extra:Map<String,ByteArray> = emptyMap()):ByteArray = ByteArrayOutputStream().also {out->ZipOutputStream(out).use {z->
        BundleIdentity(owner).write(z);AnchorMetadata(owner).write(z)
        z.putNextEntry(ZipEntry("manifest.json"))
        z.write("""{"format":"xnote","version":1,"pages":[{"reference_id":"$page","items":$items}]}""".toByteArray());z.closeEntry()
        extra.forEach {(name,data)->z.putNextEntry(ZipEntry(name));z.write(data);z.closeEntry()}
    }}.toByteArray()
    @Test fun onlyThePageGetsAnIdentityAndInkSamplesAreUnchanged() {
        val doc=Document.blank();val p=doc.pages.single()
        val samples=listOf(Sample(-3.5,7.0,.5),Sample(13.0,17.0,.25))
        p.items+=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),samples)
        doc.enableReferenceAnchors()
        val bytes=ByteArrayOutputStream().also {codec.write(doc.snapshot(),it)}.toByteArray()
        val manifest=JSONObject(entries(bytes).getValue("manifest.json").toString(Charsets.UTF_8))
        val saved=manifest.getJSONArray("pages").getJSONObject(0)
        assertEquals(p.referenceIdentity.id,saved.getString("reference_id"))
        assertFalse(saved.getJSONArray("items").getJSONObject(0).has("reference_id"))
        assertEquals(samples,(codec.read(bytes.inputStream()).pages.single().items.single() as Stroke).samples)
    }
    @Test fun pageReferenceSurvivesUnknownAndUnreadableObjectsAndRecoveryCountsTheirOccurrences() {
        val bytes=bundle("""[{"kind":"stroke","samples":[[1,2,0.7],[3,4,0.8]]},{"kind":"future"},{"kind":"image","asset":"assets/image-gone.png"},{"kind":"image","asset":"assets/image-gone.png"}]""")
        val loaded=codec.read(bytes.inputStream())
        assertEquals(page,loaded.pages.single().referenceIdentity.id)
        assertEquals(1,loaded.pages.single().items.size)
        val rescued=codec.readInkCopy(bytes.inputStream())
        assertEquals(3,rescued.omittedOnCopy)
        assertNotEquals(page,rescued.pages.single().referenceIdentity.id)
    }
    @Test fun twoHundredThousandObjectsDoNotConsumeThePageAnchorBudgetOrRewriteTheirBytes() {
        val items=List(200001) {"{\"kind\":\"future\",\"n\":1.00000e+02}"}.joinToString(",")
        val source="""{"format":"xnote","version":1,"pages":[{"items":[$items]}]}"""
        val open={source.byteInputStream()}
        val plan=AnchorManifest.inspect(open,false)
        assertEquals(1,plan.identities.size);assertEquals(1,plan.insertions.size)
        val result=ByteArrayOutputStream().also {AnchorManifest.write(open,it,plan)}.toString("UTF-8")
        assertEquals(source,result.replace(Regex(",\\\"reference_id\\\":\\\"[a-f0-9-]{36}\\\""),""))
    }
    @Test fun recoveryCountsBadResourcesPerObjectAcrossPagesEvenWithStoredImageDimensions() {
        val dir=Files.createTempDirectory("page-recovery-").toFile()
        try {
            val image="""{"kind":"image","asset":"assets/image-bad.png","src_w":30,"src_h":40}"""
            val ink="""{"kind":"stroke","samples":[[1,2,0.5],[3,4,0.5]]}"""
            val bytes=ByteArrayOutputStream().also {out->ZipOutputStream(out).use {z->
                BundleIdentity(owner).write(z);AnchorMetadata(owner).write(z)
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write("""{"format":"xnote","version":1,"pages":[{"reference_id":"$page","items":[$image,$ink,$image]},{"reference_id":"00000000-0000-0000-0000-000000000003","items":[null,$image,{"kind":"tape"},$ink,{"kind":"future"}]}]}""".toByteArray());z.closeEntry()
                z.putNextEntry(ZipEntry("assets/image-bad.png"));z.write(byteArrayOf(1,2,3));z.closeEntry()
            }}.toByteArray()
            val unreadable=object:com.xnotes.core.pal.ImageCodec {
                override fun probeFile(path:String):com.xnotes.core.pal.ImageSize?=null
            }
            val rescue=DocumentCodec(unreadable,FakeTextMeasurer()).readInkCopy(bytes.inputStream(),dir)
            assertEquals(6,rescue.omittedOnCopy)
            assertEquals(listOf(1,1),rescue.pages.map {it.items.size})
            assertTrue(rescue.pages.all {it.items.single() is Stroke})
            assertTrue(rescue.pages.none {it.referenceIdentity.id==page})
        } finally {dir.deleteRecursively()}
    }
    @Test fun canvasHasOnlyDocumentIdentityAndNeverWritesPageOrObjectAnchors() {
        val board=com.xnotes.core.infinite.InfiniteDocument()
        board.add(Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(1.0,2.0,.5))))
        val canvas=CanvasCodec(FakeImageCodec(),FakeTextMeasurer())
        val bytes=ByteArrayOutputStream().also {canvas.write(board.snapshotForWrite(),it)}.toByteArray()
        val stored=entries(bytes)
        assertFalse(AnchorMetadata.ENTRY in stored)
        assertFalse(stored.getValue("manifest.json").toString(Charsets.UTF_8).contains("reference_id"))
        assertEquals(board.identity,canvas.read(bytes.inputStream()).identity)
    }
    @Test fun recoverySkipsObjectsOnUnknownLayersWithoutRetainingDanglingConnectorTargets() {
        val dir=Files.createTempDirectory("page-bad-layer-").toFile()
        try {
            val items="""[{"kind":"stroke","layer":"missing","samples":[[1,2,0.5]]},{"kind":"shape","id":1,"layer":"missing","shape":"rectangle","start":[0,0],"end":[100,100]},{"kind":"image","layer":"missing","asset":"assets/image-test.png","src_w":30,"src_h":40},{"kind":"stroke","samples":[[3,4,0.5]]},{"kind":"shape","connector":{"routing":"straight","from":{"at":[0,0],"item":1,"point":0},"to":{"at":[200,200]}}}]"""
            val bytes=bundle(items,mapOf("assets/image-test.png" to byteArrayOf(1,2,3)))
            val before=bytes.clone()
            try {codec.read(bytes.inputStream(),imageDir=dir);fail("normal read must retain layer validation")} catch(_:IllegalArgumentException) {}
            val rescue=codec.readInkCopy(bytes.inputStream(),dir)
            assertEquals(3,rescue.omittedOnCopy)
            assertEquals(2,rescue.pages.single().items.size)
            assertEquals(DrawingLayers.DEFAULT,rescue.pages.single().items.filterIsInstance<Stroke>().single().layerId)
            assertNull(rescue.pages.single().items.filterIsInstance<ConnectorItem>().single().start.target)
            assertNotEquals(page,rescue.pages.single().referenceIdentity.id)
            assertArrayEquals(before,bytes)
        } finally {dir.deleteRecursively()}
    }
    @Test fun recoveredPendingImagesKeepTheirOriginalOrderAfterEarlierBadObjectsAreRemoved() {
        val dir=Files.createTempDirectory("page-recovery-order-").toFile()
        try {
            val bad="""{"kind":"stroke","layer":"missing","samples":[[1,2,0.5]]}"""
            val image="""{"kind":"image","asset":"assets/image-test.png","src_w":30,"src_h":40}"""
            val good="""{"kind":"stroke","samples":[[3,4,0.5]]}"""
            for(tail in listOf("",",$good")) {
                val bytes=bundle("[$bad,$image$tail]",mapOf("assets/image-test.png" to byteArrayOf(1,2,3)))
                val rescue=codec.readInkCopy(bytes.inputStream(),dir)
                assertEquals(1,rescue.omittedOnCopy)
                assertEquals(if(tail.isEmpty()) listOf("image") else listOf("image","stroke"),rescue.pages.single().items.map {it.kind})
            }
        } finally {dir.deleteRecursively()}
    }
}
