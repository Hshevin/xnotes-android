package com.xnotes.mindmap

import com.xnotes.core.geometry.Rect
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OriginalPdfFormatTest {
    private val owner="00000000-0000-4000-8000-000000000011"
    private val page="00000000-0000-4000-8000-000000000012"
    private val projection=PdfOriginalProjection("a".repeat(64),3,2,Rect(-20.0,30.0,120.0,80.0))
    private fun source()=MapSource(documentId=owner,scope=SourceScope.PDF_ORIGINAL,pageId=page,originalPdf=projection,lastKnownPage=4)
    private fun graph(s:MapSource)=MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,sources=listOf(s))))
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
    @Test fun independentOriginalAndUserSpaceProjectionRoundTripAsVersionSix() {
        val doc=graph(source());val bytes=MapCodec.encode(doc);val json=JSONObject(String(bytes))
        assertEquals(6,json.getInt("version"));assertEquals(doc,MapCodec.decode(bytes))
        assertTrue(source(json).isNull("region"));assertEquals(page,source(json).getString("pageId"))
        assertEquals(-20.0,source(json).getJSONObject("originalPdf").getJSONArray("userRegion").getDouble(0),0.0)
        assertEquals(3,JSONObject(String(MapCodec.encode(graph(MapSource(documentId=owner))))).getInt("version"))
    }
    @Test fun retainedOriginalPixelsKeepVersionSixAfterCurrentSourceChanges() {
        val png=asset();val old=source().target
        val changed=MapSource(documentId=owner,previewKey=png.key,lastPreview=MapLastPreview(png.key,old,"original-pdf-v1:old",1))
        val doc=graph(changed).copy(previewAssets=mapOf(png.key to png));val encoded=MapCodec.encode(doc)
        assertEquals(6,JSONObject(String(encoded)).getInt("version"));assertEquals(doc,MapCodec.decode(encoded))
    }
    @Test fun versionsOneThroughFiveRejectNewCurrentSemanticsBeforeLegacySkips() {
        for(version in 1..5) {
            val json=JSONObject(String(MapCodec.encode(graph(source())))).put("version",version)
            assertThrows("scope v$version",IllegalArgumentException::class.java) {MapCodec.decode(json.toString().toByteArray())}
            source(json).put("scope","FILE").put("pageId",JSONObject.NULL)
            assertThrows("projection v$version",IllegalArgumentException::class.java) {MapCodec.decode(json.toString().toByteArray())}
        }
    }
    @Test fun versionsOneThroughFiveRejectOriginalOnlyInRetainedPreviewTarget() {
        val png=asset();val s=MapSource(documentId=owner,previewKey=png.key,lastPreview=MapLastPreview(png.key,source().target,"original-pdf-v1:x",1))
        for(version in 1..5) {
            val json=JSONObject(String(MapCodec.encode(graph(s).copy(previewAssets=mapOf(png.key to png))))).put("version",version)
            assertThrows("preview scope v$version",IllegalArgumentException::class.java) {MapCodec.decode(json.toString().toByteArray())}
            source(json).getJSONObject("lastPreview").getJSONObject("target").put("scope","PAGE")
            assertThrows("preview projection v$version",IllegalArgumentException::class.java) {MapCodec.decode(json.toString().toByteArray())}
        }
        val unrelated=JSONObject(String(MapCodec.encode(graph(MapSource(documentId=owner))))).put("version",1)
        source(unrelated).put("extension",JSONObject().put("scope","PDF_ORIGINAL").put("originalPdf",true))
        assertTrue(MapCodec.decode(unrelated.toString().toByteArray()).root.sources.isEmpty())
    }
    @Test fun typeAndScopeValidationNeverDowngradeMalformedOriginalToANote() {
        for(invalid in listOf(source().copy(pageId=null),source().copy(region=Rect(0.0,0.0,1.0,1.0)),
            source().copy(originalPdf=null),source().copy(scope=SourceScope.PAGE),
            source().copy(originalPdf=projection.copy(sha256="not-sha")),source().copy(originalPdf=projection.copy(pageIndex=-1)),
            source().copy(originalPdf=projection.copy(viewTurns=4)),source().copy(originalPdf=projection.copy(userRegion=Rect(0.0,0.0,Double.NaN,2.0)))))
            assertThrows(IllegalArgumentException::class.java) {invalid.validate()}
        for(field in listOf("pageIndex","viewTurns")) {
            val json=JSONObject(String(MapCodec.encode(graph(source()))));source(json).getJSONObject("originalPdf").put(field,"2")
            assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(json.toString().toByteArray())}
        }
        val json=JSONObject(String(MapCodec.encode(graph(source()))));source(json).getJSONObject("originalPdf").put("userRegion",false)
        assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(json.toString().toByteArray())}
    }
    @Test fun projectionParticipatesInExactTargetsAndSemanticCacheButDerivedObservationsDoNot() {
        val s=source();val doc=graph(s);val semantic=MapCodec.semanticPreviewVersion(doc)
        assertTrue(semantic.startsWith("xmap-render-v3:"))
        fun withSource(next:MapSource)=doc.copy(nodes=listOf(doc.root.copy(sources=listOf(next))))
        for(p in listOf(projection.copy(viewTurns=1),projection.copy(pageIndex=2),projection.copy(sha256="b".repeat(64)),
            projection.copy(userRegion=null),projection.copy(userRegion=projection.userRegion!!.copy(x=40.0)))) {
            assertNotEquals(s.target,s.copy(originalPdf=p).target)
            assertNotEquals(semantic,MapCodec.semanticPreviewVersion(withSource(s.copy(originalPdf=p))))
        }
        assertEquals(semantic,MapCodec.semanticPreviewVersion(withSource(s.copy(status=SourceStatus.TRASHED,sourceVersion="new observation",observedVersion="new-preview"))))
    }
    @Test fun replacedOrDeletedOriginalKeepsItsLastPngExplicitlyHistorical() {
        val png=asset();val s=source().let {it.copy(status=SourceStatus.CURRENT,observedVersion="original-pdf-v1:a",previewKey=png.key,
            lastPreview=MapLastPreview(png.key,it.target,"original-pdf-v1:a",1))}
        assertFalse(s.hasHistoricalPreview)
        assertTrue(s.copy(originalPdf=projection.copy(viewTurns=0)).hasHistoricalPreview)
        assertTrue(s.copy(status=SourceStatus.TRASHED).hasHistoricalPreview)
        assertTrue(s.copy(status=SourceStatus.UNAVAILABLE).hasHistoricalPreview)
        assertTrue(s.copy(observedVersion="original-pdf-v1:b").hasHistoricalPreview)
    }
}
