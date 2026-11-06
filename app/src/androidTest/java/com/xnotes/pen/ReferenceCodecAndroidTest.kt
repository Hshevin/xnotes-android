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
    @Test fun realPngAndNativeIdentityRoundTripInBothCodecsAndBadPngCannotLoseItsObjectSilently() {
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
            assertEquals(doc.pages.first().items.map {it.referenceIdentity.id},reopened.pages.first().items.map {it.referenceIdentity.id})
            val board=InfiniteDocument().apply {addAll(members());enableReferenceAnchors()}
            val canvasBytes=ByteArrayOutputStream().also {canvas.write(board.snapshotForWrite(),it)}.toByteArray()
            val loaded=canvas.read(canvasBytes.inputStream(),root)
            assertTrue(loaded.hasReferenceAnchors);assertEquals(board.items.map {it.referenceIdentity.id},loaded.items.map {it.referenceIdentity.id})
            val damaged=ByteArrayOutputStream().also {out->ZipOutputStream(out).use {dest->ZipInputStream(bytes.inputStream()).use {input->
                var e=input.nextEntry
                while(e!=null) {
                    dest.putNextEntry(ZipEntry(e.name))
                    if(e.name.startsWith("assets/image-")) dest.write(byteArrayOf(0,1,2)) else input.copyTo(dest)
                    dest.closeEntry();input.closeEntry();e=input.nextEntry
                }
            }}}.toByteArray()
            val failed=File(root,"failed-read").apply {mkdirs()}
            try {note.read(damaged.inputStream(),imageDir=failed);fail("invalid encoded image must not become a partial writable model")} catch(_:IllegalArgumentException) {}
            assertTrue(failed.list()!!.isEmpty());assertEquals(2,image.probeFile(source.path)!!.width)
        } finally {root.deleteRecursively()}
    }
}
