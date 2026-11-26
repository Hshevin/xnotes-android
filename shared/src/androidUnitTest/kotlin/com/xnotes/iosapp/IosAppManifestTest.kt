package com.xnotes.iosapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * W2-3 contract (docs/movinkpad/process/ipados-wave2-contracts-2026-11-24.md, "W2-3"): the iPad app's Info.plist
 * and project settings carry the decisions in the migration plan. Plain-text checks so they run on the JVM.
 */
class IosAppManifestTest {

    private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val plist = File(repo, "iosApp/XNotes/Info.plist").readText()
    private val project = File(repo, "iosApp/XNotes.xcodeproj/project.pbxproj").readText()

    private fun boolKey(key: String): Boolean? =
        Regex("<key>$key</key>\\s*<(true|false)/>").find(plist)?.groupValues?.get(1)?.toBooleanStrict()

    private fun stringKey(key: String): String? =
        Regex("<key>$key</key>\\s*<string>([^<]*)</string>").find(plist)?.groupValues?.get(1)

    @Test
    fun documentsAreVisibleInTheFilesApp() {
        assertEquals(true, boolKey("UIFileSharingEnabled"))
        assertEquals(true, boolKey("LSSupportsOpeningDocumentsInPlace"))
    }

    @Test
    fun theAppIsNamedXNotesAndRunsOnIPadOnly() {
        assertEquals("XNotes", stringKey("CFBundleDisplayName"))
        assertTrue(Regex("PRODUCT_BUNDLE_IDENTIFIER = \"?com\\.xnotes\\.ipad\"?;").containsMatchIn(project))
        // Device family 2 is iPad; 1 would add iPhone.
        val families = Regex("TARGETED_DEVICE_FAMILY = \"?([0-9,]+)\"?;").findAll(project).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("2"), families)
    }

    @Test
    fun theMinimumSystemIsIPadOS18() {
        val targets = Regex("IPHONEOS_DEPLOYMENT_TARGET = ([0-9.]+);").findAll(project).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("18.0"), targets)
    }

    @Test
    fun theAppSupportsMultipleWindowsAndAllOrientations() {
        assertTrue(Regex("<key>UIApplicationSupportsMultipleScenes</key>\\s*<true/>").containsMatchIn(plist))
        for (o in listOf("Portrait", "PortraitUpsideDown", "LandscapeLeft", "LandscapeRight")) {
            assertTrue(o, "UIInterfaceOrientation$o" in plist)
        }
    }

    @Test
    fun noSigningTeamOrSecretIsCommitted() {
        // A free Apple ID's team id is personal; signing is chosen locally in Xcode, not committed.
        assertTrue(Regex("DEVELOPMENT_TEAM = \"?\"?;").containsMatchIn(project) || "DEVELOPMENT_TEAM" !in project)
    }
}
