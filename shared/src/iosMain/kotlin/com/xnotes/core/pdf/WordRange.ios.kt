@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.xnotes.core.pdf

import kotlinx.cinterop.useContents
import platform.NaturalLanguage.NLTokenizer
import platform.NaturalLanguage.NLTokenUnit.NLTokenUnitWord

internal actual fun wordRange(text: String, at: Int): IntRange? {
    val tokenizer = NLTokenizer(NLTokenUnitWord)
    tokenizer.string = text
    return tokenizer.tokenRangeAtIndex(at.toULong()).useContents {
        (location.toInt() until (location + length).toInt()).takeIf { at in it }
    }
}
