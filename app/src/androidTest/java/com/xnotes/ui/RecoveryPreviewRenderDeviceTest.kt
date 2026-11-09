package com.xnotes.ui

import android.content.Context
import android.view.ContextThemeWrapper
import com.xnotes.R
import com.xnotes.pen.PenTestRunner
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.SettingsRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

/** Shared CPU behavior; compilation does not establish native PDFium or MovinkPad execution. */
class RecoveryPreviewRenderDeviceTest:RecoveryPreviewRenderContract() {
    override fun scenario(block:suspend (Editor,Context,File)->Unit)=runBlocking {
        val base=PenTestRunner.instance.targetContext;val root=File(base.cacheDir,"recovery-render-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextThemeWrapper(base,R.style.Theme_Xnotes) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val previous=LiveSettings.get(SettingsRepository(context))
        try {withContext(Dispatchers.Main) {
            com.xnotes.platform.FontCatalog.init(context);com.xnotes.platform.TemplateLibrary.init(context)
            val editor=Editor(context)
            try {withTimeout(30000){block(editor,context,root)}} finally {
                (Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(editor) as CoroutineScope).cancel()
            }
        }} finally {LiveSettings.set(previous);root.deleteRecursively()}
    }
}
