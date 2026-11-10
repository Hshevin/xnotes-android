package com.xnotes.mindmap

import com.xnotes.core.geometry.Rect
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CanvasRegionFormatTest {
    private val owner="00000000-0000-4000-8000-000000000001"
    private val rect=Rect(-120.0,-80.0,300.0,200.0)
    private fun region()=MapSource(documentId=owner,scope=SourceScope.CANVAS_REGION,region=rect)
    private fun graph(source:MapSource)=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,sources=listOf(source))))
    private fun source(j:JSONObject)=j.getJSONArray("nodes").getJSONObject(0).getJSONArray("sources").getJSONObject(0)
    private fun asset():MapPreviewAsset {
        val out=ByteArrayOutputStream();val data=java.io.DataOutputStream(out)
        data.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(name:String,body:ByteArray) {val type=name.toByteArray();val crc=CRC32().apply {update(type);update(body)}
            data.writeInt(body.size);data.write(type);data.write(body);data.writeInt(crc.value.toInt())}
        val header=ByteArrayOutputStream();java.io.DataOutputStream(header).use {it.writeInt(2);it.writeInt(2);it.write(byteArrayOf(8,6,0,0,0))}
        chunk("IHDR",header.toByteArray());val pixels=ByteArrayOutputStream()
        DeflaterOutputStream(pixels).use {repeat(2) {_ ->it.write(0);it.write(ByteArray(8) {255.toByte()})}}
        chunk("IDAT",pixels.toByteArray());chunk("IEND",byteArrayOf());return MapPreviewAsset.fromPng(out.toByteArray())
    }
    @Test fun fixedWorldRectWritesVersionFiveAndRoundTripsWithoutPageOrObjectIdentity() {
        val doc=graph(region());val bytes=MapCodec.encode(doc);val j=JSONObject(String(bytes))
        assertEquals(5,j.getInt("version"));assertEquals("MIND_MAP",j.getString("organization"))
        assertTrue(j.getJSONArray("nodes").getJSONObject(0).has("fileCardId"))
        assertEquals(doc,MapCodec.decode(bytes));assertEquals(rect,MapCodec.decode(bytes).root.sources.single().region)
        assertTrue(source(j).isNull("pageId"));assertFalse(source(j).has("objectIds"));assertFalse(source(j).has("lastKnownPage"))
        val file=graph(MapSource(documentId=owner));assertEquals(3,JSONObject(String(MapCodec.encode(file))).getInt("version"))
    }
    @Test fun olderVersionsRejectNewScopeEvenInTheirNormallyIgnoredSourceAndPreviewFields() {
        val png=asset();val original=region().let {it.copy(previewKey=png.key,lastPreview=MapLastPreview(png.key,it.target,"native:v1",1))}
        val doc=graph(original).copy(previewAssets=mapOf(png.key to png))
        for(version in 1..4) {
            val current=JSONObject(String(MapCodec.encode(doc))).put("version",version)
            assertThrows("current scope in v$version",IllegalArgumentException::class.java) {MapCodec.decode(current.toString().toByteArray())}
            source(current).put("scope","FILE").put("region",JSONObject.NULL)
            assertThrows("preview target in v$version",IllegalArgumentException::class.java) {MapCodec.decode(current.toString().toByteArray())}
        }
        val unrelated=JSONObject(String(MapCodec.encode(graph(MapSource(documentId=owner))))).put("version",1)
        source(unrelated).put("extension",JSONObject().put("scope","CANVAS_REGION"))
        assertTrue(MapCodec.decode(unrelated.toString().toByteArray()).root.sources.isEmpty())
    }
    @Test fun retainedCanvasRangePixelsKeepVersionFiveAfterTheCurrentTargetBecomesWholeFile() {
        val png=asset();val old=region().target
        val source=MapSource(documentId=owner,previewKey=png.key,lastPreview=MapLastPreview(png.key,old,"native:v1",1))
        val doc=graph(source).copy(previewAssets=mapOf(png.key to png));val bytes=MapCodec.encode(doc)
        assertEquals(5,JSONObject(String(bytes)).getInt("version"));assertEquals(doc,MapCodec.decode(bytes))
        assertEquals(old,MapCodec.decode(bytes).root.sources.single().lastPreview!!.target)
    }
    @Test fun canvasScopeRequiresRectAndNoPageWhileExistingFileAndPageRulesRemainStrict() {
        for(source in listOf(region().copy(region=null),region().copy(pageId=owner),region().copy(lastKnownPage=1),
            region().copy(region=Rect(0.0,0.0,0.0,10.0)),region().copy(region=Rect(Double.NaN,0.0,10.0,10.0)),
            MapSource(documentId=owner,region=rect))) assertThrows(IllegalArgumentException::class.java) {source.validate()}
        region().validate();MapSource(documentId=owner,scope=SourceScope.PAGE,pageId=owner,region=rect).validate()
        assertThrows(IllegalArgumentException::class.java) {region().target.copy(pageId=owner).validate()}
    }
    @Test fun sameRectOlderVersionAndEmptyRegionPreviewsAreAlwaysLabelledHistorical() {
        val png=asset();val source=region().let {it.copy(previewKey=png.key,status=SourceStatus.CURRENT,
            observedVersion="native:v1",lastPreview=MapLastPreview(png.key,it.target,"native:v1",1))}
        assertFalse(source.hasHistoricalPreview)
        assertTrue(source.copy(observedVersion="native:v2").hasHistoricalPreview)
        assertTrue(source.copy(status=SourceStatus.EMPTY).hasHistoricalPreview)
        assertTrue(source.copy(region=rect.copy(x=50.0)).hasHistoricalPreview)
        assertTrue(source.copy(status=SourceStatus.UNAVAILABLE).hasHistoricalPreview)
        assertTrue(source.copy(acceptedPreviewVersion="native:v2").hasHistoricalPreview)
        val native=source.copy(observedVersion=null,sourceVersion="new-raw",lastPreview=source.lastPreview!!.copy(renderedVersion="native-sha256:old-raw"))
        assertTrue(native.hasHistoricalPreview)
    }
}
