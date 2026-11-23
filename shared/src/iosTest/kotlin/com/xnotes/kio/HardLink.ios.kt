@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.xnotes.kio

import okio.Path

actual fun createHardLinkForTest(existing: Path, link: Path) {
    check(platform.posix.link(existing.toString(), link.toString()) == 0)
}
