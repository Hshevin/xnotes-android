package com.xnotes.kio

import okio.Buffer
import okio.Deflater
import okio.DeflaterSink
import okio.buffer

object RawDeflate {
    fun deflate(bytes: ByteArray, level: Int = 6): ByteArray {
        val output = Buffer()
        DeflaterSink(output, Deflater(level, true)).buffer().use { it.write(bytes) }
        return output.readByteArray()
    }

    fun inflate(bytes: ByteArray): ByteArray = inflateRaw(bytes)
}

internal expect fun inflateRaw(bytes: ByteArray): ByteArray
