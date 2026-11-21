package com.xnotes.map.source

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.BundleCopyPlan
import com.xnotes.format.BundleEntries
import com.xnotes.format.BundleHeader
import com.xnotes.format.BundleReferenceInfo
import com.xnotes.format.BundleSnapshot
import com.xnotes.format.JsonPull
import com.xnotes.storage.NotebookStorage
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.zip.ZipFile

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
    private val storage = NotebookStorage.get(app)

    override fun locations(documentId: String): List<String> = storage.registry.locations(documentId)

    override fun read(location: String): SourceCatalog? {
        val uri = Uri.parse(location)
        if (uri.scheme == "content") return readContent(uri)
        val file = when (uri.scheme) {
            null -> File(location).also { require(it.isAbsolute) }
            "file" -> File(requireNotNull(uri.path))
            else -> throw IOException("Unsupported source URI")
        }
        val input = try { Files.newInputStream(file.toPath()) } catch (_: NoSuchFileException) { return null }
        return catalog(input, file.name)
    }

    private fun readContent(uri: Uri): SourceCatalog? {
        val provider = app.contentResolver.acquireUnstableContentProviderClient(uri)
            ?: throw IOException("Source provider is unavailable")
        provider.use {
            val asset = try {
                provider.openAssetFile(uri, "r") ?: throw IOException("Source provider returned no file")
            } catch (error: FileNotFoundException) {
                val cause = error.cause as? ErrnoException
                if (cause != null && cause.errno != OsConstants.ENOENT && cause.errno != OsConstants.ENOTDIR) throw error
                // Some providers report an unreadable but existing document as FileNotFoundException.
                // Our DocumentsProvider synthesizes a metadata row even for an absent path.
                if (cause == null && uri.authority != app.packageName + ".documents" &&
                    provider.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } == true
                ) throw error
                return null
            }
            return asset.use {
                val name = provider.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
                catalog(asset.createInputStream(), name)
            }
        }
    }

    // A private snapshot also handles pipes and sliced assets. Closing it releases the stream's copy.
    private fun catalog(input: InputStream, name: String): SourceCatalog {
        return BundleSnapshot.fromInput(input, app.cacheDir).use { snapshot ->
            val file = snapshot.file
            val header = BundleHeader.read(file)
            val id = requireNotNull(header.identity).documentId
            if (header.anchors == null && !hasNativeFormat(file)) {
                BundleEntries.digests(file)
                SourceCatalog(id, SourceCatalog.Kind.OTHER, name, null)
            } else {
                val info = BundleReferenceInfo.inspectStrict(file)
                val kind = when (info.kind) {
                    DocumentKind.NOTE -> SourceCatalog.Kind.NOTE
                    DocumentKind.CANVAS -> SourceCatalog.Kind.CANVAS
                    else -> error("Native bundle lost its format")
                }
                val pages = info.orderedPageIds.takeIf { kind == SourceCatalog.Kind.NOTE && info.header.anchors != null }
                SourceCatalog(id, kind, name, pages)
            }
        }
    }

    // The native inspector rejects unknown formats. Identify those separately so that a malformed
    // note can't fall back to OTHER. Known formats get their full validation in inspectStrict.
    private fun hasNativeFormat(file: File): Boolean = ZipFile(file).use { zip ->
        val manifest = requireNotNull(zip.getEntry(BundleCopyPlan.MANIFEST))
        InputStreamReader(zip.getInputStream(manifest), Charsets.UTF_8.newDecoder()).use { reader ->
            val json = JsonPull(reader, strict = true, tokenLimit = Int.MAX_VALUE)
            val fields = mutableSetOf<String>()
            json.beginObject()
            while (json.hasNext()) {
                val key = json.nextName()
                require(fields.add(key))
                if (key == "format") {
                    val format = json.nextString()
                    require(format.isNotBlank())
                    if (BundleReferenceInfo.kindOf(format) != null) return true
                } else {
                    json.skipValue()
                }
            }
            json.endObject()
            require("format" in fields && json.peek() == JsonPull.Token.END_DOCUMENT)
            false
        }
    }
}
