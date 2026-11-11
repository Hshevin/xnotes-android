package com.xnotes.mindmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkPadDrawingRegressionTest {
    @Test fun repeatedFramesPreserveTheHostsCanvasStackTransformAndPixels() {
        val bitmap=Bitmap.createBitmap(240,200,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap)
            bitmap.eraseColor(Color.MAGENTA)
            canvas.save();canvas.translate(50f,60f)
            val count=canvas.saveCount
            val bounds=android.graphics.Rect().also {canvas.getClipBounds(it)}
            val pad=InkPadView(RuntimeEnvironment.getApplication()).apply {layout(0,0,100,80)}
            repeat(3) {
                pad.draw(canvas)
                assertEquals("A frame must not consume the host's save",count,canvas.saveCount)
                assertEquals(bounds,android.graphics.Rect().also {canvas.getClipBounds(it)})
            }
            assertEquals("Toolbar above the pad stays intact",Color.MAGENTA,bitmap.getPixel(70,30))
            assertEquals("Neighboring pane stays intact",Color.MAGENTA,bitmap.getPixel(190,100))
            assertNotEquals("Paper is actually painted",Color.MAGENTA,bitmap.getPixel(100,100))
            canvas.restore()
            assertEquals(1,canvas.saveCount)
        } finally {bitmap.recycle()}
    }
    @Test fun firstFrameOnAnUnsavedCanvasDoesNotUnderflow() {
        val bitmap=Bitmap.createBitmap(100,80,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap)
            InkPadView(RuntimeEnvironment.getApplication()).apply {layout(0,0,100,80)}.draw(canvas)
            assertEquals(1,canvas.saveCount)
        } finally {bitmap.recycle()}
    }
}
