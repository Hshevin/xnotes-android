package com.xnotes.format

import java.io.File

/** Stage a manifest-only page ID upgrade while retaining the original entry payloads. */
internal object BundleAnchors {
    const val ENTRY=AnchorMetadata.ENTRY
    data class Prepared(val file:File,val identity:BundleIdentity,val sourceStamp:String,val anchorCount:Int)

    fun prepare(source: File, directory: File, expectedAnchored: Boolean = false,
                cancelled: () -> Boolean = { false }): Prepared {
        val stamp = BundleMigration.stamp(source)
        val header = BundleHeader.read(source, cancelled)
        val identity = requireNotNull(header.identity)
        require(!expectedAnchored || header.anchors != null)
        val candidate = File.createTempFile("anchors-", ".zip", directory)
        try {
            val count = candidate.outputStream().use { rewriteBundle(source, it, identity, cancelled, activate = true) }
            check(BundleMigration.stamp(source) == stamp) { "Source changed during page ID upgrade" }
            return Prepared(candidate, identity, stamp, count)
        } catch (error: Throwable) { candidate.delete(); throw error }
    }
    fun publish(prepared: Prepared, destination: File) = BundleMigration.publish(
        BundleMigration.Prepared(prepared.file, prepared.identity, prepared.sourceStamp), destination)
}
