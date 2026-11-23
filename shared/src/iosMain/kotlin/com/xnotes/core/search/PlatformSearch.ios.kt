@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.xnotes.core.search

import com.xnotes.core.util.codePointText
import kotlin.time.TimeSource
import platform.Foundation.*

actual fun interface SearchRunnable { actual fun run() }
actual fun interface SearchExecutor { actual fun execute(command: SearchRunnable) }
private val epoch = TimeSource.Monotonic.markNow()
internal actual fun monotonicNanos(): Long = epoch.elapsedNow().inWholeNanoseconds

internal actual fun normalize(text: String, form: Normalization): String = (text as NSString).let {
    when (form) {
        Normalization.NFD -> it.decomposedStringWithCanonicalMapping
        Normalization.NFC -> it.precomposedStringWithCanonicalMapping
        Normalization.NFKC -> it.precomposedStringWithCompatibilityMapping
    }
}

private fun regex(expression: String) = NSRegularExpression(expression, 0u, null)
private fun NSRegularExpression.matches(text: String) = numberOfMatchesInString(text, 0u, NSMakeRange(0u, text.length.toULong())) != 0uL
private val accentScripts = regex("[\\p{Latin}\\p{Greek}\\p{Hebrew}\\p{Arabic}]")
internal actual fun accentScript(base: Char): Boolean = accentScripts.matches(base.toString())
private val supplementaryCategories = CharCategory.entries.map { it to regex("\\p{${it.code}}") }
internal actual fun category(code: Int): CharCategory = if (code in 0..0xffff) code.toChar().category else {
    val text = codePointText(code)
    supplementaryCategories.first { it.second.matches(text) }.first
}
internal actual fun letterOrDigit(code: Int) = NSCharacterSet.letterCharacterSet.longCharacterIsMember(code.toUInt()) || NSCharacterSet.decimalDigitCharacterSet.longCharacterIsMember(code.toUInt())
