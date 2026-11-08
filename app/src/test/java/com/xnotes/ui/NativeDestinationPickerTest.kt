package com.xnotes.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
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
}
