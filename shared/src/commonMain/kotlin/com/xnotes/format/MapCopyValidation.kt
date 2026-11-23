package com.xnotes.format

import okio.*

/** Raw publication may assign an identity before an editor opens the file. Validate on the
 * snapshot, then let the copy plan stream the original entries and verify their digests. */
internal fun MapJson.validateForCopy(zip: BundleDirectory, cancelled: () -> Boolean) {
    val map = zip.open(ENTRY).use {
        CanvasCodec.readMapEntry(it.cancellable(cancelled))
    }
    val layers = zip.open(BundleCopyPlan.MANIFEST).use { input ->
        val reader = jsonChars(input.cancellable(cancelled), strict=true)
        val p = JsonPull(reader, strict = true, tokenLimit = Int.MAX_VALUE)
        readLayers(p)
    }
    checkLayers(map.state, layers)
}
