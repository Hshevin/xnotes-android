@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.xnotes.storage.lite

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import okio.IOException
import okio.Path
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSURL

internal actual fun coordinateLiteWrite(path: Path, publish: () -> Stamp): Stamp = memScoped {
    val url = NSURL.fileURLWithPath(path.toString())
    val error = alloc<ObjCObjectVar<NSError?>>()
    var result: Result<Stamp>? = null
    NSFileCoordinator(filePresenter = null).coordinateWritingItemAtURL(url, options = 0uL, error = error.ptr) { granted ->
        result = runCatching {
            val grantedPath = granted?.URLByResolvingSymlinksInPath?.path ?: throw SourceConflict()
            if (grantedPath != url.URLByResolvingSymlinksInPath?.path) throw SourceConflict()
            publish()
        }
    }
    result?.getOrThrow() ?: throw IOException(error.value?.localizedDescription ?: "Could not coordinate document update")
}
