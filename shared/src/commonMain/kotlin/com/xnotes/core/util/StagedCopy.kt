package com.xnotes.core.util

import com.xnotes.kio.KFileHandle
import com.xnotes.kio.Sha256

/**
 * Where a file stands that a staged copy was meant to replace. Copying over a file empties it and
 * fills it again, so a crash in between leaves it cut short; the staged copy is kept until the file
 * is seen whole, and this says, at the next launch, what is left to do.
 */
enum class StagedCopy {
    /** The file is the staged copy, whole. */
    DONE,

    /** The copy never began, or stopped part way: copying again finishes it. */
    REDO,

    /** The file is something else by now, and is left as it is. */
    LEAVE,
    ;

    companion object {
        private const val HEAD = 64 * 1024
        private const val END = 4096

        /**
         * What [target] is beside [staged], the copy meant for it. [oldSize] and [oldEnd] are what
         * the file was before the copy: its length and [endHash].
         */
        fun of(target: KFileHandle, staged: KFileHandle, oldSize: Long, oldEnd: String): StagedCopy =
            of(target.reader(), staged.reader(), oldSize, oldEnd)

        internal fun of(target: StagedReader, staged: StagedReader, oldSize: Long, oldEnd: String): StagedCopy {
            val t = target.size()
            val s = staged.size()
            if (t == s && sameAt(target, staged, 0L, HEAD) && sameAt(target, staged, maxOf(0L, s - END), END)) return DONE
            if (t == oldSize && endHash(target) == oldEnd) return REDO
            // Every byte it has is the copy's: the copy stopped there.
            if (t < s && sameAt(target, staged, 0L, HEAD) && sameAt(target, staged, maxOf(0L, t - END), END)) return REDO
            return LEAVE
        }

        /** A hash of [ch]'s last bytes, which with its length tells what the file was. */
        fun endHash(ch: KFileHandle): String = endHash(ch.reader())

        internal fun endHash(ch: StagedReader): String {
            val size = ch.size()
            val bytes = bytesAt(ch, maxOf(0L, size - END), minOf(size, END.toLong()).toInt())
            return Sha256.hex(bytes)
        }

        /** Whether [a] and [b] hold the same bytes from [at] on, for [len] bytes or as many as [a] has. */
        private fun sameAt(a: StagedReader, b: StagedReader, at: Long, len: Int): Boolean {
            val n = minOf(len.toLong(), a.size() - at).toInt()
            if (n <= 0) return true
            if (at + n > b.size()) return false
            return bytesAt(a, at, n).contentEquals(bytesAt(b, at, n))
        }

        private fun bytesAt(ch: StagedReader, at: Long, len: Int): ByteArray {
            val bytes = ByteArray(len)
            var offset = 0
            while (offset < len) {
                val count = ch.read(at + offset, bytes, offset, len - offset)
                if (count < 0) break
                offset += count
            }
            return bytes
        }
    }
}

internal interface StagedReader {
    fun size(): Long
    fun read(at: Long, bytes: ByteArray, offset: Int, length: Int): Int
}

private fun KFileHandle.reader(): StagedReader = object : StagedReader {
    override fun size() = this@reader.size()
    override fun read(at: Long, bytes: ByteArray, offset: Int, length: Int) = this@reader.read(at, bytes, offset, length)
}
