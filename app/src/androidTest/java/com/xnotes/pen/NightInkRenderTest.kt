package com.xnotes.pen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.GLES30
import com.xnotes.core.infinite.MeshData
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.pal.NightInkRenderer
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.gl.GeometryStore
import com.xnotes.gl.InkShader
import com.xnotes.platform.AndroidRenderer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class NightInkRenderTest {
    @Test fun layerPreviewKeepsHiddenInkAndTransparentBackground() {
        val page = com.xnotes.core.model.Page(64.0, 64.0)
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN).copy(rgba = Rgba(0, 0, 0), baseWidth = 8.0))
        stroke.addSample(Sample(8.0, 32.0, 1.0, 0.0))
        stroke.addSample(Sample(56.0, 32.0, 1.0, 10.0))
        page.items.add(stroke)
        page.layers.update(page.layers.activeId) { it.copy(visible = false) }
        val preview = com.xnotes.core.model.LayerPreview(page.items, com.xnotes.core.geometry.Rect(0.0, 0.0, 64.0, 64.0))
        val bitmap = checkNotNull(com.xnotes.platform.LayerThumbnailRenderer.render(preview.itemsFor(page.layers.activeId), preview.frame, 64))
        try {
            assertEquals(Color.BLACK, bitmap.getPixel(32, 32))
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
            assertEquals(64, bitmap.width)
            assertEquals(Rgba(0, 0, 0), stroke.config.rgba)
        } finally { bitmap.recycle() }
        assertNull(com.xnotes.platform.LayerThumbnailRenderer.render(preview.itemsFor(page.layers.activeId), preview.frame, active = { false }))
    }

    @Test fun androidCanvasDisplaysWhiteAndKeepsOriginalBlack() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN).copy(rgba = Rgba(0, 0, 0), baseWidth = 8.0))
            stroke.addSample(Sample(8.0, 32.0, 1.0, 0.0))
            stroke.addSample(Sample(56.0, 32.0, 1.0, 10.0))
            val renderer = AndroidRenderer(Canvas(bitmap))
            bitmap.eraseColor(Color.BLACK)
            stroke.paint(NightInkRenderer(renderer))
            assertEquals(Color.WHITE, bitmap.getPixel(32, 32))
            assertEquals(Rgba(0, 0, 0), stroke.config.rgba)
            bitmap.eraseColor(Color.WHITE)
            stroke.paint(renderer)
            assertEquals(Color.BLACK, bitmap.getPixel(32, 32))
        } finally { bitmap.recycle() }
    }

    @Test fun pageCachesAndWetInkRespectThemeAndPdfBoundary() {
        val page = com.xnotes.core.model.Page(64.0, 64.0)
        val state = com.xnotes.canvas.CanvasState(
            com.xnotes.core.model.Document(mutableListOf(page)),
            com.xnotes.platform.AndroidSurfaceFactory(), com.xnotes.ui.theme.monochromePalette(true),
        ).apply { zoom = 1.0; renderScale = 1.0; viewportW = 64; viewportH = 64 }
        val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN).copy(rgba = Rgba(0, 0, 0), baseWidth = 8.0))
        stroke.finished = false
        repeat(200) { stroke.addSample(Sample(8.0 + it * 0.24, 32.0, 1.0, it * 6.0)) }
        val screen = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            state.paintLiveStroke(AndroidRenderer(Canvas(screen)), stroke, page)
            assertEquals(Color.WHITE, screen.getPixel(32, 32))
            stroke.finished = true
            page.items.add(stroke)
            fun cachedPixel(): Int {
                val entry = state.cacheFor(page)
                val bitmap = (entry.surface as com.xnotes.platform.AndroidRasterSurface).bitmap
                return bitmap.getPixel(((32.0 - entry.cover.left) * entry.res).toInt(), ((32.0 - entry.cover.top) * entry.res).toInt())
            }
            assertEquals(Color.WHITE, cachedPixel())
            state.palette = com.xnotes.ui.theme.monochromePalette(false)
            state.invalidateAllCaches()
            assertEquals(Color.BLACK, cachedPixel())
            state.palette = com.xnotes.ui.theme.monochromePalette(true)
            page.pdfPage = 0
            state.invalidateAllCaches()
            assertEquals(Color.BLACK, cachedPixel())
            assertEquals(Rgba(0, 0, 0), stroke.config.rgba)
        } finally { state.invalidateAllCaches(); screen.recycle() }
    }

    @Test fun glInkChangesOnlyBlackAndResetsOnDayMode() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val versions = IntArray(2)
        assertTrue(EGL14.eglInitialize(display, versions, 0, versions, 1))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_STENCIL_SIZE, 8, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE), 0, configs, 0, 1, count, 0))
        assertTrue(count[0] > 0)
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 64, EGL14.EGL_HEIGHT, 64, EGL14.EGL_NONE), 0)
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
        val store = GeometryStore()
        var shader: InkShader? = null
        try {
            shader = InkShader(1)
            store.onContextCreated(1)
            GLES30.glViewport(0, 0, 64, 64)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDisable(GLES30.GL_DITHER)
            val mesh = MeshData(doubleArrayOf(0.0, 0.0, 64.0, 0.0, 64.0, 64.0, 0.0, 64.0), DoubleArray(8), intArrayOf(0, 1, 2, 0, 2, 3))
            fun pixel(color: Rgba, dark: Boolean): Rgba {
                val slice = checkNotNull(store.put(mesh, color))
                shader.begin(0.0, 0.0, 0.0, 0.0, 1.0, 64.0, 64.0, lightBlackInk = dark)
                assertTrue(store.bindForDraw(1))
                store.bindAttributes(shader)
                store.drawRange(slice.indexOffset, slice.indexCount)
                val buffer = ByteBuffer.allocateDirect(4)
                GLES30.glReadPixels(32, 32, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
                store.free(slice)
                return Rgba(buffer.get(0).toInt() and 255, buffer.get(1).toInt() and 255, buffer.get(2).toInt() and 255, buffer.get(3).toInt() and 255)
            }
            assertEquals(Rgba(255, 255, 255, 83), pixel(Rgba(0, 0, 0, 83), true))
            assertEquals(Rgba(255, 0, 0), pixel(Rgba(255, 0, 0), true))
            assertEquals(Rgba(1, 1, 1), pixel(Rgba(1, 1, 1), true))
            assertEquals(Rgba(0, 0, 0), pixel(Rgba(0, 0, 0), false))
            // Exercise the scene's stencil/cover path too: translucent ink does not use the
            // ordinary vertex-colour shader for its final colour.
            val scene = com.xnotes.gl.CanvasScene()
            scene.onContextCreated(1)
            val stroke = Stroke(Tool.PEN, ToolDefaults.configFor(Tool.PEN).copy(rgba = Rgba(0, 0, 0, 128), baseWidth = 8.0))
            stroke.addSample(Sample(8.0, 32.0, 1.0, 0.0))
            stroke.addSample(Sample(56.0, 32.0, 1.0, 10.0))
            val meshed = checkNotNull(com.xnotes.core.infinite.ItemMesher.mesh(stroke))
            scene.upsert(stroke, meshed.parts, stroke.paintBounds())
            val frame = com.xnotes.gl.FrameState(1.0, 0.0, 0.0, 64, 64,
                com.xnotes.core.infinite.CanvasBackground(), Rgba(0, 0, 0), lightBlackInk = true)
            for (dark in listOf(true, false)) {
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glStencilMask(0xff)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_STENCIL_BUFFER_BIT)
                scene.drawContent(frame.copy(lightBlackInk = dark))
                val buffer = ByteBuffer.allocateDirect(4)
                GLES30.glReadPixels(32, 32, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
                val red = buffer.get(0).toInt() and 255
                if (dark) assertTrue("translucent night ink: $red", red in 126..130)
                else assertEquals(0, red)
            }
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        } finally {
            shader?.release()
            store.release()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }
}
