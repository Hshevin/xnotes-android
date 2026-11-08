package com.xnotes.pen

import android.content.Context
import android.view.ContextThemeWrapper
import com.xnotes.R
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Shared real Editor/queue paths; compiling this wrapper does not mean device execution. */
class EditorRecoveryPreviewDeviceTest:EditorRecoveryPreviewContract() {
    override fun runPreviewScenario(block:suspend (Context,File)->Unit)=runBlocking {
        val base=PenTestRunner.instance.targetContext
        val root=File(base.cacheDir,"editor-recovery-preview-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextThemeWrapper(base,R.style.Theme_Xnotes) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        try {withContext(Dispatchers.Default) {block(context,root)}} finally {root.deleteRecursively()}
    }
}
