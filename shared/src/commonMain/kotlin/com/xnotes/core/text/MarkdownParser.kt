package com.xnotes.core.text

expect object MarkdownParser : MarkdownSyntax

abstract class MarkdownSyntax internal constructor() {
    private val LANG_ALIASES = mapOf(
        "js" to "javascript", "jsx" to "javascript",
        "py" to "python", "kt" to "kotlin", "kts" to "kotlin",
        "sh" to "bash", "shell" to "bash", "zsh" to "bash",
        "c++" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp",
        "ts" to "typescript", "mts" to "typescript", "cts" to "typescript",
        "golang" to "go",
        "rs" to "rust",
        "htm" to "html", "xhtml" to "html",
        "assembly" to "asm", "nasm" to "asm", "gas" to "asm", "x86asm" to "asm", "armasm" to "asm", "mipsasm" to "asm",
        "yml" to "yaml",
        "patch" to "diff",
        "docker" to "dockerfile", "containerfile" to "dockerfile",
        "svg" to "xml", "xsd" to "xml", "xsl" to "xml", "xslt" to "xml", "plist" to "xml",
    )

    /** Common fence-info aliases to the bundled grammar ids. */
    fun normalizeLang(id: String): String = LANG_ALIASES[id] ?: id

    /**
     * The address a link written to [dest] opens, or null when it is not one this app can
     * open: a web or mail address. A "www." host is taken as the web address it names.
     */
    fun linkTarget(dest: String): String? {
        val d = dest.trim()
        return when {
            d.startsWith("http://", ignoreCase = true) || d.startsWith("https://", ignoreCase = true) ->
                d.takeIf { it.substringAfter("://").isNotEmpty() }
            d.startsWith("mailto:", ignoreCase = true) -> d.takeIf { it.length > "mailto:".length }
            d.startsWith("www.", ignoreCase = true) -> "https://$d"
            else -> null
        }
    }
}
