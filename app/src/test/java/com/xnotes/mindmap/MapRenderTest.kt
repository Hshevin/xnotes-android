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
}
