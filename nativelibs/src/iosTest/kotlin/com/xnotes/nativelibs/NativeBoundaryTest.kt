package com.xnotes.nativelibs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeBoundaryTest {
    private fun redPage(): ByteArray = buildString {
        append("%PDF-1.4\n")
        val paint = "1 0 0 rg 0 0 2 2 re f"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 2 2] /Contents 4 0 R /Resources << >> >>",
            "<< /Length ${paint.length} >>\nstream\n$paint\nendstream",
        )
        val offsets = objects.mapIndexed { index, body -> length.also { append("${index + 1} 0 obj\n$body\nendobj\n") } }
        val xref = length
        append("xref\n0 5\n0000000000 65535 f \n")
        offsets.forEach { append("${it.toString().padStart(10, '0')} 00000 n \n") }
        append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    }.encodeToByteArray()

    @Test
    fun pdfOwnsItsInputAndReturnsArgb() {
        val bytes = redPage()
        val doc = assertNotNull(Pdfium.open(bytes))
        try {
            bytes.fill(0)
            assertTrue(doc.render(0, 2, 2).all { it == 0xffff0000.toInt() })
            assertFailsWith<IllegalArgumentException> { doc.pageSize(1) }
            assertFailsWith<IllegalArgumentException> { doc.render(0, Int.MAX_VALUE, 2) }
        } finally {
            doc.close()
        }
        doc.close()
        assertFailsWith<IllegalStateException> { doc.pageCount }
    }

    @Test
    fun allGrammarsAcceptTheBundledRuntime() {
        for (language in TreeSitter.languages()) {
            assertNotNull(TreeSitter.parseSExpression(language, ""), language)
        }
        assertEquals("(document (array (string (string_content)) (number)))",
            TreeSitter.parseSExpression("json", "[\"字\",42]"))
    }
}
