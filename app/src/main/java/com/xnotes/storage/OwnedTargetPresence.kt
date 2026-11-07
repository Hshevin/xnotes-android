package com.xnotes.storage

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Positive absence evidence for an accessible owned root; a failed content read is insufficient. */
internal object OwnedTargetPresence {
    fun confirmedMissing(root:File?,target:File?,attributes:(Path)->BasicFileAttributes={Files.readAttributes(it,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)}):Boolean {
        if(root==null || target==null) return false
        return try {
            val base=root.absoluteFile.toPath().normalize();val leaf=target.absoluteFile.toPath().normalize()
            if(leaf==base || !leaf.startsWith(base)) return false
            fun parentsAvailable():Boolean {
                var at=leaf.parent ?: return false
                while(at.startsWith(base)) {
                    val info=attributes(at)
                    if(!info.isDirectory || info.isSymbolicLink) return false
                    if(at==base) return true
                    at=at.parent ?: return false
                }
                return false
            }
            if(!parentsAvailable()) return false
            try {attributes(leaf);false} catch(_:NoSuchFileException) {parentsAvailable()}
        } catch(_:IOException) {false} catch(_:SecurityException) {false} catch(_:IllegalArgumentException) {false}
    }
}
