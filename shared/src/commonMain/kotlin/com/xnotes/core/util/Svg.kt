package com.xnotes.core.util

import com.xnotes.core.pal.assetPath
import com.xnotes.kio.openRead
import com.xnotes.core.pal.AssetFile as File

/**
 * Sniffs whether a file holds SVG markup. Every raster image format opens with a binary magic
 * number, so a leading '<' (after an optional UTF-8 BOM and whitespace) means vector markup.
 * Content-based on purpose: inserted images live in extension-less temp files.
 */
object Svg {
    fun isSvgFile(file: File): Boolean = runCatching {
        openRead(file.assetPath()).use { input ->
            val head = ByteArray(64)
            val n = input.read(0, head, 0, head.size)
            var i = 0
            if (n >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
            while (i < n && head[i].toInt().toChar().isWhitespace()) i++
            i < n && head[i] == '<'.code.toByte()
        }
    }.getOrDefault(false)
}
