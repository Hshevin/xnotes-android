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
    @Test fun realPngRoundTripKeepsPageIdentityAndRecoveryReportsBadImage() {
        val root=File(PenTestRunner.instance.targetContext.cacheDir,"reference-codec-${UUID.randomUUID()}").apply {mkdirs()}
        try {
            val source=File(root,"original.png")
            val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
            try {source.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {bitmap.recycle()}
            fun members():List<CanvasItem> = listOf(ImageItem(ImageData(source,2,2),Rect(0.0,0.0,20.0,20.0)),
                Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(10.0,20.0,.7))))
            val image=AndroidImageCodec();val text=AndroidTextMeasurer();val note=DocumentCodec(image,text);val canvas=CanvasCodec(image,text)
            val doc=Document.blank().apply {pages.first().items.addAll(members())}
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
