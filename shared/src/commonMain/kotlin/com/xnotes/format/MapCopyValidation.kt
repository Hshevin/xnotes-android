package com.xnotes.format

import okio.*

/** Validate map references before copying a bundle without decoding its drawing items. */
internal fun MapJson.validateForCopy(zip: BundleDirectory, cancelled: () -> Boolean) {
    val map = zip.open(ENTRY).use {
        CanvasCodec.readMapEntry(it.cancellable(cancelled))
    }
    val layers = zip.open("manifest.json").use { input ->
        val reader = jsonChars(input.cancellable(cancelled), strict=true)
        val p = JsonPull(reader, strict = true, tokenLimit = Int.MAX_VALUE)
        readLayers(p)
    }
    checkLayers(map.state, layers)
}
