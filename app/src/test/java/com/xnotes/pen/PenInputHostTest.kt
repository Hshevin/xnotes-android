package com.xnotes.pen

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Android event semantics without APK packaging; does not replace device instrumentation. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PenInputHostTest : RecordedPenInputContract()
