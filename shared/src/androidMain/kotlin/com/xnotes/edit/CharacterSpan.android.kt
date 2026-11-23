package com.xnotes.edit

import java.text.BreakIterator

internal actual fun characterSpan(text: String, index: Int): IntRange =
    BreakIterator.getCharacterInstance().run { setText(text); preceding(index + 1) until following(index) }
