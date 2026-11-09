package com.xnotes.mindmap

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class MapPreviewFormatTest {
    @get:Rule val temporary=TemporaryFolder()
    private val owner="00000000-0000-4000-8000-000000000011"
    private val page="00000000-0000-4000-8000-000000000012"
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private fun png(w:Int=2,h:Int=2,extra:Int=0,shade:Int=255):ByteArray {
        val out=ByteArrayOutputStream();val data=java.io.DataOutputStream(out)
        data.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(name:String,body:ByteArray) {
            val type=name.toByteArray(Charsets.US_ASCII);val crc=CRC32().apply {update(type);update(body)}
            data.writeInt(body.size);data.write(type);data.write(body);data.writeInt(crc.value.toInt())
        }
        val header=ByteArrayOutputStream();java.io.DataOutputStream(header).use {it.writeInt(w);it.writeInt(h);it.write(byteArrayOf(8,6,0,0,0))}
        chunk("IHDR",header.toByteArray())
        val compressed=ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use {z->repeat(h) {z.write(0);z.write(ByteArray(w*4) {shade.toByte()})};z.write(ByteArray(extra))}
        chunk("IDAT",compressed.toByteArray());chunk("IEND",byteArrayOf());return out.toByteArray()
    }
    private fun doc(asset:MapPreviewAsset):MindMapDocument {
        val target=MapSourceTarget(owner,SourceScope.PAGE,page)
        val source=MapSource(documentId=owner,scope=SourceScope.PAGE,pageId=page,previewKey=asset.key,sourceVersion="raw-v1",
            observedVersion="native-v1",acceptedPreviewVersion="native-v1",lastPreview=MapLastPreview(asset.key,target,"native-v1",123L))
        return MindMapDocument(nodes=listOf(MapNode(parent=null,sources=listOf(source))),previewAssets=mapOf(asset.key to asset))
    }
    private fun chunk(name:String,body:ByteArray):ByteArray=ByteArrayOutputStream().also {out->
        java.io.DataOutputStream(out).use {data->
            val type=name.toByteArray(Charsets.US_ASCII);val crc=CRC32().apply {update(type);update(body)}
            data.writeInt(body.size);data.write(type);data.write(body);data.writeInt(crc.value.toInt())
        }
    }.toByteArray()
    @Test fun assetOwnsValidatedPngBytesAndNeverExposesSharedMutableStorage() {
        val bytes=png();val expected=bytes.copyOf();val asset=MapPreviewAsset.fromPng(bytes)
        assertEquals(hash(expected),asset.key);assertEquals(2,asset.width);assertEquals(2,asset.height)
        bytes.fill(0);assertArrayEquals(expected,asset.pngBytes())
        asset.pngBytes().fill(1);assertArrayEquals(expected,asset.pngBytes())
        assertEquals(asset,MapPreviewAsset.fromEncoded(asset.key,asset.pngBase64,2,2))
    }
    @Test fun invalidOrOversizedPngCannotEnterTheAssetTableEvenWithMatchingClaimedHash() {
        for(bytes in listOf(png().copyOf(20),png()+byteArrayOf(0),png(extra=1),png(513,1),png().also {it[it.lastIndex]=(it.last()+1).toByte()})) {
            assertThrows(IllegalArgumentException::class.java) {MapPreviewAsset.fromPng(bytes)}
        }
        val asset=MapPreviewAsset.fromPng(png())
        assertThrows(IllegalArgumentException::class.java) {MapPreviewAsset.fromEncoded("0".repeat(64),asset.pngBase64,2,2)}
        assertThrows(IllegalArgumentException::class.java) {MapPreviewAsset.fromEncoded(asset.key,asset.pngBase64,3,2)}
        assertThrows(IllegalArgumentException::class.java) {MapPreviewAsset.fromEncoded(asset.key,"A".repeat(400000),2,2)}
    }
    @Test fun v3RoundTripPreservesPreviewBindingPngAndTheExistingGraphFeatures() {
        val original=doc(MapPreviewAsset.fromPng(png()))
        val free=MapNode(parent=null,free=true,text="independent")
        val d=original.copy(nodes=original.nodes+free,direction=MapDirection.DOWN,
            relations=listOf(MapRelation(from=original.root.id,to=free.id,kind=RelationKind.DEPENDS_ON)))
        val bytes=MapCodec.encode(d);assertEquals(3,JSONObject(String(bytes)).getInt("version"))
        val loaded=MapCodec.decode(bytes);assertEquals(d,loaded)
        val s=loaded.root.sources.single();assertEquals(page,s.pageId);assertEquals("raw-v1",s.sourceVersion)
        assertArrayEquals(original.previewAssets.values.single().pngBytes(),loaded.previewAssets.getValue(s.previewKey!!).pngBytes())
    }
    @Test fun v2WithAnOpaquePreviewKeyAndV1RemainReadableWithoutInventingAnAsset() {
        val s=MapSource(documentId=owner,previewKey="legacy_opaque")
        val root=MapNode(parent=null,sources=listOf(s));val free=MapNode(parent=null,free=true,text="free")
        val expected=MindMapDocument(nodes=listOf(root,free),direction=MapDirection.RIGHT,relations=listOf(MapRelation(from=root.id,to=free.id)))
        val j=MapCodec.toJson(expected).put("version",2);j.remove("previewAssets")
        assertEquals(expected,MapCodec.decode(j.toString().toByteArray()))
        val v1=MapCodec.toJson(MindMapDocument()).put("version",1);v1.remove("previewAssets")
        val restored=MapCodec.decode(v1.toString().toByteArray());assertTrue(restored.previewAssets.isEmpty());assertTrue(restored.root.sources.isEmpty())
    }
    @Test fun aNewDescriptorMustReferenceTheExactAssetAndCannotReuseAnOpaqueLegacyKey() {
        val d=doc(MapPreviewAsset.fromPng(png()));val s=d.root.sources.single()
        assertThrows(IllegalArgumentException::class.java) {d.copy(previewAssets=emptyMap()).validate()}
        assertThrows(IllegalArgumentException::class.java) {d.copy(nodes=listOf(d.root.copy(sources=listOf(s.copy(previewKey="legacy_opaque"))))).validate()}
    }
    @Test fun aPreviewOfTheFormerTargetRemainsReadableButIsNeverReboundToTheNewPage() {
        val d=doc(MapPreviewAsset.fromPng(png()));val old=d.root.sources.single()
        val other="00000000-0000-4000-8000-000000000013"
        val changed=d.copy(nodes=listOf(d.root.copy(sources=listOf(old.copy(pageId=other,status=SourceStatus.STALE)))))
        val restored=MapCodec.decode(MapCodec.encode(changed));val s=restored.root.sources.single()
        assertEquals(other,s.pageId);assertEquals(page,s.lastPreview!!.target.pageId)
        assertArrayEquals(d.previewAssets.values.single().pngBytes(),restored.previewAssets.getValue(s.previewKey!!).pngBytes())
    }
    @Test fun corruptOrDanglingAssetTablesAndWrongDescriptorTypesFailBeforeReplacingAValidMap() {
        val d=doc(MapPreviewAsset.fromPng(png()));val good=MapCodec.encode(d)
        fun invalid(edit:(JSONObject)->Unit) {
            val j=JSONObject(String(good));edit(j)
            assertThrows(Exception::class.java) {MapCodec.decode(j.toString().toByteArray())}
            assertEquals(d,MapCodec.decode(good))
        }
        invalid {it.getJSONArray("previewAssets").getJSONObject(0).put("pngBase64","?")}
        invalid {it.getJSONArray("previewAssets").getJSONObject(0).put("key","0".repeat(64))}
        invalid {it.getJSONArray("previewAssets").getJSONObject(0).put("width",513)}
        // JSONObject would normalize put(2.0) back to the integer literal 2 on serialization.
        for(value in listOf("2.0","2e0","2.00000000000000000001")) {
            val raw=String(good).replace(Regex("\"width\":2(?=[,}])"),"\"width\":$value")
            assertNotEquals(String(good),raw)
            assertThrows(Exception::class.java) {MapCodec.decode(raw.toByteArray())}
        }
        invalid {it.getJSONArray("previewAssets").put(it.getJSONArray("previewAssets").getJSONObject(0))}
        invalid {it.put("previewAssets",org.json.JSONArray())}
        invalid {it.getJSONArray("nodes").getJSONObject(0).getJSONArray("sources").getJSONObject(0)
            .getJSONObject("lastPreview").put("generatedAt","123")}
        invalid {it.getJSONArray("nodes").getJSONObject(0).getJSONArray("sources").getJSONObject(0)
            .getJSONObject("lastPreview").getJSONObject("target").put("scope","FILE")}
    }
    @Test fun pruningIsPerFrameAndNeverRemovesAnUndoFramesLastPixels() {
        val d=doc(MapPreviewAsset.fromPng(png()))
        val detached=d.update(d.root.id) {it.copy(sources=emptyList())}.prunePreviewAssets()
        assertTrue(detached.previewAssets.isEmpty());assertEquals(1,d.previewAssets.size)
        assertEquals(d,MapCodec.decode(MapCodec.encode(d)))
    }
    @Test fun semanticPreviewVersionIgnoresChecksAndAssetsButTracksActualMapContentAndTargets() {
        val d=doc(MapPreviewAsset.fromPng(png()));val s=d.root.sources.single()
        val expected=MapCodec.semanticPreviewVersion(d);assertTrue(expected.startsWith("xmap-render-v1:"))
        val checked=d.update(d.root.id) {it.copy(sources=listOf(s.copy(status=SourceStatus.UNAVAILABLE,
            sourceVersion="raw-after-check",observedVersion="preview-after-check",acceptedPreviewVersion="accepted-after-check",lastKnownPage=45,
            previewKey=null,lastPreview=null)))}.prunePreviewAssets()
        assertEquals(expected,MapCodec.semanticPreviewVersion(checked))
        for(changed in listOf(
            d.update(d.root.id) {it.copy(text="Body changed")},
            d.update(d.root.id) {it.copy(x=20.0)},
            d.update(d.root.id) {it.copy(sources=listOf(s.copy(note="Remark changed")))},
            d.update(d.root.id) {it.copy(sources=listOf(s.copy(fileName="Renamed file")))},
            d.update(d.root.id) {it.copy(sources=listOf(s.copy(pageId="00000000-0000-4000-8000-000000000013")))},
            d.update(d.root.id) {it.copy(primarySource=s.id)},
        )) assertNotEquals(expected,MapCodec.semanticPreviewVersion(changed))
        assertEquals(expected,MapCodec.semanticPreviewVersion(MapCodec.decode(MapCodec.encode(d))))
    }
    @Test fun aSmallCompressedFileCannotExceedTheAggregateDecodedPixelBudget() {
        val assets=(0..64).map {MapPreviewAsset.fromPng(png(512,512,shade=it))}
        val doc=MindMapDocument(previewAssets=assets.take(64).associateBy {it.key})
        doc.validate()
        assertThrows(IllegalArgumentException::class.java) {doc.copy(previewAssets=assets.associateBy {it.key}).validate()}
        val j=MapCodec.toJson(MindMapDocument())
        j.put("previewAssets",org.json.JSONArray().also {a->assets.forEach {asset->a.put(JSONObject()
            .put("key",asset.key).put("pngBase64",asset.pngBase64).put("width",512).put("height",512))}})
        assertTrue(j.toString().toByteArray().size<1024*1024)
        assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(j.toString().toByteArray())}
    }
    @Test fun validChecksumsCannotSmuggleIllegalChunkOrderingOrTransparencyIntoTheDecoder() {
        val plain=png();val palette=chunk("PLTE",byteArrayOf(0,0,0))
        for(metadata in listOf(palette+palette,chunk("tRNS",byteArrayOf(0,0,0,0,0,0)),
            chunk("sbit",byteArrayOf(8,8,8,8)),chunk("sBIT",byteArrayOf(9,8,8,8)),chunk("iCCP",byteArrayOf(1,0,0)))) {
            val bytes=plain.copyOfRange(0,33)+metadata+plain.copyOfRange(33,plain.size)
            assertThrows(IllegalArgumentException::class.java) {MapPreviewAsset.fromPng(bytes)}
            assertThrows(IllegalArgumentException::class.java) {MapPreviewAsset.fromEncoded(hash(bytes),java.util.Base64.getEncoder().encodeToString(bytes),2,2)}
        }
        val valid=plain.copyOfRange(0,33)+chunk("sBIT",byteArrayOf(8,8,8,8))+plain.copyOfRange(33,plain.size)
        assertEquals(2,MapPreviewAsset.fromPng(valid).width)
    }
    @Test fun newFormatRequiresAnIntegerVersionAndRejectsNearThreeRoundedLiterals() {
        val good=String(MapCodec.encode(MindMapDocument()))
        for(value in listOf("3.0","3e0","3.0000000000000000000001","3.00000000000000000000001","4","\"3\"")) {
            val invalid=good.replace("\"version\":3","\"version\":$value");assertNotEquals(good,invalid)
            assertThrows(Exception::class.java) {MapCodec.decode(invalid.toByteArray())}
        }
    }
    @Test fun aFailedDestinationWriteRecoversTheExactPreviewWithoutDiscardingItForAnUnreadableFileVersion() {
        val current=doc(MapPreviewAsset.fromPng(png()))
        val initial=current.update(current.root.id) {n->n.copy(sources=n.sources.map {it.copy(previewKey=null,lastPreview=null)})}.prunePreviewAssets()
        val target=object:MapTarget {
            var bytes=MapCodec.encode(initial)
            override fun read()=bytes
            override fun write(bytes:ByteArray) {this.bytes=byteArrayOf(123);throw java.io.IOException("provider failed after truncating")}
        }
        val directory=temporary.newFolder();val store=MapStore(directory,"graph",target)
        assertEquals(initial,store.open().document)
        assertThrows(java.io.IOException::class.java) {store.save(current)}
        val recovery=MapStore(directory,"graph",target);val opened=recovery.open()
        assertTrue(opened.recovered);assertTrue(opened.conflict);assertEquals(current,opened.document)
        assertArrayEquals(current.previewAssets.values.single().pngBytes(),opened.document.previewAssets.values.single().pngBytes())
        assertThrows(Exception::class.java) {recovery.discardRecovery()}
        assertEquals(current,MapStore(directory,"graph",target).open().document)
        assertArrayEquals(byteArrayOf(123),target.bytes)
    }
}
