package com.xnotes.nativelibs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract for the iOS native libraries (docs/movinkpad/process/ipados-migration-plan-2026-11-23.md, "W1-B").
 * Runs on iosSimulatorArm64.
 */
class NativeLibsSmokeTest {

    /** A one-page 200 × 100 pt PDF with a black rectangle covering x 50–150, y 25–75 (PDF space). */
    private fun miniPdf(): ByteArray {
        val content = "0 0 0 rg 50 25 100 50 re f"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents 4 0 R /Resources << >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
        )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, body ->
            offsets += out.length
            out.append("${i + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().encodeToByteArray()
    }

    @Test
    fun pdfiumOpensMeasuresAndRendersAPage() {
        val doc = assertNotNull(Pdfium.open(miniPdf()))
        try {
            assertEquals(1, doc.pageCount)
            val (w, h) = doc.pageSize(0)
            assertEquals(200.0, w, 0.01)
            assertEquals(100.0, h, 0.01)

            val pixels = doc.render(0, 200, 100)
            assertEquals(200 * 100, pixels.size)
            fun at(x: Int, y: Int) = pixels[y * 200 + x]
            // Device space is top-down; the rectangle is centred either way.
            assertEquals(0xFF000000.toInt(), at(100, 50), "centre should be black ink")
            assertEquals(0xFFFFFFFF.toInt(), at(5, 5), "corner should be the white page")
            assertEquals(0xFFFFFFFF.toInt(), at(195, 95), "corner should be the white page")
        } finally {
            doc.close()
        }
    }

    @Test
    fun pdfiumRejectsGarbage() {
        assertNull(Pdfium.open("not a pdf".encodeToByteArray()))
    }

    @Test
    fun treeSitterCarriesEveryBundledGrammar() {
        val expected = setOf(
            "asm", "bash", "c", "cpp", "css", "diff", "dockerfile", "go", "html", "java", "javascript",
            "json", "kotlin", "lua", "php", "python", "rust", "toml", "typescript", "xml", "yaml",
        )
        val missing = expected - TreeSitter.languages()
        assertTrue(missing.isEmpty(), "missing grammars: $missing")
    }

    @Test
    fun treeSitterParsesKotlinAndJson() {
        val kotlin = assertNotNull(TreeSitter.parseSExpression("kotlin", "fun main() { println(1) }"))
        assertTrue(kotlin.startsWith("(source_file"), kotlin)
        assertTrue("function_declaration" in kotlin, kotlin)

        val json = assertNotNull(TreeSitter.parseSExpression("json", """{"a": [1, 2]}"""))
        assertTrue(json.startsWith("(document"), json)
        assertTrue("array" in json, json)

        assertNull(TreeSitter.parseSExpression("no-such-language", "x"))
    }
}
