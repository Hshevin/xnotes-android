package com.xnotes.format

import com.xnotes.core.pal.*
import com.xnotes.kio.*
import okio.*
import kotlin.test.assertFailsWith

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.*
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.testkit.*
import kotlin.test.Test

/** Page references cannot impose identity or count requirements on individual drawing objects. */
class PageReferenceContractTest : BundleTestFiles() {
    private val codec=DocumentCodec(FakeImageCodec(),FakeTextMeasurer())
    private val owner="00000000-0000-0000-0000-000000000001"
    private val page="00000000-0000-0000-0000-000000000002"
    private fun entries(bytes:ByteArray):Map<String,ByteArray> = buildMap {
        bundleReader(bytes.asSource()).use {z->
            var e=z.next()
            while(e!=null) {put(e.name,e.data.readByteArray());e.finish();e=z.next()}
        }
    }
    private fun bundle(items:String,extra:Map<String,ByteArray> = emptyMap()):ByteArray = Buffer().also {out->bundleWriter(out).use {z->
        z.addIdentity(BundleIdentity(owner));z.addMarker(AnchorMetadata(owner))
        z.putDeflated("manifest.json","""{"format":"xnote","version":1,"pages":[{"reference_id":"$page","items":$items}]}""".encodeToByteArray())
        extra.forEach {(name,data)->z.putDeflated(name,data)}
    }}.bytes()
    @Test fun onlyThePageGetsAnIdentityAndInkSamplesAreUnchanged() {
        val doc=Document.blank();val p=doc.pages.single()
        val samples=listOf(Sample(-3.5,7.0,.5),Sample(13.0,17.0,.25))
        p.items+=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),samples)
        doc.enableReferenceAnchors()
        val bytes=Buffer().also {codec.write(doc.snapshot(),it)}.bytes()
        val manifest=TestJsonTree.parse(entries(bytes).getValue("manifest.json").decodeToString())
        val saved=manifest.field("pages").item(0)
        assertEquals(p.referenceIdentity.id,saved.string("reference_id"))
        assertFalse(saved.field("items").item(0).has("reference_id"))
        assertEquals(samples,(codec.read(bytes.asSource()).pages.single().items.single() as Stroke).samples)
    }
    @Test fun pageReferenceSurvivesUnknownAndUnreadableObjectsAndRecoveryCountsTheirOccurrences() {
        val bytes=bundle("""[{"kind":"stroke","samples":[[1,2,0.7],[3,4,0.8]]},{"kind":"future"},{"kind":"image","asset":"assets/image-gone.png"},{"kind":"image","asset":"assets/image-gone.png"}]""")
        val loaded=codec.read(bytes.asSource())
        assertEquals(page,loaded.pages.single().referenceIdentity.id)
        assertEquals(1,loaded.pages.single().items.size)
        val rescued=codec.readInkCopy(bytes.asSource())
        assertEquals(3,rescued.omittedOnCopy)
        assertNotEquals(page,rescued.pages.single().referenceIdentity.id)
    }
    @Test fun twoHundredThousandObjectsDoNotConsumeThePageAnchorBudgetOrRewriteTheirBytes() {
        val items=List(200001) {"{\"kind\":\"future\",\"n\":1.00000e+02}"}.joinToString(",")
        val source="""{"format":"xnote","version":1,"pages":[{"items":[$items]}]}"""
        val open={source.encodeToByteArray().asSource()}
        val plan=AnchorManifest.inspectSource(open,false)
        assertEquals(1,plan.identities.size);assertEquals(1,plan.insertions.size)
        val result=Buffer().also {AnchorManifest.write(open,it,plan)}.readUtf8()
        assertEquals(source,result.replace(Regex(",\\\"reference_id\\\":\\\"[a-f0-9-]{36}\\\""),""))
    }
    @Test fun recoveryCountsBadResourcesPerObjectAcrossPagesEvenWithStoredImageDimensions() {
        val dir=testDirectory("page-recovery-")
        try {
            val image="""{"kind":"image","asset":"assets/image-bad.png","src_w":30,"src_h":40}"""
            val ink="""{"kind":"stroke","samples":[[1,2,0.5],[3,4,0.5]]}"""
            val bytes=Buffer().also {out->bundleWriter(out).use {z->
                z.addIdentity(BundleIdentity(owner));z.addMarker(AnchorMetadata(owner))
                z.putDeflated("manifest.json","""{"format":"xnote","version":1,"pages":[{"reference_id":"$page","items":[$image,$ink,$image]},{"reference_id":"00000000-0000-0000-0000-000000000003","items":[null,$image,{"kind":"tape"},$ink,{"kind":"future"}]}]}""".encodeToByteArray())
                z.putDeflated("assets/image-bad.png",byteArrayOf(1,2,3))
            }}.bytes()
            val unreadable=object:com.xnotes.core.pal.ImageCodec {
                override fun probeFile(path:String):com.xnotes.core.pal.ImageSize?=null
            }
            val rescue=DocumentCodec(unreadable,FakeTextMeasurer()).readInkCopy(bytes.asSource(),dir)
            assertEquals(6,rescue.omittedOnCopy)
            assertEquals(listOf(1,1),rescue.pages.map {it.items.size})
            assertTrue(rescue.pages.all {it.items.single() is Stroke})
            assertTrue(rescue.pages.none {it.referenceIdentity.id==page})
        } finally {dir.deleteTree()}
    }
    @Test fun canvasHasOnlyDocumentIdentityAndNeverWritesPageOrObjectAnchors() {
        val board=com.xnotes.core.infinite.InfiniteDocument()
        board.add(Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(1.0,2.0,.5))))
        val canvas=CanvasCodec(FakeImageCodec(),FakeTextMeasurer())
        val bytes=Buffer().also {canvas.write(board.snapshotForWrite(),it)}.bytes()
        val stored=entries(bytes)
        assertFalse(AnchorMetadata.ENTRY in stored)
        assertFalse(stored.getValue("manifest.json").decodeToString().contains("reference_id"))
        assertEquals(board.identity,canvas.read(bytes.asSource()).identity)
    }
    @Test fun recoverySkipsObjectsOnUnknownLayersWithoutRetainingDanglingConnectorTargets() {
        val dir=testDirectory("page-bad-layer-")
        try {
            val items="""[{"kind":"stroke","layer":"missing","samples":[[1,2,0.5]]},{"kind":"shape","id":1,"layer":"missing","shape":"rectangle","start":[0,0],"end":[100,100]},{"kind":"image","layer":"missing","asset":"assets/image-test.png","src_w":30,"src_h":40},{"kind":"stroke","samples":[[3,4,0.5]]},{"kind":"shape","connector":{"routing":"straight","from":{"at":[0,0],"item":1,"point":0},"to":{"at":[200,200]}}}]"""
            val bytes=bundle(items,mapOf("assets/image-test.png" to byteArrayOf(1,2,3)))
            val before=bytes.copyOf()
            try {codec.read(bytes.asSource(),imageDir=dir);fail("normal read must retain layer validation")} catch(_:IllegalArgumentException) {}
            val rescue=codec.readInkCopy(bytes.asSource(),dir)
            assertEquals(3,rescue.omittedOnCopy)
            assertEquals(2,rescue.pages.single().items.size)
            assertEquals(DrawingLayers.DEFAULT,rescue.pages.single().items.filterIsInstance<Stroke>().single().layerId)
            assertNull(rescue.pages.single().items.filterIsInstance<ConnectorItem>().single().start.target)
            assertNotEquals(page,rescue.pages.single().referenceIdentity.id)
            assertArrayEquals(before,bytes)
        } finally {dir.deleteTree()}
    }
    @Test fun recoveredPendingImagesKeepTheirOriginalOrderAfterEarlierBadObjectsAreRemoved() {
        val dir=testDirectory("page-recovery-order-")
        try {
            val bad="""{"kind":"stroke","layer":"missing","samples":[[1,2,0.5]]}"""
            val image="""{"kind":"image","asset":"assets/image-test.png","src_w":30,"src_h":40}"""
            val good="""{"kind":"stroke","samples":[[3,4,0.5]]}"""
            for(tail in listOf("",",$good")) {
                val bytes=bundle("[$bad,$image$tail]",mapOf("assets/image-test.png" to byteArrayOf(1,2,3)))
                val rescue=codec.readInkCopy(bytes.asSource(),dir)
                assertEquals(1,rescue.omittedOnCopy)
                assertEquals(if(tail.isEmpty()) listOf("image") else listOf("image","stroke"),rescue.pages.single().items.map {it.kind})
            }
        } finally {dir.deleteTree()}
    }
}
