package com.xnotes.pen

import android.content.Context
import android.view.ContextThemeWrapper
import com.xnotes.R
import com.xnotes.ui.Editor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Actual Editor on Android; only source compilation has been performed unless the runner is run. */
class EditorSaveGateDeviceTest : EditorSaveGateContract() {
    override fun runEditorScenario(block: suspend (Editor) -> Unit) = runBlocking {
        val base = PenTestRunner.instance.targetContext
        val root = File(base.cacheDir, "editor-save-gate-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextThemeWrapper(base, R.style.Theme_Xnotes) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        try {
            withContext(Dispatchers.Main) {
                val editor = Editor(context)
                try { withTimeout(10000L) { block(editor) } } finally {
                    for (pane in listOfNotNull(editor, editor.secondary)) {
                        pane.controller.resetGestureState()
                        (Editor::class.java.getDeclaredField("autosaveScope").apply { isAccessible = true }.get(pane) as CoroutineScope).cancel()
                    }
                }
            }
        } finally { root.deleteRecursively() }
    }
}
