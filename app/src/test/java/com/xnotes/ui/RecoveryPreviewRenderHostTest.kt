package com.xnotes.ui

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.view.ContextThemeWrapper
import com.xnotes.R
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.SettingsRepository
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryPreviewRenderHostTest:RecoveryPreviewRenderContract() {
    @Before fun noEglContextIsCreated() {
        fun <T> handle(type:Class<T>):T=ReflectionHelpers.callConstructor(type,ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE,0L))
        if(EGL14.EGL_NO_DISPLAY==null) EGL14.EGL_NO_DISPLAY=handle(EGLDisplay::class.java)
        if(EGL14.EGL_NO_CONTEXT==null) EGL14.EGL_NO_CONTEXT=handle(EGLContext::class.java)
        if(EGL14.EGL_NO_SURFACE==null) EGL14.EGL_NO_SURFACE=handle(EGLSurface::class.java)
    }
    override fun scenario(block:suspend (Editor,Context,File)->Unit) {
        val base=RuntimeEnvironment.getApplication();val root=File(base.cacheDir,"recovery-render-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextThemeWrapper(base,R.style.Theme_Xnotes) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val previous=LiveSettings.get(SettingsRepository(context))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main);var editor:Editor?=null;var failure:Throwable?=null
        val job=scope.launch(start=CoroutineStart.UNDISPATCHED) {try {
            com.xnotes.platform.FontCatalog.init(context);com.xnotes.platform.TemplateLibrary.init(context)
            val actual=Editor(context).also {editor=it};block(actual,context,root)
        } catch(error:Throwable) {failure=error}}
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!job.isCompleted && System.nanoTime()<deadline) {shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(10));Thread.sleep(1)}
            assertTrue("Actual recovery renderer scenario did not settle",job.isCompleted);failure?.let {throw it}
        } finally {
            scope.cancel()
            val cleanupDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(!job.isCompleted && System.nanoTime()<cleanupDeadline) {shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(10));Thread.sleep(1)}
            editor?.let {(Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(it) as CoroutineScope).cancel()}
            LiveSettings.set(previous)
            if(job.isCompleted) root.deleteRecursively()
        }
    }
}
