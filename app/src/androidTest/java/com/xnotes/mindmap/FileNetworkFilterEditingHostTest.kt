package com.xnotes.mindmap

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import android.view.ContextThemeWrapper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import com.xnotes.R
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.BundleHeader
import com.xnotes.format.DocumentCodec
import com.xnotes.pen.PenTestRunner
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.ComposeHostFixture
import com.xnotes.ui.theme.Palette
import com.xnotes.ui.theme.XnotesTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual UI and raw contract parsing. Android source compilation is not device execution. */
class FileNetworkFilterEditingHostTest {
    private val runner get()=PenTestRunner.instance
    private fun tree(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null) emptyList() else
        listOf(node)+(0 until node.childCount).flatMap {tree(node.getChild(it))}
    private fun nodes()=tree(runner.uiAutomation.rootInActiveWindow)
    private fun match(node:AccessibilityNodeInfo,label:String)=node.text?.toString()==label || node.contentDescription?.toString()==label
    private fun clickTarget(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
        var at:AccessibilityNodeInfo?=node
        repeat(6) {val item=at;if(item?.isVisibleToUser==true && item.isEnabled && item.isClickable) return item;at=item?.parent}
        return null
    }
    private fun reveal(label:String) {
        repeat(40) {
            runner.waitForIdleSync()
            if(nodes().any {it.isVisibleToUser && match(it,label)}) return
            val scroller=nodes().filter {it.isVisibleToUser && it.isScrollable}.maxByOrNull {Rect().also(it::getBoundsInScreen).height()}
            assertTrue("Expected filter information: $label",scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)==true)
            Thread.sleep(20)
        }
        fail("Unreachable filter information: $label")
    }
    private fun click(label:String) {
        repeat(40) {
            runner.waitForIdleSync()
            val target=nodes().filter {it.isVisibleToUser && match(it,label)}.firstNotNullOfOrNull(::clickTarget)
            if(target!=null) {assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync();return}
            val scroller=nodes().filter {it.isVisibleToUser && it.isScrollable}.maxByOrNull {Rect().also(it::getBoundsInScreen).height()}
            assertTrue("Expected visible filter control: $label",scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)==true)
            Thread.sleep(20)
        }
        fail("Unreachable filter control: $label")
    }
    private class Registry(private val context:Context):ActivityResultRegistry() {
        val launched=mutableListOf<Int>()
        val intents=mutableListOf<Intent>()
        override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
            launched+=requestCode;intents+=contract.createIntent(context,input)
        }
        fun raw(request:Int,uri:Uri?) {
            val result=uri?.let {Intent().setData(it).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            assertTrue(dispatchResult(request,if(uri==null) Activity.RESULT_CANCELED else Activity.RESULT_OK,result))
        }
    }
    private class Fixture(base:Context) {
        val directory=File(AppStorageDocumentsProvider.rootDir(base),"network-filter-ui-${UUID.randomUUID()}").apply {mkdirs()}
        private val privateDir=File(base.cacheDir,"network-filter-private-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextThemeWrapper(base,android.R.style.Theme_Material_Light_NoActionBar) {
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir()=File(privateDir,"private").apply {mkdirs()}
            override fun getCacheDir()=File(privateDir,"cache").apply {mkdirs()}
        }
        val storage=NotebookStorage(context)
        private val storageField=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        private val previous=storageField.get(null)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val workspace=FileNetworkWorkspace(context,scope)
        val binding=Any()
        lateinit var entry:FileNetworkWorkspace.Entry
        private fun seed(name:String):Pair<File,Document> {
            val file=File(directory,name)
            val doc=Document(mutableListOf(Page(400.0,400.0))).apply {enableReferenceAnchors()}
            file.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(doc.fixedWriteSnapshot(),it)}
            storage.registry.registerObserved(BundleHeader.read(file),"xnote",name,Uri.fromFile(file).toString())
            return file to doc
        }
        val s=seed("S.xnote")
        val t=seed("T.xnote")
        val original=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Owner"),
            MapNode(id="concept",parent="root",text="Two sources stay complete",sources=listOf(
                MapSource(documentId=s.second.identity.documentId,fileName=s.first.name),
                MapSource(documentId=t.second.identity.documentId,fileName=t.first.name)))))
        val graph=File(directory,"Evidence.xmap").apply {writeBytes(MapCodec.encode(original))}
        private val uri=DocumentsContract.buildDocumentUriUsingTree(AppStorageDocumentsProvider.treeUri(base),"root/${directory.name}/${graph.name}")
        val folder=DocumentsContract.buildTreeDocumentUri(uri.authority,"root/${directory.name}")
        val bytes=mapOf(s.first to s.first.readBytes(),t.first to t.first.readBytes(),graph to graph.readBytes())
        init {
            storage.registry.registerMapObserved(original.id,graph.name,uri.toString())
            storageField.set(null,storage)
        }
        fun start() {
            workspace.bindHost(binding)
            entry=requireNotNull(workspace.admit(binding,NetworkQuery(s.second.identity.documentId,s.first.name,
                listOf(AppStorageDocumentsProvider.treeUri(context).toString()))))
        }
        fun assertUntouched() {bytes.forEach {(file,expected)->assertArrayEquals(expected,file.readBytes())}}
        suspend fun close() {
            withContext(Dispatchers.Main) {workspace.dispose();scope.cancel()}
            scope.coroutineContext[Job]?.join()
            storage.registry.close();storageField.set(null,previous)
            directory.deleteRecursively();privateDir.deleteRecursively()
        }
    }

    @Test fun aDirectoryResultBelongsToItsOriginalDraftAndCanceledResultsNeverChangeTheFilter()=ComposeHostFixture.run {host->
        val fixture=Fixture(runner.targetContext)
        val registry=Registry(host.activity)
        val owner=object:ActivityResultRegistryOwner {override val activityResultRegistry=registry}
        try {
            host.onMain {fixture.start()}
            host.setContent {CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                XnotesTheme(Palette.DEFAULT) {FileNetworkWorkspaceHost(fixture.workspace,
                    FileNetworkHostActions(open={_,_,_->false},close={fixture.workspace.close(fixture.binding,it)},
                        busy={false},cancel={},feedback={null})) {}}
            }}
            host.await("actual network filter UI") {!fixture.entry.controller.loading && fixture.entry.presentation.cameraReady &&
                fixture.entry.controller.report?.concepts?.any {it.location.graphId==fixture.original.id}==true}
            val context=host.activity
            val permissions=context.contentResolver.persistedUriPermissions.map {it.uri to it.isReadPermission}.toSet()
            click(context.getString(R.string.network_filter))
            click(context.getString(R.string.network_filter_folder_choose))
            val a=host.onMain {registry.launched.single().also {assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE,registry.intents.single().action)}}
            click(context.getString(R.string.cancel))
            click(context.getString(R.string.network_filter))
            host.onMain {assertEquals(1,registry.launched.size);assertNull(fixture.entry.controller.filter.folder)}
            reveal(context.getString(R.string.network_filter_picker_busy))
            host.onMain {registry.raw(a,fixture.folder)} // goes through OpenDocumentTree.parseResult
            runner.waitForIdleSync()
            host.onMain {assertNull(fixture.entry.controller.filter.folder);assertEquals(1,registry.launched.size)}
            reveal(context.getString(R.string.network_filter_folder_unrestricted))
            assertFalse(nodes().any {it.isVisibleToUser && match(it,fixture.directory.name)})
            click(context.getString(R.string.network_filter_folder_choose))
            val b=host.onMain {registry.launched.last().also {assertNotEquals(a,it)}}
            host.onMain {registry.raw(b,null)}
            runner.waitForIdleSync()
            host.onMain {assertNull(fixture.entry.controller.filter.folder)}
            click(context.getString(R.string.network_filter_folder_choose))
            val c=host.onMain {registry.launched.last().also {assertNotEquals(b,it)}}
            host.onMain {
                assertTrue(fixture.entry.controller.applyFilter(NetworkFilter(fileIds=setOf(fixture.t.second.identity.documentId))))
                assertTrue(fixture.entry.controller.applyFilter(NetworkFilter()))
            }
            host.onMain {registry.raw(c,fixture.folder)}
            runner.waitForIdleSync()
            reveal(context.getString(R.string.network_filter_folder_unrestricted))
            assertFalse("A→B→A filter changes do not revive an old draft's directory callback",
                nodes().any {it.isVisibleToUser && match(it,fixture.directory.name)})
            click(context.getString(R.string.cancel))
            click(context.getString(R.string.network_filter))
            click(context.getString(R.string.network_filter_folder_choose))
            val d=host.onMain {registry.launched.last().also {assertNotEquals(c,it)}}
            host.onMain {registry.raw(d,fixture.folder)}
            reveal(fixture.directory.name)
            click(context.getString(R.string.apply))
            host.await("actual metadata filter completed") {!fixture.entry.controller.filtering &&
                fixture.entry.controller.filter.folder?.uri==fixture.folder.toString() && fixture.entry.controller.report?.filterActive==true}
            host.onMain {assertEquals(setOf(fixture.s.second.identity.documentId,fixture.t.second.identity.documentId),
                fixture.entry.controller.report!!.concepts.single {it.location.graphId==fixture.original.id}.members.map {it.fileId}.toSet())}
            assertEquals("External filter selection must not persist new roots",permissions,
                context.contentResolver.persistedUriPermissions.map {it.uri to it.isReadPermission}.toSet())
            fixture.assertUntouched()
        } finally {runBlocking {withTimeout(15000) {fixture.close()}}}
    }
}
