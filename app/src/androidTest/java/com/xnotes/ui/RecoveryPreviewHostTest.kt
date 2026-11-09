package com.xnotes.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.app.ActivityOptionsCompat
import com.xnotes.R
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.format.BundleIdentity
import com.xnotes.format.BundleMigration
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.pen.PenTestRunner
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.Settings
import com.xnotes.settings.SettingsRepository
import com.xnotes.storage.NotebookStorage
import com.xnotes.storage.MigrationPublication
import com.xnotes.ui.theme.XnotesTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Exercises actual Compose actions and ActivityResultRegistry dispatch, with actual Editor/storage IO. */
class RecoveryPreviewHostTest {
    private val runner get()=PenTestRunner.instance
    private data class Launch(val code:Int,val action:String?,val suggestedName:String?)
    private class Registry(private val context:Context):ActivityResultRegistry() {
        val launched=mutableListOf<Launch>()
        var failNextLaunch=false
        override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
            if(failNextLaunch) {failNextLaunch=false;throw android.content.ActivityNotFoundException("Injected picker failure")}
            val intent=contract.createIntent(context,input)
            launched+=Launch(requestCode,intent.action,intent.getStringExtra(Intent.EXTRA_TITLE))
        }
        fun deliver(launch:Launch,uri:Uri?) {
            assertTrue(dispatchResult(launch.code,if(uri==null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                uri?.let {Intent().setData(it)}))
        }
    }
    private fun waitUntil(label:String,check:()->Boolean) {
        repeat(500) {runner.waitForIdleSync();if(check()) return;Thread.sleep(10)}
        fail("Recovery UI did not settle: $label")
    }
    private fun action(label:String):AccessibilityNodeInfo? {
        for(node in runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label).orEmpty()) {
            var at:AccessibilityNodeInfo?=node
            repeat(5) {val current=at;if(current?.isClickable==true) return current;at=current?.parent}
        }
        return null
    }
    private fun visible(label:String)=runner.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label).orEmpty().isNotEmpty()
    private fun click(label:String) {
        var target:AccessibilityNodeInfo?=null
        waitUntil(label) {target=action(label);target?.isEnabled==true}
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private class Fixture(val host:ComposeHostFixture,val root:File,val trackWrites:Boolean) {
        val context=object:ContextThemeWrapper(host.activity,R.style.Theme_Xnotes) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val writes=AtomicInteger()
        val writesByUri=ConcurrentHashMap<String,AtomicInteger>()
        private val storageField=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        val storage=NotebookStorage(context,targetOverride=if(!trackWrites) null else {uri->
            val file=File(requireNotNull(Uri.parse(uri).path))
            require(file.canonicalPath.startsWith(root.canonicalPath+File.separator))
            object:MigrationPublication.Target {
                override fun read()=file.inputStream()
                override fun write(source:File) {
                    writes.incrementAndGet();writesByUri.computeIfAbsent(uri){AtomicInteger()}.incrementAndGet();source.copyTo(file,overwrite=true)
                }
            }
        })
        init {storageField.set(null,storage)}
        val primary=host.onMain {com.xnotes.platform.FontCatalog.init(context);com.xnotes.platform.TemplateLibrary.init(context);Editor(context)}
        val secondary=host.onMain {primary.secondaryPane().also {primary.focusPane(Pane.PRIMARY)}}
        val displayed=mutableStateOf(primary)
        var controller=host.onMain {RecoveryPreviewController(context)}
        var registry=Registry(context)
        private val controllers=mutableListOf(controller)
        val noteA:File
        val noteB:File
        val canvasB:File
        val liveA=primary.state.document
        val liveB=secondary.state.document
        private val cameraA=Triple(primary.state.zoom,primary.state.scrollX,primary.state.scrollY)
        private val cameraB=Triple(secondary.state.zoom,secondary.state.scrollX,secondary.state.scrollY)
        init {
            host.onMain {liveA.dirty=true;liveB.dirty=true;primary.showRail(false)}
            val codec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
            fun note(slot:String,name:String,size:Double):File {
                val dir=File(context.filesDir,slot).apply {mkdirs()}
                File(dir,"session.json").writeText("""{"displayName":"$name","dirty":true}""")
                return File(dir,"document.xnote").apply {outputStream().use {codec.write(Document(mutableListOf(Page(size,500.0))),it)}}
            }
            noteA=note("session","Primary history",500.0);noteB=note("session-b","Secondary history",700.0)
            val canvas=InfiniteDocument().apply {add(ShapeItem(ShapeKind.RECTANGLE,Pt(0.0,0.0),Pt(400.0,200.0),Rgba(0,0,0)))}
            val slot=File(context.filesDir,"session-b")
            File(slot,"canvas-session.json").writeText("""{"display_name":"Secondary canvas","dirty":true}""")
            canvasB=File(slot,"canvas.xcanvas").apply {outputStream().use {CanvasCodec(AndroidImageCodec(),AndroidTextMeasurer()).write(canvas,it)}}
        }
        fun compose() {
            val currentController=controller;val currentRegistry=registry
            host.setContent {
                val configuration=Configuration(LocalConfiguration.current).apply {screenWidthDp=840}
                val owner=object:ActivityResultRegistryOwner {override val activityResultRegistry=currentRegistry}
                CompositionLocalProvider(LocalConfiguration provides configuration,LocalActivityResultRegistryOwner provides owner) {
                    XnotesTheme(primary.palette) {
                        val pane=displayed.value
                        Backstage(pane,BackstageView.HOME,{},onImportPdf={},onOpenFile={},onPickRoot={},onShareFile={},
                            onSaveCopyFile={},onExportFilePdf={},onExitApp={},onPreviewRecovery={currentController.open(pane)})
                        RecoveryPreviewHost(currentController)
                    }
                }
            }
        }
        fun reconstructHost() {
            val state=Bundle();host.onMain {registry.onSaveInstanceState(state)}
            host.disposeContent();runBlocking {withTimeout(10000){controller.dispose().join()}}
            registry=Registry(context).also {newRegistry->host.onMain {newRegistry.onRestoreInstanceState(state)}}
            controller=host.onMain {RecoveryPreviewController(context)};controllers+=controller
            compose()
        }
        fun assertWorkspaceKept()=host.onMain {
            assertSame(liveA,primary.state.document);assertSame(liveB,secondary.state.document)
            assertTrue(liveA.dirty);assertTrue(liveB.dirty);assertSame(primary,primary.active)
            assertEquals(cameraA,Triple(primary.state.zoom,primary.state.scrollX,primary.state.scrollY))
            assertEquals(cameraB,Triple(secondary.state.zoom,secondary.state.scrollX,secondary.state.scrollY))
        }
        fun cleanup() {
            host.disposeContent()
            for(c in controllers) runBlocking {withTimeout(10000){c.dispose().join()}}
            host.onMain {for(editor in listOf(primary,secondary)) {
                editor.controller.resetGestureState()
                (Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(editor) as CoroutineScope).cancel()
            }}
        }
    }
    private fun scenario(trackWrites:Boolean=false,block:(Fixture)->Unit) {
        val base=runner.targetContext;val root=File(base.cacheDir,"recovery-host-${UUID.randomUUID()}").apply {mkdirs()}
        val previous=LiveSettings.get(SettingsRepository(base));LiveSettings.set(Settings())
        try {ComposeHostFixture.run {host->
            val storageField=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
            val previousStorage=storageField.get(null)
            try {val f=Fixture(host,root,trackWrites);try {f.compose();block(f)} finally {f.cleanup()}}
            finally {
                val own=storageField.get(null) as? NotebookStorage
                try {if(own!==previousStorage) own?.registry?.close()} finally {storageField.set(null,previousStorage)}
            }
        }}
        finally {LiveSettings.set(previous);root.deleteRecursively()}
    }
    private fun label(f:Fixture,id:Int)=f.host.activity.getString(id)
    private fun openNote(f:Fixture,editor:Editor) {
        f.host.change {f.displayed.value=editor}
        click(label(f,R.string.recovery_materials));click(label(f,R.string.recovery_preview_note))
    }
    private fun saveLaunch(f:Fixture):Launch {
        val before=f.registry.launched.size;click(label(f,R.string.recovery_preview_save_copy))
        waitUntil("CreateDocument launch") {f.host.onMain {f.registry.launched.size==before+1}}
        return f.host.onMain {f.registry.launched.last()}.also {assertEquals(Intent.ACTION_CREATE_DOCUMENT,it.action)}
    }
    private fun assertRawCopy(source:File,copy:File) {
        val expected=BundleMigration.fingerprints(source).filterKeys {it!=BundleIdentity.ENTRY}
        assertEquals(expected,BundleMigration.fingerprints(copy).filterKeys {it!=BundleIdentity.ENTRY})
        assertNotEquals(BundleIdentity.read(source)?.documentId,BundleIdentity.read(copy)!!.documentId)
    }
    @Test fun secondaryRecoveryCopiesRetryTheirRetainedStageAndResumeAfterAHostRestart()=scenario(trackWrites=true) {f->
        val before=listOf(f.noteA,f.noteB,f.canvasB).associateWith {it.readBytes()}
        f.host.change {f.displayed.value=f.secondary}
        click(label(f,R.string.recovery_materials))
        waitUntil("both legacy choices") {action(label(f,R.string.recovery_preview_note))!=null && action(label(f,R.string.recovery_preview_canvas))!=null}
        click(label(f,R.string.recovery_preview_note))
        f.host.onMain {f.registry.failNextLaunch=true}
        click(label(f,R.string.recovery_preview_save_copy))
        waitUntil("picker launch failure releases its slot") {action(label(f,R.string.recovery_preview_save_copy))?.isEnabled==true}
        assertTrue(f.host.onMain {f.registry.launched.isEmpty()})
        val launch=saveLaunch(f)
        assertTrue(launch.suggestedName.orEmpty().endsWith(".xnote"))
        val target=File(f.root,"note-copy.xnote").apply {writeBytes(byteArrayOf())}
        val targetUri=Uri.fromFile(target).toString()
        f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_recovery_finish BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT,'injected completion'); END")
        f.host.onMain {f.registry.deliver(launch,Uri.fromFile(target))}
        waitUntil("typed retained copy") {target.length()>0 && action(label(f,R.string.recovery_copy_continue))?.isEnabled==true}
        val written=target.readBytes();assertEquals(1,f.writes.get());assertTrue(f.storage.hasRecovery(targetUri))
        click(label(f,R.string.recovery_copy_continue))
        waitUntil("failed completion keeps the same retry") {action(label(f,R.string.recovery_copy_continue))?.isEnabled==true && visible(label(f,R.string.recovery_copy_retry_failed))}
        assertEquals(1,f.writes.get());assertArrayEquals(written,target.readBytes());assertTrue(f.storage.hasRecovery(targetUri))
        f.storage.registry.writableDatabase.execSQL("DROP TRIGGER fail_recovery_finish")
        click(label(f,R.string.recovery_copy_continue))
        waitUntil("verified note copy") {!f.storage.hasRecovery(targetUri) && action(label(f,R.string.recovery_preview_save_copy))?.isEnabled==true}
        assertEquals(1,f.writes.get());assertArrayEquals(written,target.readBytes())
        assertRawCopy(f.noteB,target);f.assertWorkspaceKept()
        click(label(f,R.string.close_preview));f.host.change {f.displayed.value=f.secondary}
        click(label(f,R.string.recovery_materials));click(label(f,R.string.recovery_preview_canvas))
        val canvasLaunch=saveLaunch(f);assertTrue(canvasLaunch.suggestedName.orEmpty().endsWith(".xcanvas"))
        val canvasTarget=File(f.root,"canvas-copy.xcanvas").apply {writeBytes(byteArrayOf())}
        val canvasUri=Uri.fromFile(canvasTarget).toString()
        f.storage.registry.writableDatabase.execSQL("CREATE TRIGGER fail_recovery_finish BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT,'injected completion'); END")
        f.host.onMain {f.registry.deliver(canvasLaunch,Uri.fromFile(canvasTarget))}
        waitUntil("canvas stage retained") {canvasTarget.length()>0 && action(label(f,R.string.recovery_copy_continue))?.isEnabled==true}
        assertTrue(f.storage.hasRecovery(canvasUri));assertEquals(1,f.writesByUri[canvasUri]?.get())
        click(label(f,R.string.close_preview));f.reconstructHost()
        f.host.change {f.displayed.value=f.secondary};click(label(f,R.string.recovery_materials))
        val beforeResume=f.registry.launched.size;click(label(f,R.string.recovery_copy_resume_pick))
        waitUntil("OpenDocument resume launch") {f.host.onMain {f.registry.launched.size==beforeResume+1}}
        val resume=f.host.onMain {f.registry.launched.last()};assertEquals(Intent.ACTION_OPEN_DOCUMENT,resume.action)
        f.storage.registry.writableDatabase.execSQL("DROP TRIGGER fail_recovery_finish")
        f.host.onMain {f.registry.deliver(resume,Uri.fromFile(canvasTarget))}
        waitUntil("verified canvas copy after restart") {!f.storage.hasRecovery(canvasUri) && visible(label(f,R.string.recovery_copy_saved))}
        assertEquals(1,f.writesByUri[canvasUri]?.get())
        assertRawCopy(f.canvasB,canvasTarget);f.assertWorkspaceKept()
        for((source,bytes) in before) assertArrayEquals(bytes,source.readBytes())
    }
    @Test fun oldPickerResultsCannotRebindAfterClosingReopeningOrRecreatingTheHost() {
        for(recreate in listOf(false,true)) scenario {f->
            openNote(f,f.primary);val old=saveLaunch(f)
            val staleTarget=File(f.root,"stale.xnote").apply {writeBytes(byteArrayOf())}
            click(label(f,R.string.close_preview))
            if(recreate) f.reconstructHost()
            openNote(f,f.secondary)
            var next:Launch?=null
            if(recreate) {
                val launch=saveLaunch(f);next=launch;assertNotEquals(old.code,launch.code)
            } else {
                waitUntil("old picker blocks a second launch") {action(label(f,R.string.recovery_preview_save_copy))?.isEnabled==false}
                assertEquals(1,f.host.onMain {f.registry.launched.size})
            }
            f.host.onMain {f.registry.deliver(old,Uri.fromFile(staleTarget))}
            if(!recreate) next=saveLaunch(f)
            assertEquals(0,staleTarget.length());f.assertWorkspaceKept()
            val target=File(f.root,"secondary-copy.xnote").apply {writeBytes(byteArrayOf())}
            f.host.onMain {f.registry.deliver(requireNotNull(next),Uri.fromFile(target))}
            waitUntil("new picker publishes only its own body") {target.length()>0 && action(label(f,R.string.recovery_preview_save_copy))?.isEnabled==true}
            assertEquals(0,staleTarget.length());assertRawCopy(f.noteB,target);f.assertWorkspaceKept()
        }
    }
}
