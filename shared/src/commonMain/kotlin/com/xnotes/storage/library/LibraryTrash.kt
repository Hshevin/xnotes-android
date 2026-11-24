package com.xnotes.storage.library

import com.xnotes.kio.Uuids
import okio.Path

private const val INFO = "trash.json"
private val wrapperName = Regex("[0-9]+-[0-9a-fA-F]{6}")

internal class LibraryTrash(
    private val files: LibraryFiles,
    private val clock: () -> Long,
    private val move: (Path, Path) -> Unit,
) {
    private val fs = files.fs
    private val root = files.root
    private val trash = root / SIDECAR / "trash"

    fun put(source: Path, isDir: Boolean): Path {
        if (source.name.equals(INFO, ignoreCase = true)) refuse(LibraryFailure.NAME_CONFLICT)
        val dir = source.parent!!
        val now = clock()
        val wrapper = trash / "$now-${Uuids.random().take(6)}"
        val payload = wrapper / source.name
        val folder = if (dir == root) emptyList() else dir.relativeTo(root).segments
        val item = TrashItem(payload, isDir, folder, now, files.colors(dir)[source.name])
        LibraryEdit(files).run {
            directory(wrapper, exclusive = true)
            json(wrapper / INFO, SidecarJson.trash(item))
            colors(dir, mapOf(source.name to null))
            repin(source, null)
            move(source, payload)
        }
        return payload
    }

    fun list(): List<TrashItem> {
        if (files.checked(trash, hidden = true)?.isDirectory != true) return emptyList()
        val sidecar = trash.parent!!
        for (file in fs.list(sidecar)) {
            if (file.parent == sidecar && file.name.startsWith(PURGING) && Uuids.isValid(file.name.removePrefix(PURGING))) {
                finishDelete(file)
            }
        }
        return fs.list(trash).mapNotNull { wrapper ->
            try { inspect(wrapper, pruneEmpty = true) } catch (_: Exception) { null }
        }.sortedByDescending(TrashItem::deleted)
    }

    private fun inspect(wrapper: Path, pruneEmpty: Boolean = false): TrashItem? {
        if (wrapper.parent != trash || !wrapperName.matches(wrapper.name)) return null
        if (files.checked(wrapper, hidden = true)?.isDirectory != true) return null
        val children = fs.list(wrapper)
        val info = wrapper / INFO
        val payloads = children.filter { it != info }
        if (payloads.isEmpty()) {
            if (pruneEmpty && (children.isEmpty() || files.checked(info, hidden = true)?.isRegularFile == true)) {
                fs.delete(info)
                fs.delete(wrapper)
            }
            return null
        }
        val path = payloads.singleOrNull()?.takeIf { it.parent == wrapper && validName(it.name) } ?: return null
        val meta = files.checked(path, hidden = true) ?: return null
        if (!meta.isRegularFile && !meta.isDirectory) return null
        val fallback = TrashItem(path, meta.isDirectory, emptyList(), wrapper.name.substringBefore('-').toLongOrNull() ?: 0, null)
        val json = files.json(info) ?: return fallback
        return SidecarJson.trash(json, fallback)
    }

    /** Re-read the wrapper, including its original folder, rather than trusting UI-provided paths. */
    private fun current(item: TrashItem): TrashItem {
        val wrapper = item.path.parent ?: refuse(LibraryFailure.INVALID_NAME)
        if (wrapper.parent != trash || !wrapperName.matches(wrapper.name)) refuse(LibraryFailure.INVALID_NAME)
        val found = inspect(wrapper) ?: refuse(LibraryFailure.NOT_FOUND)
        if (found != item) refuse(LibraryFailure.NOT_FOUND)
        return found
    }

    fun restore(item: TrashItem): Path {
        val stored = current(item)
        if (!stored.originalFolder.all(::validName)) refuse(LibraryFailure.INVALID_NAME)
        val original = stored.originalFolder.fold(root) { at, part -> at / part }
        val dir = if (files.checked(original)?.isDirectory == true) original else root
        val dest = files.destination(dir, stored.path.name, stored.isDir, copy = false)
        LibraryEdit(files).run {
            colors(dir, mapOf(dest.name to stored.color))
            move(stored.path, dest)
        }
        // The payload is already committed. Listing also prunes a metadata-only wrapper after interruption.
        try {
            val wrapper = stored.path.parent!!
            fs.delete(wrapper / INFO)
            fs.delete(wrapper)
        } catch (_: Exception) { }
        return dest
    }

    fun delete(item: TrashItem): Boolean {
        val wrapper = current(item).path.parent!!
        // Commit permanent deletion before unlinking children. Even interrupted directory removal
        // must never expose a partly deleted item as restorable to either Android or iPad.
        val retired = root / SIDECAR / "$PURGING${Uuids.random()}"
        files.moveNew(wrapper, retired)
        finishDelete(retired)
        return true
    }

    private fun finishDelete(retired: Path) {
        try {
            if (files.checked(retired, hidden = true)?.isDirectory == true) unlinkTree(retired)
        } catch (_: Exception) { /* The next trash listing retries committed deletions. */ }
    }

    private fun unlinkTree(path: Path) {
        val info = fs.metadata(path)
        if (info.isDirectory && info.symlinkTarget == null) {
            for (child in fs.list(path)) {
                require(child.parent == path && child == child.normalized())
                unlinkTree(child)
            }
        }
        fs.delete(path, mustExist = true)
    }

    fun purge(days: Int, delete: (TrashItem) -> Boolean) {
        if (days <= 0) return
        val cutoff = clock() - days * 86_400_000L
        for (item in list()) if (item.deleted in 1 until cutoff) delete(item)
    }

    private companion object { const val PURGING = ".purging-" }
}
