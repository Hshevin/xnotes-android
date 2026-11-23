@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.nativelibs

import com.xnotes.nativelibs.interop.xn_ts_count
import com.xnotes.nativelibs.interop.xn_ts_free
import com.xnotes.nativelibs.interop.xn_ts_name
import com.xnotes.nativelibs.interop.xn_ts_parse
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned

object TreeSitter {
    private val indices = (0 until xn_ts_count()).associateBy { checkNotNull(xn_ts_name(it)).toKString() }

    fun languages(): Set<String> = indices.keys.toSet()

    fun parseSExpression(language: String, source: String): String? {
        val index = indices[language] ?: return null
        val bytes = source.encodeToByteArray()
        val expression = bytes.usePinned {
            xn_ts_parse(index, if (bytes.isEmpty()) null else it.addressOf(0), bytes.size)
        } ?: return null
        return try {
            expression.toKString()
        } finally {
            xn_ts_free(expression)
        }
    }
}
