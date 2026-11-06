package com.xnotes.mindmap

import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MapInputHostTest : MapInputContract() {
    override fun ui(block: (Context) -> Unit) { block(RuntimeEnvironment.getApplication()) }
}
