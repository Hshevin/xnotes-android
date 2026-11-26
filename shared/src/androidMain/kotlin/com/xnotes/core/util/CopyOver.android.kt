package com.xnotes.core.util

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import okio.sink
import okio.source

/**
 * Copies [temp] over a destination that is emptied the moment it is opened, and says whether the
 * bytes went out. [temp] is opened first: when it has gone, [open] never runs and the destination
 * keeps what it holds.
 */
fun copyOver(temp: File, bufferSize: Int, open: () -> OutputStream?): Boolean =
    copyOver(bufferSize, { FileInputStream(temp).source() }) { open()?.sink() }
