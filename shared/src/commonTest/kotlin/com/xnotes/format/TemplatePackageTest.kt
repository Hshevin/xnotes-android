package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.openRead
import okio.*
import com.xnotes.core.template.TemplateKind
import com.xnotes.core.testkit.assertEquals
import com.xnotes.core.testkit.assertFalse
import kotlin.test.assertFailsWith
import com.xnotes.core.testkit.assertTrue
import kotlin.test.Test

class TemplatePackageTest : BundleTestFiles() {

    private fun temp(bytes: ByteArray = byteArrayOf()): AssetFile =
        testFile("tpk", null).apply { setBytes(bytes) }

    private val json = """{ "xtemplate": 1, "name": "Photo", "kind": "cover", "items": [
        { "type": "image", "src": "images/a.jpg", "fit": "cover" } ] }"""

    @Test fun aPackageIsToldFromPlainJsonByItsFirstBytes() {
        assertTrue(TemplatePackage.isPackage(byteArrayOf(0x50, 0x4b, 3, 4)))
        assertFalse(TemplatePackage.isPackage("{ }".encodeToByteArray()))
        assertFalse(TemplatePackage.isPackage(temp(byteArrayOf(0x50))))
    }

    @Test fun aWrittenPackageReadsBackItsJsonAndPictures() {
        val picture = temp(byteArrayOf(1, 2, 3, 4, 5))
        val pkg = temp()
        TemplatePackage.write(pkg, json, listOf("images/a.jpg" to picture))

        assertTrue(TemplatePackage.isPackage(pkg))
        assertEquals(json, TemplatePackage.readJson(pkg))
        val out = temp()
        assertTrue(TemplatePackage.extract(pkg, "images/a.jpg", out))
        assertTrue(out.fileBytes().contentEquals(picture.fileBytes()))
        assertFalse(TemplatePackage.extract(pkg, "images/missing.jpg", temp()))
        assertEquals(TemplateKind.COVER, TemplateReader.read(TemplatePackage.readJson(pkg)).kind)
    }

    @Test fun picturesAreStoredAndTheJsonIsDeflated() {
        val pkg = temp()
        TemplatePackage.write(pkg, json, listOf("images/a.jpg" to temp(byteArrayOf(9, 9))))
        bundleDirectory(pkg).use { zip ->
            assertEquals(8, zip.entry("template.json")!!.method)
            assertEquals(0, zip.entry("images/a.jpg")!!.method)
        }
    }

    @Test fun onlyRelativePathsWithoutParentSegmentsAreAllowed() {
        assertTrue(TemplatePackage.validSrc("images/a.jpg"))
        assertTrue(TemplatePackage.validSrc("images/..a.jpg"))
        assertFalse(TemplatePackage.validSrc(""))
        assertFalse(TemplatePackage.validSrc("/etc/passwd"))
        assertFalse(TemplatePackage.validSrc("../a.jpg"))
        assertFalse(TemplatePackage.validSrc("images/../../a.jpg"))
    }

    @Test fun aPackageWithoutTemplateJsonOrTooBigIsRefused() {
        val pkg = temp()
        bundleWriter(pkg.fileSink()).use { zos ->
            zos.putDeflated("other.json","{}".encodeToByteArray())
        }
        assertFailsWith<TemplateFormatException> { TemplatePackage.readJson(pkg) }

        val big = temp()
        com.xnotes.kio.openReadWrite(big.assetPath()).use { it.truncate(TemplatePackage.MAX_BYTES + 1) }
        assertFailsWith<TemplateFormatException> { TemplatePackage.readJson(big) }
        assertFailsWith<TemplateFormatException> { TemplatePackage.readJson(temp("PK not a zip".encodeToByteArray())) }
    }

    @Test fun kindReadsCoverAndEverythingElseAsPage() {
        fun kindOf(v: String?) = TemplateReader.read(
            """{ "xtemplate": 1, "name": "x", ${if (v == null) "" else "\"kind\": $v,"} "items": [] }""",
        ).kind
        assertEquals(TemplateKind.COVER, kindOf("\"cover\""))
        assertEquals(TemplateKind.PAGE, kindOf("\"page\""))
        assertEquals(TemplateKind.PAGE, kindOf("\"spine\""))
        assertEquals(TemplateKind.PAGE, kindOf("3"))
        assertEquals(TemplateKind.PAGE, kindOf(null))
    }
}
