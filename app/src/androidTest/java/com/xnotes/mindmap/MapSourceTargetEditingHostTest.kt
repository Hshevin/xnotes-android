package com.xnotes.mindmap

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat
import com.xnotes.R
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.format.AnchorMetadata
import com.xnotes.format.BundleIdentity
import com.xnotes.pen.PenTestRunner
import com.xnotes.storage.MigrationPublication
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.ComposeHostFixture
import com.xnotes.ui.theme.TextSize
import com.xnotes.ui.theme.XnotesTheme
import com.xnotes.ui.theme.monochromePalette
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real Compose/source row/visual input. This source must be run separately on Android. */
class MapSourceTargetEditingHostTest {
    @Test fun adjustingTheActualSecondPageConfirmsOnceAndPersistsItsPermanentIdentity()=SourceTargetAndroidFixture.run {f->
        f.openSources();f.ui.click(f.label(R.string.source_target_adjust));f.awaitVisual()
        f.ui.click(f.label(R.string.source_target_pages))
        f.ui.click(f.context.getString(R.string.graph_source_page_number,2));f.awaitVisual()
        f.host.onMain {
            val state=f.controller.state!!
            assertEquals(f.pages[1],state.frame!!.pageId)
            assertEquals(SourceRangeScope.WHOLE_PAGE,state.selectionScope);assertNull(state.region)
            assertEquals(f.pages[0],f.currentSource().pageId);assertFalse(f.session.canUndo)
        }
        f.ui.click(f.label(R.string.source_target_confirm_range));f.awaitSaved()
        val changed=f.host.onMain {f.currentSource()}
        assertEquals(f.pages[1],changed.pageId);assertNull(changed.region)
        assertEquals(f.source.id,changed.id);assertEquals("Keep this remark",changed.note)
        f.ui.click(f.label(R.string.source_target_return))
        f.host.onMain {f.session.undo();assertEquals(f.pages[0],f.currentSource().pageId);assertFalse(f.session.canUndo)
            f.session.redo();assertEquals(f.pages[1],f.currentSource().pageId);f.session.save()}
        f.host.await("actual map write") {!f.session.saving}
        val cold=MapCodec.decode(f.maps[0].readBytes()).root.sources.single()
        assertEquals(changed.target,cold.target);assertEquals(changed.id,cold.id)
        f.assertSourceUnchanged()
    }

