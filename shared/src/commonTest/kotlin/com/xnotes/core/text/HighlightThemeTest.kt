package com.xnotes.core.text

import com.xnotes.core.testkit.assertEquals
import com.xnotes.core.testkit.assertNull
import kotlin.test.Test

class HighlightThemeTest {

    @Test
    fun neovimRootsTakeTheirSlotsColour() {
        val t = HighlightTheme.DARK
        assertEquals(t.colorFor("keyword"), t.colorFor("conditional"))
        assertEquals(t.colorFor("keyword"), t.colorFor("repeat"))
        assertEquals(t.colorFor("constant"), t.colorFor("boolean"))
        assertEquals(t.colorFor("number"), t.colorFor("float"))
        assertEquals(t.colorFor("function"), t.colorFor("method.call"))
    }

    @Test
    fun ourSlotsStillWinAndUnknownRootsStayPlain() {
        val t = HighlightTheme.LIGHT
        assertEquals(t.colorFor("keyword"), t.colorFor("keyword.conditional"))
        assertNull(t.colorFor("label"))
        assertNull(t.colorFor("none"))
    }
}
