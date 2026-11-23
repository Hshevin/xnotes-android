package com.xnotes.format

import java.io.InputStreamReader
import java.util.zip.ZipFile
import okio.source

/** Raw publication may assign an identity before an editor opens the file. Validate on the
 * snapshot, then let the copy plan stream the original entries and verify their digests. */
internal fun MapJson.validateForCopy(zip: ZipFile, cancelled: () -> Boolean) {
    val map = zip.getInputStream(zip.getEntry(ENTRY)).use {
        CanvasCodec.readMapEntry(BundleEntries.cancellable(it, cancelled).source())
    }
    val layers = zip.getInputStream(zip.getEntry(BundleCopyPlan.MANIFEST)).use { input ->
        val reader = InputStreamReader(BundleEntries.cancellable(input, cancelled), Charsets.UTF_8.newDecoder())
        val p = JsonPull(reader, strict = true, tokenLimit = Int.MAX_VALUE)
        readLayers(p)
    }
    checkLayers(map.state, layers)
}
