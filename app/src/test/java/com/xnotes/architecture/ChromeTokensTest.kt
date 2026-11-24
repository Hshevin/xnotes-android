package com.xnotes.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chrome takes its text sizes and gaps from `ui/theme/Tokens.kt` and draws no decorative shadow.
 * A merge from upstream brings literal sizes and shadows with every redesigned screen; this keeps
 * them from landing unnoticed.
 */
class ChromeTokensTest {
    private val root: File = listOf(File("src/main/java/com/xnotes"), File("app/src/main/java/com/xnotes"))
        .first { it.isDirectory }

    private fun chromeSources(): Sequence<File> =
        File(root, "ui").walkTopDown().filter { it.isFile && it.extension == "kt" } + sequenceOf(File(root, "EditorActivity.kt"))

    /** The argument list of every call to [name] on [line], parentheses balanced, ending at the line's end at most. */
    private fun calls(line: String, name: Regex): List<String> = name.findAll(line).map { m ->
        var depth = 0
        var end = line.length
        for (i in m.range.last until line.length) {
            when (line[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) { end = i + 1; break }
            }
        }
        line.substring(m.range.last, end)
    }.toList()

    private val gapCall = Regex("""(?:\bpadding|\bPaddingValues|\bspacedBy|Spacer\(Modifier\.(?:size|width|height))\(""")

    /** A literal gap: any dp but 0 (none) and 1 (a hairline offset). */
    private val literalDp = Regex("""(?<![\w.])(\d+(?:\.\d+)?)\.dp\b""")
    private val literalSp = Regex("""\bfontSize\s*=\s*\d+(?:\.\d+)?\.sp\b""")

    /** What was in the tree when this check came in; it may only shrink. */
    private val debt = mapOf(
        "ExplorerLayouts.kt" to 5,
        "FilePreview.kt" to 2,
        "SidePanel.kt" to 1,
        "SidebarFolderTree.kt" to 1,
        "TrashPane.kt" to 1,
    )

    @Test fun chromeGapsAndTextSizesComeFromTokens() {
        val found = mutableMapOf<String, MutableList<String>>()
        for (file in chromeSources()) file.readLines().forEachIndexed { i, line ->
            val gaps = calls(line, gapCall).sumOf { call ->
                literalDp.findAll(call).count { it.groupValues[1].toDouble() !in setOf(0.0, 1.0) }
            }
            val sizes = literalSp.findAll(line).count()
            repeat(gaps + sizes) { found.getOrPut(file.name) { mutableListOf() }.add("${file.name}:${i + 1}") }
        }
        val over = found.filter { (name, hits) -> hits.size > (debt[name] ?: 0) }
        assertEquals(
            "Use TextSize.* for font sizes and Space.* for padding, spacedBy and spacers (ui/theme/Tokens.kt); " +
                "add a token there when no step fits",
            emptyMap<String, List<String>>(), over,
        )
    }

    @Test fun chromeDrawsNoShadows() {
        val hits = chromeSources().flatMap { file ->
            file.readLines().asSequence().mapIndexedNotNull { i, line ->
                if (Regex("""(?:\.|\breturn |=\s*)shadow\(""").containsMatchIn(line)) "${file.name}:${i + 1}" else null
            }
        }.toList()
        assertEquals("Chrome is flat: separate surfaces with a hairline border instead of a shadow", emptyList<String>(), hits)
    }
}
