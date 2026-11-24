package com.xnotes.pen

import com.xnotes.ui.Editor

/** Type [text] into the text box open on the page, as the keyboard does: one edit through its caret. */
internal fun Editor.typeInOpenBox(text: String) {
    flowText.replaceExternal(flowText.selection, text)
}
