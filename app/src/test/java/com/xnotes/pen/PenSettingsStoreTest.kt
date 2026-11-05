package com.xnotes.pen

import com.xnotes.platform.JsonStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PenSettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun bindingsSurviveNewStoreInstance() {
        val path = File(tmp.root, "settings.json")
        val value = JSONObject().put("pen_shortcuts", PenBindingsJson.write(listOf(PenShortcut("Button", listOf(PenTrigger(PenTrigger.Kind.MOTION, 4, "", 0)), PenBinding("undo", PenBindingMode.SELECT)))))
        assertTrue(JsonStore(path).writeChecked(value))
        assertEquals("undo", PenBindingsJson.read(JsonStore(path).read()?.optJSONArray("pen_shortcuts")).single().binding.action)
    }

    @Test fun replacementFailureDoesNotDeletePreviousDestination() {
        val destination = tmp.newFolder("settings.json")
        val previous = File(destination, "keep").apply { writeText("previous content") }
        assertFalse(JsonStore(destination).writeChecked(JSONObject().put("new", true)))
        assertEquals("previous content", previous.readText())
        assertFalse(File(tmp.root, "settings.json.pending").exists())
    }
}
