package com.xnotes.core.search

expect fun interface SearchRunnable { fun run() }
expect fun interface SearchExecutor { fun execute(command: SearchRunnable) }
internal expect fun monotonicNanos(): Long

internal enum class Normalization { NFD, NFC, NFKC }
internal expect fun normalize(text: String, form: Normalization): String
internal expect fun accentScript(base: Char): Boolean
internal expect fun category(code: Int): CharCategory
internal expect fun letterOrDigit(code: Int): Boolean
internal fun unicodeSpace(code: Int): Boolean = code in 0..0xffff && code.toChar().isWhitespace()
