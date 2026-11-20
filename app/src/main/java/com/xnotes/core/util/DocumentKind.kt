package com.xnotes.core.util

/**
 * The document types the app stores, and the file extension each uses.
 *
 * The extension is spelled once here rather than at the two dozen sites that used to hard-code
 * `.xnote`. That matters now there are two: every place that lists, names, renames or opens a file
 * has to agree on which extensions are documents, and a place that forgets one silently hides the
 * user's files from the explorer.
 */
enum class DocumentKind(val extension: String) {
    /** The paged notebook. */
    NOTE("xnote"),

    /** The infinite canvas. */
    CANVAS("xcanvas"),

    /** References two documents and their viewports, without embedding their contents. */
    SPLIT("xsplit"),

    /** Native text and editable-ink tree document. */
    MIND_MAP("xmap"),

    /**
     * Upstream's PDF document: a paged notebook that keeps its note inside the PDF once written on.
     * Not recognised by name in this build (see [PDF_DOCUMENTS_ENABLED]); a picked PDF is imported
     * and its original stays immutable.
     */
    PDF("pdf"),
    ;

    /** The extension with its dot, as it appears at the end of a file name. */
    val suffix: String get() = ".$extension"

    companion object {
        /**
         * Whether a `.pdf` file is listed, opened and saved as a document of its own, as upstream
         * does. Off here: writing a note into the PDF itself would bypass the controlled publish
         * and save handoff and change the original PDF, which this build keeps immutable.
         */
        const val PDF_DOCUMENTS_ENABLED = false

        /** The kinds a file name can denote in this build. */
        private val named: List<DocumentKind> = entries.filter { PDF_DOCUMENTS_ENABLED || it != PDF }

        /** The kind [name] denotes, or null when it is not a document at all. */
        fun ofName(name: String): DocumentKind? =
            named.firstOrNull { name.endsWith(it.suffix, ignoreCase = true) }

        fun isDocument(name: String): Boolean = ofName(name) != null

        /** [name] without its document extension, or unchanged when it has none. */
        fun stripSuffix(name: String): String {
            val kind = ofName(name) ?: return name
            return name.dropLast(kind.suffix.length)
        }

        /** [name] with [kind]'s extension, added only if it is not already the right one. */
        fun withSuffix(name: String, kind: DocumentKind): String =
            if (name.endsWith(kind.suffix, ignoreCase = true)) name else "$name${kind.suffix}"
    }
}
