package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.KFiles
import okio.*

/** Stage a manifest-only page ID upgrade while retaining the original entry payloads. */
object BundleAnchors {
    const val ENTRY=AnchorMetadata.ENTRY
    data class Prepared(val file:AssetFile,val identity:BundleIdentity,val sourceStamp:String,val anchorCount:Int)

    fun prepare(source: AssetFile, directory: AssetFile, expectedAnchored: Boolean = false,
                cancelled: () -> Boolean = { false }): Prepared {
        val stamp = BundleMigration.stamp(source)
        val header = BundleHeader.read(source, cancelled)
        val identity = requireNotNull(header.identity)
        require(!expectedAnchored || header.anchors != null)
        val candidate = temporaryAsset("anchors-", ".zip", directory)
        try {
            val count = candidate.durable { rewriteBundle(source, it, identity, cancelled, activate = true) }
            check(BundleMigration.stamp(source) == stamp) { "Source changed during page ID upgrade" }
            return Prepared(candidate, identity, stamp, count)
        } catch (error: Throwable) { candidate.remove(); throw error }
    }
    fun publish(prepared: Prepared, destination: AssetFile) = BundleMigration.publish(
        BundleMigration.Prepared(prepared.file, prepared.identity, prepared.sourceStamp), destination)
}
