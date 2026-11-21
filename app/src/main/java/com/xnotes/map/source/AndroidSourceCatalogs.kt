package com.xnotes.map.source

import android.content.Context

/**
 * [SourceCatalogs] over the app's document registry and files. Read-only: it never enables page
 * ids, repairs, migrates or registers anything, and keeps no file open after [read] returns.
 *
 * - [locations]: the registry's available locations for the id, in its order.
 * - [read]: the file's bundle header and page anchors (as `NativeSourceSnapshot.ReferenceCatalog`
 *   gives them): NOTE with ordered page ids, NOTE with null ids when the note has none yet, CANVAS
 *   with null; anything else OTHER. A file that is not there → null; unreadable or corrupt → throws.
 *
 * Called off the main thread.
 */
class AndroidSourceCatalogs(context: Context) : SourceCatalogs {
    private val app = context.applicationContext
    override fun locations(documentId: String): List<String> = TODO("phase 3 backend")
    override fun read(location: String): SourceCatalog? = TODO("phase 3 backend")
}
