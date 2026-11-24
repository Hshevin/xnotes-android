package com.xnotes.storage.library

import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.asAssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.core.util.CoreLock
import com.xnotes.core.util.DocumentKind
import com.xnotes.core.util.locked
import com.xnotes.format.BundleMigration
import com.xnotes.kio.Uuids
import com.xnotes.storage.lite.LiteKind
import com.xnotes.storage.lite.LiteStore
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.time.Clock

enum class LibraryFailure { INVALID_NAME, NAME_CONFLICT, TARGET_IN_SOURCE, NOT_FOUND, PENDING_RECOVERY, IO_ERROR }

sealed interface LibraryResult {
    data class Success(val path: Path) : LibraryResult
    data class Failure(val reason: LibraryFailure) : LibraryResult
}

data class TrashItem(
    val path: Path,
    val isDir: Boolean,
    val originalFolder: List<String>,
    val deleted: Long,
    val color: Rgba?,
)

private val libraryLock = CoreLock()

/** Local file operations for one Documents tree. Call on an IO thread. Batch results keep input order. */
class Library(
    private val fs: FileSystem,
    private val root: Path,
    private val store: LiteStore,
    clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val files = LibraryFiles(fs, root)
    private val bin = LibraryTrash(files, clock, ::moveUnlessProtected)

    init { require(store.manages(fs, root)) }

    fun colors(dir: Path): Map<String, Rgba> = reading(emptyMap()) {
        files.directory(dir)
        files.colors(dir)
    }

    fun setColors(dir: Path, changes: Map<String, Rgba?>): Boolean = writing {
        files.directory(dir)
        require(changes.keys.all(::validName))
        changes.keys.forEach { files.checked(dir / it) }
        colors(dir, changes)
    }

    fun colorNames(): Map<Rgba, String> = reading(emptyMap(), files::names)

    fun setColorName(color: Rgba, name: String?): Boolean = writing {
        val before = files.names()
        val after = before.toMutableMap()
        if (name.isNullOrBlank()) after.remove(color) else after[color] = name.trim()
        if (before != after) json(root / SIDECAR / NAMES, SidecarJson.names(after))
    }

    fun findByColor(color: Rgba): List<Path> = reading(emptyList()) {
        walk(root, readColors = true) { _, _, itemColor -> itemColor == color }
    }

    fun search(start: Path, query: String): List<Path> = reading(emptyList()) {
        val needle = query.trim()
        if (needle.isEmpty()) emptyList() else walk(start) { path, isDir, _ ->
            !isDir && DocumentKind.stripSuffix(path.name).contains(needle, ignoreCase = true)
        }
    }

    private fun walk(start: Path, readColors: Boolean = false, matches: (Path, Boolean, Rgba?) -> Boolean): List<Path> {
        files.directory(start)
        val result = mutableListOf<Path>()
        val stack = ArrayDeque<Path>()
        stack.add(start)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            if (!reading(false) { files.checked(dir)?.isDirectory == true }) continue
            val colors = if (readColors) reading(emptyMap()) { files.colors(dir) } else emptyMap()
            for (child in fs.listOrNull(dir).orEmpty()) {
                if (child.parent != dir || !validName(child.name)) continue
                val info = fs.metadataOrNull(child) ?: continue
                if (info.symlinkTarget != null) continue
                if (info.isDirectory) stack.add(child)
                else if (!info.isRegularFile || !DocumentKind.isDocument(child.name)) continue
                if (matches(child, info.isDirectory, colors[child.name])) result.add(child)
            }
        }
        return result.sortedWith(compareBy({ it.name.lowercase() }, Path::toString))
    }

    fun notePins(): List<Path> = reading(emptyList()) {
        val before = files.pins()
        val paths = before.mapNotNull { relative ->
            val names = relative.split('/')
            if (!names.all(::validName)) return@mapNotNull null
            val path = names.fold(root) { dir, name -> dir / name }
            try {
                path.takeIf { DocumentKind.isDocument(it.name) && files.checked(it)?.isRegularFile == true }
            } catch (_: Refusal) { null }
        }.distinct()
        val kept = paths.map(files::relative)
        if (before != kept) LibraryEdit(files).run { json(root / SIDECAR / PINS, SidecarJson.pins(kept)) }
        paths
    }

    fun setNotePinned(doc: Path, pinned: Boolean): Boolean = writing {
        require(files.entry(doc).isRegularFile && DocumentKind.isDocument(doc.name))
        val path = files.relative(doc)
        val before = files.pins()
        val after = before.filter { it != path } + listOfNotNull(path.takeIf { pinned })
        if (before != after) json(root / SIDECAR / PINS, SidecarJson.pins(after))
    }

    fun rename(item: Path, newName: String): LibraryResult = change {
        val info = files.entry(item)
        if (!validName(newName)) refuse(LibraryFailure.INVALID_NAME)
        val kind = if (info.isRegularFile) DocumentKind.ofName(item.name) else null
        val name = if (kind == null) newName else {
            val stem = if (newName.endsWith(kind.suffix, true)) newName.dropLast(kind.suffix.length) else newName
            stem + item.name.takeLast(kind.suffix.length)
        }
        relocate(item, item.parent!! / name)
    }

    fun moveInto(items: List<Path>, target: Path): List<LibraryResult> = changes(items) { item ->
        checkTarget(item, target)
        relocate(item, target / item.name)
    }

    private fun checkTarget(item: Path, target: Path): FileMetadata {
        val info = files.entry(item)
        files.directory(target)
        if (info.isDirectory && files.withinEntry(target, item)) refuse(LibraryFailure.TARGET_IN_SOURCE)
        return info
    }

    private fun relocate(item: Path, dest: Path): Path {
        if (dest == item) return item
        val oldDir = item.parent!!
        val newDir = dest.parent!!
        if (fs.list(newDir).any { it.name.equals(dest.name, ignoreCase = true) }) refuse(LibraryFailure.NAME_CONFLICT)
        val color = files.colors(oldDir)[item.name]
        LibraryEdit(files).run {
            if (oldDir == newDir) colors(oldDir, mapOf(item.name to null, dest.name to color))
            else {
                colors(oldDir, mapOf(item.name to null))
                colors(newDir, mapOf(dest.name to color))
            }
            repin(item, dest)
            moveUnlessProtected(item, dest)
        }
        return dest
    }

    /** Recovery records carry a source stamp and may be held by an existing session with that exact path. */
    private fun moveUnlessProtected(from: Path, to: Path) = store.withRecoveryIndex { recoveries ->
        if (recoveries.any { files.withinEntry(it.original, from) || files.withinEntry(it.original, to) }) {
            refuse(LibraryFailure.PENDING_RECOVERY)
        }
        files.moveNew(from, to)
    }

    fun copyInto(items: List<Path>, target: Path): List<LibraryResult> = changes(items) { item ->
        val info = checkTarget(item, target)
        val dest = files.destination(target, item.name, info.isDirectory, copy = true)
        val color = files.colors(item.parent!!)[item.name]
        val stage = prepareCopy(item, info, target)
        cleaningOnFailure(stage) {
            LibraryEdit(files).run {
                colors(target, mapOf(dest.name to color))
                moveUnlessProtected(stage, dest)
            }
        }
        dest
    }

    private fun prepareCopy(source: Path, info: FileMetadata, target: Path): Path {
        if (info.isRegularFile && LiteKind.of(source) != null) return independentBundle(source, target)
        val stage = target / ".copy-${Uuids.random()}"
        if (info.isDirectory) {
            fs.createDirectory(stage, mustCreate = true)
            cleaningOnFailure(stage) { copyChildren(source, stage, stage) }
        } else {
            check(info.isRegularFile)
            val output = fs.sink(stage, mustCreate = true)
            cleaningOnFailure(stage) {
                output.buffer().use { sink -> fs.source(source).use { sink.writeAll(it) } }
            }
        }
        return stage
    }

    private fun copyChildren(directory: Path, destination: Path, stage: Path) {
        for (child in fs.list(directory)) {
            require(child.parent == directory && child == child.normalized())
            val info = files.checked(child, hidden = true) ?: refuse(LibraryFailure.NOT_FOUND)
            val to = destination / child.name
            when {
                info.isDirectory -> {
                    fs.createDirectory(to, mustCreate = true)
                    copyChildren(child, to, stage)
                }
                !info.isRegularFile -> refuse(LibraryFailure.IO_ERROR)
                LiteKind.of(child) != null -> fs.atomicMove(independentBundle(child, stage), to)
                else -> fs.copy(child, to)
            }
        }
    }

    private fun independentBundle(source: Path, directory: Path): Path =
        BundleMigration.prepare(source.asAssetFile(), directory.asAssetFile(), copy = true).file.assetPath()

    private fun cleaningOnFailure(stage: Path, action: () -> Unit) {
        try { action() } catch (failure: Throwable) {
            try { fs.deleteRecursively(stage) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun trash(items: List<Path>): List<LibraryResult> = changes(items) { item ->
        val info = files.entry(item)
        bin.put(item, info.isDirectory)
    }

    fun listTrash(): List<TrashItem> = reading(emptyList(), bin::list)
    fun restore(item: TrashItem): LibraryResult = change { bin.restore(item) }
    fun delete(item: TrashItem): Boolean = reading(false) { bin.delete(item) }
    fun emptyTrash(): Int = listTrash().count(::delete)
    fun purgeExpired(days: Int) { reading(Unit) { bin.purge(days, ::delete) } }

    private fun changes(items: List<Path>, action: (Path) -> Path) = items.map { item -> change { action(item) } }

    private fun change(action: () -> Path): LibraryResult = libraryLock.locked {
        try { LibraryResult.Success(action()) }
        catch (e: Refusal) { LibraryResult.Failure(e.reason) }
        catch (_: Exception) { LibraryResult.Failure(LibraryFailure.IO_ERROR) }
    }

    private fun writing(action: LibraryEdit.() -> Unit) = reading(false) {
        LibraryEdit(files).run(action)
        true
    }

    private fun <T> reading(fallback: T, action: () -> T): T = libraryLock.locked {
        try { action() } catch (_: Exception) { fallback }
    }
}

/** Directory listing needs only the local colour file; it never opens the bundles or the recovery store. */
internal fun listingColors(fs: FileSystem, dir: Path): Map<String, Rgba> = libraryLock.locked {
    try { LibraryFiles(fs, dir).colors(dir) } catch (_: Exception) { emptyMap() }
}
