@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.xnotes.kio

import kotlinx.cinterop.*
import okio.Buffer
import platform.zlib.*

internal actual fun inflateRaw(bytes: ByteArray): ByteArray = memScoped {
    if (bytes.isEmpty()) throw KioException("Missing DEFLATE stream")
    val stream = alloc<z_stream_s>()
    stream.zalloc = null
    stream.zfree = null
    stream.opaque = null
    if (inflateInit2(stream.ptr, -15) != Z_OK) throw KioException("Cannot initialize inflater")
    try {
        val output = Buffer()
        val chunk = ByteArray(8192)
        bytes.usePinned { input -> chunk.usePinned { scratch ->
            stream.next_in = input.addressOf(0).reinterpret()
            stream.avail_in = bytes.size.toUInt()
            do {
                stream.next_out = scratch.addressOf(0).reinterpret()
                stream.avail_out = chunk.size.toUInt()
                val status = inflate(stream.ptr, Z_NO_FLUSH)
                if (status != Z_OK && status != Z_STREAM_END) throw KioException("Invalid DEFLATE stream ($status)")
                output.write(chunk, 0, chunk.size - stream.avail_out.toInt())
            } while (status != Z_STREAM_END)
        } }
        output.readByteArray()
    } finally { inflateEnd(stream.ptr) }
}
