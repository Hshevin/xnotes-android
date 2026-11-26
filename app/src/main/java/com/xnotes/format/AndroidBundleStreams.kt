package com.xnotes.format

import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader

internal fun cancellableInput(input: InputStream, cancelled: () -> Boolean): InputStream =
    object : FilterInputStream(input) {
        override fun read(): Int { check(!cancelled()); return super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            check(!cancelled())
            return super.read(b, off, len)
        }
    }

internal fun bundleFormat(input: InputStream, cancelled: () -> Boolean = { false }): String {
    val reader = JsonPull(com.xnotes.format.JsonChars(InputStreamReader(cancellableInput(input, cancelled), Charsets.UTF_8)::read))
    reader.beginObject()
    while (reader.hasNext()) {
        if (reader.nextName() == "format") return reader.nextString().also {
            require(it == "xnote" || CanvasCodec.isCanvasFormat(it)) { "Unsupported document format" }
        }
        reader.skipValue()
    }
    error("Missing document format")
}
