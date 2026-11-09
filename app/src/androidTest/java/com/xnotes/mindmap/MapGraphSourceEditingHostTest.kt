package com.xnotes.mindmap

import android.content.Intent
import android.graphics.drawable.AnimationDrawable
import android.net.Uri
import android.view.Choreographer
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import com.xnotes.MainActivity
import com.xnotes.R
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.AddItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.pen.PenTestRunner
import com.xnotes.platform.AppStorageDocumentsProvider
import com.xnotes.storage.NotebookStorage
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual Main source row, editing field, return/close buttons and Activity recreation.
 * Recovery cases inject only MapStore's write failure; reads, actor and journal stay real.
 * Source compilation is not Android execution, real SAF failure or MovinkPad acceptance. */
class MapGraphSourceEditingHostTest {
    private val runner get()=PenTestRunner.instance
    private fun <T> main(action:()->T):T {
        var result:Result<T>?=null
        runner.runOnMainSync {result=runCatching(action)}
        return requireNotNull(result).getOrThrow()
    }
    private fun await(label:String,check:()->Boolean) {
        repeat(1500) {runner.waitForIdleSync();if(check()) return;Thread.sleep(10)}
        fail("Actual Main map source did not settle: $label")
    }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null) emptyList() else
        listOf(node)+(0 until node.childCount).flatMap {nodes(node.getChild(it))}
    private fun shown(label:String)=nodes(runner.uiAutomation.rootInActiveWindow).any {it.isVisibleToUser && it.text?.toString()==label}
    private fun click(label:String) {
        var button:AccessibilityNodeInfo?=null
        await(label) {
            button=nodes(runner.uiAutomation.rootInActiveWindow).firstOrNull {
                it.isVisibleToUser && it.isClickable && it.isEnabled && it.text?.toString()==label
            };button!=null
        }
        assertTrue(requireNotNull(button).performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync()
    }
    private fun graph(activity:MainActivity)=views(activity.window.decorView).filterIsInstance<MindMapView>().singleOrNull()
    private fun ready(activity:MainActivity)=activity.mapWorkspace!=null && activity.documentEditor!=null &&
        views(activity.window.decorView).let {tree->tree.any {it.javaClass.name=="androidx.compose.ui.platform.AndroidComposeView" && it.width>0} &&
            tree.filterIsInstance<ImageView>().none {it.drawable is AnimationDrawable}}
    private fun join(job:Job?) {if(job!=null) runBlocking {withTimeout(15000) {job.join()}}}
    private fun center(view:MindMapView):Pt {
        val camera=view.viewport();val scale=view.contentScale()
        return Pt((view.width/2.0-camera.panX)/scale,(view.height/2.0-camera.panY)/scale)
    }
    private class WriteFault(val session:MapSession) {
        private val store=MapSession::class.java.getDeclaredField("store").apply {isAccessible=true}.get(session) as MapStore
        private val field=MapStore::class.java.getDeclaredField("target").apply {isAccessible=true}
        private val original=field.get(store) as MapTarget
        val writes=AtomicInteger()
        init {field.set(store,object:MapTarget {
            override fun read()=original.read()
            override fun write(bytes:ByteArray) {writes.incrementAndGet();throw IOException("Injected map write failure; real reads and recovery")}
        })}
        fun restore() {field.set(store,original)}
    }
    private inner class Scene(var activity:MainActivity,val folder:File,val a:File,val b:File,
        val source:MapSource,val aDoc:MindMapDocument,val bDoc:MindMapDocument) {
        val maps=main {requireNotNull(activity.mapWorkspace)}
        val native=main {requireNotNull(activity.documentEditor)}
        lateinit var origin:MapWorkspace.Entry
        fun originOrNull()=if(::origin.isInitialized) origin else null
        var nativeId:String?=null
        val faults=mutableListOf<WriteFault>()
        val extra=mutableListOf<MapDocumentLease>()
        var monitoring=false;var hiddenFrames=0;var nativeSurfaceFrames=0
        val frame=object:Choreographer.FrameCallback {
            override fun doFrame(at:Long) {
                if(!monitoring) return
                if(!maps.visible) hiddenFrames++
                if(views(activity.window.decorView).filterIsInstance<SurfaceView>().any {it.isAttachedToWindow && it.isShown}) nativeSurfaceFrames++
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        fun uri(file:File)=android.provider.DocumentsContract.buildDocumentUriUsingTree(
            AppStorageDocumentsProvider.treeUri(activity),"root/"+file.relativeTo(AppStorageDocumentsProvider.rootDir(activity)).invariantSeparatorsPath)
        fun label(id:Int)=activity.getString(id)
        fun startFrames()=main {monitoring=true;Choreographer.getInstance().postFrameCallback(frame)}
        fun stopFrames()=main {monitoring=false;Choreographer.getInstance().removeFrameCallback(frame)}
        fun openB() {
            main {assertSame(origin,maps.current);origin.session.selected="n"}
            if(!shown(label(R.string.open))) click(label(R.string.graph_sources))
            click(label(R.string.open))
            await("actual FILE B actor") {main {maps.visible && maps.current?.session?.document?.id==bDoc.id && graph(activity)?.isEnabled==true}}
        }
        fun edit(id:String,value:String) {
            val session=main {requireNotNull(maps.current).session}
            main {requireNotNull(graph(activity)).onEdit(id)}
            await("actual composing text field") {main {session.textDraft!=null && activity.currentFocus!=null}}
            val connection=main {activity.currentFocus!!.onCreateInputConnection(EditorInfo())}
            assertNotNull(connection)
            main {connection!!.setSelection(0,session.textDraft!!.value.text.length);assertTrue(connection.commitText(value,1));connection.finishComposingText()}
            click(label(R.string.map_done))
            await("actual Done commits one edit") {main {session.textDraft==null && session.textRestore==null && session.document!!.node(id).text==value}}
            main {session.cancelPendingAutosave()}
        }
        fun failSave(entry:MapWorkspace.Entry):WriteFault {
            val fault=main {WriteFault(entry.session).also(faults::add)}
            main {entry.session.cancelPendingAutosave();entry.session.save()}
            await("actual retained map recovery") {main {!entry.session.saving && entry.session.saveFailed && entry.session.recoverySafe}}
            return fault
        }
    }
    private fun scenario(test:(Scene)->Unit) {
        val base=runner.targetContext
        val existing=listOf("session","session-b").flatMap {slot->listOf("document.xnote","session.json","canvas.xcanvas","canvas-session.json").map {File(base.filesDir,"$slot/$it")}}
        val split=File(base.filesDir,"split-workspace.json")
        val splitOpen=split.exists() && runCatching {org.json.JSONObject(split.readText()).optBoolean("secondOpen",false)}.getOrDefault(true)
        assertTrue("Use an empty instrumentation profile; existing recovery is preserved",existing.none {it.exists()} && !splitOpen)
        val folder=File(AppStorageDocumentsProvider.rootDir(base),"map-graph-host-${UUID.randomUUID()}").apply {mkdirs()}
        val bDoc=MindMapDocument(nodes=listOf(MapNode(id="br",parent=null,text="B root"),MapNode(id="bn",parent="br",text="B saved")))
        val source=MapSource(documentId=bDoc.id,fileName="B.xmap",note="Actual B file source")
        val aDoc=MindMapDocument(nodes=listOf(MapNode(id="ar",parent=null,text="A root"),MapNode(id="n",parent="ar",text="A source",sources=listOf(source),primarySource=source.id)))
        val a=File(folder,"A.xmap").apply {writeBytes(MapCodec.encode(aDoc))};val b=File(folder,"B.xmap").apply {writeBytes(MapCodec.encode(bDoc))}
        var activity:MainActivity?=null;var scene:Scene?=null
        try {
            val host=runner.startActivitySync(Intent(base,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=host;await("Main completes restore") {main {ready(host)}}
            val f=Scene(host,folder,a,b,source,aDoc,bDoc);scene=f
            main {assertNull(f.maps.current);assertTrue(f.native.openPanes.isEmpty());f.native.newNote()}
            main {
                val model=f.native.state.document;f.nativeId=model.identity.documentId;val page=model.pages.first()
                val stroke=Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(100.0,120.0,.5),Sample(160.0,180.0,.7)))
                page.items+=stroke;f.native.history.push(AddItem(page,stroke));model.dirty=true
            }
            val registry=NotebookStorage.get(base).registry
            runBlocking(Dispatchers.IO) {
                registry.registerMapObserved(aDoc.id,a.name,f.uri(a).toString());registry.registerMapObserved(bDoc.id,b.name,f.uri(b).toString())
            }
            // Setup starts with a dirty scratch owner. Subsequent FILE hops use Main's real policy.
            main {assertTrue(f.maps.open(f.uri(a),"A",preparePresentation={f.native.awaitMapSourceParking()?.let {proof->({proof.current()})}},flushNativeOnPresent=false))}
            await("actual A map view") {main {f.maps.visible && f.maps.current?.session?.document?.id==aDoc.id && graph(host)?.height?.let {it>0}==true}}
            f.origin=main {requireNotNull(f.maps.current)}
            test(f);activity=f.activity
        } finally {
            scene?.let {f->
                activity=f.activity;f.stopFrames()
                main {f.faults.forEach {it.restore()}}
                // Only test-owned actors and recovery are saved/closed during cleanup.
                val sessions=(main {listOfNotNull(f.maps.current?.session,f.originOrNull()?.takeIf {it.lease.alive}?.session)}+f.extra.map {it.session}+f.faults.map {it.session}).distinct()
                sessions.forEach {session->
                    if(main {session.document!=null && session.dirty}) runBlocking {withTimeout(15000) {withContext(Dispatchers.Main) {session.saveAttempt()}.await()}}
                }
                join(main {f.maps.current?.let {f.maps.close(it)}})
                f.extra.forEach {join(main {it.close()})}
                main {
                    val editor=requireNotNull(f.activity.documentEditor)
                    val panes=editor.openPanes
                    if(panes.all {it.visibleDocumentId==f.nativeId})
                        editor.goHomeAll(panes.map {it.documentOwner.discardForOpen()}.toSet())
                }
                await("test-owned scratch closes") {main {requireNotNull(f.activity.documentEditor).openPanes.isEmpty()}}
                assertNotNull(runBlocking {withTimeout(15000) {withContext(Dispatchers.Main) {requireNotNull(f.activity.documentEditor).awaitHostParking()}}})
            }
            activity?.let {main {it.finish()};runner.waitForIdleSync()};folder.deleteRecursively()
        }
    }
    @Test fun actualMainFileSourceEditingReturnChildCloseAndRecreationRetainTheTwoMapActorsWithoutMountingNative()=scenario {f->
        val nativeModel=main {f.native.state.document};val nativeHistory=main {f.native.history};val nativeUndo=main {nativeHistory.nextUndo}
        val camera=main {val view=requireNotNull(graph(f.activity));view.zoomBy(1.3f);view.panText(73f,-41f);view.viewport() to center(view)}
        f.startFrames();f.openB();val child=main {requireNotNull(f.maps.current)}
        f.edit("bn","B edited through Main")
        main {child.session.undo();assertEquals("B saved",child.session.document!!.node("bn").text);child.session.redo();child.session.save()}
        await("actual saved B JSON") {main {!child.session.saving && !child.session.dirty}}
        assertEquals("B edited through Main",MapCodec.decode(f.b.readBytes()).node("bn").text)
        click(f.label(R.string.graph_return_parent_map))
        await("actual Return button restores A") {main {f.maps.current===f.origin && graph(f.activity)?.document?.id==f.aDoc.id}}
        main {
            assertEquals("n",f.origin.session.selected);assertEquals(f.source.id,f.origin.presentation.sourceFocus?.sourceId)
            assertSame(nativeModel,f.native.state.document);assertSame(nativeHistory,f.native.history);assertSame(nativeUndo,nativeHistory.nextUndo);assertTrue(nativeModel.dirty)
        }
        await("return camera") {main {graph(f.activity)?.let {kotlin.math.abs(it.viewport().zoom-camera.first.zoom)<1e-5f &&
            kotlin.math.abs(center(it).x-camera.second.x)<.02 && kotlin.math.abs(center(it).y-camera.second.y)<.02}==true}}
        f.openB();assertSame(child,main {f.maps.current})
        click(f.label(R.string.map_close))
        await("child Back uses source return") {main {f.maps.current===f.origin && f.maps.visible}}
        assertTrue(main {child.lease.alive && !child.session.closing})
        f.openB();f.edit("bn","Dirty B survives recreate")
        val aModel=main {f.origin.session.document};val bModel=main {child.session.document}
        val monitor=runner.addMonitor(MainActivity::class.java.name,null,false)
        val next=try {main {f.activity.recreate()};runner.waitForMonitorWithTimeout(monitor,15000) as? MainActivity}
            finally {runner.removeMonitor(monitor)}
        assertNotNull(next);f.activity=requireNotNull(next)
        await("recreated Main retains B route") {main {ready(f.activity) && f.activity.mapWorkspace===f.maps && f.maps.current===child && graph(f.activity)?.height?.let {it>0}==true}}
        main {assertSame(aModel,f.origin.session.document);assertSame(bModel,child.session.document);assertTrue(child.session.canUndo);assertTrue(f.maps.canReturnSource)}
        click(f.label(R.string.graph_return_parent_map))
        await("recreated Return uses retained parent") {main {f.maps.current===f.origin && f.maps.visible}}
        f.stopFrames();assertEquals(0,f.hiddenFrames);assertEquals(0,f.nativeSurfaceFrames)
    }
    @Test fun theActualChildRecoveryCloseButtonReturnsToAWithoutDroppingTheFailedBActorOrHistory()=scenario {f->
        f.openB();val child=main {requireNotNull(f.maps.current)};val bytes=f.b.readBytes()
        f.edit("bn","Child local recovery")
        val fault=f.failSave(child);val attempts=fault.writes.get();val model=main {child.session.document}
        click(f.label(R.string.map_close_recovery))
        await("child recovery close returns parent") {main {f.maps.current===f.origin && f.maps.visible}}
        main {assertTrue(child.lease.alive);assertSame(model,child.session.document);assertTrue(child.session.canUndo);assertTrue(child.session.recoverySafe)}
        assertEquals(attempts,fault.writes.get());assertArrayEquals(bytes,f.b.readBytes())
        f.openB();assertSame(child,main {f.maps.current})
    }
    @Test fun theActualRootRecoveryCloseButtonKeepsItsJournalAndDoesNotAttemptAnotherWrite()=scenario {f->
        f.edit("n","Root local recovery")
        val fault=f.failSave(f.origin);val attempts=fault.writes.get();val bytes=f.a.readBytes()
        click(f.label(R.string.map_close_recovery))
        await("root recovery close leaves map workspace") {main {f.maps.current==null && !f.maps.visible}}
        assertEquals(attempts,fault.writes.get());assertArrayEquals(bytes,f.a.readBytes())
        val lease=main {MapDocuments.acquire(f.activity,f.uri(f.a)).also(f.extra::add)}
        await("reacquired managed recovery actor") {main {!lease.session.loading}}
        main {assertEquals("Root local recovery",lease.session.document!!.node("n").text);assertTrue(lease.session.recoverySafe);assertTrue(lease.session.dirty)}
    }
}
