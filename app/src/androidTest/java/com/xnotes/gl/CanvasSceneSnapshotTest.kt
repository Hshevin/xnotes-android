package com.xnotes.gl

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.GLES30
import com.xnotes.core.audio.InkFade
import com.xnotes.core.geometry.Flip
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.ItemMesher
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.SyncMark
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.pen.PenTestRunner
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.PI

/** Real EGL scene/queue tests; these do not exercise a window, front-buffer handoff, or target GPU compatibility. */
class CanvasSceneSnapshotTest {
    @Test fun queuedImageFieldsStayFrozenUntilAnotherUpsert() = withPbuffer {
        val files = mutableListOf<File>()
        try {
            val original = quadrantImage(files, 0)
            val replacement = quadrantImage(files, 32)
            val mutations = listOf(
                ImageMutation("source", listOf(32, 96, 160, 224)) { it.image = replacement },
                ImageMutation("rect", listOf(255, 0, 255, 128)) { it.rect = Rect(24.0, 8.0, 40.0, 40.0) },
                ImageMutation("orientation", listOf(64, 192, 0, 128)) { it.orientation = 90 },
                ImageMutation("angle", listOf(192, 128, 64, 0)) { it.angle = PI },
                ImageMutation("flip", listOf(64, 0, 192, 128)) { it.flip = Flip.X },
                ImageMutation("part", listOf(0, 0, 128, 128)) { it.part = Rect(0.0, 0.0, 0.5, 1.0) },
            )
            for ((field, expected, mutate) in mutations) withScene { scene, decodes ->
                val item = ImageItem(original, Rect(8.0, 8.0, 40.0, 40.0))
                scene.fade = InkFade(RECORDING_ID, PLAYHEAD_MS)
                scene.upsertImage(item, item.paintBounds())
                // Change one image field after enqueue, before even the first scene drain/decode.
                mutate(item)
                item.sync = FUTURE_MARK
                drawReady(scene, decodes)
                val before = imageProbes()
                for (i in before.indices) assertGray("$field: queued image at probe $i", i * 64, before[i])
                assertEquals("$field: original source decoded once", 1, decodes.completed)

                // An explicit upsert must adopt that field. Freeze null sync again so a missing
                // image-field update cannot pass merely because the playback alpha changed.
                item.sync = null
                scene.upsertImage(item, item.paintBounds())
                item.sync = FUTURE_MARK
                drawReady(scene, decodes)
                val after = imageProbes()
                for (i in after.indices) assertGray("$field: explicit upsert at probe $i", expected[i], after[i])
            }
        } finally {
            files.forEach { it.delete() }
        }
    }

    @Test fun syncMessagesFreezeMarksWithoutReplacingGeometry() = withPbuffer {
        withScene { scene, decodes ->
            val stroke = strokeAt(32.0)
            val mesh = checkNotNull(ItemMesher.mesh(stroke))
            scene.fade = InkFade(RECORDING_ID, PLAYHEAD_MS)
            scene.upsert(stroke, mesh.parts, mesh.bounds)
            // Recording stamps ink after its initial model-add/upsert. Without a message, the
            // already queued null mark must keep full opacity even though the live model changed.
            stroke.sync = FUTURE_MARK
            drawReady(scene, decodes)
            assertGray("upsert froze null sync", 0, grayAt(32, 32))
            val geometry = scene.describe(GlStats())
            assertTrue(geometry.vertices > 0 && geometry.liveGeometryBytes > 0)

            scene.syncChanged(listOf(stroke))
            stroke.sync = null
            drawReady(scene, decodes)
            assertGray("syncChanged froze the future mark", FADED_BLACK, grayAt(32, 32))
            assertSameGeometry(geometry, scene.describe(GlStats()))

            // Deliberately withhold a geometry notification: a metadata-only message must leave
            // the stored mesh/bounds at y=32 instead of rebuilding from this moved model.
            stroke.translate(0.0, -20.0)
            scene.syncChanged(listOf(stroke))
            stroke.sync = FUTURE_MARK
            drawReady(scene, decodes)
            assertGray("syncChanged froze null", 0, grayAt(32, 32))
            assertGray("metadata did not rebuild moved geometry", 255, grayAt(32, 12))
            assertSameGeometry(geometry, scene.describe(GlStats()))
            assertEquals(0, decodes.completed)
        }
    }

