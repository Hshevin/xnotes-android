package com.xnotes.mindmap

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import com.xnotes.ui.DestinationPickerState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkCanvasPickerTest {
    @Test fun restoredCreationKeepsItsRegistryKeyAndRetainsTheUriWithoutAcquiringANewFlow() {
        val context=RuntimeEnvironment.getApplication();val codes=mutableListOf<Int>()
        fun registry()=object:ActivityResultRegistry() {
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {codes+=requestCode}
        }
        val saved=DestinationPickerState();val originalRegistry=registry()
        val first=NetworkCanvasPicker(originalRegistry,saved);var callbacks=0
        assertTrue(first.launch(true,"Restored.xmap") {callbacks++})
        val original=requireNotNull(saved.pending);val bundle=Bundle();originalRegistry.onSaveInstanceState(bundle)
        first.dispose()
        val restoredRegistry=registry().also {it.onRestoreInstanceState(bundle)}
        val next=NetworkCanvasPicker(restoredRegistry,saved)
        val uri=Uri.parse("content://targets/document/restored.xmap")
        try {
            assertTrue(next.busy);assertEquals(1,codes.size)
            assertFalse(next.launch(false,"unused") {fail("Wrong flow")})
            assertTrue(restoredRegistry.dispatchResult(codes.single(),Activity.RESULT_OK,Intent().setData(uri)))
            assertFalse(next.busy);assertEquals(0,callbacks)
            assertEquals(original,saved.interrupted?.request);assertEquals(uri.toString(),saved.interrupted?.uri)
        } finally {next.dispose()}
    }
    @Test fun nativeMapCreationDoesNotAskTheProviderToAppendAJsonExtension() {
        var intent:Intent?=null
        val registry=object:ActivityResultRegistry() {
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
                intent=contract.createIntent(RuntimeEnvironment.getApplication(),input)
            }
        }
        val picker=NetworkCanvasPicker(registry)
        try {
            assertTrue(picker.launch(true,"New.xmap") {})
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent?.action)
            assertEquals("New.xmap",intent?.getStringExtra(Intent.EXTRA_TITLE))
            assertEquals("application/octet-stream",intent?.type)
        } finally {picker.dispose()}
    }
    @Test fun aRetiredOpenSlotCannotDeliverItsRawUriToANewCreateFlow() {
        val codes=mutableListOf<Int>();val intents=mutableListOf<Intent>()
        val registry=object:ActivityResultRegistry() {
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
                codes+=requestCode;intents+=contract.createIntent(RuntimeEnvironment.getApplication(),input)
            }
        }
        val picker=NetworkCanvasPicker(registry);var old=0;val fresh=mutableListOf<Uri?>()
        val uri=Uri.parse("content://targets/document/new.xmap")
        try {
            assertTrue(picker.launch(false,"unused") {old++});assertEquals(Intent.ACTION_OPEN_DOCUMENT,intents.single().action)
            picker.retire();assertTrue(picker.busy);assertFalse(picker.launch(true,"New.xmap") {fresh+=it})
            assertTrue(registry.dispatchResult(codes.single(),Activity.RESULT_OK,Intent().setData(uri)))
            assertEquals(0,old);assertFalse(picker.busy)
            assertTrue(picker.launch(true,"New.xmap") {fresh+=it});assertEquals(Intent.ACTION_CREATE_DOCUMENT,intents.last().action)
            assertNotEquals(codes.first(),codes.last())
            assertTrue(registry.dispatchResult(codes.last(),Activity.RESULT_CANCELED,null));assertEquals(listOf<Uri?>(null),fresh)
        } finally {picker.dispose()}
    }
}
