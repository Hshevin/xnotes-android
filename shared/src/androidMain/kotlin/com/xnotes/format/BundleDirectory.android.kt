package com.xnotes.format

import java.io.File
import java.util.zip.Inflater
import java.util.zip.ZipFile
import okio.source

internal actual fun isBundleZipError(error: Exception) = error is java.util.zip.ZipException

internal actual fun bundleDirectory(file: File): BundleDirectory {
    val zip = ZipFile(file)
    try {
        return object : BundleDirectory {
            override val comment = zip.comment
            override val entries = zip.entries().asSequence().map { entry ->
                DirectoryEntry(entry.name, entry.method, entry.size, entry.compressedSize, entry.crc, entry.time) { zip.getInputStream(entry).source() }
            }.toList()
            override fun close() = zip.close()
        }
    } catch (error: Throwable) { zip.close(); throw error }
}

internal actual fun inflateMetadata(bytes: ByteArray, size: Long): ByteArray {
    val inflater = Inflater(true)
    try {
        inflater.setInput(bytes)
        val output = ByteArray(size.toInt() + 1)
        var count = 0
        while (!inflater.finished()) {
            val read = inflater.inflate(output, count, output.size - count)
            require(inflater.finished() || read > 0) { "Invalid metadata compressed length" }
            count += read
            require(count <= size) { "Bundle metadata decompression limit exceeded" }
        }
        require(count.toLong() == size && inflater.bytesRead == bytes.size.toLong()) { "Metadata compressed length mismatch" }
        return output.copyOf(count)
    } finally { inflater.end() }
}
