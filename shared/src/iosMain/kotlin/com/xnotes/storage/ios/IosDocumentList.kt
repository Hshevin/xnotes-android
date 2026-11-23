package com.xnotes.storage.ios

import com.xnotes.core.util.DocumentKind
import com.xnotes.kio.KFiles
import okio.Path

data class IosDocumentEntry(val path: Path, val kind: DocumentKind?, val modifiedAtMillis: Long?) {
    val isFolder: Boolean get() = kind == null
}

fun listIosDocuments(dir: Path): List<IosDocumentEntry> = KFiles.system.list(dir).mapNotNull { path ->
    if (path.name.startsWith('.')) return@mapNotNull null
    val info = KFiles.system.metadataOrNull(path) ?: return@mapNotNull null
    val kind = when {
        info.symlinkTarget != null -> return@mapNotNull null
        info.isDirectory -> null
        info.isRegularFile -> DocumentKind.ofName(path.name) ?: return@mapNotNull null
        else -> return@mapNotNull null
    }
    IosDocumentEntry(path, kind, info.lastModifiedAtMillis)
}.sortedWith(compareBy({ !it.isFolder }, { it.path.name.lowercase() }, { it.path.name }))
