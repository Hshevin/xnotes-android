package com.xnotes.storage.lite

import platform.Foundation.NSOperationQueue

internal actual fun <T> runOffThread(work: () -> T): T {
    val queue = NSOperationQueue()
    var result: Result<T>? = null
    queue.addOperationWithBlock { result = runCatching(work) }
    queue.waitUntilAllOperationsAreFinished()
    return requireNotNull(result).getOrThrow()
}
