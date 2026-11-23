package com.xnotes.core.util

import com.xnotes.kio.KFiles
import okio.Buffer
import okio.FileNotFoundException
import okio.Path
import okio.Sink
import okio.Source
import okio.use

/** Open the source before asking for the destructive destination stream. */
fun copyOver(temp: Path, bufferSize: Int, open: () -> Sink?): Boolean =
    copyOver(bufferSize, { KFiles.system.source(temp) }, open)

internal fun copyOver(bufferSize: Int, source: () -> Source, open: () -> Sink?): Boolean {
    require(bufferSize > 0)
    val input = try { source() } catch (_: FileNotFoundException) { return false }
    return input.use {
        val output = open() ?: return false
        output.use {
            val buffer = Buffer()
            while (true) {
                val count = input.read(buffer, bufferSize.toLong())
                if (count < 0) break
                output.write(buffer, count)
            }
        }
        true
    }
}
