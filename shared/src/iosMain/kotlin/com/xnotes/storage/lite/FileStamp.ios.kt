@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.xnotes.storage.lite

import com.xnotes.kio.FileIdentity
import kotlinx.cinterop.*
import okio.IOException
import okio.Path
import platform.posix.*

internal actual fun fileStamp(path: Path): Stamp? = memScoped {
    val info = alloc<stat>()
    if (stat(path.toString(), info.ptr) == 0) {
        Stamp(FileIdentity(info.st_dev.toLong(), info.st_ino.toLong()), info.st_size,
            info.st_mtimespec.tv_sec * 1_000_000_000L + info.st_mtimespec.tv_nsec)
    } else when (val error = errno) {
        ENOENT, ENOTDIR -> null
        else -> throw IOException("stat failed (errno $error)")
    }
}

internal actual fun pdfCacheDirectory(privateDir: Path): Path = com.xnotes.storage.ios.IosStorageLocations.cache / "embedded-pdf"
