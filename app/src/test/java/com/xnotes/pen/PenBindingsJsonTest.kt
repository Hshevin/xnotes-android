package com.xnotes.pen

import com.xnotes.settings.Preferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PenBindingsJsonTest {
    private val shortcuts = listOf(PenShortcut("My pen", listOf(
        PenTrigger(PenTrigger.Kind.MOTION, 128, "digitizer", 16386),
        PenTrigger(PenTrigger.Kind.KEY, 600, "remote", 257, 42)), PenBinding("eraser")))

    @Test fun recordedAliasesAndBehaviorSurviveRoundTrip() {
        assertEquals(shortcuts, PenBindingsJson.read(JSONArray(PenBindingsJson.write(shortcuts).toString())))
    }
    @Test fun missingOrMalformedSettingsAreEmpty() {
        assertTrue(PenBindingsJson.read(null).isEmpty())
        assertTrue(PenBindingsJson.read(JSONArray("[{}]")).isEmpty())
    }
    @Test fun oldProPenSettingsAreNotMigrated() {
        val old = JSONObject("""{"pro_pen_bindings":{"32":{"action":"eraser","mode":"HOLD"}}}""")
        assertTrue(Preferences.fromJson(old).penShortcuts.isEmpty())
        assertFalse(Preferences.fromJson(old).toJson().has("pro_pen_bindings"))
    }
    @Test fun duplicateTriggersAndTooManyMappingsAreRejected() {
        assertFalse(PenShortcut.valid(shortcuts + shortcuts))
        assertFalse(PenShortcut.valid((1..33).map { PenShortcut("Key $it", listOf(PenTrigger(PenTrigger.Kind.KEY, it, "", 0))) }))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidMappingIsNotSilentlySaved() {
        PenBindingsJson.write(shortcuts + shortcuts)
    }
}
