package com.xnotes.kio

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import okio.FileHandle
import okio.FileSystem
import okio.Path

actual object KFiles {
    actual val system: FileSystem = FileSystem.SYSTEM
    actual fun tempDirectory(): Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
}

actual fun fileIdentity(path: Path): FileIdentity? {
    if (System.getProperty("java.vm.name") == "Dalvik") {
        return try {
            Os.stat(path.toString()).let { FileIdentity(it.st_dev, it.st_ino) }
        } catch (failure: ErrnoException) {
            if (failure.errno == OsConstants.ENOENT || failure.errno == OsConstants.ENOTDIR) null
            else throw KioException(failure)
        }
    }
    return try {
        val attrs = Files.readAttributes(path.toNioPath(), "unix:dev,ino")
        FileIdentity((attrs.getValue("dev") as Number).toLong(), (attrs.getValue("ino") as Number).toLong())
    } catch (_: NoSuchFileException) { null } catch (_: NotDirectoryException) { null }
}

internal actual fun openWritable(path: Path, exclusive: Boolean): FileHandle {
    if (!exclusive) return KFiles.system.openReadWrite(path)
    Files.createFile(path.toNioPath())
    return try { KFiles.system.openReadWrite(path, mustExist = true) } catch (failure: Throwable) {
        try { Files.delete(path.toNioPath()) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        throw failure
    }
}
