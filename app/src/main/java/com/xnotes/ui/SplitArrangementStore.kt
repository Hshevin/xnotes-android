package com.xnotes.ui

import java.io.File
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ

internal sealed interface SplitArrangementRead {
    data object Absent:SplitArrangementRead
    data object Invalid:SplitArrangementRead
    data class Ready(val request:SplitOpenRequest):SplitArrangementRead
}


/** Only private arrangement JSON is accessed here. Source references are never opened. */
internal class SplitArrangementStore(private val filesDir:File) {

    fun readManaged(uri:String):SplitArrangementRead = try {
        val parsed=URI(uri)
        require(parsed.scheme=="file" && parsed.rawAuthority.isNullOrEmpty() && parsed.rawQuery==null && parsed.rawFragment==null)
        val directory=SplitArrangementPaths.directory(filesDir,create=false)
        val original=File(parsed).absoluteFile
        val file=original.canonicalFile
        require(!Files.isSymbolicLink(original.toPath()) && file.parentFile==directory && file.extension=="xsplit")
        read(file,allowLegacyFormat=false)
    } catch(_:Exception) {SplitArrangementRead.Invalid}

    fun readRecent():SplitArrangementRead = try {
        val file=SplitArrangementPaths.recent(filesDir)
        read(file,allowLegacyFormat=true)
    } catch(_:Exception) {SplitArrangementRead.Invalid}

    private fun read(file:File,allowLegacyFormat:Boolean):SplitArrangementRead {
        val path=file.toPath()
        if(Files.notExists(path,NOFOLLOW_LINKS)) return SplitArrangementRead.Absent
        require(Files.isRegularFile(path,NOFOLLOW_LINKS))
        val request=Files.newByteChannel(path,READ,NOFOLLOW_LINKS).use {channel->
            Channels.newInputStream(channel).use {SplitOpenCodec.read(it,allowLegacyFormat)}
        }
        return request?.let {SplitArrangementRead.Ready(it)} ?: SplitArrangementRead.Invalid
    }
}

/** These paths grant access only to app-private arrangement JSON, never to its source URIs. */
internal object SplitArrangementPaths {
    fun root(filesDir:File)=filesDir.canonicalFile.also {require(it.isDirectory)}
    fun directory(filesDir:File,create:Boolean):File {
        val directory=File(root(filesDir),"splits")
        require(directory.canonicalFile==directory && !Files.isSymbolicLink(directory.toPath()))
        if(create) Files.createDirectories(directory.toPath())
        require(Files.isDirectory(directory.toPath(),NOFOLLOW_LINKS) || !create && Files.notExists(directory.toPath(),NOFOLLOW_LINKS))
        return directory
    }
    fun recent(filesDir:File)=File(root(filesDir),"saved-split.json").also {
        require(it.canonicalFile==it && !Files.isSymbolicLink(it.toPath()))
    }
}
