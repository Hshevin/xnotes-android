package com.xnotes.mindmap

import android.content.Context
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.xnotes.R
import com.xnotes.pen.PenTestRunner
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.Settings
import com.xnotes.settings.SettingsRepository
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.ComposeHostFixture
import com.xnotes.ui.theme.TextSize
import com.xnotes.ui.theme.XnotesTheme
import com.xnotes.ui.theme.monochromePalette
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real Compose host and View remounts. Compilation alone is not Android/Surface acceptance. */
class MapWorkspaceEditingHostTest {
    private val runner get()=PenTestRunner.instance
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun graph(host:ComposeHostFixture)=views(host.activity.window.decorView).filterIsInstance<MindMapView>().singleOrNull()
    private fun scenario(block:(ComposeHostFixture,MapWorkspace,Uri,AtomicInteger,AtomicInteger)->Unit) {
        val base=runner.targetContext
        val root=File(base.cacheDir,"map-main-host-${UUID.randomUUID()}").apply {mkdirs()}
        val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        val previous=singleton.get(null)
        val settings=LiveSettings.get(SettingsRepository(base));LiveSettings.set(Settings())
        try {ComposeHostFixture.run {host->
            val context=object:ContextThemeWrapper(host.activity,R.style.Theme_Xnotes) {
                override fun getApplicationContext():Context=this
                override fun getFilesDir()=File(root,"files").apply {mkdirs()}
                override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
                override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            }
            val storage=NotebookStorage(context)
            singleton.set(null,storage)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            val workspace=host.onMain {MapWorkspace(context,scope)}
            val uri=Uri.fromFile(File(root,"map.xmap").apply {writeBytes(MapCodec.encode(MindMapDocument(
                nodes=listOf(MapNode(id="root",parent=null,text="Host topic")))) )})
            val mounted=AtomicInteger();val detached=AtomicInteger()
            try {
                host.setContent {CompositionLocalProvider(LocalContext provides context) {XnotesTheme(monochromePalette(false)) {
                    MapWorkspaceHost(workspace,onClosed={workspace.close(it)}) {
                        DisposableEffect(Unit) {mounted.incrementAndGet();onDispose {detached.incrementAndGet()}}
                        Text("Native reading surface",fontSize=TextSize.body)
                    }
                }}}
                host.await("initial native host") {mounted.get()==1}
                block(host,workspace,uri,mounted,detached)
            } finally {
                host.disposeContent()
                val cleanup=host.onMain {workspace.dispose()}
                runBlocking {withTimeout(10000) {cleanup.join()}}
                scope.cancel();storage.registry.close();singleton.set(null,previous)
            }
        }} finally {singleton.set(null,previous);LiveSettings.set(settings);root.deleteRecursively()}
    }
    @Test fun showingTheMapUnmountsNativeContentAndClosingRestoresTheNativeHost()=scenario {host,workspace,uri,mounted,detached->
        host.onMain {assertTrue(workspace.open(uri,"Host map"))}
        host.await("map mounted") {workspace.visible && graph(host)?.height?.let {it>0}==true}
        val entry=host.onMain {requireNotNull(workspace.current)}
        assertEquals(1,mounted.get());assertEquals(1,detached.get())
        assertEquals("root",host.onMain {entry.session.document!!.root.id})
        host.onMain {workspace.close(entry)}
        host.await("native restored") {!workspace.visible && workspace.current==null && mounted.get()==2}
        assertEquals(1,detached.get());assertNull(host.onMain {graph(host)})
    }
    @Test fun hidingAndReturningCreatesANewViewWithTheSameCameraDocumentSelectionAndRedo()=scenario {host,workspace,uri,mounted,detached->
        host.onMain {workspace.open(uri,"Host map")}
        host.await("initial map geometry") {workspace.visible && graph(host)?.height?.let {it>0}==true}
        val entry=host.onMain {requireNotNull(workspace.current)}
        val old=host.onMain {requireNotNull(graph(host))}
        host.awaitStableWindowBounds(old)
        val camera=host.onMain {
            entry.session.operate {it.update("root") {node->node.copy(text="Temporary")}}
            entry.session.undo();assertTrue(entry.session.canRedo)
            old.zoomBy(1.25f);old.viewport()
        }
        val document=host.onMain {entry.session.document}
        val selection=host.onMain {entry.session.selected}
        host.onMain {assertTrue(workspace.hide(entry))}
        host.await("hidden map body") {!workspace.visible && graph(host)==null && mounted.get()==2}
        host.onMain {assertTrue(workspace.show(entry))}
        host.await("restored camera") {
            val current=graph(host)
            current!=null && current!==old && current.width>0 && current.height>0 && current.viewport().let {
                kotlin.math.abs(it.zoom-camera.zoom)<.00001f && kotlin.math.abs(it.panX-camera.panX)<.005f && kotlin.math.abs(it.panY-camera.panY)<.005f
            }
        }
        host.onMain {
            assertSame(entry,workspace.current);assertSame(document,entry.session.document)
            assertEquals(selection,entry.session.selected);assertTrue(entry.session.canRedo)
        }
        assertEquals(2,mounted.get());assertEquals(2,detached.get())
    }
}
