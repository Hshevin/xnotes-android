package com.xnotes.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking a layer must happen on the tap itself. A double-tap handler on a row makes Compose hold
 * every single tap back by the double-tap timeout (~0.3 s) to rule out a second one.
 */
class LayerTapTest {
    private val panel: File = listOf(File("src/main/java/com/xnotes/ui/LayersPanel.kt"), File("app/src/main/java/com/xnotes/ui/LayersPanel.kt"))
        .first { it.exists() }

    @Test fun layerRowsHaveNoDoubleTapHandler() {
        val source = panel.readText()
        for (slow in listOf("combinedClickable", "onDoubleClick", "onDoubleTap")) {
            assertTrue("LayersPanel uses $slow: a double-tap handler delays every tap; rename from the row's more menu instead",
                slow !in source)
        }
    }
}
