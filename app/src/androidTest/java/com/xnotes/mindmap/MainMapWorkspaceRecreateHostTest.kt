package com.xnotes.mindmap

import android.content.Intent
import android.net.Uri
import android.graphics.drawable.AnimationDrawable
import android.widget.ImageView
import android.view.View
import android.view.ViewGroup
import com.xnotes.MainActivity
import com.xnotes.R
import com.xnotes.core.geometry.Pt
import com.xnotes.pen.PenTestRunner
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual MainActivity recreation. Source compilation is not a claim this Android test has run. */
class MainMapWorkspaceRecreateHostTest {
    private val runner get()=PenTestRunner.instance
    private fun <T> main(action:()->T):T {
        var result:Result<T>?=null
        runner.runOnMainSync {result=runCatching(action)}
        return requireNotNull(result).getOrThrow()
    }
    private fun waitUntil(label:String,check:()->Boolean) {
        repeat(1000) {runner.waitForIdleSync();if(check()) return;Thread.sleep(10)}
        fail("Actual Main map recreation did not settle: $label")
    }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun graph(activity:MainActivity)=views(activity.window.decorView).filterIsInstance<MindMapView>().singleOrNull()
    private fun mainReady(activity:MainActivity):Boolean {
        val tree=views(activity.window.decorView)
        return activity.mapWorkspace!=null && activity.documentEditor!=null &&
            tree.any {it.javaClass.name=="androidx.compose.ui.platform.AndroidComposeView" && it.width>0 && it.height>0} &&
            tree.filterIsInstance<ImageView>().none {it.drawable is AnimationDrawable}
    }
    private fun worldCenter(view:MindMapView):Pt {
        val camera=view.viewport();val scale=view.contentScale()
        return Pt((view.width/2.0-camera.panX)/scale,(view.height/2.0-camera.panY)/scale)
    }
    private fun digest(activity:MainActivity,uri:String):String?=runCatching {
        val sha=MessageDigest.getInstance("SHA-256")
        requireNotNull(activity.contentResolver.openInputStream(Uri.parse(uri))).use {input->
            val block=ByteArray(8192)
            while(true) {val count=input.read(block);if(count<0) break;sha.update(block,0,count)}
        }
        sha.digest().joinToString("") {"%02x".format(it)}
    }.getOrNull()
    private fun join(job:Job?) {if(job!=null) runBlocking {withTimeout(15000) {job.join()}}}

    @Test fun recreatingTheActualMainRetainsItsMapActorHistoryPresentationAndSourcePanel() {
        val base=runner.targetContext
        val fixed=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Recreate topic"),
            MapNode(id="concept",parent="root",text="Before")))
        val file=File.createTempFile("main-map-recreate-",".xmap",base.cacheDir).apply {writeBytes(MapCodec.encode(fixed))}
        val uri=Uri.fromFile(file)
        var activity:MainActivity?=null
        var workspace:MapWorkspace?=null
        var owned:MapWorkspace.Entry?=null
        var monitor:android.app.Instrumentation.ActivityMonitor?=null
        try {
            val first=runner.startActivitySync(Intent(base,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=first
            waitUntil("Main actual workspace and completed native restore") {main {mainReady(first)}}
            val maps=main {requireNotNull(first.mapWorkspace)};workspace=maps
            val native=main {requireNotNull(first.documentEditor)}
            val nativeIdentity=main {native.openPanes.map {Triple(it.pane,it.visibleDocumentId,it.visibleDocumentUri)}}
            // Only observe original native sources. The test never reopens, rewrites or deletes them.
            val originalHashes=nativeIdentity.mapNotNull {(_,_,path)->path?.let {location->digest(first,location)?.let {location to it}}}.toMap()
            main {assertTrue(maps.open(uri,"Recreate map",preparePresentation={
                val proof=native.awaitHostParking()
                if(proof==null) null else ({proof.current()})
            }))}
            waitUntil("map visible with a ready native MapView") {main {
                maps.visible && maps.current?.session?.loading==false && graph(first)?.let {it.width>0 && it.height>0 && it.isEnabled}==true
            }}
            val entry=main {requireNotNull(maps.current)};owned=entry
            val session=entry.session
            val oldView=main {requireNotNull(graph(first))}
            val camera=main {
                assertTrue(session.operate {it.update("concept") {node->node.copy(text="First body edit")}})
                assertTrue(session.operate {it.update("concept") {node->node.copy(text="Second body edit")}})
                session.undo();assertTrue(session.canUndo);assertTrue(session.canRedo)
                session.selected="concept"
                oldView.zoomBy(1.35f);oldView.panText(87f,-49f)
                oldView.viewport() to worldCenter(oldView)
            }
            val document=main {session.document}
            main {entry.presentation.sourcesFor="concept"}
            waitUntil("source list before recreation") {
                runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(first.getString(R.string.graph_sources_count,0)).orEmpty().isNotEmpty()
            }
            val watched=runner.addMonitor(MainActivity::class.java.name,null,false);monitor=watched
            main {first.recreate()}
            val next=runner.waitForMonitorWithTimeout(watched,15000) as? MainActivity
            runner.removeMonitor(watched);monitor=null
            assertNotNull("MainActivity did not recreate",next)
            val recreated=requireNotNull(next);activity=recreated
            waitUntil("retained graph in recreated Main") {main {
                recreated.mapWorkspace!=null && recreated.documentEditor!=null && graph(recreated)?.let {it.width>0 && it.height>0 && it.isEnabled}==true
            }}
            main {
                val actual=requireNotNull(recreated.mapWorkspace)
                assertSame(maps,actual);assertSame(entry,actual.current)
                assertSame(session,actual.current!!.session);assertSame(entry.presentation,actual.current!!.presentation)
                assertSame(document,session.document)
                assertTrue(session.canUndo);assertTrue(session.canRedo);assertEquals("concept",session.selected)
                assertEquals("First body edit",session.document!!.node("concept").text)
                assertEquals("concept",actual.current!!.presentation.sourcesFor)
                assertEquals(nativeIdentity,requireNotNull(recreated.documentEditor).openPanes.map {Triple(it.pane,it.visibleDocumentId,it.visibleDocumentUri)})
            }
            waitUntil("new MapView restored once to its former camera") {main {
                graph(recreated)?.let {view->
                    view!==oldView && kotlin.math.abs(view.viewport().zoom-camera.first.zoom)<.00001f &&
                        kotlin.math.abs(worldCenter(view).x-camera.second.x)<.01 && kotlin.math.abs(worldCenter(view).y-camera.second.y)<.01
                }==true
            }}
            waitUntil("same source panel restored") {
                runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(recreated.getString(R.string.graph_sources_count,0)).orEmpty().isNotEmpty()
            }
            main {
                session.redo();assertEquals("Second body edit",session.document!!.node("concept").text)
                session.undo();assertEquals("First body edit",session.document!!.node("concept").text)
            }
            originalHashes.forEach {(location,before)->assertEquals("Original native source changed during map recreation",before,digest(recreated,location))}
        } finally {
            monitor?.let(runner::removeMonitor)
            val host=activity;val maps=workspace;val entry=owned
            if(maps!=null && entry!=null) join(main {maps.close(entry)})
            host?.let {main {it.finish()};runner.waitForIdleSync()}
            // This test owns only its randomly named cache document. No source/profile tree cleanup.
            file.delete()
        }
    }
}
