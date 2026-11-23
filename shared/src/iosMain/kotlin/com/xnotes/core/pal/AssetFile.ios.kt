package com.xnotes.core.pal

import com.xnotes.kio.KFiles
import okio.Path
import okio.Path.Companion.toPath

actual typealias AssetFile = okio.Path
internal actual fun AssetFile.assetPath() = this
internal actual fun Path.asAssetFile() = this
internal actual fun AssetFile.canonicalKey(): String {
    val fs = KFiles.system
    val absolute = if (isAbsolute) this else fs.canonicalize(".".toPath()) / this
    fun resolved(path: Path): Path = if (fs.exists(path)) fs.canonicalize(path)
        else resolved(requireNotNull(path.parent)) / path.name
    return resolved(absolute.normalized()).toString()
}
