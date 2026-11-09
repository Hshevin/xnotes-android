package com.xnotes.mindmap

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class NetworkCanvasPickerTest {
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
