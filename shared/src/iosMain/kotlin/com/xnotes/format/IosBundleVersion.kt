package com.xnotes.format

import okio.Path
import okio.use

fun newerIosBundle(path: Path): Boolean = bundleDirectory(path).use { archive ->
    listOf("manifest.json", MapJson.ENTRY, BundleIdentity.ENTRY, AnchorMetadata.ENTRY).any { name ->
        archive.entry(name)?.open()?.use { source ->
            val json = JsonPull(jsonChars(source, strict = true), strict = true)
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() == "version") return@any json.nextInt() > 1
                json.skipValue()
            }
            false
        } ?: false
    }
}
