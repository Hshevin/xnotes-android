@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.platform.ios

import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import com.xnotes.kio.atomicReplace
import kotlinx.cinterop.*
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import platform.CoreGraphics.*
import platform.posix.*

/** A URL-backed CGPDFContext has no write-error result. Supply a checked file consumer so a
 * short write or failed flush during CGPDFContextClose cannot publish a truncated PDF. */
internal fun writePdf(out: Path, cancelled: () -> Boolean, paint: (OwnedContext) -> Boolean): Boolean {
    val stage = (out.parent ?: ".".toPath()) / ".${Uuids.random()}.tmp"
    val file = fopen(stage.toString(), "wbx") ?: throw IOException("Could not create PDF staging file")
    try {
        val complete = try {
            val context = pdfContext(file)
            val painted = try { paint(context) } finally {
                CGPDFContextClose(context.ref)
                context.close()
            }
            if (ferror(file) != 0 || fflush(file) != 0) throw IOException("Could not write PDF")
            painted
        } finally {
            if (fclose(file) != 0) throw IOException("Could not close PDF")
        }
        if (!complete || cancelled()) return false
        atomicReplace(stage, out)
        return true
    } finally { KFiles.system.delete(stage) }
}

private fun pdfContext(file: CPointer<FILE>): OwnedContext {
    val consumer = memScoped {
        val callbacks = alloc<CGDataConsumerCallbacks>()
        callbacks.putBytes = staticCFunction { info, bytes, count -> fwrite(bytes, 1u, count, info?.reinterpret()) }
        callbacks.releaseConsumer = null
        checkNotNull(CGDataConsumerCreate(file, callbacks.ptr))
    }
    return try {
        OwnedContext(checkNotNull(CGPDFContextCreate(consumer, null, null)))
    } finally { CGDataConsumerRelease(consumer) }
}