    @Test fun visualRegionMoveIsOnlyADraftUntilUpAndCancelAddsNoHistoryBeforeAConfirmedFixedRange()=SourceTargetAndroidFixture.run {f->
        f.openSources()
        fun chooseRegion() {
            f.ui.click(f.label(R.string.source_target_adjust));f.awaitVisual()
            f.ui.click(f.label(R.string.source_target_region));f.awaitVisual()
            f.host.await("region input surface") {f.visual()!=null && f.controller.state?.selectionScope==SourceRangeScope.REGION}
        }
        fun draw():Rect {
            val view=f.host.onMain {requireNotNull(f.visual())}
            var stable=0;var previous:Pair<Int,Int>?=null
            f.host.await("source selection dialog geometry") {
                val size=view.width to view.height
                if(view.isAttachedToWindow && view.isShown && view.hasWindowFocus() && size.first>0 && size.second>0) {
                    stable=if(previous==size) stable+1 else 1;previous=size;stable>=4
                } else {stable=0;previous=null;false}
            }
            return f.host.onMain {
                val a=Pt(view.width*.25,view.height*.25);val b=Pt(view.width*.65,view.height*.65)
                val start=view.viewport.viewportToContent(a);val end=view.viewport.viewportToContent(b)
                SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_DOWN,listOf(a),intArrayOf(7),intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
                SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_MOVE,listOf(b),intArrayOf(7),intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
                assertNull(f.controller.state!!.region);assertNull(f.currentSource().region)
                SourceTargetAndroidInput.touch(view,MotionEvent.ACTION_UP,listOf(b),intArrayOf(7),intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
                Rect(start.x,start.y,end.x-start.x,end.y-start.y).also {expected->
                    assertRegion(expected,f.controller.state!!.region)
                }
            }
        }
        chooseRegion();draw();f.ui.click(f.label(R.string.cancel))
        f.host.await("canceled source draft") {f.controller.state==null}
        f.host.onMain {assertNull(f.currentSource().region);assertFalse(f.session.canUndo)}
        chooseRegion();val accepted=draw()
        f.ui.click(f.label(R.string.source_target_confirm_range));f.awaitSaved()
        f.host.onMain {assertRegion(accepted,f.currentSource().region);assertEquals(f.pages[0],f.currentSource().pageId)
            f.session.undo();assertNull(f.currentSource().region);assertFalse(f.session.canUndo);f.session.redo()}
        assertRegion(accepted,MapCodec.decode(f.maps[0].readBytes()).root.sources.single().region)
        f.assertSourceUnchanged()
    }
    private fun assertRegion(expected:Rect,actual:Rect?) {
        assertNotNull(actual);assertEquals(expected.x,actual!!.x,1e-3);assertEquals(expected.y,actual.y,1e-3)
        assertEquals(expected.w,actual.w,1e-3);assertEquals(expected.h,actual.h,1e-3)
    }
}

/** A real window and workspace, with only the external picker UI replaced by its registry. */
internal class SourceTargetAndroidFixture private constructor(val host:ComposeHostFixture,val root:File) {
    val context=object:ContextThemeWrapper(host.activity,R.style.Theme_Xnotes) {
        override fun getApplicationContext():Context=this
        override fun getFilesDir()=File(root,"files").apply {mkdirs()}
        override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
        override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
    }
    val writes=AtomicInteger()
    val storage=NotebookStorage(context,targetOverride={uri->
        val file=File(requireNotNull(Uri.parse(uri).path))
        require(file.canonicalPath.startsWith(root.canonicalPath+File.separator))
        object:MigrationPublication.Target {
            override fun read()=file.inputStream()
            override fun write(candidate:File) {writes.incrementAndGet();candidate.copyTo(file,overwrite=true)}
        }
    })
    val documentId=UUID.randomUUID().toString()
    val pages=List(2) {UUID.randomUUID().toString()}
    val file=File(root,"saved-source.xnote")
    val source=MapSource(documentId=documentId,fileName=file.name,scope=SourceScope.PAGE,pageId=pages[0],note="Keep this remark",lastKnownPage=1)
    val original:ByteArray
    val maps=List(2) {index->File(root,"map-$index.xmap").apply {writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(
        MapNode(id="root",parent=null,text="Topic $index",sources=listOf(source),primarySource=source.id)))))}}
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val workspace=host.onMain {MapWorkspace(context,scope)}
    val registry=Registry(context)
    val ui=SourceTargetAndroidUi(host)
    val session get()=requireNotNull(workspace.current).session
    val controller get()=session.sourceTargetEdits
    init {
        ZipOutputStream(file.outputStream()).use {zip->
            BundleIdentity(documentId).write(zip);AnchorMetadata(documentId).write(zip)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"format":"xnote","version":1,"has_pdf":false,"pages":[{"reference_id":"${pages[0]}","items":[]},{"reference_id":"${pages[1]}","items":[]}]}""".toByteArray())
            zip.closeEntry()
        }
        original=file.readBytes()
    }
    class Registry(private val context:Context):ActivityResultRegistry() {
        val launches=mutableListOf<Int>()
        override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,contract.createIntent(context,input).action)
            launches+=requestCode
        }
        fun deliver(requestCode:Int,uri:Uri) {
            assertTrue(dispatchResult(requestCode,Activity.RESULT_OK,Intent().setData(uri)))
        }
    }
    private fun compose() {
        runBlocking(Dispatchers.IO) {MapSourceTargetAccess(context,storage).inspectFile(Uri.fromFile(file)).closeAndAwait()}
        host.setContent {
            val owner=object:ActivityResultRegistryOwner {override val activityResultRegistry=registry}
            CompositionLocalProvider(LocalContext provides context,LocalActivityResultRegistryOwner provides owner) {
                XnotesTheme(monochromePalette(false)) {
                    MapWorkspaceHost(workspace,onClosed={workspace.close(it)}) {Text("Parked native host",fontSize=TextSize.body)}
                }
            }
        }
        open(0)
    }
    fun open(index:Int) {
        host.onMain {assertTrue(workspace.open(Uri.fromFile(maps[index]),"Source target map $index"))}
        host.await("actual keyed workspace entry $index") {workspace.visible && workspace.current?.uri==Uri.fromFile(maps[index]) &&
            !session.loading && allViews(host.activity.window.decorView).filterIsInstance<MindMapView>().any {it.width>0 && it.height>0}}
    }
    fun label(id:Int)=context.getString(id)
    fun currentSource()=session.document!!.root.sources.single()
    fun openSources() {ui.click(label(R.string.graph_sources));ui.awaitVisible(label(R.string.source_target_adjust))}
    fun awaitVisual() {host.await("actual saved source visual") {controller.state?.step==SourceTargetEditStep.VISUAL && controller.image!=null}}
    fun awaitSaved() {host.await("confirmed source target saved") {controller.state?.step==SourceTargetEditStep.SAVED && !session.saving}}
    fun visual()=WindowInspector.getGlobalWindowViews().flatMap(::allViews).filterIsInstance<SourceRegionVisualView>().singleOrNull {it.isShown}
    fun assertSourceUnchanged() {assertArrayEquals(original,file.readBytes());assertEquals(0,writes.get())}
    private fun close() {
        host.disposeContent();val job=host.onMain {workspace.dispose()}
        runBlocking {withTimeout(10000) {job.join()}};scope.cancel();storage.registry.close()
    }
    companion object {
        fun allViews(view:View):List<View> = listOf(view)+if(view is ViewGroup)
            (0 until view.childCount).flatMap {allViews(view.getChildAt(it))} else emptyList()
        fun run(test:(SourceTargetAndroidFixture)->Unit) {
            val runner=PenTestRunner.instance
            val root=File(runner.targetContext.cacheDir,"source-target-ui-${UUID.randomUUID()}").apply {mkdirs()}
            val field=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
            val prior=field.get(null)
            try {ComposeHostFixture.run {host->
                val fixture=SourceTargetAndroidFixture(host,root);field.set(null,fixture.storage)
                try {fixture.compose();test(fixture)} finally {fixture.close();field.set(null,prior)}
            }} finally {field.set(null,prior);root.deleteRecursively()}
        }
    }
}

internal class SourceTargetAndroidUi(private val host:ComposeHostFixture) {
    private val runner get()=PenTestRunner.instance
    private fun nodes()=runner.uiAutomation.rootInActiveWindow
    private fun clickable(label:String)=nodes()?.findAccessibilityNodeInfosByText(label)?.firstNotNullOfOrNull {node->
        var at:AccessibilityNodeInfo?=node
        repeat(6) {val current=at;if(current?.isClickable==true) return@firstNotNullOfOrNull current;at=current?.parent}
        null
    }
    fun enabled(label:String)=clickable(label)?.isEnabled
    fun awaitVisible(label:String) {
        repeat(500) {runner.waitForIdleSync()
            if(nodes()?.findAccessibilityNodeInfosByText(label).orEmpty().isNotEmpty()) return
            Thread.sleep(10)
        }
        fail("Source target UI did not appear: $label")
    }
    fun awaitEnabled(label:String,value:Boolean) {
        repeat(500) {runner.waitForIdleSync();if(enabled(label)==value) return;Thread.sleep(10)}
        fail("Source target action did not become enabled=$value: $label")
    }
    fun click(label:String) {
        var target:AccessibilityNodeInfo?=null
        repeat(80) {
            runner.waitForIdleSync()
            target=clickable(label)?.takeIf {it.isEnabled}
            if(target!=null) {
                target!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                runner.waitForIdleSync()
                assertTrue("Actual source action: $label",target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync();return
            }
            Thread.sleep(10)
        }
        fail("No enabled actual source action: $label")
    }
}

internal object SourceTargetAndroidInput {
    fun touch(view:SourceRegionVisualView,action:Int,points:List<Pt>,ids:IntArray,tools:IntArray,flags:Int=0) {
        val properties=Array(points.size) {i->MotionEvent.PointerProperties().apply {id=ids[i];toolType=tools[i]}}
        val coords=Array(points.size) {i->MotionEvent.PointerCoords().apply {x=points[i].x.toFloat();y=points[i].y.toFloat();pressure=1f}}
        val event=MotionEvent.obtain(1,android.os.SystemClock.uptimeMillis(),action,points.size,properties,coords,0,0,1f,1f,0,0,
            if(tools.any {it==MotionEvent.TOOL_TYPE_STYLUS}) android.view.InputDevice.SOURCE_STYLUS else android.view.InputDevice.SOURCE_TOUCHSCREEN,flags)
        try {assertTrue(view.onTouchEvent(event))} finally {event.recycle()}
    }
}
