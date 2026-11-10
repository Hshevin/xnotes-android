package com.xnotes.mindmap

import android.content.Intent
import android.graphics.drawable.AnimationDrawable
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import com.xnotes.MainActivity
import com.xnotes.R
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.enableReferenceAnchors
import com.xnotes.core.model.fixedWriteSnapshot
import com.xnotes.format.DocumentCodec
import com.xnotes.pen.PenTestRunner
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.ui.Editor
import com.xnotes.ui.Pane
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Main consumers for shortcuts, incoming intents and the saved split browser.
 * Source compilation is not evidence of Android execution or Surface/GPU acceptance. */
class MainMixedEntryPointsHostTest {
    private val runner get()=PenTestRunner.instance
    private fun <T> main(action:()->T):T {
        var result:Result<T>?=null;runner.runOnMainSync {result=runCatching(action)}
        return requireNotNull(result).getOrThrow()
    }
    private fun await(label:String,check:()->Boolean) {
        repeat(1500) {runner.waitForIdleSync();if(check()) return;Thread.sleep(10)}
        fail("Main mixed entry point did not settle: $label")
    }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap {views(view.getChildAt(it))} else emptyList()
    private fun graph(activity:MainActivity)=views(activity.window.decorView).filterIsInstance<MindMapView>().singleOrNull()
    private fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null) emptyList() else
        listOf(node)+(0 until node.childCount).flatMap {nodes(node.getChild(it))}
    private fun matches(node:AccessibilityNodeInfo,label:String)=node.text?.toString()==label || node.contentDescription?.toString()==label
    private fun shown(label:String)=nodes(runner.uiAutomation.rootInActiveWindow).any {it.isVisibleToUser && matches(it,label)}
    private fun click(label:String) {
        var target:AccessibilityNodeInfo?=null
        await(label) {target=nodes(runner.uiAutomation.rootInActiveWindow).firstOrNull {
            it.isVisibleToUser && it.isEnabled && it.isClickable && matches(it,label)
        };target!=null}
        assertTrue(requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK));runner.waitForIdleSync()
    }
    private fun join(job:Job?) {if(job!=null) runBlocking {withTimeout(15000) {job.join()}}}
    @Suppress("UNCHECKED_CAST")
    private fun pendingState(activity:MainActivity)=MainActivity::class.java.getDeclaredField("pendingImport\$delegate")
        .apply {isAccessible=true}.get(activity) as androidx.compose.runtime.MutableState<Uri?>
    private fun pending(activity:MainActivity):Uri?=pendingState(activity).value
    private fun clearPending(activity:MainActivity) {pendingState(activity).value=null}
    private class Scene(val activity:MainActivity,val native:Editor,val maps:MapWorkspace,val entry:MapWorkspace.Entry,
        val noteFile:File,val importFile:File,val savedFile:File?)
    private fun scenario(savedFixture:Boolean=false,test:(Scene)->Unit) {
        val base=runner.targetContext
        val recovery=listOf("session","session-b").flatMap {slot->
            listOf("document.xnote","session.json","canvas.xcanvas","canvas-session.json").map {File(base.filesDir,"$slot/$it")}
        }
        assertTrue("Use an empty instrumentation profile; recovery is never replaced",recovery.none {it.exists()})
        val token=UUID.randomUUID().toString()
        val folder=File(base.cacheDir,"mixed-entrypoints-$token").apply {mkdirs()}
        val note=File(folder,"source.xnote")
        val model=Document(mutableListOf(Page(500.0,500.0))).apply {enableReferenceAnchors()}
        note.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(model.fixedWriteSnapshot(),it)}
        val map=File(folder,"map.xmap").apply {writeBytes(MapCodec.encode(MindMapDocument(
            nodes=listOf(MapNode(id="root",parent=null,text="Mixed entry points")))))}
        val incoming=File(folder,"incoming.pdf").apply {writeText("%PDF-1.4\n% delayed import fixture\n")}
        val recent=File(base.filesDir,"saved-split.json")
        val stored=if(savedFixture) File(File(base.filesDir,"splits").apply {mkdirs()},"mixed-entrypoints-$token.xsplit") else null
        if(savedFixture) {
            assertFalse("The saved split fixture never replaces an existing file",recent.exists())
            val json=JSONObject().put("format","xnotes-split").put("version",2)
                .put("primaryPath",Uri.fromFile(note).toString()).put("secondPath",Uri.fromFile(note).toString())
                .put("primaryId",model.identity.documentId).put("secondId",model.identity.documentId)
                .put("primaryOpen",true).put("secondOpen",true).put("shared",true).put("ratio",.5)
            recent.writeText(json.toString());stored!!.writeText(json.toString())
        }
        var scene:Scene?=null;var activity:MainActivity?=null
        try {
            val host=runner.startActivitySync(Intent(base,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=host
            await("actual Main ready") {main {host.documentEditor!=null && host.mapWorkspace!=null &&
                views(host.window.decorView).let {tree->tree.any {it.javaClass.name=="androidx.compose.ui.platform.AndroidComposeView" && it.width>0} &&
                    tree.filterIsInstance<ImageView>().none {it.drawable is AnimationDrawable}}}}
            val native=main {requireNotNull(host.documentEditor)};val maps=main {requireNotNull(host.mapWorkspace)}
            main {assertTrue(native.openPanes.isEmpty());assertNull(maps.current)}
            runBlocking {withTimeout(15000) {withContext(Dispatchers.Main) {
                assertTrue(native.openSingleFromHome(Uri.fromFile(note).toString(),note.name))
                assertTrue(maps.open(Uri.fromFile(map),"Map",flushNativeOnPresent=false,
                    preparePresentation={native.awaitMapSourceParking()?.let {proof->({proof.current()})}}))
            }}}
            await("actual full Map") {main {maps.visible && !maps.opening && graph(host)?.width?.let {it>0}==true}}
            val entry=main {requireNotNull(maps.current)}
            main {
                assertTrue(maps.showSplit(entry,MapSplitLayout(Pane.SECONDARY,Pane.PRIMARY,.5f,true,false)))
                maps.splitPresentation.rememberNativeHint(native.nativeRestoreHint())
            }
            await("actual constrained native and Map surfaces") {main {
                native.view.isShown && native.view.width>0 && graph(host)?.let {it.isShown && it.width>0}==true
            }}
            val ready=Scene(host,native,maps,entry,note,incoming,stored);scene=ready;test(ready)
        } finally {
            scene?.let {s->
                main {clearPending(s.activity);s.native.cancelImport()}
                join(main {s.maps.current?.let {s.maps.close(it)}})
                main {s.maps.splitPresentation.clear();s.native.goHomeAll(s.native.openPanes.map {it.documentOwner.discardForOpen()}.toSet())}
                await("owned native closes") {main {s.native.openPanes.isEmpty()}}
                assertNotNull(runBlocking {withTimeout(15000) {withContext(Dispatchers.Main) {s.native.awaitHostParking()}}})
            }
            activity?.let {main {it.finish()};runner.waitForIdleSync()}
            if(savedFixture) {recent.delete();stored?.delete()}
            folder.deleteRecursively()
        }
    }

    @Test fun actualOpenAndPreferencesShortcutsUseNativeSlotHomeAndRetainBothDocuments()=scenario {s->
        val model=main {s.native.state.document};val history=main {s.native.history}
        main {
            assertTrue(s.native.handleKeyDown(KeyEvent(1000,1001,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_O,0,KeyEvent.META_CTRL_ON)))
        }
        await("Ctrl+O native Home") {main {s.maps.splitPresentation.layout?.nativeHome==true &&
            s.maps.splitPresentation.layout?.focusedPane==Pane.PRIMARY && graph(s.activity)?.isShown==true}}
        main {assertTrue(s.native.noteOpen);assertSame(model,s.native.state.document);assertSame(history,s.native.history);assertSame(s.entry,s.maps.current)}
        main {
            assertTrue(s.native.handleKeyDown(KeyEvent(1000,1002,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_COMMA,0,KeyEvent.META_CTRL_ON)))
        }
        await("real slot Preferences") {shown(s.activity.getString(R.string.pref_general))}
        main {
            assertTrue(s.maps.visible);assertSame(s.entry,s.maps.current);assertTrue(s.native.noteOpen)
            assertSame(model,s.native.state.document);assertSame(history,s.native.history);assertNotNull(graph(s.activity))
        }
    }

    @Test fun externalPdfIntentStaysPendingUntilMixedLayoutIsActuallyRemoved()=scenario {s->
        val source=Uri.fromFile(s.importFile);val model=main {s.native.state.document}
        main {runner.callActivityOnNewIntent(s.activity,Intent(Intent.ACTION_VIEW).setDataAndType(source,"application/pdf"))}
        await("actual mixed deferred import notice") {shown(s.activity.getString(R.string.mixed_split_import_pending))}
        main {
            assertEquals(source,pending(s.activity));assertNull(s.native.pendingImport)
            assertSame(s.entry,s.maps.current);assertTrue(s.maps.visible);assertTrue(s.native.noteOpen);assertSame(model,s.native.state.document)
        }
        join(main {s.maps.closeToHome(s.entry)})
        await("root Map Home retains the incoming URI") {main {s.maps.current==null && s.maps.splitPresentation.layout!=null}}
        main {assertEquals(source,pending(s.activity));assertTrue(s.native.noteOpen);assertSame(model,s.native.state.document)}
        main {assertTrue(s.maps.removeSplitHome(Pane.SECONDARY))}
        await("normal import resumes after layout removal") {main {pending(s.activity)==null && s.native.pendingImport?.uri==source.toString()}}
    }

    @Test fun rootMapHomeRejectsSavedOpenRestoreAndPairBeforeChangingAnyNativeSlot()=scenario(savedFixture=true) {s->
        val model=main {s.native.state.document};val history=main {s.native.history}
        click(s.activity.getString(R.string.map_close))
        await("actual root close enters Map Home") {main {s.maps.current==null && s.maps.splitPresentation.layout!=null}}
        click(s.activity.getString(R.string.toolbar_home))
        await("actual main menu parks Map Home") {main {s.maps.splitPresentation.layout==null && s.maps.splitPresentation.returnLayout!=null}}
        val parked=main {s.maps.splitPresentation.returnLayout}
        click(s.activity.getString(R.string.split_workspace))
        fun unchanged()=main {
            assertNull(s.maps.current);assertNull(s.maps.splitPresentation.layout);assertEquals(parked,s.maps.splitPresentation.returnLayout)
            assertTrue(s.native.noteOpen);assertSame(model,s.native.state.document);assertSame(history,s.native.history)
        }
        click(s.activity.getString(R.string.split_choose_pair))
        await("pair is explicitly deferred") {shown(s.activity.getString(R.string.mixed_split_pair_pending))};unchanged()
        click(s.activity.getString(R.string.split_restore_recent))
        await("saved restore is explicitly deferred") {shown(s.activity.getString(R.string.mixed_split_replace_saved_pending))};unchanged()
        click(requireNotNull(s.savedFile).nameWithoutExtension)
        await("saved file open is explicitly deferred") {shown(s.activity.getString(R.string.mixed_split_replace_saved_pending))};unchanged()
    }
}
