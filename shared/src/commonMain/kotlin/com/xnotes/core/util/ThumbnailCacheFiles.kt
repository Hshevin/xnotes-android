package com.xnotes.core.util

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.asAssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.core.pal.canonicalKey
import com.xnotes.kio.KFiles
import com.xnotes.kio.Sha256
import okio.Path

// A shared generation prevents another pane or an old render from restoring discarded thumbnails.
class ThumbnailCacheFiles(dir: AssetFile, private val format: Int, private val maxFiles: Int = 256) {
    private val directory = dir.assetPath()
    private val fs = KFiles.system

    private class State {
        val lock = CoreLock()
        var theme: String? = null
        var generation = 0L
    }

    private val state = states.getOrPut(dir.canonicalKey()) { State() }

    fun useTheme(theme: String): Long = state.lock.locked {
        val signature = "$format\n$theme"
        if (state.theme != signature) {
            state.generation++
            state.theme = signature
            runCatching {
                val mark = directory / MARK
                if (!fs.exists(mark) || fs.read(mark) { readUtf8() } != signature) {
                    fs.listOrNull(directory)?.forEach { removeQuietly(it) }
                    fs.createDirectories(directory)
                    fs.write(mark) { writeUtf8(signature) }
                }
            }
        }
        state.generation
    }

    fun isCurrent(generation: Long): Boolean = state.lock.locked {
        state.theme != null && state.generation == generation
    }

    fun <T> load(uri: String, generation: Long, read: (AssetFile) -> T?): T? = state.lock.locked {
        if (!isCurrent(generation)) return@locked null
        val file = directory / "${key(uri)}.png"
        if (!fs.exists(file)) return@locked null
        runCatching { read(file.asAssetFile()) }.getOrNull()
    }

    fun store(uri: String, generation: Long, write: (AssetFile) -> Unit) = state.lock.locked {
        if (!isCurrent(generation)) return@locked
        runCatching {
            fs.createDirectories(directory)
            write((directory / "${key(uri)}.png").asAssetFile())
            val pngs = fs.listOrNull(directory).orEmpty().filter { it.name.endsWith(".png") }
            pngs.sortedByDescending { fs.metadataOrNull(it)?.lastModifiedAtMillis ?: 0L }.drop(maxFiles).forEach { removeFile(it) }
        }
        Unit
    }

    fun remove(uri: String) = state.lock.locked {
        removeFile(directory / "${key(uri)}.png")
    }

    fun prune(keep: Set<String>) = state.lock.locked {
        runCatching {
            val keys = keep.mapTo(HashSet()) { key(it) }
            fs.listOrNull(directory)?.forEach { file ->
                if (file.name != MARK && file.name.substringBeforeLast('.') !in keys) removeQuietly(file)
            }
        }
        Unit
    }

    private fun removeFile(file: Path) {
        removeQuietly(file)
        removeQuietly(directory / "${file.name.substringBeforeLast('.')}.txt")
    }

    private fun removeQuietly(path: Path) { runCatching { fs.delete(path) } }

    private fun key(uri: String): String =
        Sha256.hex(uri.encodeToByteArray()).take(32)

    private companion object {
        const val MARK = "format"
        val states = LockedMap<String, State>()
    }
}
