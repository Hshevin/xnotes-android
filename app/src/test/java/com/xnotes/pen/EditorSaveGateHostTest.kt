package com.xnotes.pen

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.view.ContextThemeWrapper
import com.xnotes.ui.Editor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class EditorSaveGateHostTest : EditorSaveGateContract() {
    @Before fun initializeNoEglHandles() {
        if (EGL14.EGL_NO_DISPLAY == null) EGL14.EGL_NO_DISPLAY = noHandle(EGLDisplay::class.java)
        if (EGL14.EGL_NO_CONTEXT == null) EGL14.EGL_NO_CONTEXT = noHandle(EGLContext::class.java)
        if (EGL14.EGL_NO_SURFACE == null) EGL14.EGL_NO_SURFACE = noHandle(EGLSurface::class.java)
    }

    private fun <T> noHandle(type: Class<T>): T = ReflectionHelpers.callConstructor(
        type, ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE, 0L),
    )

    override fun runEditorScenario(block: suspend (Editor) -> Unit) {
        val base = RuntimeEnvironment.getApplication()
        val root = File(base.cacheDir, "editor-save-gate-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var editor: Editor? = null
        var failure: Throwable? = null
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val actual = Editor(isolatedContext(base, root)).also { editor = it }
                block(actual)
            } catch (error: Throwable) { failure = error }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15L)
            while (!job.isCompleted && System.nanoTime() < deadline) {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(10L))
                Thread.sleep(1L)
            }
            assertTrue("actual Editor scenario did not settle", job.isCompleted)
            failure?.let { throw it }
        } finally {
            scope.cancel()
            editor?.let {
                for (pane in listOfNotNull(it, it.secondary)) {
                    pane.controller.resetGestureState()
                    (Editor::class.java.getDeclaredField("autosaveScope").apply { isAccessible = true }.get(pane) as CoroutineScope).cancel()
                }
            }
            root.deleteRecursively()
        }
    }

    @Test fun actualEditorConstructsWithoutCreatingAnEglContext() {
        val base = RuntimeEnvironment.getApplication()
        val root = File(base.cacheDir, "editor-save-gate-${UUID.randomUUID()}").apply { mkdirs() }
        val context = isolatedContext(base, root)
        var editor: Editor? = null
        try {
            editor = Editor(context)
            assertTrue(editor.state.document.pages.isNotEmpty())
        } finally {
            editor?.let {
                (Editor::class.java.getDeclaredField("autosaveScope").apply { isAccessible = true }.get(it) as CoroutineScope).cancel()
            }
            root.deleteRecursively()
        }
    }

    @Test fun reconstructingThePrimaryEditorKeepsTheFirstOwnersActiveSourceAssets() {
        val base = RuntimeEnvironment.getApplication()
        val root = File(base.cacheDir, "editor-save-assets-${UUID.randomUUID()}").apply { mkdirs() }
        val context = isolatedContext(base, root)
        val editors = ArrayList<Editor>()
        try {
            editors += Editor(context)
            val pdf = File(context.filesDir, "pdfsrc/active-original.pdf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val image = File(context.filesDir, "noteimg/active-image.png").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
            editors += Editor(context)
            org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3, 4), pdf.readBytes())
            org.junit.Assert.assertArrayEquals(byteArrayOf(5, 6, 7, 8), image.readBytes())
        } finally {
            editors.forEach {
                (Editor::class.java.getDeclaredField("autosaveScope").apply { isAccessible = true }.get(it) as CoroutineScope).cancel()
            }
            root.deleteRecursively()
        }
    }

    private fun isolatedContext(base: Context, root: File): Context =
        object : ContextThemeWrapper(base, android.R.style.Theme_Material_Light_NoActionBar) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
}
