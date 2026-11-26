package com.xnotes.storage.lite

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import okio.Path

internal actual fun linkNewFile(stage: Path, target: Path): Boolean = try {
    Files.createLink(target.toNioPath(), stage.toNioPath())
    true
} catch (_: FileAlreadyExistsException) {
    false
}

// Android's editor supplies its APK resources through AssetManager.
internal actual fun bundledTemplateDirectory(): Path? = null
