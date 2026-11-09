package com.xnotes.ui

import android.content.Context
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.xnotes.R
import com.xnotes.core.history.AddItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.format.DocumentCodec
import com.xnotes.mindmap.*
import com.xnotes.pen.PenTestRunner
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.storage.MigrationPublication
import com.xnotes.storage.NotebookStorage
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual SidePanel/Compose/a11y callbacks and real native/map files. Compile-only until authorized on-device. */
class NativeBacklinksEditingHostTest {
    private val runner get()=PenTestRunner.instance
    private fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null) emptyList() else
        listOf(node)+(0 until node.childCount).flatMap {nodes(node.getChild(it))}
    private fun all()=nodes(runner.uiAutomation.rootInActiveWindow)
    private fun visible(label:String)=all().any {it.isVisibleToUser && (it.text?.toString()==label || it.contentDescription?.toString()==label)}
    private fun waitUntil(label:String,check:()->Boolean) {
        repeat(1200) {runner.waitForIdleSync();if(check()) return;Thread.sleep(10)}
        fail("Actual native reverse-reference UI did not settle: $label")
    }
    private fun click(label:String) {
        var target:AccessibilityNodeInfo?=null
        waitUntil(label) {
            target=all().filter {it.isVisibleToUser && (it.text?.toString()==label || it.contentDescription?.toString()==label)}.firstNotNullOfOrNull {node->
                var at:AccessibilityNodeInfo?=node
                repeat(5) {val candidate=at;if(candidate?.isClickable==true && candidate.isEnabled) return@firstNotNullOfOrNull candidate;at=candidate?.parent};null
            };target!=null
        }
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun scrollTo(label:String) {
        repeat(30) {
            runner.waitForIdleSync();if(visible(label)) return
            val list=all().firstOrNull {it.isVisibleToUser && it.isScrollable} ?: error("No actual scrollable reference list")
            list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(30)
        }
        fail("Reference group never became visible: $label")
    }
    private class Fixture(val host:ComposeHostFixture,val root:File) {
        val context=object:ContextThemeWrapper(host.activity,R.style.Theme_Xnotes) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
            override fun getExternalFilesDir(type:String?)=File(root,"external").apply {mkdirs()}
        }
        val source=File(root,"source.xnote")
        val graph=File(AppStorageDocumentsProvider.rootDir(context),"backlinks-${UUID.randomUUID()}.xmap")
        val writes=AtomicInteger()
        val storage=NotebookStorage(context,targetOverride={uri->
            val file=File(requireNotNull(Uri.parse(uri).path));require(file.canonicalPath.startsWith(root.canonicalPath+File.separator))
            object:MigrationPublication.Target {
                override fun read()=file.inputStream()
                override fun write(candidate:File) {writes.incrementAndGet();candidate.copyTo(file,overwrite=true)}
            }
        })
        lateinit var editor:Editor
        val prepared get()=this::editor.isInitialized
        lateinit var pageSource:MapSource
        lateinit var originalMap:MindMapDocument
        val opened=mutableListOf<MapBacklinkOpenRequest>()
        fun label(id:Int)=context.getString(id)
        fun prepare() {
            val document=Document(mutableListOf(Page(500.0,500.0),Page(500.0,500.0))).apply {enableReferenceAnchors()}
            val codec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
            source.outputStream().use {codec.write(document.fixedWriteSnapshot(),it)}
            pageSource=MapSource(documentId=document.identity.documentId,fileName="Source note",scope=SourceScope.PAGE,
                pageId=document.pages[0].referenceIdentity.id,lastKnownPage=1,note="Exact current-page membership")
            originalMap=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Original root"),
                MapNode(id="concept",parent="root",text="Body and other sources stay",sources=listOf(pageSource,
                    MapSource(documentId=document.identity.documentId,fileName="Source note"),
                    MapSource(documentId=document.identity.documentId,fileName="Source note",scope=SourceScope.PAGE,
                        pageId=document.pages[1].referenceIdentity.id,lastKnownPage=2))),))
            graph.writeBytes(MapCodec.encode(originalMap))
            storage.registry.registerMapObserved(originalMap.id,graph.name,Uri.fromFile(graph).toString())
            host.onMain {editor=Editor(context)}
            runBlocking {withContext(Dispatchers.Main) {
                editor.openAsync(Uri.fromFile(source).toString(),"source.xnote");editor.view.layout(0,0,1000,1000);editor.goToPage(0)
                val page=editor.state.document.pages[0]
                val ink=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(100.0,100.0,.5),Sample(140.0,130.0,.7)))
                page.items+=ink;editor.history.push(AddItem(page,ink));editor.state.document.dirty=true
                editor.sidebarVisible=true
            }}
        }
        fun compose()=host.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                SidePanel(editor,references=BacklinkHostHooks(open={request->if(request.current()) {opened+=request;true} else false}))
            }
        }
    }
    private fun scenario(block:(Fixture)->Unit) {
        val root=File(runner.targetContext.cacheDir,"native-backlinks-ui-${UUID.randomUUID()}").apply {mkdirs()}
        val field=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true};val previous=field.get(null)
        try {ComposeHostFixture.run {host->
            val f=Fixture(host,root);field.set(null,f.storage)
            try {f.prepare();f.compose();click(f.label(R.string.graph_backlinks_title));block(f)}
            finally {
                host.disposeContent()
                if(f.prepared) {
                host.onMain {f.editor.goHomeAll(setOf(f.editor.discardForOpen()))}
                waitUntil("isolated native owner closes") {host.onMain {!f.editor.noteOpen}}
                runBlocking {withContext(Dispatchers.Main) {f.editor.awaitHostParking()}
                    val owner=host.onMain {Editor::class.java.getDeclaredField("sessionOwner").apply {isAccessible=true}.get(f.editor) as LegacySessionQueue.Owner}
                    owner.awaitAccepted()
                    val auto=host.onMain {Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(f.editor) as CoroutineScope}
                    host.onMain {auto.cancel()};auto.coroutineContext[Job]?.join()
                }
                }
                waitUntil("owned map leases release") {host.onMain {MapDocuments.observations(f.context).isEmpty()}}
                f.storage.registry.close();field.set(null,previous)
            }
        }} finally {field.set(null,previous);root.deleteRecursively()}
    }
    @Test fun realReferencesTabGroupsActualPermanentPagesAndOpensTheExactSourceMembership()=scenario {f->
        val bytes=f.source.readBytes()
        waitUntil("current permanent page row") {visible(f.pageSource.note)}
        assertTrue(visible(f.label(R.string.graph_backlinks_current_page)))
        click(f.label(R.string.open))
        waitUntil("exact typed callback") {f.host.onMain {f.opened.isNotEmpty()}}
        f.host.onMain {
            assertEquals(f.pageSource.id,f.opened.single().row.sourceId)
            assertEquals(f.pageSource.target,f.opened.single().row.target)
            assertTrue(f.editor.state.document.dirty);assertTrue(f.editor.history.canUndo)
        }
        scrollTo(f.label(R.string.graph_backlinks_other_pages));scrollTo(f.label(R.string.graph_backlinks_whole_file))
        assertFalse(all().any {it.text?.toString()?.contains(requireNotNull(f.pageSource.pageId).take(8))==true})
        assertArrayEquals(bytes,f.source.readBytes());assertEquals(0,f.writes.get())
    }
    @Test fun removeConfirmationCancelsOrRemovesOnlyTheOneGraphSourceAndOffersItsActualNodeReceipt()=scenario {f->
        val bytes=f.source.readBytes();val graphBytes=f.graph.readBytes()
        waitUntil("current permanent page row") {visible(f.pageSource.note)}
        click(f.label(R.string.remove));waitUntil("remove confirmation") {visible(f.label(R.string.graph_backlinks_remove_title))}
        click(f.label(R.string.cancel));assertArrayEquals(graphBytes,f.graph.readBytes())
        click(f.label(R.string.remove));waitUntil("second confirmation") {visible(f.label(R.string.graph_backlinks_remove_title))}
        click(f.label(R.string.remove));waitUntil("verified source removal") {visible(f.label(R.string.graph_backlinks_removed))}
        val saved=MapCodec.decode(f.graph.readBytes());val node=saved.node("concept")
        assertEquals("Body and other sources stay",node.text)
        assertEquals(f.originalMap.node("concept").sources.filterNot {it.id==f.pageSource.id},node.sources)
        assertEquals(f.originalMap.root,saved.root)
        click(f.label(R.string.graph_backlinks_view_node));waitUntil("actual node-only review callback") {f.host.onMain {f.opened.isNotEmpty()}}
        f.host.onMain {assertNotNull(f.opened.single().review);assertTrue(f.editor.state.document.dirty);assertTrue(f.editor.history.canUndo)}
        assertArrayEquals(bytes,f.source.readBytes());assertEquals(0,f.writes.get())
    }
}
