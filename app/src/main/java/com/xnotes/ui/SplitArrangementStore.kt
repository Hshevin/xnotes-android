package com.xnotes.ui

import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ

internal sealed interface SplitArrangementRead {
    data object Absent:SplitArrangementRead
    data object Invalid:SplitArrangementRead
    data class Ready(val request:SplitOpenRequest):SplitArrangementRead
}

/** Only private arrangement JSON is accessed here. Source references are never opened. */
internal class SplitArrangementStore(private val filesDir:File,private val fileOps:FileOps=SystemFileOps) {
    internal interface FileOps {
        fun createLink(link:Path,existing:Path)
        fun deleteIfExists(path:Path)
    }
    private object SystemFileOps:FileOps {
        override fun createLink(link:Path,existing:Path) {Files.createLink(link,existing)}
        override fun deleteIfExists(path:Path) {Files.deleteIfExists(path)}
    }

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

    fun prepareNamed(request:MixedSplitRequest,name:String):PreparedSplitPublication? {
        var stage:Path?=null
        try {
            val bytes=SplitOpenCodec.encodeMixed(request)
            val folder=SplitArrangementPaths.directory(filesDir,create=true)
            stage=Files.createTempFile(folder.toPath(),".split-",".pending")
            FileOutputStream(stage.toFile()).use {stream->stream.write(bytes);stream.fd.sync()}
            return PreparedSplitPublication(filesDir,folder,stage,fileStem(name),fileOps)
        } catch(_:Exception) {
            stage?.let {owned->try {fileOps.deleteIfExists(owned)} catch(_:Exception) {}}
            return null
        }
    }

    fun saveNamed(request:MixedSplitRequest,name:String):File? {
        val prepared=prepareNamed(request,name) ?: return null
        return try {prepared.commit()} finally {prepared.close()}
    }
    private fun read(file:File,allowLegacyFormat:Boolean):SplitArrangementRead {
        val path=file.toPath()
        if(Files.notExists(path,NOFOLLOW_LINKS)) return SplitArrangementRead.Absent
        require(Files.isRegularFile(path,NOFOLLOW_LINKS))
        val request=Files.newByteChannel(path,READ,NOFOLLOW_LINKS).use {channel->
            Channels.newInputStream(channel).use {SplitOpenCodec.read(it,allowLegacyFormat)}
        }
        return request?.let {SplitArrangementRead.Ready(it)} ?: SplitArrangementRead.Invalid
    }
    private fun fileStem(name:String):String {
        val clean=name.removeSuffix(".xsplit").take(160).map {char->
            if(char.code<32 || char.code==127 || char in "/\\:*?\"<>|") '_' else char
        }.joinToString("").trim().trim('.').take(80).let {
            if(it.lastOrNull()?.isHighSurrogate()==true) it.dropLast(1) else it
        }
        return clean.ifBlank {"Split"}
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
