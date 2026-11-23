package com.xnotes.core.util

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

private fun FileChannel.reader(): StagedReader = object : StagedReader {
    override fun size() = this@reader.size()
    override fun read(at: Long, bytes: ByteArray, offset: Int, length: Int) =
        this@reader.read(ByteBuffer.wrap(bytes, offset, length), at)
}

fun StagedCopy.Companion.of(target: FileChannel, staged: FileChannel, oldSize: Long, oldEnd: String): StagedCopy =
    of(target.reader(), staged.reader(), oldSize, oldEnd)

fun StagedCopy.Companion.endHash(channel: FileChannel): String = endHash(channel.reader())
