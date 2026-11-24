@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.xnotes.storage.lite

import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSBundle
import platform.posix.EEXIST
import platform.posix.errno
import platform.posix.link

internal actual fun linkNewFile(stage: Path, target: Path): Boolean {
    if (link(stage.toString(), target.toString()) == 0) return true
    val error = errno
    if (error == EEXIST) return false
    throw IOException("Exclusive publication failed (errno $error)")
}

internal actual fun bundledTemplateDirectory(): Path? =
    NSBundle.mainBundle.pathForResource("templates", ofType = null)?.toPath()
