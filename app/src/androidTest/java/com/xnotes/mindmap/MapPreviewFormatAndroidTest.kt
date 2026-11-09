package com.xnotes.mindmap

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Platform PNG and org.json contracts. Compiling this class is not device acceptance. */
class MapPreviewFormatAndroidTest {
    private fun graph():MindMapDocument {
        val bitmap=Bitmap.createBitmap(4,3,Bitmap.Config.ARGB_8888)
        val png=try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            ByteArrayOutputStream().also {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray()
        } finally {bitmap.recycle()}
        val asset=MapPreviewAsset.fromPng(png)
        val source=MapSource(documentId=UUID.randomUUID().toString(),scope=SourceScope.PAGE,pageId=UUID.randomUUID().toString(),
            previewKey=asset.key,sourceVersion="raw",observedVersion="native-sha256:raw",acceptedPreviewVersion="native-sha256:raw")
        val bound=source.copy(lastPreview=MapLastPreview(asset.key,source.target,"native-sha256:raw",123L))
        return MindMapDocument(nodes=listOf(MapNode(parent=null,sources=listOf(bound))),previewAssets=mapOf(asset.key to asset))
    }
    private fun source(j:JSONObject)=j.getJSONArray("nodes").getJSONObject(0).getJSONArray("sources").getJSONObject(0)

    @Test fun actualAndroidPngAndFormerTargetPreviewRoundTripWithoutRebindingPixels() {
        val original=graph();val old=original.root.sources.single()
        val changed=original.update(original.root.id) {it.copy(sources=listOf(old.copy(pageId=UUID.randomUUID().toString(),status=SourceStatus.STALE)))}
        val loaded=MapCodec.decode(MapCodec.encode(changed));assertEquals(changed,loaded)
        assertEquals(old.target,loaded.root.sources.single().lastPreview!!.target)
        assertArrayEquals(original.previewAssets.values.single().pngBytes(),loaded.previewAssets.values.single().pngBytes())
    }
    @Test fun optionalDerivedNullsAndStrictPreviewNumbersUseAndroidJsonTypes() {
        val original=graph();val j=JSONObject(String(MapCodec.encode(original)))
        val s=source(j)
        s.put("observedVersion",JSONObject.NULL).put("acceptedPreviewVersion",JSONObject.NULL).put("lastPreview",JSONObject.NULL)
        val loaded=MapCodec.decode(j.toString().toByteArray()).root.sources.single()
        assertNull(loaded.observedVersion);assertNull(loaded.acceptedPreviewVersion);assertNull(loaded.lastPreview)
        val raw=String(MapCodec.encode(original))
        for(value in listOf("\"123\"","123.0","123e0","true","-1","null")) {
            val invalid=raw.replace("\"generatedAt\":123","\"generatedAt\":$value")
            assertNotEquals(raw,invalid)
            assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(invalid.toByteArray())}
        }
        for(value in listOf("3.0","3e0","3.0000000000000000000001")) {
            val invalid=raw.replace("\"version\":3","\"version\":$value");assertNotEquals(raw,invalid)
            assertThrows(IllegalArgumentException::class.java) {MapCodec.decode(invalid.toByteArray())}
        }
    }
}
