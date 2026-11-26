package com.xnotes.kio

import okio.Buffer
import okio.HashingSink
import okio.blackholeSink
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class Crc32 {
    private var state = -1
    val value: Long get() = state.inv().toUInt().toLong()

    fun update(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        for (index in offset until offset + length) {
            state = table[(state xor bytes[index].toInt()) and 255] xor (state ushr 8)
        }
    }

    fun reset() { state = -1 }

    companion object {
        private val table = IntArray(256) { index ->
            var bits = index
            repeat(8) { bits = (bits ushr 1) xor (if (bits and 1 != 0) 0xedb88320.toInt() else 0) }
            bits
        }
        fun of(bytes: ByteArray): Long = Crc32().apply { update(bytes, 0, bytes.size) }.value
    }
}

class Sha256 {
    private val sink = HashingSink.sha256(blackholeSink())
    private val buffer = Buffer()
    fun update(bytes: ByteArray, offset: Int, length: Int) {
        buffer.write(bytes, offset, length)
        if (length != 0) sink.write(buffer, length.toLong())
    }
    fun hex(): String = sink.hash.hex()
    companion object {
        fun hex(bytes: ByteArray): String = Sha256().apply { update(bytes, 0, bytes.size) }.hex()
    }
}

@OptIn(ExperimentalUuidApi::class)
object Uuids {
    fun random(): String = Uuid.random().toString()
    fun isValid(text: String): Boolean = text.length == 36 && try { Uuid.parse(text); true } catch (_: IllegalArgumentException) { false }
}
