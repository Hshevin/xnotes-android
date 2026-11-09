package com.xnotes.ui

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import com.xnotes.R
import com.xnotes.pen.PenTestRunner
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.Settings
import com.xnotes.settings.SettingsRepository
import com.xnotes.ui.theme.XnotesTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real window/Compose accessibility clicks; source compilation alone is not device execution. */
class BackstageRecoveryEntryHostTest {
    private val runner get()=PenTestRunner.instance
    private fun clickRecovery(label:String) {
        repeat(400) {
            runner.waitForIdleSync()
            val named=runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label).orEmpty()
            val target=named.firstNotNullOfOrNull {node->
                var candidate:AccessibilityNodeInfo?=node
                repeat(4) {
                    val current=candidate
                    if(current?.isClickable==true && current.isEnabled) return@firstNotNullOfOrNull current
                    candidate=current?.parent
                }
                null
            }
            if(target!=null) {assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync();return}
            Thread.sleep(10)
        }
        fail("Recovery materials action was not reachable")
    }
    private fun scenario(pane:Pane,rail:Boolean) {
        val base=runner.targetContext
        val root=File(base.cacheDir,"backstage-recovery-entry-${UUID.randomUUID()}").apply {mkdirs()}
        val previous=LiveSettings.get(SettingsRepository(base))
        LiveSettings.set(Settings())
        try {
            ComposeHostFixture.run {host->
                val context=object:ContextThemeWrapper(host.activity,R.style.Theme_Xnotes) {
                    override fun getApplicationContext():Context=this
                    override fun getFilesDir()=File(root,"files").apply {mkdirs()}
                    override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
                    override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
                }
                val editor=host.onMain {Editor(context,pane).apply {showRail(rail);state.document.dirty=true}}
                try {
                    val original=host.onMain {editor.state.document}
                    val camera=host.onMain {Triple(editor.state.zoom,editor.state.scrollX,editor.state.scrollY)}
                    val open=host.onMain {editor.noteOpen to editor.canvasOpen}
                    assertNull(host.onMain {editor.browseRoot})
                    val version=mutableStateOf(0)
                    val calls=mutableListOf<Pair<Pane,Int>>()
                    var otherActions=0
                    host.setContent {
                        val configuration=Configuration(LocalConfiguration.current).apply {screenWidthDp=840}
                        CompositionLocalProvider(LocalConfiguration provides configuration) {
                            XnotesTheme(editor.palette) {
                                val displayedVersion=version.value
                                Backstage(editor,BackstageView.HOME,{otherActions++},
                                    onImportPdf={otherActions++},onOpenFile={otherActions++},onPickRoot={otherActions++},
                                    onShareFile={otherActions++},onSaveCopyFile={otherActions++},onExportFilePdf={otherActions++},
                                    onExitApp={otherActions++},onPreviewRecovery={calls+=editor.pane to displayedVersion})
                            }
                        }
                    }
                    val label=host.activity.getString(R.string.recovery_materials)
                    clickRecovery(label)
                    host.onMain {assertEquals(listOf(pane to 0),calls)}
                    host.change {version.value=1}
                    clickRecovery(label)
                    host.onMain {
                        assertEquals(listOf(pane to 0,pane to 1),calls)
                        assertEquals(0,otherActions);assertSame(original,editor.state.document);assertTrue(original.dirty)
                        assertEquals(camera,Triple(editor.state.zoom,editor.state.scrollX,editor.state.scrollY))
                        assertEquals(open,editor.noteOpen to editor.canvasOpen);assertEquals(pane,editor.pane);assertNull(editor.browseRoot)
                    }
                } finally {
                    host.disposeContent()
                    host.onMain {
                        editor.controller.resetGestureState()
                        (Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(editor) as CoroutineScope).cancel()
                    }
                }
            }
        } finally {LiveSettings.set(previous);root.deleteRecursively()}
    }
    @Test fun sidebarRecoveryActionWorksWithoutAFolderAndKeepsThePrimaryWorkspace()=scenario(Pane.PRIMARY,false)
    @Test fun railRecoveryActionWorksWithoutAFolderAndKeepsTheSecondaryWorkspace()=scenario(Pane.SECONDARY,true)
}
