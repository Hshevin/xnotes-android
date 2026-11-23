package com.xnotes.core.search

import java.text.Normalizer

actual typealias SearchRunnable = java.lang.Runnable
actual typealias SearchExecutor = java.util.concurrent.Executor
internal actual fun monotonicNanos(): Long = System.nanoTime()
internal actual fun normalize(text: String, form: Normalization): String = Normalizer.normalize(text, Normalizer.Form.valueOf(form.name))
internal actual fun accentScript(base: Char): Boolean = when (Character.UnicodeScript.of(base.code)) {
    Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK, Character.UnicodeScript.HEBREW, Character.UnicodeScript.ARABIC -> true
    else -> false
}
private val categories = CharCategory.entries.associateBy { it.value }
internal actual fun category(code: Int): CharCategory = categories.getValue(Character.getType(code))
internal actual fun letterOrDigit(code: Int): Boolean = Character.isLetterOrDigit(code)
