package com.xnotes.core.pal

import okio.Path.Companion.toOkioPath

actual typealias AssetFile = java.io.File
internal actual fun AssetFile.assetPath() = toOkioPath()
internal actual fun AssetFile.canonicalKey() = canonicalPath
internal actual fun okio.Path.asAssetFile() = toFile()
