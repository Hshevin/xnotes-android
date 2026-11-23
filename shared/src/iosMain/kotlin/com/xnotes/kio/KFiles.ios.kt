@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.xnotes.kio

import kotlinx.cinterop.*
import okio.FileHandle
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.posix.*

actual object KFiles {
    actual val system: FileSystem = FileSystem.SYSTEM
    actual fun tempDirectory(): Path = NSTemporaryDirectory().toPath()
}

private fun failure(code: Int = errno) = KioException(strerror(code)?.toKString() ?: "POSIX error $code")

actual fun fileIdentity(path: Path): FileIdentity? = memScoped {
    val info = alloc<stat>()
    if (stat(path.toString(), info.ptr) == 0) FileIdentity(info.st_dev.toLong(), info.st_ino.toLong())
    else when (val code = errno) { ENOENT, ENOTDIR -> null; else -> throw failure(code) }
}

internal actual fun openWritable(path: Path, exclusive: Boolean): FileHandle {
    val descriptor = open(path.toString(), O_RDWR or O_CREAT or (if (exclusive) O_EXCL else 0), 0x180)
    if (descriptor < 0) throw failure()
    return object : FileHandle(true) {
        override fun protectedSize(): Long = memScoped {
            val info = alloc<stat>()
            if (fstat(descriptor, info.ptr) != 0) throw failure()
            info.st_size
        }
        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
            if (byteCount == 0) return 0
            return array.usePinned { pinned ->
                var count: Long
                do { count = pread(descriptor, pinned.addressOf(arrayOffset), byteCount.convert(), fileOffset) } while (count < 0 && errno == EINTR)
                if (count < 0) throw failure()
                if (count == 0L) -1 else count.toInt()
            }
        }
        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) {
            array.usePinned { pinned ->
                var done = 0
                while (done < byteCount) {
                    val count = pwrite(descriptor, pinned.addressOf(arrayOffset + done), (byteCount - done).convert(), fileOffset + done)
                    if (count < 0 && errno == EINTR) continue
                    if (count <= 0) throw failure()
                    done += count.toInt()
                }
            }
        }
        override fun protectedFlush() { while (fsync(descriptor) != 0) { if (errno != EINTR) throw failure() } }
        override fun protectedResize(size: Long) { if (ftruncate(descriptor, size) != 0) throw failure() }
        override fun protectedClose() { if (platform.posix.close(descriptor) != 0) throw failure() }
    }
}
