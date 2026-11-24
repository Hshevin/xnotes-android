@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.storage.ios

import com.xnotes.core.model.Rgba
import com.xnotes.core.util.DocumentKind
import com.xnotes.explorer.ExplorerEntry
import com.xnotes.kio.KFiles
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import okio.Path
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.lstat
import platform.posix.stat
import platform.posix.timespec

data class IosDocumentEntry(
    val path: Path,
    val kind: DocumentKind?,
    val modifiedAtMillis: Long?,
    override val size: Long = 0,
    val createdAtMillis: Long? = null,
    /** Visible direct children, when this directory can be listed. */
    val childCount: Int? = null,
) : ExplorerEntry {
    val isFolder: Boolean get() = kind == null
    override val name: String get() = path.name
    override val isDir: Boolean get() = isFolder
    override val modified: Long get() = modifiedAtMillis ?: 0
    override val created: Long get() = createdAtMillis ?: modified
    override val color: Rgba? get() = null
}

private fun timespec.toMillis(): Long = tv_sec * 1000 + tv_nsec / 1_000_000

/** Read file type, size and birth time together, without following a symbolic link. */
private fun visibleEntry(path: Path): IosDocumentEntry? {
    if (path.name.startsWith('.')) return null
    return memScoped {
        val info = alloc<stat>()
        if (lstat(path.toString(), info.ptr) != 0) return@memScoped null
        val kind = when (info.st_mode.toInt() and S_IFMT) {
            S_IFDIR -> null
            S_IFREG -> DocumentKind.ofName(path.name) ?: return@memScoped null
            else -> return@memScoped null
        }
        val birth = info.st_birthtimespec
        IosDocumentEntry(
            path = path,
            kind = kind,
            modifiedAtMillis = info.st_mtimespec.toMillis(),
            size = if (kind == null) 0 else info.st_size,
            createdAtMillis = birth.takeUnless { it.tv_sec == 0L && it.tv_nsec == 0L }?.toMillis(),
        )
    }
}

fun listIosDocuments(dir: Path): List<IosDocumentEntry> = KFiles.system.list(dir).mapNotNull { path ->
    val entry = visibleEntry(path) ?: return@mapNotNull null
    if (!entry.isFolder) return@mapNotNull entry
    val count = KFiles.system.listOrNull(path)?.count { visibleEntry(it) != null }
    entry.copy(childCount = count)
}.sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }, { it.name }))
