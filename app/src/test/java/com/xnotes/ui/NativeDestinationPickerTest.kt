package com.xnotes.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Uses real AndroidX contract parsing and registry request-code restoration, not typed callback dispatch. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NativeDestinationPickerTest {
    private class NoViews:AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index:Int,instance:Unit)=Unit
        override fun insertBottomUp(index:Int,instance:Unit)=Unit
        override fun remove(index:Int,count:Int)=Unit
        override fun move(from:Int,to:Int,count:Int)=Unit
        override fun onClear()=Unit
    }
    private class Host(val registry:Registry,restored:Map<String,List<Any?>>?=null) {
        val saved=SaveableStateRegistry(restored) {true}
        private val recomposer=Recomposer(Dispatchers.Unconfined)
        private val composition=Composition(NoViews(),recomposer)
        lateinit var picker:NativeDestinationPicker
        init {
            val owner=object:ActivityResultRegistryOwner {override val activityResultRegistry=registry}
            composition.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner,LocalSaveableStateRegistry provides saved) {
                    picker=rememberNativeDestinationPicker()
                }
            }
        }
        fun close() {composition.dispose();recomposer.cancel()}
    }
    private data class Launch(val code:Int,val name:String)
    private class Registry(private val context:Context):ActivityResultRegistry() {
        val launches=mutableListOf<Launch>()
        var failNext=false
        override fun <I,O> onLaunch(code:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
            val actual=contract.createIntent(context,input)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,actual.action)
            assertEquals("application/octet-stream",actual.type)
            launches+=Launch(code,requireNotNull(actual.getStringExtra(Intent.EXTRA_TITLE)))
            if(failNext) {failNext=false;throw IllegalStateException("picker unavailable")}
        }
        fun result(launch:Launch,uri:Uri?) {
            assertTrue(dispatchResult(launch.code,if(uri==null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                uri?.let {Intent().setData(it)}))
        }
    }

    @Test fun fixedCallbacksRejectBusyRequestsConsumeBeforeInvocationAndRecoverAfterActualLaunchFailure() {
        val registry=Registry(RuntimeEnvironment.getApplication())
        val picker=NativeDestinationPicker(registry)
        val a=mutableListOf<Uri?>();val b=mutableListOf<Uri?>();val c=mutableListOf<Uri?>()
        val d=mutableListOf<Uri?>();var refusedCallbacks=0
        val bUri=Uri.parse("content://native-picker/document/b")
        val dUri=Uri.parse("content://native-picker/document/d")
        try {
            assertFalse(picker.busy)
            assertTrue(picker.launch("a.xnote") {a+=it});assertTrue(picker.busy)
            assertFalse(picker.launch("refused.xnote") {refusedCallbacks++})
            assertEquals(listOf("a.xnote"),registry.launches.map {it.name})
            val first=registry.launches.single();registry.result(first,null)
            assertEquals(listOf<Uri?>(null),a);assertFalse(picker.busy)

            assertTrue(picker.launch("b.xcanvas") {uri->
                assertFalse("Consume old cell before its callback",picker.busy)
                b+=uri
                assertTrue(picker.launch("c.xnote") {c+=it})
            })
            val second=registry.launches.last();registry.result(second,bUri)
            assertEquals(listOf(bUri),b);assertTrue(picker.busy)
            assertEquals("c.xnote",registry.launches.last().name)
            // A duplicate old raw result must not bind to C's newer callback.
            registry.dispatchResult(second.code,Activity.RESULT_OK,Intent().setData(dUri))
            assertEquals(listOf(bUri),b);assertTrue(c.isEmpty());assertTrue(picker.busy)
            registry.result(registry.launches.last(),null)
            assertEquals(listOf<Uri?>(null),c);assertFalse(picker.busy)

            registry.failNext=true
            assertFalse(picker.launch("failed.xnote") {refusedCallbacks++})
            val failed=registry.launches.last();assertFalse(picker.busy)
            assertTrue(picker.launch("d.xnote") {d+=it});val last=registry.launches.last()
            assertNotEquals(failed.code,last.code)
            registry.dispatchResult(failed.code,Activity.RESULT_OK,Intent().setData(bUri))
            assertTrue(d.isEmpty());assertTrue(picker.busy)
            registry.result(last,dUri)
            assertEquals(listOf(dUri),d);assertFalse(picker.busy);assertEquals(0,refusedCallbacks)
        } finally {picker.dispose()}
    }

    @Test fun disposedAndRestoredOldRequestCodesNeverRebindToANewRuntimePickerCallback() {
        val context=RuntimeEnvironment.getApplication()
        val firstRegistry=Registry(context);val first=NativeDestinationPicker(firstRegistry)
        val a=mutableListOf<Uri?>();val b=mutableListOf<Uri?>()
        val aUri=Uri.parse("content://native-picker/document/old-a")
        val bUri=Uri.parse("content://native-picker/document/new-b")
        assertTrue(first.launch("old-a.xnote") {a+=it})
        val old=firstRegistry.launches.single();val saved=Bundle()
        firstRegistry.onSaveInstanceState(saved)
        first.dispose();assertFalse(first.busy)
        assertFalse(first.launch("closed.xnote") {a+=it})
        firstRegistry.dispatchResult(old.code,Activity.RESULT_OK,Intent().setData(aUri))
        assertTrue(a.isEmpty())

        val secondRegistry=Registry(context)
        secondRegistry.onRestoreInstanceState(saved)
        val second=NativeDestinationPicker(secondRegistry)
        try {
            assertTrue(second.launch("new-b.xcanvas") {b+=it})
            val fresh=secondRegistry.launches.single()
            assertNotEquals("Fresh runtime keys cannot reuse restored A's request code",old.code,fresh.code)
            assertTrue(second.busy)
            secondRegistry.result(old,aUri)
            assertTrue("A callback was dropped on disposal",a.isEmpty())
            assertTrue("Old A result cannot become B's destination",b.isEmpty());assertTrue(second.busy)
            secondRegistry.result(fresh,bUri)
            assertEquals(listOf(bUri),b);assertFalse(second.busy)
        } finally {second.dispose()}
    }

    @Test fun aSavedOutstandingRequestIsReregisteredAndConsumedAfterHostRecreation() {
        val context=RuntimeEnvironment.getApplication()
        val first=Host(Registry(context));var abandonedCallbacks=0
        assertTrue(first.picker.launch("unfinished.xnote") {abandonedCallbacks++})
        val launch=first.registry.launches.single();val registryState=Bundle()
        first.registry.onSaveInstanceState(registryState)
        val composeState=first.saved.performSave()
        first.close()

        val restoredRegistry=Registry(context).also {it.onRestoreInstanceState(registryState)}
        val restored=Host(restoredRegistry,composeState)
        try {
            assertTrue("Recreated host must retain the original outstanding registry slot",restored.picker.busy)
            assertFalse("New work cannot replace the outstanding system result",restored.picker.launch("other.xnote") {fail("Wrong owner")})
            assertTrue("Restoring must register, never launch the picker a second time",restoredRegistry.launches.isEmpty())
            restoredRegistry.result(launch,Uri.parse("content://native-picker/document/unfinished"))
            assertFalse("The restored result must be consumed instead of orphaned in the registry",restored.picker.busy)
            assertEquals("A disposed owner must not publish into its replacement",0,abandonedCallbacks)
            val retained=requireNotNull(restored.picker.saved.interrupted)
            assertEquals("unfinished.xnote",retained.request.name)
            assertEquals("content://native-picker/document/unfinished",retained.uri)
            assertTrue("Returned destination must itself survive another saved-state round",restored.saved.performSave().toString().contains(retained.uri))
        } finally {restored.close()}
    }

    @Test fun rejectedDestinationRetainsTheActualRequestAndNeverDeletesAnExistingEmptyFile() {
        val registry=Registry(RuntimeEnvironment.getApplication());val picker=NativeDestinationPicker(registry)
        val file=java.io.File.createTempFile("existing-native-picker-",".xnote",RuntimeEnvironment.getApplication().cacheDir)
        var current=true;var callbacks=0
        try {
            assertTrue(picker.launch("copy.xnote",isCurrent={current}) {callbacks++})
            val request=requireNotNull(picker.saved.pending);current=false
            registry.result(registry.launches.single(),Uri.fromFile(file))
            assertEquals(0,callbacks);assertFalse(picker.busy)
            assertEquals(InterruptedDestination(request,Uri.fromFile(file).toString()),picker.saved.interrupted)
            assertTrue("Empty existing provider targets are never deletion authority",file.exists());assertEquals(0L,file.length())
            assertFalse("A second request cannot overwrite the unacknowledged result",picker.launch("next.xnote") {})
        } finally {picker.dispose();file.delete()}
    }
}
