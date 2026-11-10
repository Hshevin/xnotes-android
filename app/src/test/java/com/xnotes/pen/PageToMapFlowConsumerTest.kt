package com.xnotes.pen

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat
import com.xnotes.mindmap.*
import com.xnotes.platform.LegacySessionQueue
import com.xnotes.storage.NotebookStorage
import com.xnotes.ui.Editor
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers

/** Real reading Editor → destination actor/history → provider, without a substitute source reader. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class PageToMapFlowConsumerTest {
    private class NoViews:AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index:Int,instance:Unit)=Unit
        override fun insertBottomUp(index:Int,instance:Unit)=Unit
        override fun remove(index:Int,count:Int)=Unit
        override fun move(from:Int,to:Int,count:Int)=Unit
        override fun onClear()=Unit
    }
    @Before fun eglHandles() {
        fun <T> empty(type:Class<T>):T=ReflectionHelpers.callConstructor(type,ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE,0L))
        if(EGL14.EGL_NO_DISPLAY==null) EGL14.EGL_NO_DISPLAY=empty(EGLDisplay::class.java)
        if(EGL14.EGL_NO_CONTEXT==null) EGL14.EGL_NO_CONTEXT=empty(EGLContext::class.java)
        if(EGL14.EGL_NO_SURFACE==null) EGL14.EGL_NO_SURFACE=empty(EGLSurface::class.java)
    }
    private class Maps(val root:File):ContentProvider() {
        var failWrite=false;var writes=0;var deletes=0
        fun file(uri:Uri)=File(root,requireNotNull(uri.lastPathSegment)+".xmap")
        override fun onCreate()=true
        override fun getType(uri:Uri)="application/json"
        override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,order:String?):Cursor=
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {addRow(arrayOf(file(uri).name,file(uri).length()))}
        override fun insert(uri:Uri,v:ContentValues?):Uri?=null
        override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
        override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int {deletes++;error("Reference flow must not delete a provider target")}
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            if('w' in mode) {if(failWrite) throw java.io.IOException("Target write denied");writes++}
            return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.parseMode(mode))
        }
    }
    private class FlowFixture(val source:EditorPageCopyFixture) {
        val context:Context=source.context
        val maps=Maps(source.root)
        private val authority="page-to-map-${UUID.randomUUID()}"
        val target=Uri.parse("content://$authority/document/graph")
        val empty=Uri.parse("content://$authority/document/new")
        val leases=mutableListOf<MapDocumentLease>()
        val controllers=mutableListOf<PageToMapController>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        init {
            maps.attachInfo(context,ProviderInfo().apply {this.authority=this@FlowFixture.authority})
            ShadowContentResolver.registerProviderInternal(authority,maps)
            maps.file(target).writeBytes(MapCodec.encode(MindMapDocument(nodes=listOf(MapNode(id="r",parent=null,text="Center"),
                MapNode(id="n",parent="r",text="Original concept")))))
            maps.file(empty).writeBytes(byteArrayOf())
        }
        suspend fun flow():PageToMapController {
            val page=source.editor.state.document.pages[1]
            assertTrue(source.editor.requestPageReference(page))
            return PageToMapController(context,requireNotNull(source.editor.pageReferenceRequest),scope).also {
                controllers+=it;it.start();await {it.step==PageToMapStep.DESTINATION}
            }
        }
        suspend fun lease():MapDocumentLease=MapDocuments.acquire(context,target).also {
            leases+=it;await {!it.session.loading};assertFalse(it.session.loadFailed)
        }
        suspend fun close() {
            source.beforeTargetRead=null;source.release.countDown();maps.failWrite=false
            controllers.forEach {it.close()?.join()};leases.forEach {it.close().join()}
            scope.cancel();scope.coroutineContext[Job]?.join()
        }
    }
    private fun scenario(block:suspend FlowFixture.()->Unit) {
        val root=File(RuntimeEnvironment.getApplication().cacheDir,"page-map-flow-${UUID.randomUUID()}").apply {mkdirs()}
        val source=EditorPageCopyFixture(RuntimeEnvironment.getApplication(),root)
        val singleton=NotebookStorage::class.java.getDeclaredField("instance").apply {isAccessible=true}
        val previous=singleton.get(null);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        var failure:Throwable?=null;var fixture:FlowFixture?=null
        val job=scope.launch {
            try {
                withContext(Dispatchers.IO) {source.seed(source.target)}
                withContext(Dispatchers.Main) {
                    singleton.set(null,source.storage);source.editor=Editor(source.context)
                    source.editor.openAsync(source.targetUri,"target.xnote");assertTrue(source.editor.noteOpen);source.stopDebounce()
                    fixture=FlowFixture(source);fixture!!.block()
                }
            } catch(error:Throwable) {failure=error}
            finally {withContext(NonCancellable) {
                withContext(Dispatchers.Main) {
                    fixture?.close()
                    runCatching {Editor::class.java.getDeclaredField("autosaveScope").apply {isAccessible=true}.get(source.editor) as CoroutineScope}
                        .getOrNull()?.let {it.cancel();it.coroutineContext[Job]?.join()}
                }
                runCatching {(Editor::class.java.getDeclaredField("sessionOwner").apply {isAccessible=true}.get(source.editor) as LegacySessionQueue.Owner).awaitAccepted()}
                singleton.set(null,previous);withContext(Dispatchers.IO) {source.storage.registry.close()}
            }}
        }
        try {
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(35)
            while(!job.isCompleted && System.nanoTime()<end) {
                // This headless consumer has no Recomposer to publish snapshotFlow changes.
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idle();Thread.yield()
            }
            assertTrue("Page-to-map consumer did not settle",job.isCompleted);failure?.let {throw it}
        } finally {source.release.countDown();scope.cancel();root.deleteRecursively()}
    }
    @Test fun readingPageMapCreationUsesTheNativeExtensionWithoutProviderJsonSuffix()=scenario {
        val registry=object:ActivityResultRegistry() {
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?)=Unit
        }
        val owner=object:ActivityResultRegistryOwner {override val activityResultRegistry=registry}
        val recomposer=Recomposer(Dispatchers.Main.immediate)
        val composition=Composition(NoViews(),recomposer)
        try {
            composition.setContent {
                CompositionLocalProvider(LocalContext provides context,LocalActivityResultRegistryOwner provides owner) {
                    PageToMapFlowHost(source.editor,{},{_,_->})
                }
            }
            // Inspect the contracts actually registered by the production host. No document or
            // destination is substituted, and neither picker needs to leave the process.
            val callbacksField=ActivityResultRegistry::class.java.declaredFields.single {it.name=="keyToCallback" || it.name=="mKeyToCallback"}.apply {isAccessible=true}
            val callbacks=callbacksField.get(registry) as Map<*,*>
            val intents=callbacks.values.map {callback->
                val field=callback!!.javaClass.declaredFields.single {ActivityResultContract::class.java.isAssignableFrom(it.type)}.apply {isAccessible=true}
                val contract=field.get(callback) as ActivityResultContract<*,*>
                if(contract is androidx.activity.result.contract.ActivityResultContracts.CreateDocument) contract.createIntent(context,"Referenced.xmap") else null
            }.filterNotNull()
            val creation=intents.single()
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,creation.action)
            assertEquals("Referenced.xmap",creation.getStringExtra(Intent.EXTRA_TITLE))
            assertEquals("application/octet-stream",creation.type)
        } finally {composition.dispose();recomposer.cancel()}
    }
    @Test fun theReadingPageUsesTheActualOpenMapAndMergesLateBodyEditsInOneUndoableOperation()=scenario {
        val existing=lease();val flow=flow();val sourcePage=source.editor.state.document.pages[1]
        val visibleDocument=source.editor.state.document
        assertTrue(flow.selectMap(target));await {flow.step==PageToMapStep.TARGET}
        assertSame(existing.session,flow.targetSession);assertTrue(flow.chooseExistingNode("n"))
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        source.beforeTargetRead={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        try {
            assertTrue(flow.confirm());withContext(Dispatchers.IO) {assertTrue(entered.await(5,TimeUnit.SECONDS))}
            assertTrue(existing.session.operate {it.update("n") {n->n.copy(text="Late body preserved")}})
            source.beforeTargetRead=null;release.countDown();await {flow.step==PageToMapStep.ADDED}
            val model=existing.session.document!!;val added=model.node("n").sources.single()
            assertEquals(sourcePage.referenceIdentity.id,added.pageId);assertEquals("Late body preserved",model.node("n").text)
            assertEquals("n",existing.session.selected);assertSame(visibleDocument,source.editor.state.document)
            assertTrue(source.editor.state.document.pages.any {it===sourcePage})
            existing.session.undo();assertEquals("Late body preserved",existing.session.document!!.node("n").text)
            assertTrue(existing.session.document!!.node("n").sources.isEmpty())
            existing.session.redo();assertEquals(added.id,existing.session.document!!.node("n").sources.single().id)
            assertTrue(existing.session.saveAttempt().await() is MapSaveOutcome.Written)
            assertEquals(added,MapCodec.decode(maps.file(target).readBytes()).node("n").sources.single())
        } finally {source.beforeTargetRead=null;release.countDown()}
    }
    @Test fun aNewGraphCanReceiveAFreeReferenceAndANonemptyCreateTargetIsNeverOverwritten()=scenario {
        val flow=flow();val before=maps.file(target).readBytes()
        assertTrue(flow.createMap(target));await {flow.step==PageToMapStep.TARGET_ERROR}
        assertArrayEquals(before,maps.file(target).readBytes());assertEquals(0,maps.writes);assertEquals(0,maps.deletes)
        assertTrue(flow.createMap(empty));await {flow.step==PageToMapStep.TARGET}
        assertTrue(flow.chooseNewReference(false,"Independent reference"));assertTrue(flow.confirm())
        await {flow.step==PageToMapStep.ADDED}
        val session=flow.targetSession!!;val node=session.document!!.nodes.single {it.kind==NodeKind.REFERENCE}
        assertTrue(node.free);assertNull(node.parent);assertEquals("Independent reference",node.text);assertEquals(1,node.sources.size)
        assertEquals(node.id,session.selected);assertTrue(session.canUndo)
        session.undo();assertTrue(session.document!!.nodes.none {it.id==node.id})
        session.redo();assertEquals(node,session.document!!.node(node.id))
        assertTrue(session.saveAttempt().await() is MapSaveOutcome.Written)
        assertEquals(node,MapCodec.decode(maps.file(empty).readBytes()).node(node.id))
    }
    @Test fun aFailedGraphSaveRetainsTheAddedSourceAndRetryDoesNotAppendItTwice()=scenario {
        val flow=flow();assertTrue(flow.selectMap(target));await {flow.step==PageToMapStep.TARGET}
        assertTrue(flow.chooseExistingNode("n"));maps.failWrite=true;assertTrue(flow.confirm())
        await {flow.step==PageToMapStep.SAVE_FAILED}
        val session=flow.targetSession!!;val retained=session.document!!.node("n").sources.single()
        assertTrue(session.recoverySafe);assertEquals("Original concept",session.document!!.node("n").text)
        maps.failWrite=false;assertTrue(flow.retrySave());await {flow.step==PageToMapStep.ADDED}
        assertEquals(listOf(retained.id),session.document!!.node("n").sources.map {it.id})
        assertEquals(retained.id,MapCodec.decode(maps.file(target).readBytes()).node("n").sources.single().id)
    }
    @Test fun aReloadedDestinationRejectsTheOldConfirmationAndCancelBeforeConfirmLeavesBothDocumentsUntouched()=scenario {
        val existing=lease();val flow=flow();val before=maps.file(target).readBytes()
        assertTrue(flow.selectMap(target));await {flow.step==PageToMapStep.TARGET}
        assertTrue(flow.chooseExistingNode("n"))
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        source.beforeTargetRead={entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
        try {
            assertTrue(flow.confirm());withContext(Dispatchers.IO) {assertTrue(entered.await(5,TimeUnit.SECONDS))}
            existing.session.useFileVersion();await {!existing.session.saving && !existing.session.replacingDocument}
            source.beforeTargetRead=null;release.countDown();await {flow.step==PageToMapStep.TARGET_ERROR}
            assertTrue(existing.session.document!!.node("n").sources.isEmpty());assertArrayEquals(before,maps.file(target).readBytes())
        } finally {source.beforeTargetRead=null;release.countDown()}
        flow.close()?.join()
        val next=this.flow();assertTrue(next.selectMap(target));await {next.step==PageToMapStep.TARGET}
        assertTrue(next.chooseNewReference(true,"Cancelled branch"));next.close()?.join()
        assertTrue(existing.session.document!!.node("n").sources.isEmpty());assertEquals(2,existing.session.document!!.nodes.size)
        assertArrayEquals(before,maps.file(target).readBytes());assertEquals(0,maps.deletes)
    }
    @Test fun aTargetReloadAfterChoosingANodeCannotGiveTheOldConfirmationANewTicket()=scenario {
        val existing=lease();val flow=flow()
        assertTrue(flow.selectMap(target));await {flow.step==PageToMapStep.TARGET}
        assertTrue(flow.chooseExistingNode("n"))
        val before=maps.file(target).readBytes()
        existing.session.useFileVersion();await {!existing.session.saving && !existing.session.replacingDocument}
        assertFalse("A previous choice cannot re-sign itself for a reloaded document",flow.confirm())
        assertTrue(existing.session.document!!.node("n").sources.isEmpty())
        assertArrayEquals(before,maps.file(target).readBytes())
    }
    @Test fun oldTargetRowsAndOldConfirmationActionsCannotSelectOrCommitAfterANewChoice()=scenario {
        val flow=flow();assertTrue(flow.selectMap(target));await {flow.step==PageToMapStep.TARGET}
        val oldContext=flow.targetContext
        assertTrue(flow.changeMap());assertTrue(flow.selectMap(target));await {flow.step==PageToMapStep.TARGET}
        assertFalse(flow.chooseExistingNode("n",oldContext));assertNull(flow.destination)
        assertTrue(flow.chooseExistingNode("n"))
        assertTrue(flow.chooseNewReference(true,"First title"));val oldConfirm=flow.confirmationToken
        assertTrue(flow.renameNewReference("Final title"));assertFalse(flow.confirm(oldConfirm))
        assertEquals(2,flow.targetSession!!.document!!.nodes.size)
        assertTrue(flow.confirm());await {flow.step==PageToMapStep.ADDED}
        val added=flow.targetSession!!.document!!.nodes.single {it.kind==NodeKind.REFERENCE}
        assertEquals("Final title",added.text);assertEquals(1,added.sources.size);assertFalse(added.free)
        assertEquals("n",added.parent)
    }
    @Test fun oldSaveAndOpenActionsNeverActOnTheReadingPanesReplacementDocument()=scenario {
        val flow=flow();assertTrue(flow.selectMap(target));await {flow.step==PageToMapStep.TARGET}
        val uri=flow.targetUri;val context=flow.targetContext;var opens=0;var saves=0
        source.editor.newNote();await {source.editor.currentUri==null}
        val replacement=source.editor.state.document
        assertFalse(flow.openMap(uri,context) {opens++});assertEquals(0,opens)
        flow.close()?.join()
        assertTrue(source.editor.requestPageReference(replacement.pages.first()))
        val saving=PageToMapController(this.context,requireNotNull(source.editor.pageReferenceRequest),scope).also {controllers+=it;it.start()}
        await {saving.step==PageToMapStep.SAVE_REQUIRED}
        source.editor.newNote();await {source.editor.state.document!==replacement}
        val newest=source.editor.state.document
        assertFalse(saving.saveSource {saves++});assertEquals(0,saves)
        assertSame(newest,source.editor.state.document);assertNull(newest.path)
        assertTrue(source.editor.requestPageReference(newest.pages.first()))
        val current=PageToMapController(this.context,requireNotNull(source.editor.pageReferenceRequest),scope).also {controllers+=it;it.start()}
        await {current.step==PageToMapStep.SAVE_REQUIRED}
        assertTrue(current.saveSource {saves++});assertEquals(1,saves)
    }
    companion object {
        private suspend fun await(check:()->Boolean) {
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(!check()) {kotlin.check(System.nanoTime()<until) {"Page-to-map state did not settle"};withContext(Dispatchers.IO) {Thread.sleep(4)}}
        }
    }
}