    @Test fun syncMessagesNeverCreateAbsentRecords() = withPbuffer {
        withScene { scene, decodes ->
            val removed = strokeAt(20.0)
            val notYetCommitted = strokeAt(44.0)
            val removedMesh = checkNotNull(ItemMesher.mesh(removed))
            val heldMesh = checkNotNull(ItemMesher.mesh(notYetCommitted))
            scene.upsert(removed, removedMesh.parts, removedMesh.bounds)
            drawReady(scene, decodes)
            assertEquals(1, scene.itemCount)
            assertGray("record exists before removal", 0, grayAt(32, 20))

            scene.remove(removed)
            removed.sync = FUTURE_MARK
            notYetCommitted.sync = FUTURE_MARK
            // Hidden/removed and held items can both lack a scene record. This checks that queue
            // boundary only; it does not simulate the editor's layer or front-buffer lifecycle.
            scene.syncChanged(listOf(removed, notYetCommitted))
            drawReady(scene, decodes)
            assertEquals(0, scene.itemCount)
            assertEquals(0, scene.describe(GlStats()).vertices)
            assertGray("removed record stayed absent", 255, grayAt(32, 20))
            assertGray("uncommitted record stayed absent", 255, grayAt(32, 44))

            // A later real upsert is still accepted and captures the latest metadata.
            scene.fade = InkFade(RECORDING_ID, PLAYHEAD_MS)
            scene.upsert(notYetCommitted, heldMesh.parts, heldMesh.bounds)
            notYetCommitted.sync = null
            drawReady(scene, decodes)
            assertEquals(1, scene.itemCount)
            assertGray("only an upsert creates the record", FADED_BLACK, grayAt(32, 44))
            assertGray("other missing record stayed absent", 255, grayAt(32, 20))
        }
    }

    private fun strokeAt(y: Double) = Stroke(
        Tool.PEN,
        ToolDefaults.configFor(Tool.PEN).copy(rgba = Rgba(0, 0, 0), baseWidth = 8.0),
        straight = true,
    ).apply {
        addSample(Sample(8.0, y, 1.0, 0.0))
        addSample(Sample(56.0, y, 1.0, 10.0))
    }

    private data class ImageMutation(val field: String, val expected: List<Int>, val mutate: (ImageItem) -> Unit)

    private fun assertSameGeometry(before: GlStats, after: GlStats) {
        assertEquals(before.items, after.items)
        assertEquals(before.vertices, after.vertices)
        assertEquals(before.indices, after.indices)
        assertEquals(before.vertexCapacity, after.vertexCapacity)
        assertEquals(before.indexCapacity, after.indexCapacity)
        assertEquals(before.liveGeometryBytes, after.liveGeometryBytes)
        assertEquals(before.geometryBytes, after.geometryBytes)
        assertEquals(before.wetVertices, after.wetVertices)
    }

    private fun quadrantImage(files: MutableList<File>, offset: Int): ImageData {
        val file = File.createTempFile("canvas-scene-", ".png", PenTestRunner.instance.targetContext.cacheDir)
        files += file
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 16) for (x in 0 until 16) {
                val gray = offset + (if (x < 8) 0 else 64) + (if (y < 8) 0 else 128)
                bitmap.setPixel(x, y, Color.rgb(gray, gray, gray))
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return ImageData(file, 16, 16)
    }

    private fun imageProbes() = listOf(grayAt(18, 18), grayAt(38, 18), grayAt(18, 38), grayAt(38, 38))

