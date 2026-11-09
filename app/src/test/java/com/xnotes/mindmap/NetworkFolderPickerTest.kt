package com.xnotes.mindmap

import android.app.Activity
import android.content.Context
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import androidx.compose.runtime.mutableStateOf
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** Raw Intent results exercise OpenDocumentTree.parseResult and the registry's real request mapping. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkFolderPickerTest {
    private class Registry(private val context:Context):ActivityResultRegistry() {
        val requests=mutableListOf<Int>()
        val intents=mutableListOf<Intent>()
        var reject=false
        override fun <I,O> onLaunch(code:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
            if(reject) throw IllegalStateException("Picker unavailable")
            requests+=code;intents+=contract.createIntent(context,input)
        }
        fun raw(code:Int,uri:Uri?)=dispatchResult(code,if(uri==null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            uri?.let {Intent().setData(it).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)})
    }
    private val folder=Uri.parse("content://folders/tree/root%2Fchosen")

    @Test fun aRetiredDraftKeepsTheOriginalSlotUntilItsRawResultIsConsumed() {
        val registry=Registry(RuntimeEnvironment.getApplication())
        val picker=NetworkFolderPicker(registry)
        val a=mutableListOf<Uri?>();val b=mutableListOf<Uri?>()
        try {
            assertTrue(picker.launch {a+=it});assertTrue(picker.busy)
            val first=registry.requests.single()
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE,registry.intents.single().action)
            picker.retire()
            assertTrue(picker.busy);assertFalse(picker.launch {b+=it})
            assertTrue(registry.raw(first,folder));assertFalse(picker.busy)
            assertTrue(a.isEmpty());assertTrue(b.isEmpty())
            assertTrue(picker.launch {b+=it})
            val second=registry.requests.last();assertNotEquals(first,second)
            assertTrue(registry.raw(second,null));assertEquals(listOf<Uri?>(null),b);assertFalse(picker.busy)
            assertTrue(picker.launch {b+=it})
            assertTrue(registry.raw(registry.requests.last(),folder));assertEquals(listOf(null,folder),b)
            assertFalse(picker.busy)
        } finally {picker.dispose()}
    }

    @Test fun reconstructedHostsNeverAttachANewCallbackToAnOldRawRequestCodeAndLaunchFailuresReleaseOnlyTheirSlot() {
        val context=RuntimeEnvironment.getApplication()
        val oldRegistry=Registry(context);val old=NetworkFolderPicker(oldRegistry)
        var oldCalls=0
        assertTrue(old.launch {oldCalls++})
        val oldCode=oldRegistry.requests.single()
        val saved=Bundle().also(oldRegistry::onSaveInstanceState)
        old.dispose();assertFalse(old.busy)
        val registry=Registry(context).apply {onRestoreInstanceState(saved)}
        val fresh=NetworkFolderPicker(registry)
        val results=mutableListOf<Uri?>()
        try {
            registry.reject=true
            assertFalse(fresh.launch {results+=it});assertFalse(fresh.busy)
            registry.reject=false
            assertTrue(fresh.launch {results+=it})
            val current=registry.requests.single();assertNotEquals(oldCode,current)
            assertTrue(registry.raw(oldCode,folder))
            assertEquals(0,oldCalls);assertTrue(results.isEmpty());assertTrue(fresh.busy)
            assertTrue(registry.raw(current,folder));assertEquals(listOf(folder),results);assertFalse(fresh.busy)
            fresh.dispose()
            assertFalse(fresh.launch {fail("Disposed host cannot receive a result")})
        } finally {fresh.dispose()}
    }

    @Test fun cancelingTheActualFolderLookupCancelsTheProviderSignalAndCannotPublishItsLateName() {
        val context=RuntimeEnvironment.getApplication()
        val authority="network-folder-cancel-${UUID.randomUUID()}"
        val uri=DocumentsContract.buildTreeDocumentUri(authority,"root")
        val entered=CompletableDeferred<CancellationSignal?>()
        val closed=AtomicBoolean()
        val provider=object:ContentProvider() {
            override fun onCreate()=true
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?)=query(uri,p,s,a,sort,null)
            override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,sort:String?,signal:CancellationSignal?):Cursor {
                entered.complete(signal)
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                while(signal?.isCanceled!=true && System.nanoTime()<deadline) Thread.yield()
                // A provider may return a final cursor even after cancellation. Its name is not a UI result.
                return object:MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) {
                    init {addRow(arrayOf("Late directory"))}
                    override fun close() {closed.set(true);super.close()}
                }
            }
            override fun getType(uri:Uri)=DocumentsContract.Document.MIME_TYPE_DIR
            override fun insert(uri:Uri,values:ContentValues?):Uri?=error("Metadata only")
            override fun update(uri:Uri,values:ContentValues?,s:String?,a:Array<out String>?)=error("Metadata only")
            override fun delete(uri:Uri,s:String?,a:Array<out String>?)=error("Metadata only")
        }
        provider.attachInfo(context,ProviderInfo().apply {this.authority=authority})
        ShadowContentResolver.registerProviderInternal(authority,provider)
        val draftFolder=mutableStateOf<NetworkFolder?>(null)
        runBlocking {
            val lookup=launch(Dispatchers.Default) {draftFolder.value=readNetworkFolder(context,uri,"Folder")}
            try {
                val signal=withTimeout(10000) {entered.await()}
                assertNotNull("The real provider receives a cancellable metadata query",signal)
                lookup.cancelAndJoin()
                assertTrue(signal!!.isCanceled);assertTrue(closed.get())
                assertNull("A canceled lookup cannot update the filter draft with its late name",draftFolder.value)
            } finally {lookup.cancelAndJoin()}
        }
    }
}
