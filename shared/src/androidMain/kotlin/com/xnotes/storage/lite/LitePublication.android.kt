package com.xnotes.storage.lite

import com.xnotes.core.util.CoreLock
import com.xnotes.core.util.locked
import okio.Path

private val publication = CoreLock()

internal actual fun coordinateLiteWrite(path: Path, publish: () -> Stamp): Stamp = publication.locked(publish)
