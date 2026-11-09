package com.xnotes.mindmap

import com.xnotes.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class MapNodePreviewPropertiesReviewTest {
    private fun document():MindMapDocument {
        val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes)
        out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(type:String,payload:ByteArray) {
            val name=type.toByteArray(Charsets.US_ASCII);out.writeInt(payload.size);out.write(name);out.write(payload)
            out.writeInt(CRC32().apply {update(name);update(payload)}.value.toInt())
        }
        chunk("IHDR",ByteArrayOutputStream().also {DataOutputStream(it).apply {writeInt(1);writeInt(1);write(byteArrayOf(8,6,0,0,0))}}.toByteArray())
        chunk("IDAT",ByteArrayOutputStream().also {DeflaterOutputStream(it).use {z->z.write(byteArrayOf(0,0,0,0,255.toByte()))}}.toByteArray());chunk("IEND",byteArrayOf())
        val asset=MapPreviewAsset.fromPng(bytes.toByteArray())
        val source=MapSource(documentId="00000000-0000-0000-0000-000000000001",fileName="Source").let {
            it.copy(previewKey=asset.key,lastPreview=MapLastPreview(asset.key,it.target,"native-sha256:source",0))
        }
        return MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Before",sources=listOf(source))),previewAssets=mapOf(asset.key to asset))
    }
    @Test fun aNodeWithAnActualPreviewCanConfirmTextPropertiesAndUndoOnce() {
        MapComposeReviewFixture(document()).use {f->
            val before=f.session.document!!
            f.content {MapNodeDialog(before.root,onDismiss={},onConfirm={f.session.editNodeProperties(before.root.id,it)},previewAssets=before.previewAssets)}
            f.enter("Renamed")
            f.click(R.string.map_done)
            assertEquals("Renamed",f.session.document!!.root.text)
            assertEquals(before.previewAssets,f.session.document!!.previewAssets);assertEquals(before.root.sources,f.session.document!!.root.sources)
            f.session.undo();assertEquals(before,f.session.document);assertFalse(f.session.canUndo)
        }
    }
    @Test fun aNodeWithAnActualPreviewCanChangeTypeAndConfirmProperties() {
        MapComposeReviewFixture(document()).use {f->
            val before=f.session.document!!
            f.content {MapNodeDialog(before.root,onDismiss={},onConfirm={f.session.editNodeProperties(before.root.id,it)},previewAssets=before.previewAssets)}
            f.click(R.string.map_ink_node);f.click(R.string.map_done)
            assertEquals(NodeKind.INK,f.session.document!!.root.kind)
            assertEquals(before.previewAssets,f.session.document!!.previewAssets);assertEquals(before.root.sources,f.session.document!!.root.sources)
            f.session.undo();assertEquals(before,f.session.document);assertFalse(f.session.canUndo)
        }
    }
}
