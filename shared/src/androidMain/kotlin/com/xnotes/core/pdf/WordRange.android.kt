package com.xnotes.core.pdf

import java.text.BreakIterator

internal actual fun wordRange(text: String, at: Int): IntRange? {
    val words = BreakIterator.getWordInstance().apply { setText(text) }
    val end = words.following(at)
    val start = if (words.isBoundary(at)) at else words.preceding(at)
    return (start until end).takeIf { text.substring(start, end).codePoints().anyMatch(Character::isLetterOrDigit) }
}
