@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.xnotes.storage.ios

import com.xnotes.kio.KFiles
import com.xnotes.kio.fileIdentity
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.*

object IosStorageLocations {
    val documents: Path by lazy { directory(NSDocumentDirectory) }
    val `private`: Path by lazy { create(directory(NSApplicationSupportDirectory) / "XNotes") }
    val noBackup: Path by lazy {
        val path = create(`private` / "NoBackup")
        val url = NSURL.fileURLWithPath(path.toString(), isDirectory = true)
        check(url.setResourceValue(NSNumber.numberWithBool(true), NSURLIsExcludedFromBackupKey, null))
        path
    }
    val cache: Path by lazy { create(directory(NSCachesDirectory) / "XNotes") }

    fun stagingFor(dir: Path): Path {
        val staging = create(dir / ".xnotes-staging")
        check(KFiles.system.metadata(staging).symlinkTarget == null)
        check(checkNotNull(fileIdentity(dir)).device == checkNotNull(fileIdentity(staging)).device)
        return staging
    }

    fun isExcludedFromBackup(path: Path): Boolean {
        val url = NSURL.fileURLWithPath(path.toString())
        val values = checkNotNull(url.resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), null))
        return (values[NSURLIsExcludedFromBackupKey] as? NSNumber)?.boolValue == true
    }

    private fun directory(kind: NSSearchPathDirectory): Path {
        val url = NSFileManager.defaultManager.URLForDirectory(kind, NSUserDomainMask, null, true, null)
        return create(checkNotNull(url?.path).toPath())
    }

    private fun create(path: Path): Path = path.also { KFiles.system.createDirectories(it) }
}
