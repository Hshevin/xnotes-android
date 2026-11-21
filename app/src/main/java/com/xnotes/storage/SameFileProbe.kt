package com.xnotes.storage

import android.content.ContentResolver
import android.net.Uri
import android.system.Os
import android.system.OsConstants

internal fun interface SameFileProbe {
    /** null means that at least one location could not be identified as a regular file. */
    fun sameFile(a: String, b: String): Boolean?
}

internal class AndroidSameFileProbe(private val resolver: ContentResolver) : SameFileProbe {
    override fun sameFile(a: String, b: String): Boolean? = try {
        resolver.openFileDescriptor(Uri.parse(a), "r")?.use { first ->
            resolver.openFileDescriptor(Uri.parse(b), "r")?.use { second ->
                // Keep both open so an unlinked file's inode cannot be recycled between stats.
                val left = Os.fstat(first.fileDescriptor)
                val right = Os.fstat(second.fileDescriptor)
                if (OsConstants.S_ISREG(left.st_mode) && OsConstants.S_ISREG(right.st_mode) &&
                    left.st_ino != 0L && right.st_ino != 0L
                ) left.st_dev == right.st_dev && left.st_ino == right.st_ino else null
            }
        }
    } catch (_: Exception) {
        null
    }
}
