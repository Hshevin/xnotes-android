package com.xnotes.pen

import android.graphics.Bitmap
import com.xnotes.core.model.*
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.DocumentCodec
import com.xnotes.format.CanvasCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Real platform image header/JSON behavior. Compile-only evidence does not mean these tests ran. */
class ReferenceCodecAndroidTest {
    @Test fun platformBundleCopyKeepsInkAndRebindsDocumentAndPageIdentities() {
        val root=File(PenTestRunner.instance.targetContext.cacheDir,"page-copy-${UUID.randomUUID()}").apply {mkdirs()}
        try {
            val codec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
            val doc=Document.blank().apply {enableReferenceAnchors()}
            val originalPage=doc.pages.single().referenceIdentity.id
            doc.pages.single().items.add(Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(10.0,20.0,.5))))
            val source=File(root,"original.xnote").apply {outputStream().use {codec.write(doc.snapshot(),it)}}
            val before=source.readBytes()
            val copy=com.xnotes.format.BundleMigration.prepare(source,root,copy=true)
            try {
                val read=copy.file.inputStream().use {codec.read(it)}
                assertNotEquals(doc.identity.documentId,read.identity.documentId)
                assertNotEquals(originalPage,read.pages.single().referenceIdentity.id)
                assertEquals((doc.pages.single().items.single() as Stroke).samples,(read.pages.single().items.single() as Stroke).samples)
            } finally {copy.file.delete()}
            val portable=File(root,"portable.xnote")
            portable.outputStream().use {com.xnotes.format.BundlePdf.write(source,it,doc.identity,null,false)}
            val same=portable.inputStream().use {codec.read(it)}
            assertEquals(doc.identity,same.identity);assertEquals(originalPage,same.pages.single().referenceIdentity.id)
            assertTrue(before.contentEquals(source.readBytes()))
        } finally {root.deleteRecursively()}
    }
    @Test fun platformJsonDistinguishesMissingSourceFieldsFromExplicitNull() {
        val source=com.xnotes.mindmap.MapSource(documentId="00000000-0000-0000-0000-000000000001",
            scope=com.xnotes.mindmap.SourceScope.PAGE,pageId="00000000-0000-0000-0000-000000000002",
            region=Rect(1.0,2.0,30.0,40.0))
        val doc=com.xnotes.mindmap.MindMapDocument(nodes=listOf(com.xnotes.mindmap.MapNode(parent=null,sources=listOf(source))))
        val encoded=com.xnotes.mindmap.MapCodec.encode(doc)
        assertEquals(doc,com.xnotes.mindmap.MapCodec.decode(encoded))
        for(key in listOf("objectIds","includeAnnotations")) {
            val j=org.json.JSONObject(String(encoded))
            j.getJSONArray("nodes").getJSONObject(0).getJSONArray("sources").getJSONObject(0).put(key,org.json.JSONObject.NULL)
            try {com.xnotes.mindmap.MapCodec.decode(j.toString().toByteArray());fail("explicit null is not an absent legacy field")} catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun realPngRoundTripKeepsPageIdentityAndRecoveryReportsBadImage() {
        val root=File(PenTestRunner.instance.targetContext.cacheDir,"reference-codec-${UUID.randomUUID()}").apply {mkdirs()}
        try {
            val source=File(root,"original.png")
            val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
            try {source.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {bitmap.recycle()}
            fun members():List<CanvasItem> = listOf(ImageItem(ImageData(source,2,2),Rect(0.0,0.0,20.0,20.0)),
                Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(10.0,20.0,.7))))
            val image=AndroidImageCodec();val text=AndroidTextMeasurer();val note=DocumentCodec(image,text);val canvas=CanvasCodec(image,text)
            val doc=Document.blank().apply {pages.first().items.addAll(members());enableReferenceAnchors()}
            val bytes=ByteArrayOutputStream().also {note.write(doc.snapshot(),it)}.toByteArray()
            val reopened=note.read(bytes.inputStream(),imageDir=root)
            assertTrue(reopened.hasReferenceAnchors);assertEquals(doc.identity,reopened.identity)
            assertEquals(doc.pages.first().referenceIdentity.id,reopened.pages.first().referenceIdentity.id)
            assertEquals(2,reopened.pages.first().items.size)
            val board=InfiniteDocument().apply {addAll(members())}
            val canvasBytes=ByteArrayOutputStream().also {canvas.write(board.snapshotForWrite(),it)}.toByteArray()
            val loaded=canvas.read(canvasBytes.inputStream(),root)
            assertEquals(board.identity,loaded.identity);assertEquals(2,loaded.items.size)
            val damaged=ByteArrayOutputStream().also {out->ZipOutputStream(out).use {dest->ZipInputStream(bytes.inputStream()).use {input->
                var e=input.nextEntry
                while(e!=null) {
                    dest.putNextEntry(ZipEntry(e.name))
                    if(e.name.startsWith("assets/image-")) dest.write(byteArrayOf(0,1,2)) else input.copyTo(dest)
                    dest.closeEntry();input.closeEntry();e=input.nextEntry
                }
            }}}.toByteArray()
            val failed=File(root,"failed-read").apply {mkdirs()}
            val partial=note.read(damaged.inputStream(),imageDir=failed)
            assertEquals(doc.pages.first().referenceIdentity.id,partial.pages.first().referenceIdentity.id)
            assertEquals(1,partial.pages.first().items.size)
            val rescue=note.readInkCopy(damaged.inputStream(),imageDir=failed)
            assertEquals(1,rescue.omittedOnCopy);assertEquals(1,rescue.pages.first().items.size)
            assertNotEquals(doc.pages.first().referenceIdentity.id,rescue.pages.first().referenceIdentity.id)
            assertEquals(2,image.probeFile(source.path)!!.width)
        } finally {root.deleteRecursively()}
    }
}
