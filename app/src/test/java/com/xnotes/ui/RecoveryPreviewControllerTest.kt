package com.xnotes.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Looper
import android.view.ContextThemeWrapper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import com.xnotes.R
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.DocumentCodec
import com.xnotes.platform.AndroidImageCodec
import com.xnotes.platform.AndroidTextMeasurer
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.platform.RecoveryLegacyArtifact
import com.xnotes.storage.MigrationPublication
import com.xnotes.storage.NotebookStorage
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** Executes the actual controller and backend. No mock load/copy result or replacement cell machine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryPreviewControllerTest {
    @Before fun noEglSentinels() {
        fun <T> value(type:Class<T>):T=ReflectionHelpers.callConstructor(type,ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE,0L))
        if(EGL14.EGL_NO_DISPLAY==null) EGL14.EGL_NO_DISPLAY=value(EGLDisplay::class.java)
        if(EGL14.EGL_NO_CONTEXT==null) EGL14.EGL_NO_CONTEXT=value(EGLContext::class.java)
        if(EGL14.EGL_NO_SURFACE==null) EGL14.EGL_NO_SURFACE=value(EGLSurface::class.java)
    }
    private data class Launch(val code:Int,val request:RecoveryPickerLaunch)
    private class Registry(private val context:Context):ActivityResultRegistry() {
        val launches=mutableListOf<Launch>()
        private var launching:RecoveryPickerLaunch?=null
        override fun <I,O> onLaunch(code:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
            val request=requireNotNull(launching)
            val actual=contract.createIntent(context,input)
            assertEquals(if(request.kind==RecoveryPickerKind.CREATE) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT,actual.action)
            launches.add(Launch(code,request))
        }
        fun attach(controller:RecoveryPreviewController):()->Unit {
            val registrations=mutableMapOf<Long,ActivityResultLauncher<*>>()
            controller.attachPicker {request->
                val key="controller-test-${controller.pickerNamespace}-${request.kind}-${request.id}"
                launching=request
                try {
                    when(request.kind) {
                        RecoveryPickerKind.CREATE->{
                            val launcher=register(key,ActivityResultContracts.CreateDocument("application/octet-stream")) {uri->
                                registrations.remove(request.id)?.unregister();controller.receivePickerResult(request.id,request.kind,uri)
                            }
                            registrations[request.id]=launcher;launcher.launch(requireNotNull(request.filename))
                        }
                        RecoveryPickerKind.RESUME->{
                            val launcher=register(key,ActivityResultContracts.OpenDocument()) {uri->
                                registrations.remove(request.id)?.unregister();controller.receivePickerResult(request.id,request.kind,uri)
                            }
                            registrations[request.id]=launcher;launcher.launch(arrayOf("*/*"))
                        }
                    }
                } finally {launching=null}
            }
            return {controller.detachPicker();registrations.values.toList().forEach {it.unregister()};registrations.clear()}
        }
        fun deliver(launch:Launch,uri:Uri?) {
            assertTrue(dispatchResult(launch.code,if(uri==null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                uri?.let {Intent().setData(it)}))
        }
    }
    private class Fixture(val context:Context,val root:File) {
        val reads=AtomicInteger();val writes=AtomicInteger()
        @Volatile var beforeRead:(()->Unit)?=null
        val storage=NotebookStorage(context,targetOverride={uri->
            val path=File(requireNotNull(Uri.parse(uri).path))
            require(path.canonicalPath.startsWith(root.canonicalPath+File.separator))
            object:MigrationPublication.Target {
                override fun read():java.io.InputStream {reads.incrementAndGet();beforeRead?.invoke();return path.inputStream()}
                override fun write(source:File) {writes.incrementAndGet();source.copyTo(path,overwrite=true)}
            }
        })
        val codec=DocumentCodec(AndroidImageCodec(),AndroidTextMeasurer())
        lateinit var primary:Editor
        lateinit var secondary:Editor
        val editors=mutableListOf<Editor>()
        val controllers=mutableListOf<RecoveryPreviewController>()
        val detachments=mutableListOf<()->Unit>()
        fun slot(editor:Editor)=Editor::class.java.getDeclaredField("sessionDir").apply {isAccessible=true}.get(editor) as File
        fun owner(editor:Editor)=Editor::class.java.getDeclaredField("sessionOwner").apply {isAccessible=true}.get(editor) as LegacySessionQueue.Owner
        fun seed(editor:Editor,x:Double):File {
            val doc=Document(mutableListOf(Page(500.0,500.0)))
            doc.pages.single().items.add(Stroke(Tool.PEN,ToolDefaults.configFor(Tool.PEN),listOf(Sample(x,20.0,.5),Sample(x+30,50.0,.75))))
            return File(slot(editor).apply {mkdirs()},"document.xnote").apply {outputStream().use {codec.write(doc,it)}}
        }
        fun temporaries()=root.walkTopDown().filter {it.name.startsWith("bundle-source-") || it.name.startsWith("native-open-")}
            .map {it.relativeTo(root).path}.toSet()
        suspend fun newController():Pair<RecoveryPreviewController,Registry> = withContext(Dispatchers.Main) {
            val controller=RecoveryPreviewController(context).also {controllers.add(it)}
            val registry=Registry(context);detachments.add(registry.attach(controller));controller to registry
        }
        suspend fun ready(controller:RecoveryPreviewController,editor:Editor) {
            withContext(Dispatchers.Main) {controller.open(editor)}
            awaitMain {controller.state?.phase==RecoveryPreviewPhase.ABSENT}
            withContext(Dispatchers.Main) {controller.choose(RecoveryLegacyArtifact.NOTE)}
            awaitMain {controller.state?.phase==RecoveryPreviewPhase.READY && controller.state?.rendering==false}
        }
        suspend fun awaitMain(condition:()->Boolean)=withTimeout(7000L) {
            while(!withContext(Dispatchers.Main) {condition()}) delay(1)
        }
    }
    private fun scenario(block:suspend (Fixture)->Unit) {
        val base=RuntimeEnvironment.getApplication()
        val root=File(base.cacheDir,"recovery-controller-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextThemeWrapper(base,R.style.Theme_Xnotes) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir()=File(root,"files").apply {mkdirs()}
            override fun getCacheDir()=File(root,"cache").apply {mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"private").apply {mkdirs()}
        }
        val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        val previous=singleton.get(null);val f=Fixture(context,root)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        var failure:Throwable?=null
        val job=scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    singleton.set(null,f.storage)
                    com.xnotes.platform.FontCatalog.init(context);com.xnotes.platform.TemplateLibrary.init(context)
                    f.primary=Editor(context).also {f.editors.add(it)}
                    f.secondary=f.primary.secondaryPane().also {f.editors.add(it)}
                    f.primary.state.document.dirty=true;f.secondary.state.document.dirty=true
                }
                withTimeout(20000L) {block(f)}
            } catch(error:Throwable) {failure=error}
            finally {withContext(NonCancellable) {
                val jobs=withContext(Dispatchers.Main) {
                    f.detachments.forEach {it()};f.controllers.map {it.dispose()}
                }
                jobs.forEach {it.join()}
                withContext(Dispatchers.Main) {
                    val scopes=f.editors.map {Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(it) as CoroutineScope}
                    scopes.forEach {it.cancel()};scopes.forEach {it.coroutineContext[Job]?.join()}
                }
                f.editors.forEach {f.owner(it).awaitAccepted()}
                singleton.set(null,previous);withContext(Dispatchers.IO) {f.storage.registry.close()}
            }}
        }
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!job.isCompleted && System.nanoTime()<deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));Thread.yield()
            }
            assertTrue("Actual recovery controller scenario did not settle",job.isCompleted)
            failure?.let {throw it}
        } finally {
            scope.cancel()
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(!job.isCompleted && System.nanoTime()<deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));Thread.yield()
            }
            if(job.isCompleted) root.deleteRecursively()
        }
    }

    @Test fun lateCreateResultConsumesOnlyItsOldCellBeforeANewPreviewCanActuallyPublish()=scenario {f->
        val a=f.seed(f.primary,11.0);val b=f.seed(f.secondary,77.0)
        val bytesA=a.readBytes();val bytesB=b.readBytes()
        val live=withContext(Dispatchers.Main) {listOf(f.primary,f.secondary).map {it.state.document}}
        val (controller,registry)=f.newController();f.ready(controller,f.primary)
        val outputA=File(f.root,"late-a.xnote").apply {writeBytes(byteArrayOf())}
        val outputB=File(f.root,"actual-b.xnote").apply {writeBytes(byteArrayOf())}
        val launchA=withContext(Dispatchers.Main) {controller.requestCopy();assertTrue(controller.pickerPending);registry.launches.single()}
        withContext(Dispatchers.Main) {controller.closePreview()}
        f.ready(controller,f.secondary)
        withContext(Dispatchers.Main) {
            assertTrue(controller.pickerPending);controller.requestCopy();assertEquals(1,registry.launches.size)
            registry.deliver(launchA,Uri.fromFile(outputA))
            assertFalse(controller.pickerPending);assertEquals(Pane.SECONDARY,controller.state!!.pane)
            assertEquals(0,f.reads.get());assertEquals(0,f.writes.get())
            controller.requestCopy();assertTrue(controller.pickerPending);assertEquals(2,registry.launches.size)
            registry.deliver(registry.launches.last(),Uri.fromFile(outputB))
        }
        f.awaitMain {controller.state?.saved!=null && controller.state?.busy==false}
        assertEquals(0L,outputA.length());assertEquals(1,f.writes.get())
        val copy=outputB.inputStream().use {f.codec.read(it)}
        assertEquals(77.0,(copy.pages.single().items.single() as Stroke).xAt(0),.001)
        assertNotEquals(BundleReferenceInfo.inspectStrict(b).header.identity!!.documentId,copy.identity.documentId)
        assertArrayEquals(bytesA,a.readBytes());assertArrayEquals(bytesB,b.readBytes())
        withContext(Dispatchers.Main) {
            assertSame(live[0],f.primary.state.document);assertSame(live[1],f.secondary.state.document)
            assertTrue(live[0].dirty);assertTrue(live[1].dirty)
            assertEquals(DocumentKind.NOTE,controller.state!!.kind)
        }
    }

    @Test fun actionsFromAnOldCompositionCannotChooseRenderCopyResumeOrCloseANewerPreview()=scenario {f->
        val a=f.seed(f.primary,11.0);val b=f.seed(f.secondary,77.0)
        val bytesA=a.readBytes();val bytesB=b.readBytes()
        val (controller,registry)=f.newController();f.ready(controller,f.primary)
        val oldActions=withContext(Dispatchers.Main) {requireNotNull(controller.actions())}
        withContext(Dispatchers.Main) {controller.closePreview()}
        f.ready(controller,f.secondary)
        withContext(Dispatchers.Main) {
            val displayed=requireNotNull(controller.state)
            val launches=registry.launches.size;val reads=f.reads.get();val writes=f.writes.get()
            val stale= listOf<Pair<String,()->Unit>>(
                "choose" to {oldActions.choose(RecoveryLegacyArtifact.NOTE)},
                "page" to {oldActions.page(0)},
                "save" to {oldActions.requestCopy()},
                "resume picker" to {oldActions.requestResumePicker()},
                "complete pending" to {oldActions.completePending()},
                "close/dismiss" to {oldActions.closePreview()},
            )
            for((label,action) in stale) {
                // No Main frame occurs between the old callback and the assertion; the action
                // itself must retain A's request rather than consult and mutate current B.
                action()
                assertSame("Stale $label changed the newer preview",displayed,controller.state)
                assertEquals(launches,registry.launches.size);assertFalse(controller.pickerPending)
                assertEquals(reads,f.reads.get());assertEquals(writes,f.writes.get())
            }
            assertEquals(Pane.SECONDARY,displayed.pane)
        }
        val target=File(f.root,"bound-action-b.xnote").apply {writeBytes(byteArrayOf())}
        withContext(Dispatchers.Main) {
            val actions=requireNotNull(controller.actions())
            actions.requestCopy();assertEquals(1,registry.launches.size)
            registry.deliver(registry.launches.single(),Uri.fromFile(target))
        }
        f.awaitMain {controller.state?.saved!=null && controller.state?.busy==false}
        val copied=target.inputStream().use {f.codec.read(it)}
        assertEquals(77.0,(copied.pages.single().items.single() as Stroke).xAt(0),.001)
        assertNotEquals(BundleReferenceInfo.inspectStrict(b).header.identity!!.documentId,copied.identity.documentId)
        assertEquals(1,f.writes.get());assertArrayEquals(bytesA,a.readBytes());assertArrayEquals(bytesB,b.readBytes())
        withContext(Dispatchers.Main) {assertEquals(Pane.SECONDARY,controller.state!!.pane)}
    }

    @Test fun disposeWaitsForAcceptedLoadAndForActualCopyIoBeforeReleasingItsBody()=scenario {f->
        val source=f.seed(f.primary,31.0);val sourceBytes=source.readBytes();val owner=f.owner(f.primary)
        val entered=CompletableDeferred<Unit>();val releasePrefix=CompletableDeferred<Unit>()
        val prefix=requireNotNull(owner.submit {entered.complete(Unit);releasePrefix.await()});entered.await()
        val prefixField=owner.javaClass.getDeclaredField("lastAccepted").apply {isAccessible=true}
        val initial=prefixField.get(owner)
        val (loading,_)=f.newController()
        try {
            withContext(Dispatchers.Main) {loading.open(f.primary)}
            withTimeout(5000L) {while(true) {owner.isCurrent();if(prefixField.get(owner)!==initial) break;delay(1)}}
            val disposing=withContext(Dispatchers.Main) {loading.dispose().also {assertNull(loading.state)}}
            assertFalse("Dispose must await the independently accepted FIFO command",disposing.isCompleted)
            releasePrefix.complete(Unit);prefix.await();disposing.join();owner.awaitAccepted()
            assertTrue(f.temporaries().isEmpty());assertArrayEquals(sourceBytes,source.readBytes())
            assertEquals(LegacySessionQueue.Outcome.Success("usable"),requireNotNull(owner.submit {"usable"}).await())
        } finally {withContext(NonCancellable) {
            releasePrefix.complete(Unit);prefix.await();withContext(Dispatchers.Main) {loading.dispose()}.join();owner.awaitAccepted()
        }}

        val (copying,registry)=f.newController();f.ready(copying,f.primary)
        val held=f.temporaries();assertTrue("An actual preparation must hold its source snapshot",held.any {it.substringAfterLast('/').startsWith("bundle-source-")})
        val readEntered=CountDownLatch(1);val releaseRead=CountDownLatch(1);val once=AtomicBoolean(false)
        val target=File(f.root,"cancelled-copy.xnote").apply {writeBytes(byteArrayOf())}
        f.beforeRead={if(once.compareAndSet(false,true)) {readEntered.countDown();check(releaseRead.await(5,TimeUnit.SECONDS))}}
        try {
            withContext(Dispatchers.Main) {copying.requestCopy();registry.deliver(registry.launches.single(),Uri.fromFile(target))}
            withContext(Dispatchers.IO) {assertTrue("Actual native target IO was not reached",readEntered.await(5,TimeUnit.SECONDS))}
            val disposing=withContext(Dispatchers.Main) {copying.dispose()}
            assertFalse("Dispose must not close a preparation while copy IO is still using it",disposing.isCompleted)
            assertTrue("Held raw body must survive until that IO returns",held.all {File(f.root,it).exists()})
            releaseRead.countDown();disposing.join();owner.awaitAccepted()
            assertTrue(f.temporaries().isEmpty());assertArrayEquals(sourceBytes,source.readBytes())
            assertEquals(0,f.writes.get());assertEquals(0L,target.length())
        } finally {withContext(NonCancellable) {
            releaseRead.countDown();f.beforeRead=null;withContext(Dispatchers.Main) {copying.dispose()}.join();owner.awaitAccepted()
        }}
    }

    @Test fun aCurrentPreviewWhoseActualSlotOwnerRetiresLeavesLoadingWithARecoverableError()=scenario {f->
        val source=f.seed(f.primary,41.0);val sourceBytes=source.readBytes();val owner=f.owner(f.primary)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val prefix=requireNotNull(owner.submit {entered.complete(Unit);release.await()});entered.await()
        val accepted=owner.javaClass.getDeclaredField("lastAccepted").apply {isAccessible=true}
        val before=accepted.get(owner)
        val (controller,_)=f.newController()
        var loader:Job?=null
        try {
            loader=withContext(Dispatchers.Main) {
                controller.open(f.primary)
                assertEquals(RecoveryPreviewPhase.LOADING,controller.state!!.phase)
                val request=RecoveryPreviewController::class.java.getDeclaredField("current").apply {isAccessible=true}.get(controller)
                val requestJob=request.javaClass.getDeclaredField("job").apply {isAccessible=true}.get(request) as Job
                requestJob.children.toList().single()
            }
            withTimeout(5000L) {while(true) {owner.isCurrent();if(accepted.get(owner)!==before) break;delay(1)}}
            withContext(Dispatchers.Main) {
                // A real replacement Editor announces the physical slot; the preview host itself
                // remains alive/current and has not cancelled its own request.
                f.editors.add(Editor(f.context))
            }
            assertFalse(owner.isCurrent());release.complete(Unit);prefix.await();owner.awaitAccepted()
            requireNotNull(loader).join() // Observe the actual cancelled-loader terminal state, not a timeout guess.
            withContext(Dispatchers.Main) {
                val state=requireNotNull(controller.state)
                assertEquals("An owner-retirement cancellation must not leave a permanent spinner",RecoveryPreviewPhase.INVALID,state.phase)
                assertFalse(state.busy);assertFalse(state.error.isNullOrBlank())
            }
            assertArrayEquals(sourceBytes,source.readBytes());assertTrue(f.temporaries().isEmpty())
            assertEquals(0,f.reads.get());assertEquals(0,f.writes.get())
        } finally {withContext(NonCancellable) {
            release.complete(Unit);prefix.await();owner.awaitAccepted();loader?.join()
        }}
    }

    @Test fun aRetainedCreationRefusesAnUnrelatedResumePickerAndKeepsItsOriginalRetry()=scenario {f->
        val source=f.seed(f.primary,51.0);val sourceBytes=source.readBytes()
        val (controller,registry)=f.newController();f.ready(controller,f.primary)
        val target=File(f.root,"retained-original-copy.xnote").apply {writeBytes(byteArrayOf())}
        val uri=Uri.fromFile(target)
        val database=f.storage.registry.writableDatabase
        database.execSQL("CREATE TRIGGER fail_recovery_finish BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT,'injected completion'); END")
        try {
            withContext(Dispatchers.Main) {controller.requestCopy();registry.deliver(registry.launches.single(),uri)}
            f.awaitMain {controller.state?.hasPending==true && controller.state?.busy==false}
            assertEquals("The retained state must follow a real complete target write",1,f.writes.get())
            assertTrue(f.storage.hasRecovery(uri.toString()))
            val written=target.readBytes()
            assertEquals(51.0,(target.inputStream().use {f.codec.read(it)}.pages.single().items.single() as Stroke).xAt(0),.001)
            withContext(Dispatchers.Main) {
                val launches=registry.launches.size
                controller.requestResumePicker()
                assertFalse("An unrelated completion picker must not take over an existing typed retry",controller.pickerPending)
                assertEquals(launches,registry.launches.size)
                assertTrue(controller.state!!.hasPending);assertNull(controller.state!!.saved)
            }
            assertTrue(f.storage.hasRecovery(uri.toString()));assertArrayEquals(written,target.readBytes())
            database.execSQL("DROP TRIGGER fail_recovery_finish")
            withContext(Dispatchers.Main) {controller.completePending()}
            f.awaitMain {controller.state?.saved!=null && controller.state?.busy==false}
            assertFalse(withContext(Dispatchers.Main) {controller.state!!.hasPending})
            assertFalse(f.storage.hasRecovery(uri.toString()));assertEquals(1,f.writes.get())
            assertArrayEquals(written,target.readBytes());assertArrayEquals(sourceBytes,source.readBytes())
        } finally {database.execSQL("DROP TRIGGER IF EXISTS fail_recovery_finish")}
    }
}