    private fun grayAt(x: Int, y: Int): Int {
        val bytes = ByteBuffer.allocateDirect(4)
        GLES30.glReadPixels(x, SIZE - 1 - y, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
        assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        val red = bytes.get(0).toInt() and 255
        assertTrue(abs(red - (bytes.get(1).toInt() and 255)) <= COLOR_TOLERANCE)
        assertTrue(abs(red - (bytes.get(2).toInt() and 255)) <= COLOR_TOLERANCE)
        return red
    }

    private fun assertGray(label: String, expected: Int, actual: Int) {
        assertTrue("$label: expected $expected, got $actual", abs(expected - actual) <= COLOR_TOLERANCE)
    }

    /** Decode work runs only when the test advances it; no executor or timing race is involved. */
    private class Decodes {
        val pending = ArrayDeque<Runnable>()
        var completed = 0
            private set

        fun submit(work: Runnable) {
            check(pending.size < MAX_DECODE_TASKS) { "Unexpected decode queue growth" }
            pending.addLast(work)
        }

        fun finishPending() {
            val count = pending.size
            repeat(count) {
                check(completed < MAX_DECODE_TASKS) { "Unexpected repeated image decode" }
                pending.removeFirst().run()
                completed++
            }
            check(pending.isEmpty()) { "Decode work unexpectedly scheduled more decode work" }
        }
    }

    private fun drawReady(scene: CanvasScene, decodes: Decodes) {
        repeat(MAX_DECODE_PASSES) {
            GLES30.glViewport(0, 0, SIZE, SIZE)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glDisable(GLES30.GL_STENCIL_TEST)
            GLES30.glColorMask(true, true, true, true)
            GLES30.glDepthMask(true)
            GLES30.glStencilMask(0xff)
            GLES30.glClearColor(1f, 1f, 1f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT or GLES30.GL_STENCIL_BUFFER_BIT)
            scene.drawContent(FRAME)
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            if (decodes.pending.isEmpty()) {
                assertEquals("all requested image decodes completed", 0, scene.textures.pendingCount)
                return
            }
            decodes.finishPending()
        }
        fail("Image did not settle within $MAX_DECODE_PASSES controlled draws")
    }

    private fun withScene(block: (CanvasScene, Decodes) -> Unit) {
        val store = GeometryStore()
        val scene = CanvasScene(store)
        val decodes = Decodes()
        try {
            scene.decodeOn = decodes::submit
            scene.onContextCreated(1)
            block(scene, decodes)
        } finally {
            decodes.pending.clear() // Unstarted tasks own no decoded bitmap.
            scene.textures.release()
            // Also recycles a decoded bitmap still in the ready queue if a draw/assertion failed.
            scene.textures.onContextCreated(2)
            store.release()
            // The enclosing EGL context owns the scene's other programs and transient buffers.
        }
    }

    /** Same ES3/RGBA8/stencil pbuffer requirements as NightInkRenderTest, with partial-setup cleanup. */
    private fun withPbuffer(block: () -> Unit) {
        check(EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) { "Test thread already has an EGL context" }
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertNotEquals(EGL14.EGL_NO_DISPLAY, display)
        var initialized = false
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val versions = IntArray(2)
            initialized = EGL14.eglInitialize(display, versions, 0, versions, 1)
            assertTrue(initialized)
            assertTrue(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            assertTrue(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_STENCIL_SIZE, 8, EGL14.EGL_NONE,
            ), 0, configs, 0, 1, count, 0))
            assertTrue(count[0] > 0)
            val config = checkNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            assertNotEquals(EGL14.EGL_NO_CONTEXT, context)
            surface = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, SIZE, EGL14.EGL_HEIGHT, SIZE, EGL14.EGL_NONE), 0)
            assertNotEquals(EGL14.EGL_NO_SURFACE, surface)
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
            GLES30.glDisable(GLES30.GL_DITHER)
            block()
        } finally {
            if (initialized) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            EGL14.eglReleaseThread()
        }
    }

    companion object {
        private const val SIZE = 64
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x40
        private const val COLOR_TOLERANCE = 3
        private const val RECORDING_ID = 7
        private const val PLAYHEAD_MS = 100
        private const val FADED_BLACK = 191
        private const val MAX_DECODE_PASSES = 3
        private const val MAX_DECODE_TASKS = 4
        private val FUTURE_MARK = SyncMark(RECORDING_ID, 1_000)
        private val FRAME = FrameState(1.0, 0.0, 0.0, SIZE, SIZE, CanvasBackground(), Rgba(255, 255, 255))
    }
}
