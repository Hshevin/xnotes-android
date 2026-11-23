@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.nativelibs

import com.xnotes.nativelibs.interop.xn_pdf_close
import com.xnotes.nativelibs.interop.xn_pdf_count
import com.xnotes.nativelibs.interop.xn_pdf_open
import com.xnotes.nativelibs.interop.xn_pdf_render
import com.xnotes.nativelibs.interop.xn_pdf_size
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSLock

object Pdfium {
    private val lock = NSLock()

    internal fun <T> serialized(action: () -> T): T {
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    fun open(bytes: ByteArray, password: String? = null): PdfiumDocument? {
        if (bytes.isEmpty()) return null
        return serialized {
            bytes.usePinned { xn_pdf_open(it.addressOf(0), bytes.size, password) }?.let(::PdfiumDocument)
        }
    }
}

class PdfiumDocument internal constructor(private var handle: COpaquePointer?) : AutoCloseable {
    private fun document() = checkNotNull(handle) { "Document is closed" }

    val pageCount: Int get() = Pdfium.serialized { xn_pdf_count(document()) }

    fun pageSize(index: Int): Pair<Double, Double> = Pdfium.serialized {
        val doc = document()
        require(index in 0 until xn_pdf_count(doc))
        memScoped {
            val width = alloc<DoubleVar>()
            val height = alloc<DoubleVar>()
            check(xn_pdf_size(doc, index, width.ptr, height.ptr) != 0) { "Could not measure PDF page" }
            width.value to height.value
        }
    }

    fun render(index: Int, width: Int, height: Int): IntArray = Pdfium.serialized {
        val doc = document()
        require(index in 0 until xn_pdf_count(doc))
        require(width > 0 && height > 0 && width <= Int.MAX_VALUE / 4 / height)
        IntArray(width * height).also { pixels ->
            pixels.usePinned {
                check(xn_pdf_render(doc, index, width, height, it.addressOf(0)) != 0) { "Could not render PDF page" }
            }
        }
    }

    override fun close() = Pdfium.serialized {
        handle?.let { xn_pdf_close(it) }
        handle = null
    }
}
