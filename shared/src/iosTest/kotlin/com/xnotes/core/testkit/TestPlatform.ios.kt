package com.xnotes.core.testkit

import okio.Path.Companion.toPath
import kotlin.native.identityHashCode

actual fun asset(path: String) = path.toPath()
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
actual fun identityHash(value: Any?) = value.identityHashCode()
actual fun isJavaWhitespace(codePoint: Int): Boolean = when (codePoint) {
    in 9..13, in 28..31 -> true
    0x00a0, 0x2007, 0x202f -> false
    in 0..0xffff -> when (codePoint.toChar().category) {
        CharCategory.SPACE_SEPARATOR, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR -> true
        else -> false
    }
    else -> false
}
