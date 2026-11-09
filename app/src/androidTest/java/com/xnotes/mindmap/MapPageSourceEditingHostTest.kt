package com.xnotes.mindmap

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.xnotes.R
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleAnchors
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.pen.PenTestRunner
import com.xnotes.storage.MigrationPublication
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.ComposeHostFixture
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

/** Real Compose/a11y actions and OpenDocument dispatch. Compile-only is not device acceptance. */
class MapPageSourceEditingHostTest {
    private val runner get()=PenTestRunner.instance
    private class Registry(private val context:Context):ActivityResultRegistry() {
        val launches=mutableListOf<Int>()
        override fun <I,O> onLaunch(code:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,contract.createIntent(context,input).action);launches+=code
        }
        fun deliver(code:Int,uri:Uri) {assertTrue(dispatchResult(code,Activity.RESULT_OK,Intent().setData(uri)))}
    }
    private fun waitUntil(check:()->Boolean) {
        repeat(500) {runner.waitForIdleSync();if(check()) return;Thread.sleep(10)}
        fail("PAGE source UI did not settle")
    }
    private fun visible(label:String)=runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label).orEmpty().isNotEmpty()
    private fun click(label:String) {
        var target:AccessibilityNodeInfo?=null
        waitUntil {
            target=runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstNotNullOfOrNull {node->
                var at:AccessibilityNodeInfo?=node
                repeat(5) {val current=at;if(current?.isClickable==true && current.isEnabled) return@firstNotNullOfOrNull current;at=current?.parent}
                null
            };target!=null
        }
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync()
    }
    private class Fixture(val host:ComposeHostFixture,val root:File,anchored:Boolean) {
        val context=object:ContextThemeWrapper(host.activity,R.style.Theme_Xnotes) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val sourceId=UUID.randomUUID().toString()
        val source=File(root,"source.xnote")
        val map=File(root,"graph.xmap")
        val sourceWrites=AtomicInteger()
        val storage=NotebookStorage(context,targetOverride={uri->
            val target=File(requireNotNull(Uri.parse(uri).path))
            require(target.canonicalPath.startsWith(root.canonicalPath+File.separator))
            object:MigrationPublication.Target {
                override fun read()=target.inputStream()
                override fun write(candidate:File) {sourceWrites.incrementAndGet();candidate.copyTo(target,overwrite=true)}
            }
        })
        val registry=Registry(context)
        val session=MapSession()
        init {
            ZipOutputStream(source.outputStream()).use {z->
                BundleIdentity(sourceId).write(z);if(anchored) AnchorMetadata(sourceId).write(z)
                z.putNextEntry(ZipEntry("manifest.json"))
                val ids=(0..1).map {if(anchored) "\"reference_id\":\"${UUID.randomUUID()}\"," else ""}
                z.write("""{"format":"xnote","version":1,"has_pdf":false,"pages":[{${ids[0]}"items":[]},{${ids[1]}"items":[]}],"future":1e+03}""".toByteArray());z.closeEntry()
                z.putNextEntry(ZipEntry("future.bin"));z.write(byteArrayOf(0,-1,-128,42));z.closeEntry()
            }
            map.writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Keep title")))))
        }
        fun compose() {
            host.onMain {session.open(context,Uri.fromFile(map))}
            host.setContent {
                val owner=object:ActivityResultRegistryOwner {override val activityResultRegistry=registry}
                CompositionLocalProvider(LocalContext provides context,LocalActivityResultRegistryOwner provides owner) {
                    MindMapScreen(session,"PAGE source host",{})
                }
            }
        }
        fun label(id:Int)=context.getString(id)
        fun chooseSource(test:MapPageSourceEditingHostTest) {
            val before=host.onMain {registry.launches.size}
            test.click(label(R.string.graph_sources));test.click(label(R.string.graph_add_source))
            test.waitUntil {host.onMain {registry.launches.size>before}}
            host.onMain {registry.deliver(registry.launches.last(),Uri.fromFile(source))}
            test.waitUntil {test.visible(label(R.string.graph_source_choose)) && host.onMain {session.sourcePicker.state?.busy==false}}
        }
    }
    private fun scenario(anchored:Boolean=false,block:(Fixture)->Unit) {
        val root=File(runner.targetContext.cacheDir,"page-source-ui-${UUID.randomUUID()}").apply {mkdirs()}
        val field=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true};val previous=field.get(null)
        try {ComposeHostFixture.run {host->
            val f=Fixture(host,root,anchored);field.set(null,f.storage)
            try {f.compose();waitUntil {host.onMain {!f.session.loading}};block(f)} finally {
                host.onMain {f.session.save()};waitUntil {host.onMain {!f.session.saving}}
                host.disposeContent()
                val job=host.onMain {f.session.viewModelScope.coroutineContext[Job]!!}
                host.onMain {ViewModelStore().apply {put("map",f.session);clear()}}
                waitUntil {host.onMain {job.children.none()}}
                f.storage.registry.close();field.set(null,previous)
            }
        }} finally {field.set(null,previous);root.deleteRecursively()}
    }
    @Test fun sourceDialogCancelsEnableConfirmationThenEnablesAndChoosesTheActualSecondPage()=scenario {f->
        val a=File(f.root,"a.zip").also {f.source.copyTo(it)};val original=f.host.onMain {f.session.document!!}
        f.chooseSource(this)
        click(f.label(R.string.graph_source_enable_pages));waitUntil {visible(f.label(R.string.graph_source_enable_notice))}
        click(f.label(R.string.cancel));assertEquals(0,f.sourceWrites.get());assertArrayEquals(a.readBytes(),f.source.readBytes())
        click(f.label(R.string.graph_source_enable_pages));click(f.label(R.string.graph_source_enable_confirm))
        val second=f.context.getString(R.string.graph_source_page_number,2)
        waitUntil {visible(second) && f.host.onMain {f.session.sourcePicker.state?.busy==false}}
        val ids=BundleReferenceInfo.inspectStrict(f.source).orderedPageIds
        assertEquals(1,f.sourceWrites.get());BundleAnchors.verifyActivation(a,f.source)
        click(second);waitUntil {f.host.onMain {f.session.document!!.root.sources.isNotEmpty()}}
        val changed=f.host.onMain {f.session.document!!};val attached=changed.root.sources.single()
        assertEquals(SourceScope.PAGE,attached.scope);assertEquals(ids[1],attached.pageId);assertEquals(f.sourceId,attached.documentId)
        assertEquals("Keep title",changed.root.text);f.host.onMain {f.session.undo()};assertEquals(original,f.host.onMain {f.session.document})
        f.host.onMain {f.session.redo();f.session.save()};waitUntil {f.host.onMain {!f.session.saving}}
        assertEquals(changed,MapCodec.decode(f.map.readBytes()));assertEquals(1,f.sourceWrites.get())
    }
    private fun composedClick(label:String):()->Boolean {
        fun views(root:View):List<View> = listOf(root)+if(root is ViewGroup) (0 until root.childCount).flatMap {views(root.getChildAt(it))} else emptyList()
        fun nodes(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::nodes)
        val owners=WindowInspector.getGlobalWindowViews().flatMap(::views).mapNotNull {view->
            view.javaClass.methods.firstOrNull {it.name=="getSemanticsOwner" && it.parameterCount==0}?.invoke(view) as? SemanticsOwner
        }
        return owners.flatMap {nodes(it.rootSemanticsNode)}.firstNotNullOfOrNull {node->
            if(node.config.getOrNull(SemanticsProperties.Text)?.any {it.text==label}==true && !node.config.contains(SemanticsProperties.Disabled))
                node.config.getOrNull(SemanticsActions.OnClick)?.action else null
        } ?: error("No actual composed page action: $label")
    }
    @Test fun closingThePanelRetiresItsPickerAndAnOldPageButtonCannotApplyToTheNextChoice()=scenario(anchored=true) {f->
        val before=f.source.readBytes();val original=f.host.onMain {f.session.document!!}
        f.chooseSource(this);val second=f.context.getString(R.string.graph_source_page_number,2)
        val oldPage=f.host.onMain {composedClick(second)}
        click(f.label(R.string.cancel));click(f.label(R.string.map_done))
        click(f.label(R.string.graph_sources));click(f.label(R.string.graph_add_source))
        val late=f.host.onMain {f.registry.launches.last()};click(f.label(R.string.map_done))
        click(f.label(R.string.graph_sources))
        val launches=f.host.onMain {f.registry.launches.size}
        waitUntil {runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(f.label(R.string.graph_add_source))?.any {!it.isEnabled}==true}
        assertEquals(launches,f.host.onMain {f.registry.launches.size}) // Old system launch still occupies this launcher.
        f.host.onMain {f.registry.deliver(late,Uri.fromFile(f.source))}
        runner.waitForIdleSync();assertNull(f.host.onMain {f.session.sourcePicker.state});assertEquals(original,f.host.onMain {f.session.document})
        click(f.label(R.string.graph_add_source));waitUntil {f.host.onMain {f.registry.launches.size>launches}}
        f.host.onMain {f.registry.deliver(f.registry.launches.last(),Uri.fromFile(f.source))}
        waitUntil {visible(second) && f.host.onMain {f.session.sourcePicker.state?.busy==false}}
        f.host.onMain {
            val current=f.session.sourcePicker.state
            assertTrue(oldPage());assertSame(current,f.session.sourcePicker.state);assertEquals(original,f.session.document)
        }
        click(f.label(R.string.graph_source_whole_file));waitUntil {f.host.onMain {f.session.document!!.root.sources.isNotEmpty()}}
        val attached=f.host.onMain {f.session.document!!.root.sources.single()}
        assertEquals(SourceScope.FILE,attached.scope);assertNull(attached.pageId)
        assertArrayEquals(before,f.source.readBytes());assertEquals(0,f.sourceWrites.get())
    }
}
