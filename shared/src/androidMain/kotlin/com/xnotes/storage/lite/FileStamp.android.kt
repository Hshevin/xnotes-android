package com.xnotes.storage.lite

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.xnotes.kio.FileIdentity
import okio.IOException
import okio.Path
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

internal actual fun fileStamp(path: Path): Stamp? {
    if (System.getProperty("java.vm.name") == "Dalvik") {
        return try {
            Os.stat(path.toString()).let {
                Stamp(FileIdentity(it.st_dev, it.st_ino), it.st_size,
                    it.st_mtim.tv_sec * 1_000_000_000L + it.st_mtim.tv_nsec)
            }
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ENOTDIR) null else throw IOException(error)
        }
    }
    return try {
        val attrs = Files.readAttributes(path.toNioPath(), "unix:dev,ino,size,lastModifiedTime")
        Stamp(FileIdentity((attrs.getValue("dev") as Number).toLong(), (attrs.getValue("ino") as Number).toLong()),
            attrs.getValue("size") as Long, (attrs.getValue("lastModifiedTime") as FileTime).to(TimeUnit.NANOSECONDS))
    } catch (_: NoSuchFileException) { null } catch (_: NotDirectoryException) { null }
}

internal actual fun pdfCacheDirectory(privateDir: Path): Path = privateDir / "pdf-cache"
