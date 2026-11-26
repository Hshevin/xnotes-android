package com.xnotes.core.pal

import okio.Path

expect class AssetFile
internal expect fun AssetFile.assetPath(): Path
internal expect fun AssetFile.canonicalKey(): String
internal expect fun Path.asAssetFile(): AssetFile
