@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.edit

import kotlinx.cinterop.useContents
import platform.Foundation.*

internal actual fun characterSpan(text: String, index: Int): IntRange =
    (text as NSString).rangeOfComposedCharacterSequenceAtIndex(index.toULong()).useContents {
        location.toInt() until (location + length).toInt()
    }
