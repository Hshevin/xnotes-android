package com.xnotes.storage.library

import com.xnotes.core.model.Rgba
import com.xnotes.kio.KFiles
import com.xnotes.kio.atomicWrite
import com.xnotes.kio.fileIdentity
import com.xnotes.kio.openWritable
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

internal const val SIDECAR = ".xnote"
internal const val COLORS = "colors.json"
internal const val NAMES = "color-names.json"
internal const val PINS = "pins.json"

internal class Refusal(val reason: LibraryFailure) : Exception()
internal fun refuse(reason: LibraryFailure): Nothing = throw Refusal(reason)

internal fun validName(name: String): Boolean = name.isNotBlank() && !name.startsWith('.') &&
    name.none { it == '/' || it == '\\' || it.isISOControl() } && name.toPath().volumeLetter == null

internal fun Path.within(dir: Path) = generateSequence(this) { it.parent }.any { it == dir }

/** Only descendants of a trusted, real root are addressed. Check each component before its children. */
internal class LibraryFiles(val fs: FileSystem, val root: Path) {
    init { require(root.isAbsolute && root == root.normalized()) }

    fun checked(path: Path, hidden: Boolean = false): FileMetadata? {
        if (!path.within(root) || path != path.normalized()) refuse(LibraryFailure.INVALID_NAME)
        val parts = if (path == root) emptyList() else path.relativeTo(root).segments
        if (!hidden && parts.any { !validName(it) }) refuse(LibraryFailure.INVALID_NAME)
        var at = root
        var info = fs.metadataOrNull(at)
        for (part in parts) {
            if (info == null) return null
            if (info.symlinkTarget != null) refuse(LibraryFailure.INVALID_NAME)
            if (!info.isDirectory) return null
            at /= part
            info = fs.metadataOrNull(at)
        }
        if (info?.symlinkTarget != null) refuse(LibraryFailure.INVALID_NAME)
        return info
    }

    fun entry(path: Path): FileMetadata {
        if (path == root) refuse(LibraryFailure.INVALID_NAME)
        return checked(path) ?: refuse(LibraryFailure.NOT_FOUND)
    }

    fun directory(path: Path) {
        val metadata = checked(path) ?: refuse(LibraryFailure.NOT_FOUND)
        if (!metadata.isDirectory) refuse(LibraryFailure.INVALID_NAME)
    }

    fun json(path: Path): String? {
        val info = checked(path, hidden = true) ?: return null
        check(info.isRegularFile)
        return fs.read(path) { readUtf8() }
    }

    fun colors(dir: Path) = json(dir / SIDECAR / COLORS)?.let(SidecarJson::colors).orEmpty()
    fun names() = json(root / SIDECAR / NAMES)?.let(SidecarJson::names).orEmpty()
    fun pins() = json(root / SIDECAR / PINS)?.let(SidecarJson::pins).orEmpty()
    fun relative(path: Path) = path.relativeTo(root).segments.joinToString("/")

    /** APFS can resolve different case or Unicode spellings to the same file or ancestor. */
    fun withinEntry(path: Path, ancestor: Path): Boolean {
        if (path.within(ancestor)) return true
        if (checked(ancestor, hidden = true) == null) {
            // A missing original can still have a recovery. Reserve that name under alternate case too.
            return generateSequence(path) { it.parent }.takeWhile { it != root }
                .any { it.toString().equals(ancestor.toString(), ignoreCase = true) }
        }
        val identity = fileIdentity(ancestor) ?: return false
        return generateSequence(path) { it.parent }.takeWhile { it != root }.any { at ->
            checked(at, hidden = true) != null && fileIdentity(at) == identity
        }
    }

    fun destination(dir: Path, name: String, isDir: Boolean, copy: Boolean): Path {
        val taken = fs.list(dir).mapTo(hashSetOf()) { it.name.lowercase() }
        if (name.lowercase() !in taken) return dir / name
        val dot = if (isDir) -1 else name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = if (copy) 1 else 2
        while (true) {
            val suffix = if (!copy) " ($n)" else if (n == 1) " copy" else " copy $n"
            val candidate = stem + suffix + ext
            if (candidate.lowercase() !in taken) return dir / candidate
            n++
        }
    }

    /** Reserve a new name before rename(2), which otherwise replaces an existing file or empty directory. */
    fun moveNew(source: Path, destination: Path) {
        val sourceInfo = checked(source, hidden = true) ?: refuse(LibraryFailure.NOT_FOUND)
        if (checked(destination, hidden = true) != null) refuse(LibraryFailure.NAME_CONFLICT)
        if (sourceInfo.isDirectory) fs.createDirectory(destination, mustCreate = true)
        else {
            val reservation = if (fs === KFiles.system) openWritable(destination, exclusive = true)
                else fs.openReadWrite(destination, mustCreate = true)
            try { reservation.close() } catch (failure: Throwable) {
                fs.delete(destination)
                throw failure
            }
        }
        try {
            fs.atomicMove(source, destination)
        } catch (failure: Throwable) {
            fs.delete(destination)
            throw failure
        }
    }
}

/** Sidecar edits precede the final payload rename. Undo only writes that actually succeeded, in reverse order. */
internal class LibraryEdit(private val files: LibraryFiles) {
    private val fs = files.fs
    private val undo = mutableListOf<() -> Unit>()

    fun directory(path: Path, exclusive: Boolean = false) {
        val existing = files.checked(path, hidden = true)
        if (existing != null) {
            check(!exclusive && existing.isDirectory)
            return
        }
        directory(checkNotNull(path.parent))
        fs.createDirectory(path, mustCreate = true)
        undo += { fs.delete(path) }
    }

    fun json(path: Path, text: String) {
        val original = files.json(path)
        if (text == original) return
        directory(checkNotNull(path.parent))
        atomicWrite(fs, path) { it.writeUtf8(text) }
        undo += {
            if (original == null) fs.delete(path)
            else atomicWrite(fs, path) { it.writeUtf8(original) }
        }
    }

    fun colors(dir: Path, changes: Map<String, Rgba?>) {
        val before = files.colors(dir)
        val after = before.toMutableMap()
        for ((name, color) in changes) {
            if (color == null) after.remove(name) else after[name] = color
        }
        if (before != after) json(dir / SIDECAR / COLORS, SidecarJson.colors(after))
    }

    fun repin(from: Path, to: Path?) {
        val before = files.pins()
        val old = files.relative(from)
        val new = to?.let(files::relative)
        val after = before.mapNotNull {
            if (it == old || it.startsWith("$old/")) new?.plus(it.removePrefix(old)) else it
        }.distinct()
        if (before != after) json(files.root / SIDECAR / PINS, SidecarJson.pins(after))
    }

    fun <T> run(block: LibraryEdit.() -> T): T = try {
        block()
    } catch (failure: Throwable) {
        for (restore in undo.asReversed()) {
            try { restore() } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
        }
        throw failure
    }
}
