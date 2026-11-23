package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.kio.openRead
import okio.*

private const val U32 = 0xffffffffL
private class BrokenBundleDirectory(cause: Exception): IOException("Invalid ZIP directory",cause)
internal actual fun isBundleZipError(error: Exception) = error is BrokenBundleDirectory
private fun BufferedSource.word() = readIntLe().toLong() and U32
private fun BufferedSource.short() = readShortLe().toInt() and 65535

internal actual fun bundleDirectory(file: AssetFile): BundleDirectory {
    val handle = openRead(file)
    try {
        val length = handle.size()
        val span = minOf(length, 65557L).toInt()
        val tail = requireNotNull(handle.bytesAt(length - span, span))
        val end = (span - 22 downTo 0).firstOrNull { tail.u32(it) == 0x06054b50L && it + 22 + tail.u16(it + 20) == span }
            ?: throw IllegalArgumentException("Missing ZIP end record")
        require(tail.u16(end + 4) == 0 && tail.u16(end + 6) == 0) { "Multi-disk ZIP is unsupported" }
        var count = tail.u16(end + 10).toLong(); var size = tail.u32(end + 12); var offset = tail.u32(end + 16)
        if (count == 65535L || size == U32 || offset == U32) {
            val locator = Buffer().write(requireNotNull(handle.bytesAt(length - span + end - 20, 20)))
            require(locator.word() == 0x07064b50L && locator.word() == 0L)
            val wide = Buffer().write(requireNotNull(handle.bytesAt(locator.readLongLe(), 56)))
            require(locator.word() == 1L && wide.word() == 0x06064b50L && wide.readLongLe() >= 44)
            wide.skip(4); require(wide.word() == 0L && wide.word() == 0L)
            val diskCount = wide.readLongLe(); count = wide.readLongLe(); require(diskCount == count)
            size = wide.readLongLe(); offset = wide.readLongLe()
        }
        require(size in 0..length && offset in 0..length-size && count in 0..minOf(Int.MAX_VALUE.toLong(), size/46))
        val entries = FileRegion(handle, offset, size).sourceAt(0).buffer().use { directory ->
            List(count.toInt()) {
                require(directory.word() == 0x02014b50L); directory.skip(4)
                val flags = directory.short(); val method = directory.short(); val stamp = directory.word()
                require(flags and 1 == 0 && method in listOf(0,8)) { "Unsupported ZIP compression" }
                val crc = directory.word(); var packed = directory.word(); var plain = directory.word()
                val nameLength = directory.short(); val extraLength = directory.short(); val commentLength = directory.short()
                require(directory.short() == 0); directory.skip(6); var local = directory.word()
                val name = directory.readByteArray(nameLength.toLong()).decodeToString(throwOnInvalidSequence = true)
                val extra = Buffer().write(directory.readByteArray(extraLength.toLong())); directory.skip(commentLength.toLong())
                if (plain == U32 || packed == U32 || local == U32) {
                    while (true) {
                        val tag = extra.short(); val bytes = extra.short().toLong()
                        if (tag == 1) {
                            val fields = Buffer().write(extra.readByteArray(bytes))
                            if (plain == U32) plain = fields.readLongLe()
                            if (packed == U32) packed = fields.readLongLe()
                            if (local == U32) local = fields.readLongLe()
                            break
                        }
                        extra.skip(bytes)
                    }
                }
                require(plain >= 0 && local in 0..offset - 30 && packed in 0..offset)
                DirectoryEntry(name, method, plain, packed, crc, stamp) {
                    val header = requireNotNull(handle.bytesAt(local, 30)); require(header.u32(0) == 0x04034b50L)
                    val start = local + 30 + header.u16(26) + header.u16(28); require(start in 0..offset-packed)
                    val region = FileRegion(handle, start, packed).sourceAt(0)
                    if (method == 8) InflaterSource(region, Inflater(true)) else region
                }
            }
        }
        return object : BundleDirectory {
            override val entries = entries
            override val comment = if (tail.u16(end+20) == 0) null else tail.decodeToString(end+22,tail.size,throwOnInvalidSequence = true)
            override fun close() = handle.close()
        }
    } catch (error: Throwable) {
        handle.close()
        if (error is Exception && (error is IllegalArgumentException || error is EOFException || error is kotlin.text.CharacterCodingException)) throw BrokenBundleDirectory(error)
        throw error
    }
}
