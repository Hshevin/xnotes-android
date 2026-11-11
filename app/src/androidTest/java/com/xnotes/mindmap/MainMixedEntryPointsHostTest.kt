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
import com.xnotes.ui.MixedSplitRequest
import com.xnotes.ui.SplitSourceRef
import com.xnotes.ui.SplitOpenCodec
import com.xnotes.ui.SplitOpenRequest
import com.xnotes.ui.SplitArrangementRead
import com.xnotes.ui.SplitArrangementStore
import com.xnotes.ui.SplitRecentWriter
import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.tools.Tool
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
        val noteFile:File,val importFile:File,val savedFile:File?,val savedRequest:MixedSplitRequest?) {
        val ownedArrangements=mutableListOf<File>()
    }
    private fun scenario(savedFixture:Boolean=false,typedFixture:Boolean=false,malformedFixture:Boolean=false,appStorageFixture:Boolean=false,test:(Scene)->Unit) {
        val base=runner.targetContext
        val recovery=listOf("session","session-b").flatMap {slot->
            listOf("document.xnote","session.json","canvas.xcanvas","canvas-session.json").map {File(base.filesDir,"$slot/$it")}
        }
        assertTrue("Use an empty instrumentation profile; recovery is never replaced",recovery.none {it.exists()})
        val token=UUID.randomUUID().toString()
        val folder=File(if(appStorageFixture) com.xnotes.platform.AppStorageDocumentsProvider.rootDir(base) else base.cacheDir,"mixed-entrypoints-$token").apply {mkdirs()}
        val note=File(folder,"source.xnote")
        val model=Document(mutableListOf(Page(500.0,500.0))).apply {enableReferenceAnchors()}
        note.outputStream().use {DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(model.fixedWriteSnapshot(),it)}
        val mapModel=MindMapDocument(nodes=listOf(MapNode(id="root",parent=null,text="Mixed entry points")))
        val map=File(folder,"map.xmap").apply {writeBytes(MapCodec.encode(mapModel))}
        val incoming=File(folder,"incoming.pdf").apply {writeText("%PDF-1.4\n% delayed import fixture\n")}
        val recent=File(base.filesDir,"saved-split.json")
        val hasFixture=savedFixture || typedFixture || malformedFixture
        val stored=if(hasFixture) File(File(base.filesDir,"splits").apply {mkdirs()},"mixed-entrypoints-$token.xsplit") else null
        val typed=if(typedFixture || malformedFixture) MixedSplitRequest(
            SplitSourceRef(mapModel.id,Uri.fromFile(map).toString(),"Map"),
            SplitSourceRef(model.identity.documentId,Uri.fromFile(note).toString(),"Note"),
            MapSplitLayout(Pane.PRIMARY,Pane.PRIMARY,.31f,false,true),
            MapPresentationCamera(MindMapView.Viewport(1.5f,-24f,48f),1200,800,2f),
            Editor.ReadingPosition(1.25,17.0,27.0,ViewOverrides(mode=ViewingMode.COVER,verticalScroll=false))) else null
        if(hasFixture) {
            assertFalse("The saved split fixture never replaces an existing file",recent.exists())
            val json=if(typed!=null) JSONObject(String(SplitOpenCodec.encodeMixed(typed),Charsets.UTF_8)) else JSONObject().put("format","xnotes-split").put("version",2)
                .put("primaryPath",Uri.fromFile(note).toString()).put("secondPath",Uri.fromFile(note).toString())
                .put("primaryId",model.identity.documentId).put("secondId",model.identity.documentId)
                .put("primaryOpen",true).put("secondOpen",true).put("shared",true).put("ratio",.5)
            if(malformedFixture) {
                json.getJSONArray("panes").getJSONObject(1).put("position",JSONObject.NULL)
                json.put("primaryPath",Uri.fromFile(note).toString()).put("secondPath",Uri.fromFile(note).toString())
            }
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
            val locations=if(appStorageFixture) runBlocking(Dispatchers.IO) {
                // Exercise the real controlled creation and AppStorage provider used by the Home menu.
                assertTrue(note.delete());assertTrue(map.delete())
                val tree=com.xnotes.platform.AppStorageDocumentsProvider.treeUri(base).toString()
                val parent="root/${folder.name}"
                requireNotNull(native.createBlankNoteFile(tree,parent,"source")) to
                    Uri.parse(requireNotNull(native.createBlankMindMapFile(tree,parent,"map")))
            } else Uri.fromFile(note).toString() to Uri.fromFile(map)
            runBlocking {withTimeout(15000) {withContext(Dispatchers.Main) {
                assertTrue(native.openSingleFromHome(locations.first,note.name))
                assertTrue(maps.open(locations.second,"Map",flushNativeOnPresent=false,
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
            val ready=Scene(host,native,maps,entry,note,incoming,stored,typed);scene=ready;test(ready)
        } finally {
            scene?.let {s->
                main {clearPending(s.activity);s.native.cancelImport()}
                join(main {s.maps.current?.let {s.maps.close(it)}})
                main {s.maps.splitPresentation.clear();s.native.goHomeAll(s.native.openPanes.map {it.documentOwner.discardForOpen()}.toSet())}
                await("owned native closes") {main {s.native.openPanes.isEmpty()}}
                assertNotNull(runBlocking {withTimeout(15000) {withContext(Dispatchers.Main) {s.native.awaitHostParking()}}})
            }
            activity?.let {main {it.finish()};runner.waitForIdleSync()}
            runBlocking {withTimeout(15000) {SplitRecentWriter.forFilesDir(base.filesDir).awaitAccepted()}}
            if(hasFixture || scene?.ownedArrangements?.isNotEmpty()==true) recent.delete()
            stored?.delete();scene?.ownedArrangements?.forEach {it.delete()}
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
        click(s.activity.getString(R.string.split_save))
        await("Home cannot be saved as a complete mixed arrangement") {shown(s.activity.getString(R.string.mixed_split_save_complete_first))}
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

    private fun replaceFromSavedBrowser(s:Scene,restore:Boolean,closeMapFirst:Boolean) {
        val model=main {s.native.state.document};val history=main {s.native.history}
        if(closeMapFirst) {
            click(s.activity.getString(R.string.map_close))
            await("actual root close enters Map Home") {main {s.maps.current==null && s.maps.splitPresentation.layout!=null}}
        }
        click(s.activity.getString(R.string.toolbar_home))
        await("actual main menu parks the mixed layout") {main {s.maps.splitPresentation.layout==null && s.maps.splitPresentation.returnLayout!=null}}
        val parked=main {s.maps.splitPresentation.returnLayout}
        click(s.activity.getString(R.string.split_workspace))
        if(restore) {
            click(s.activity.getString(R.string.split_choose_pair))
            await("empty pair chooser points to file selection") {shown(s.activity.getString(R.string.mixed_split_pair_pending))}
            main {assertEquals(parked,s.maps.splitPresentation.returnLayout);assertSame(model,s.native.state.document)}
            click(s.activity.getString(R.string.split_restore_recent))
        } else click(requireNotNull(s.savedFile).nameWithoutExtension)
        await("actual saved native split replaces mixed presentation") {main {
            s.maps.current==null && !s.maps.visible && s.maps.splitPresentation.layout==null &&
                s.maps.splitPresentation.returnLayout==null && s.native.openPanes.size==2 &&
                s.native.openPanes.all {it.view.isShown && it.view.width>0} && graph(s.activity)==null
        }}
        main {
            assertSame(model,s.native.state.document);assertSame(history,s.native.history)
            assertSame(s.native,s.native.secondary!!.documentOwner)
        }
        // Actual Window dispatch after adoption must reach the native pane without a new touch.
        var saves=0
        val actions=main {s.native.keyActions}
        try {
            main {
                s.native.keyActions=Editor.KeyActions(save={saves++})
                assertTrue(s.activity.dispatchKeyEvent(KeyEvent(1000,1001,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_S,0,KeyEvent.META_CTRL_ON)))
            }
            await("native keyboard focus after saved split adoption") {main {saves==1}}
        } finally {main {s.native.keyActions=actions}}
    }

    @Test fun rootMapHomeCanRestoreSavedNativeViewsWithoutReplacingTheRetainedOwner()=scenario(savedFixture=true) {s->
        replaceFromSavedBrowser(s,restore=true,closeMapFirst=true)
    }

    @Test fun parkedLiveMapCanOpenASavedSplitFileThroughTheActualMainBrowser()=scenario(savedFixture=true) {s->
        replaceFromSavedBrowser(s,restore=false,closeMapFirst=false)
    }

    private fun openTypedFromBrowser(s:Scene,recent:Boolean) {
        val model=main {s.native.state.document};val history=main {s.native.history}
        val session=main {s.entry.session}
        main {s.native.selectTool(Tool.PAN)}
        click(s.activity.getString(R.string.toolbar_home))
        await("mixed layout parks for Saved") {main {s.maps.splitPresentation.returnLayout!=null && !s.maps.visible}}
        click(s.activity.getString(R.string.split_workspace))
        if(recent) click(s.activity.getString(R.string.split_restore_recent)) else click(requireNotNull(s.savedFile).nameWithoutExtension)
        await("typed request reuses both actual documents and maps physical layout") {main {
            val layout=s.maps.splitPresentation.layout
            !s.maps.opening && s.maps.visible && s.maps.current?.session===session && layout!=null &&
                layout.nativePane==s.native.pane && layout.focusedPane==layout.mapPane && layout.ratio==.31f &&
                layout.sideBySide==false && !layout.reversed && s.native.view.isShown &&
                graph(s.activity)?.let {it.isShown && it.width>0 && it.hasFocus()}==true
        }}
        main {
            assertSame(model,s.native.state.document);assertSame(history,s.native.history)
            assertEquals(Tool.PAN,s.native.tool);assertEquals(Tool.PAN,s.native.toolbarTool)
            assertEquals(ViewingMode.COVER,s.native.viewOverrides.mode);assertEquals(false,s.native.viewOverrides.verticalScroll)
            assertEquals(requireNotNull(s.savedRequest).map.documentId,s.maps.current!!.session.document!!.id)
        }
    }

    @Test fun aTypedSavedFileUsesTheActualMainDispatcherAndRetainsTheNativeOwner()=scenario(typedFixture=true) {s->
        openTypedFromBrowser(s,recent=false)
    }

    @Test fun aTypedRecentUsesTheActualMainDispatcherAndPreservesPhysicalFirstRatio()=scenario(typedFixture=true) {s->
        openTypedFromBrowser(s,recent=true)
    }

    @Test fun malformedTypedSavedDataWithCompleteLegacyPathsCannotFallBackToNative()=scenario(malformedFixture=true) {s->
        val model=main {s.native.state.document};val history=main {s.native.history};val session=main {s.entry.session}
        click(s.activity.getString(R.string.toolbar_home))
        await("park original mixed before the bad file") {main {s.maps.splitPresentation.returnLayout!=null && !s.maps.visible}}
        val parked=main {s.maps.splitPresentation.returnLayout}
        click(s.activity.getString(R.string.split_workspace));click(requireNotNull(s.savedFile).nameWithoutExtension)
        await("typed parse rejects without Native fallback") {shown(s.activity.getString(R.string.split_arrangement_invalid))}
        main {
            assertSame(session,s.maps.current!!.session);assertEquals(parked,s.maps.splitPresentation.returnLayout)
            assertFalse(s.maps.visible);assertSame(model,s.native.state.document);assertSame(history,s.native.history)
            assertEquals(1,s.native.openPanes.size);assertFalse(s.native.secondary?.noteOpen==true)
        }
    }

    @Test fun theActualSaveSplitButtonPublishesTypedNamedAndRecentWithoutChangingTools()=scenario { verifySavedSplit(it) }
    @Test fun newlyCreatedAppStorageDocumentsSaveMixedArrangementThroughTheActualButton()=scenario(appStorageFixture=true) { verifySavedSplit(it) }
    private fun verifySavedSplit(s:Scene) {
        val recent=File(runner.targetContext.filesDir,"saved-split.json")
        assertFalse("This fixture never replaces someone else's recent arrangement",recent.exists())
        val before=SplitArrangementStore(runner.targetContext.filesDir)
        val directory=File(runner.targetContext.filesDir,"splits")
        val names=directory.listFiles()?.map {it.name}?.toSet().orEmpty()
        val model=main {s.native.state.document};val history=main {s.native.history}
        main {
            s.native.selectTool(Tool.PAN)
            assertTrue(s.maps.updateSplitLayout {it.copy(ratio=.37f,sideBySide=false,reversed=true,focusedPane=it.nativePane)})
        }
        click(s.activity.getString(R.string.split_save))
        await("Main publishes a complete typed named file and checked recent") {
            val files=directory.listFiles()?.filter {it.extension=="xsplit" && it.name !in names}.orEmpty()
            files.singleOrNull()?.let {file->
                if(file !in s.ownedArrangements) s.ownedArrangements+=file
                before.readManaged(file.toURI().toString()) is SplitArrangementRead.Ready &&
                    (before.readRecent() as? SplitArrangementRead.Ready)?.request is SplitOpenRequest.Mixed
            }==true
        }
        val saved=(before.readManaged(s.ownedArrangements.single().toURI().toString()) as SplitArrangementRead.Ready).request as SplitOpenRequest.Mixed
        main {
            assertEquals(s.maps.current!!.session.document!!.id,saved.request.map.documentId)
            assertEquals(s.native.visibleDocumentId,saved.request.native.documentId)
            assertEquals(s.maps.splitPresentation.layout,saved.request.layout)
            assertSame(model,s.native.state.document);assertSame(history,s.native.history)
            assertEquals(Tool.PAN,s.native.tool);assertEquals(Tool.PAN,s.native.toolbarTool)
        }
    }
}
