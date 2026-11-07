package com.xnotes.storage

import android.content.Context
import com.xnotes.format.BundleMigration
import com.xnotes.platform.AppStorageDocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Pins recovery to one observed root; never redirects a restore through provider fallback. */
internal class OwnedRecoveryTarget(private val context:Context,private val documentId:String,
                                   private val beforeReplace:()->Unit={}):MigrationPublication.Target {
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
    override fun write(source:File) = write(source,if(confirmedMissing()) null else BundleMigration.hash(file()))
    override fun write(source:File,expectedCurrentHash:String?) {
        val destination=file()
        fun verifyCurrent() {
            val unchanged=if(expectedCurrentHash==null) confirmedMissing()
                else file()==destination && BundleMigration.hash(destination)==expectedCurrentHash
            if(!unchanged) throw MigrationPublication.RecoveryRequired(StorageText.t(StorageMsg.MIGRATION_INTERRUPTED),
                reason=StorageMsg.MIGRATION_INTERRUPTED)
        }
        verifyCurrent()
        val staged=File.createTempFile("recovery-",".tmp",requireNotNull(destination.parentFile))
        try {
            val sourceHash=BundleMigration.hash(source)
            FileOutputStream(staged).use {out->source.inputStream().use {it.copyTo(out)};out.flush();out.fd.sync()}
            check(BundleMigration.hash(staged)==sourceHash && BundleMigration.hash(source)==sourceHash) {
                StorageText.t(StorageMsg.MIGRATION_VERIFY_FAILED)
            }
            beforeReplace()
            // Staging can take time. Do not replace a version that arrived while it was copied.
            verifyCurrent()
            Files.move(staged.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {staged.delete()}
    }
}
