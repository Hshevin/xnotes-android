package com.xnotes.kio

import java.nio.file.Files
import okio.Path

actual fun createHardLinkForTest(existing: Path, link: Path) {
    Files.createLink(link.toNioPath(), existing.toNioPath())
}
