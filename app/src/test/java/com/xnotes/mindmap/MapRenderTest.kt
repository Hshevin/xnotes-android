package com.xnotes.mindmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** Native Android drawing on the host, not a MovinkPad GPU acceptance test. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapRenderTest {
    private fun example(): MindMapDocument {
        val ink = listOf(MapInk(listOf(InkPoint(24.0, 55.0, .8), InkPoint(65.0, 130.0, .8), InkPoint(110.0, 38.0, .5)), 5.0),
            MapInk(listOf(InkPoint(140.0, 85.0, .8), InkPoint(270.0, 85.0, .8)), 3.0))
        return MapLayout.arrange(MindMapDocument(nodes = listOf(
            MapNode(id = "root", parent = null, text = "Project plan"),
            MapNode(id = "a", parent = "root", text = "Ideas and questions"),
            MapNode(id = "b", parent = "root", kind = NodeKind.INK, text = "Sketch", ink = ink),
            MapNode(id = "c", parent = "a", text = "Text and handwriting"),
            MapNode(id = "d", parent = "a", text = "Local editable files"),
            MapNode(id = "e", parent = "root", text = "Next steps", collapsed = true),
            MapNode(id = "f", parent = "e", text = "Hidden child")
        )))
    }
    @Test fun nativeGraphDrawsLightAndDarkAndExportsAPdfWithoutChangingTheEditableFile() {
        val context = RuntimeEnvironment.getApplication()
        val doc = example()
        val v = MindMapView(context).apply { document = doc; selected = doc.root.id; layout(0, 0, 1600, 1000); fit() }
        for (dark in listOf(false, true)) {
            v.dark = dark
            val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bitmap))
            val pixels = IntArray(1600 * 1000)
            bitmap.getPixels(pixels, 0, 1600, 0, 0, 1600, 1000)
            assertTrue(pixels.count { it == Color.BLACK } > 1000)
            assertTrue(pixels.count { it == Color.WHITE } > 1000)
            assertEquals(if (dark) Color.BLACK else Color.WHITE, bitmap.getPixel(0, 0))
            System.getProperty("mindmap.evidence")?.let { dir ->
                File(dir).mkdirs()
                File(dir, if (dark) "mind-map-dark.png" else "mind-map-light.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
        val file = File(context.cacheDir, "example.xmap").apply { writeBytes(MapCodec.encode(doc)) }
        val before = file.readBytes()
        val out = ByteArrayOutputStream()
        MapFiles.exportPdf(context, Uri.fromFile(file).toString(), out) { false }
        assertTrue(out.size() > 100)
        assertTrue(out.toString("ISO-8859-1").startsWith("%PDF"))
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(out.toByteArray()).use { assertEquals(1, it.numberOfPages) }
        assertArrayEquals(before, file.readBytes())
        val copy = File(context.cacheDir, "copy.xmap").apply { writeBytes(before) }
        MapFiles.assignCopy(context, Uri.fromFile(copy))
        val copied = MapFiles.read(context, Uri.fromFile(copy).toString())
        assertNotEquals(doc.id, copied.id)
        assertEquals(doc.nodes, copied.nodes)
        assertArrayEquals(before, file.readBytes())
        val thumb = MapFiles.thumbnail(context, Uri.fromFile(file).toString(), 600, 400)
        assertEquals(600, thumb.width)
        thumb.recycle()
    }
    @Test fun livePreviewEvictionKeepsCompletedInkGeometryCached() {
        val p=MapPainter()
        val completed=MapInk(List(20000) {i->InkPoint((i%300).toDouble(),(i/300).toDouble(),.7,i.toDouble())})
        val image=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image)
        p.drawInk(canvas,listOf(completed))
        assertEquals(1,p.geometryBuildCount)
        repeat(8) {frame->
            p.retainInk(listOf(completed))
            val live=MapInk(listOf(InkPoint(20.0,20.0),InkPoint(30.0+frame,35.0)))
            p.drawInk(canvas,listOf(completed,live))
        }
        assertEquals(9,p.geometryBuildCount)
        p.retainInk(listOf(completed));p.drawInk(canvas,listOf(completed))
        assertEquals(9,p.geometryBuildCount)
        image.recycle()
    }
    @Test fun transientConnectionLineDrawsInBothThemesAndDisappearsWithoutChangingTheGraph() {
        val context=RuntimeEnvironment.getApplication()
        val doc=MindMapDocument(nodes=listOf(MapNode(id="source",parent=null),MapNode(id="target",parent=null,free=true,x=500.0)))
        val bytes=MapCodec.encode(doc)
        val v=MindMapView(context).apply {document=doc;selected="source";layout(0,0,1000,600);fit()}
        fun pixels():IntArray {
            val bitmap=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888)
            return try {v.draw(Canvas(bitmap));IntArray(1000*600).also {bitmap.getPixels(it,0,1000,0,0,1000,600)}} finally {bitmap.recycle()}
        }
        fun event(action:Int,p:com.xnotes.core.geometry.Pt) {
            val e=android.view.MotionEvent.obtain(100,200,action,p.x.toFloat(),p.y.toFloat(),0)
            try {v.onTouchEvent(e)} finally {e.recycle()}
        }
        for(dark in listOf(false,true)) {
            v.dark=dark;val before=pixels();assertTrue(v.beginConnection("source"))
            event(android.view.MotionEvent.ACTION_DOWN,v.screenPoint(doc.root.bounds.center))
            event(android.view.MotionEvent.ACTION_MOVE,v.screenPoint(com.xnotes.core.geometry.Pt(350.0,36.0)))
            val preview=pixels();assertTrue(before.indices.count {before[it]!=preview[it]}>20)
            assertTrue(preview.all {Color.red(it)==Color.green(it) && Color.green(it)==Color.blue(it)})
            v.cancelConnection();event(android.view.MotionEvent.ACTION_UP,v.screenPoint(doc.root.bounds.center))
            assertArrayEquals(before,pixels());assertArrayEquals(bytes,MapCodec.encode(v.document))
        }
    }

}
