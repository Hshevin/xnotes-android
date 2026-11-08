package com.xnotes.pen

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Looper
import android.view.ContextThemeWrapper
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Real Main pumping; the test coroutine never waits for IO on the Main test thread. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class EditorSourceReopenHostTest : EditorSourceReopenContract() {
    @Before fun initializeNoEglHandles() {
        if (EGL14.EGL_NO_DISPLAY == null) EGL14.EGL_NO_DISPLAY = noHandle(EGLDisplay::class.java)
        if (EGL14.EGL_NO_CONTEXT == null) EGL14.EGL_NO_CONTEXT = noHandle(EGLContext::class.java)
        if (EGL14.EGL_NO_SURFACE == null) EGL14.EGL_NO_SURFACE = noHandle(EGLSurface::class.java)
    }

    private fun <T> noHandle(type: Class<T>): T = ReflectionHelpers.callConstructor(
        type, ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE, 0L),
    )

    override fun runSourceScenario(block: suspend (Context, File) -> Unit) {
        val base = RuntimeEnvironment.getApplication()
        val root = File(base.cacheDir, "editor-source-reopen-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextThemeWrapper(base, android.R.style.Theme_Material_Light_NoActionBar) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var failure: Throwable? = null
        val job = scope.launch {
            try { block(context, root) } catch (error: Throwable) { failure = error }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25L)
            while (!job.isCompleted && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10L))
                Thread.yield()
            }
            assertTrue("actual source reopen scenario did not settle", job.isCompleted)
            failure?.let { throw it }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }
}
