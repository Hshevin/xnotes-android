package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.KFiles
import okio.*

/** Prepare and verify on a separate file. Publishing is a distinct, atomic operation for local files. */
object BundleMigration {
    data class Prepared(val file: AssetFile, val identity: BundleIdentity, val sourceStamp: String)
    fun prepare(source: AssetFile, directory: AssetFile, copy: Boolean = false, cancelled: () -> Boolean = { false }): Prepared =
        prepareBundle(source,directory,copy,null,cancelled)

    /** Prepares an independent copy for a fixed creation UUID; grants no permission to publish it. */
    fun prepareCopy(source:AssetFile,directory:AssetFile,targetDocumentId:String,cancelled:()->Boolean={false}):Prepared {
        require(com.xnotes.kio.Uuids.isValid(targetDocumentId) && targetDocumentId.lowercase() == targetDocumentId) {"Invalid document UUID"}
        return prepareBundle(source,directory,true,targetDocumentId,cancelled)
    }

    private fun prepareBundle(source:AssetFile,directory:AssetFile,copy:Boolean,targetDocumentId:String?,cancelled:()->Boolean):Prepared =
        run {
            val stamp = stamp(source)
            val old = BundleHeader.read(source, cancelled).identity
            if (targetDocumentId != null) require(old?.documentId != targetDocumentId)
            val identity = if (copy) (old ?: BundleIdentity()).withDocumentId(targetDocumentId ?: com.xnotes.kio.Uuids.random())
                else old ?: BundleIdentity()
            val candidate = temporaryAsset("identity-", ".zip", directory)
            try {
                candidate.durable { rewriteBundle(source, it, identity, cancelled, validateCopy = targetDocumentId != null) }
                check(!cancelled())
                check(stamp(source) == stamp) { "Source changed during migration" }
                Prepared(candidate, identity, stamp)
            } catch (error: Throwable) { candidate.remove(); throw error }
        }
    fun stamp(file: AssetFile) = KFiles.system.metadata(file.assetPath()).let { "${it.size}:${it.lastModifiedAtMillis}" }
    fun publish(prepared: Prepared, destination: AssetFile) {
        check(stamp(destination) == prepared.sourceStamp) { "Source changed before migration commit" }
        com.xnotes.kio.atomicReplace(prepared.file.assetPath(), destination.assetPath())
    }
    fun fingerprints(file: AssetFile, cancelled: () -> Boolean = { false }): Map<String, String> = bundleDirectory(file).use { zip ->
        val result = linkedMapOf<String, String>()
        zip.entries.forEach { entry ->
            require(entry.name !in result) { "Duplicate ZIP entry" }
            result[entry.name] = entry.open().use { digest(it, cancelled) }
        }
        result
    }
    fun hash(file: AssetFile, cancelled: () -> Boolean = { false }): String = file.input().use { digest(it, cancelled) }
    internal fun digest(input: Source, cancelled: () -> Boolean): String {
        val sink = HashingSink.sha256(blackholeSink())
        input.cancellable(cancelled).buffer().readAll(sink)
        return sink.hash.hex()
    }
}
