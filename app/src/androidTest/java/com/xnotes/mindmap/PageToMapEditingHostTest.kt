package com.xnotes.mindmap

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat
import com.xnotes.R
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.DocumentCodec
import com.xnotes.pen.PenTestRunner
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.Settings
import com.xnotes.settings.SettingsRepository
import com.xnotes.storage.MigrationPublication
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.ComposeHostFixture
import com.xnotes.ui.Editor
import com.xnotes.ui.SidePanel
import com.xnotes.ui.SidePanelTab
import com.xnotes.ui.theme.XnotesTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual reading menu, Compose/a11y and raw ActivityResult contracts. Compile-only until run on Android. */
class PageToMapEditingHostTest {
    private val runner get()=PenTestRunner.instance
    private data class Launch(val code:Int,val action:String?)
    private class Registry(private val context:Context):ActivityResultRegistry() {
        val launches=mutableListOf<Launch>()
        override fun <I,O> onLaunch(code:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
            val intent=contract.createIntent(context,input)
            require(intent.action==Intent.ACTION_OPEN_DOCUMENT || intent.action==Intent.ACTION_CREATE_DOCUMENT)
            launches+=Launch(code,intent.action)
        }
        fun deliver(launch:Launch,uri:Uri?) {
            assertTrue(dispatchResult(launch.code,if(uri==null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                uri?.let {Intent().setData(it).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}))
        }
    }
    private fun waitUntil(label:String,check:()->Boolean) {
        repeat(600) {runner.waitForIdleSync();if(check()) return;Thread.sleep(10)}
        fail("Current-page reference UI did not settle: $label")
    }
    private fun visible(label:String)=runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label).orEmpty().isNotEmpty()
    private fun button(label:String):AccessibilityNodeInfo? {
        for(node in runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label).orEmpty()) {
            var at:AccessibilityNodeInfo?=node
            repeat(6) {val current=at;if(current?.isClickable==true) return current;at=current?.parent}
        }
        return null
    }
    private fun click(label:String) {
        var target:AccessibilityNodeInfo?=null
        waitUntil(label) {target=button(label);target?.isEnabled==true}
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync()
    }
    private class Fixture(val host:ComposeHostFixture,val root:File) {
        val context=object:ContextThemeWrapper(host.activity,R.style.Theme_Xnotes) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val source=File(root,"reading.xnote")
        val map=File(root,"destination.xmap")
        val mapUri=Uri.fromFile(map)
        val sourceWrites=AtomicInteger()
        val savesRequested=AtomicInteger()
        val mapOpens=mutableListOf<Uri>()
        val registry=Registry(context)
        val storage=NotebookStorage(context,targetOverride={uri->
            val file=File(requireNotNull(Uri.parse(uri).path))
            require(file.canonicalPath.startsWith(root.canonicalPath+File.separator))
            object:MigrationPublication.Target {
                override fun read()=file.inputStream()
                override fun write(candidate:File) {sourceWrites.incrementAndGet();candidate.copyTo(file,overwrite=true)}
            }
        })
        val editor:Editor
        val lease:MapDocumentLease
        val reading:Document
        val page:Page
        val sourceBefore:ByteArray
        val mapBefore:ByteArray
        init {
            NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}.set(null,storage)
            val seeded=Document(mutableListOf(Page(500.0,500.0))).apply {enableReferenceAnchors()}
            val codec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
            source.outputStream().use {codec.write(seeded.fixedWriteSnapshot(),it)}
            map.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="concept",parent=null,text="Existing concept")))))
            sourceBefore=source.readBytes();mapBefore=map.readBytes()
            editor=host.onMain {
                com.xnotes.platform.FontCatalog.init(context);com.xnotes.platform.TemplateLibrary.init(context)
                Editor(context).apply {sidePanelTab=SidePanelTab.PAGES}
            }
            runBlocking {withTimeout(10000) {withContext(Dispatchers.Main) {editor.openAsync(Uri.fromFile(source).toString(),source.name)}}}
            reading=host.onMain {assertTrue(editor.noteOpen);editor.state.document}
            page=host.onMain {requireNotNull(editor.currentPage)}
            assertNotNull(page.referenceIdentity.id);assertTrue(reading.hasReferenceAnchors)
            lease=host.onMain {MapDocuments.acquire(context,mapUri)}
        }
        fun label(id:Int)=context.getString(id)
        fun compose() {
            host.setContent {
                val owner=object:ActivityResultRegistryOwner {override val activityResultRegistry=registry}
                CompositionLocalProvider(LocalContext provides context,LocalActivityResultRegistryOwner provides owner) {
                    XnotesTheme(editor.palette) {
                        Box(Modifier.fillMaxSize()) {
                            SidePanel(editor)
                            PageToMapFlowHost(editor,{savesRequested.incrementAndGet()},{mapOpens+=it})
                        }
                    }
                }
            }
        }
        fun assertReadingKept() {
            host.onMain {
                assertSame(reading,editor.state.document);assertSame(page,editor.currentPage)
                assertTrue(editor.noteOpen);assertFalse(editor.canvasOpen);assertNull(editor.secondary)
                assertFalse(reading.dirty)
            }
            assertArrayEquals(sourceBefore,source.readBytes());assertEquals(0,sourceWrites.get());assertEquals(0,savesRequested.get())
        }
        fun cleanup() {
            host.disposeContent()
            val released=host.onMain {lease.close()}
            runBlocking {withTimeout(10000) {released.join()}}
            val autosave=host.onMain {
                editor.controller.resetGestureState()
                (Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(editor) as CoroutineScope).also {it.cancel()}
            }
            runBlocking {withTimeout(10000) {
                autosave.coroutineContext[Job]?.join()
                (Editor::class.java.getDeclaredField("sessionOwner").apply {isAccessible=true}.get(editor) as LegacySessionQueue.Owner).awaitAccepted()
            }}
        }
    }
    private fun scenario(block:(Fixture)->Unit) {
        val base=runner.targetContext
        val root=File(base.cacheDir,"current-page-map-ui-${UUID.randomUUID()}").apply {mkdirs()}
        val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        val previousStorage=singleton.get(null)
        val previousSettings=LiveSettings.get(SettingsRepository(base));LiveSettings.set(Settings())
        try {ComposeHostFixture.run {host->
            var fixture:Fixture?=null
            try {
                val f=Fixture(host,root).also {fixture=it}
                f.compose();waitUntil("destination actor") {host.onMain {!f.lease.session.loading}}
                assertFalse(host.onMain {f.lease.session.loadFailed});block(f)
            } finally {
                try {fixture?.cleanup()} finally {
                    (singleton.get(null) as? NotebookStorage)?.takeIf {it!==previousStorage}?.registry?.close()
                    singleton.set(null,previousStorage)
                }
            }
        }} finally {singleton.set(null,previousStorage);LiveSettings.set(previousSettings);root.deleteRecursively()}
    }
    private fun beginFromPageMenu(f:Fixture) {
        click(f.label(R.string.page_options));click(f.label(R.string.graph_reference_this_page))
        waitUntil("choose a map") {visible(f.label(R.string.graph_ref_choose_map)) && f.host.onMain {f.editor.pageReferenceRequest!=null}}
    }
    private fun pickMap(f:Fixture):Launch {
        val count=f.host.onMain {f.registry.launches.size}
        click(f.label(R.string.graph_ref_choose_map))
        waitUntil("OpenDocument launch") {f.host.onMain {f.registry.launches.size==count+1}}
        return f.host.onMain {f.registry.launches.last()}.also {assertEquals(Intent.ACTION_OPEN_DOCUMENT,it.action)}
    }
    private fun receiveMap(f:Fixture,launch:Launch) {
        f.host.onMain {f.registry.deliver(launch,f.mapUri)}
        waitUntil("actual destination nodes") {visible("Existing concept")}
    }
    private fun assertSavedSource(f:Fixture,node:MapNode) {
        val source=node.sources.single()
        assertEquals(SourceScope.PAGE,source.scope);assertEquals(f.reading.identity.documentId,source.documentId)
        assertEquals(f.page.referenceIdentity.id,source.pageId);assertEquals(1,source.lastKnownPage)
        assertEquals(node.sources,MapCodec.decode(f.map.readBytes()).node(node.id).sources)
        assertTrue(f.host.onMain {f.lease.session.canUndo})
        assertTrue(f.host.onMain {f.mapOpens.isEmpty()});f.assertReadingKept()
    }
    @Test fun aRetiredPickerCannotAttachToTheNextReadingRequestAndTheNextActualChoiceSavesWithoutLeavingThePage()=scenario {f->
        beginFromPageMenu(f);val firstRequest=f.host.onMain {f.editor.pageReferenceRequest!!}
        val old=pickMap(f);click(f.label(R.string.cancel))
        waitUntil("retired page request") {f.host.onMain {f.editor.pageReferenceRequest==null}}
        beginFromPageMenu(f)
        assertNotSame(firstRequest,f.host.onMain {f.editor.pageReferenceRequest})
        waitUntil("old system picker occupies the launcher") {button(f.label(R.string.graph_ref_choose_map))?.isEnabled==false}
        assertEquals(1,f.host.onMain {f.registry.launches.size})
        f.host.onMain {f.registry.deliver(old,f.mapUri)}
        waitUntil("old result consumed without rebinding") {button(f.label(R.string.graph_ref_choose_map))?.isEnabled==true}
        assertArrayEquals(f.mapBefore,f.map.readBytes())
        assertTrue(f.host.onMain {f.lease.session.document!!.root.sources.isEmpty()});f.assertReadingKept()
        receiveMap(f,pickMap(f));click("Existing concept");click(f.label(R.string.graph_ref_add))
        waitUntil("reference saved") {visible(f.label(R.string.graph_ref_added))}
        val node=f.host.onMain {f.lease.session.document!!.root}
        assertEquals("Existing concept",node.text);assertSavedSource(f,node)
        click(f.label(R.string.map_done))
        waitUntil("flow closed") {f.host.onMain {f.editor.pageReferenceRequest==null}}
        assertTrue(f.host.onMain {f.mapOpens.isEmpty()});f.assertReadingKept()
    }
    @Test fun theFreeReferenceActionCreatesTheNodeAndItsPageSourceInOneUndoableStep()=scenario {f->
        beginFromPageMenu(f);receiveMap(f,pickMap(f))
        click(f.label(R.string.graph_ref_new_free));click(f.label(R.string.graph_ref_add))
        waitUntil("free reference saved") {visible(f.label(R.string.graph_ref_added))}
        val graph=f.host.onMain {f.lease.session.document!!}
        assertEquals(2,graph.nodes.size);assertEquals("Existing concept",graph.root.text)
        val node=graph.nodes.single {it.free}
        assertNull(node.parent);assertEquals(NodeKind.REFERENCE,node.kind);assertSavedSource(f,node)
        f.host.onMain {f.lease.session.undo()}
        assertEquals(1,f.host.onMain {f.lease.session.document!!.nodes.size})
        assertTrue(f.host.onMain {f.lease.session.document!!.root.sources.isEmpty()})
        f.assertReadingKept()
    }
}
