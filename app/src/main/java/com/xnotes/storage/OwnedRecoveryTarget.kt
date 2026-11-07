package com.xnotes.storage

import android.content.Context
import com.xnotes.platform.AppStorageDocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/** Pins recovery to one observed owned root; provider fallback must never redirect a restore. */
internal class OwnedRecoveryTarget(private val context:Context,private val documentId:String):MigrationPublication.Target {
    private val root=observedRoot()
    private fun observedRoot():File? = try {AppStorageDocumentsProvider.recoveryRoot(context)?.canonicalFile}
        catch(_:IOException) {null} catch(_:SecurityException) {null}

    private fun file(resolveLinks:Boolean=true):File {
        val current=observedRoot()
        if(root==null || current!=root) throw FileNotFoundException(StorageText.t(StorageMsg.NOTE_UNREADABLE))
        require(documentId.startsWith("root/")) {StorageText.t(StorageMsg.INVALID_NOTE_PATH)}
        val raw=File(root,documentId.removePrefix("root/"))
        val canonical=raw.canonicalFile
        require(canonical.path.startsWith(root.path+File.separator)) {StorageText.t(StorageMsg.INVALID_NOTE_PATH)}
        return if(resolveLinks) canonical else raw
    }
    override fun read()=file().inputStream()
    override fun confirmedMissing():Boolean = try {OwnedTargetPresence.confirmedMissing(root,file(resolveLinks=false))}
        catch(_:IOException) {false} catch(_:SecurityException) {false} catch(_:IllegalArgumentException) {false}
    override fun write(source:File) {
        // Direct pinned path, not a second provider root lookup that could choose a fallback root.
        FileOutputStream(file()).use {out->source.inputStream().use {it.copyTo(out)};out.flush();out.fd.sync()}
    }
}
