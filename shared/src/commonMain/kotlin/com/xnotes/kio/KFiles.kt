package com.xnotes.kio

import okio.BufferedSink
import okio.FileHandle
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use as useOkio

typealias KioException = IOException

internal inline fun <T : okio.Closeable, R> T.use(block: (T) -> R): R = useOkio(block)

expect object KFiles {
    val system: FileSystem
    fun tempDirectory(): Path
}

data class FileIdentity(val device: Long, val inode: Long)
expect fun fileIdentity(path: Path): FileIdentity?
internal expect fun openWritable(path: Path, exclusive: Boolean): FileHandle
internal expect fun atomicReplace(source: Path, target: Path)

fun atomicWrite(fs: FileSystem, target: Path, write: (BufferedSink) -> Unit) {
    val temp = (target.parent ?: ".".toPath()) / ".${Uuids.random()}.tmp"
    val handle = if (fs === KFiles.system) openWritable(temp, true) else fs.openReadWrite(temp, mustCreate = true)
    try {
        handle.use {
            it.sink().buffer().use(write)
            it.flush()
        }
        fs.atomicMove(temp, target)
    } catch (failure: Throwable) {
        try { fs.delete(temp) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        throw failure
    }
}

interface PositionedFile {
    fun size(): Long
    fun read(at: Long, into: ByteArray, offset: Int, length: Int): Int
    fun write(at: Long, bytes: ByteArray, offset: Int, length: Int)
    fun truncate(size: Long)
}

class KFileHandle internal constructor(private val handle: FileHandle) : AutoCloseable, PositionedFile {
    override fun size(): Long = handle.size()
    override fun read(at: Long, into: ByteArray, offset: Int, length: Int): Int = handle.read(at, into, offset, length)
    override fun write(at: Long, bytes: ByteArray, offset: Int, length: Int) { writable(); handle.write(at, bytes, offset, length) }
    override fun truncate(size: Long) { writable(); handle.resize(size) }
    fun sync() { writable(); handle.flush() }
    private fun writable() { if (!handle.readWrite) throw KioException("Read-only file handle") }
    override fun close() = handle.close()
}

fun openRead(path: Path) = KFileHandle(KFiles.system.openReadOnly(path))
fun openReadWrite(path: Path) = KFileHandle(openWritable(path, false))
