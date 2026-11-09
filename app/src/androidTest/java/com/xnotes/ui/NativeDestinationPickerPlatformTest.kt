package com.xnotes.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.core.app.ActivityOptionsCompat
import com.xnotes.pen.PenTestRunner
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

/** Real platform registry/bundle restoration; no claim of an actual process-kill or SAF provider test. */
class NativeDestinationPickerPlatformTest {
    private class NoViews:AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index:Int,instance:Unit)=Unit
        override fun insertBottomUp(index:Int,instance:Unit)=Unit
        override fun remove(index:Int,count:Int)=Unit
        override fun move(from:Int,to:Int,count:Int)=Unit
        override fun onClear()=Unit
    }
    private class Registry:ActivityResultRegistry() {
        val codes=mutableListOf<Int>()
        override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {codes+=requestCode}
    }
    private class Host(val registry:Registry,restored:Map<String,List<Any?>>?=null) {
        val saved=SaveableStateRegistry(restored) {true}
        private val recomposer=Recomposer(Dispatchers.Main.immediate)
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

    @Test fun restoredOutstandingRegistryResultIsConsumedWithoutRebindingTheRetiredOwner() {
        var failure:Throwable?=null
        PenTestRunner.instance.runOnMainSync {
            try {
                val first=Host(Registry());var oldCallbacks=0
                assertTrue(first.picker.launch("Original.xnote") {oldCallbacks++})
                val code=first.registry.codes.single();val state=Bundle()
                first.registry.onSaveInstanceState(state)
                val saved=first.saved.performSave();first.close()
                val next=Host(Registry().also {it.onRestoreInstanceState(state)},saved)
                try {
                    assertTrue("Original launch must be registered again after saved-state restoration",next.picker.busy)
                    assertTrue(next.registry.codes.isEmpty())
                    assertFalse(next.picker.launch("Replacement.xnote") {fail("Old result acquired a new owner")})
                    assertTrue(next.registry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(Uri.parse("content://picker/document/original"))))
                    assertFalse(next.picker.busy);assertEquals(0,oldCallbacks)
                } finally {next.close()}
            } catch(error:Throwable) {failure=error}
        }
        failure?.let {throw it}
    }

    @Test fun aRestoredOpenResultKeepsItsUriAndOriginalRequestForExplicitRetry() {
        var failure:Throwable?=null
        PenTestRunner.instance.runOnMainSync {
            try {
                val first=Host(Registry());var oldCallbacks=0
                assertTrue(first.picker.launchOpen(arrayOf("*/*")) {oldCallbacks++})
                val code=first.registry.codes.single();val request=requireNotNull(first.picker.saved.pending)
                val state=Bundle();first.registry.onSaveInstanceState(state)
                val saved=first.saved.performSave();first.close()
                val next=Host(Registry().also {it.onRestoreInstanceState(state)},saved)
                val uri=Uri.parse("content://picker/document/original-open")
                try {
                    assertTrue(next.picker.busy);assertTrue(next.registry.codes.isEmpty())
                    assertTrue(next.registry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(uri)))
                    assertFalse(next.picker.busy);assertEquals(0,oldCallbacks)
                    assertEquals(uri.toString(),next.picker.saved.interrupted?.uri)
                    assertEquals(request,next.picker.saved.interrupted?.request)
                } finally {next.close()}
            } catch(error:Throwable) {failure=error}
        }
        failure?.let {throw it}
    }
}
