package com.xnotes.kio

import okio.Buffer
import okio.Inflater
import okio.InflaterSource
import okio.buffer

internal actual fun inflateRaw(bytes: ByteArray): ByteArray =
    InflaterSource(Buffer().write(bytes), Inflater(true)).buffer().use { it.readByteArray() }
