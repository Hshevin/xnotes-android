package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.asAssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.KFiles
import com.xnotes.kio.Sha256
import com.xnotes.kio.Uuids
import com.xnotes.kio.openWritable
import okio.*

expect abstract class SnapshotInputAdapters()

/** Owns a private immutable container snapshot, never the external source or its managed PDF resources. */
class BundleSnapshot private constructor(val file:AssetFile,val sourceHash:String):Closeable {
    override fun close() {KFiles.system.delete(file.assetPath())}
    companion object : SnapshotInputAdapters() {
        fun take(source:AssetFile,directory:AssetFile?=null,cancelled:()->Boolean={false}):BundleSnapshot {
            val expected=hash(source,cancelled)
            val snapshot=copyInput(KFiles.system.source(source.assetPath()),directory,cancelled)
            try {
                check(snapshot.sourceHash==expected && hash(source,cancelled)==expected && hash(snapshot.file,cancelled)==expected) {"Source changed while taking bundle snapshot"}
                return snapshot
            } catch(error:Throwable) {snapshot.close();throw error}
        }
        /** Takes ownership of input; one copy for stream-based export, with no second PDF-sized staging copy. */
        fun fromInput(input:Source,directory:AssetFile?=null,cancelled:()->Boolean={false}):BundleSnapshot {
            val snapshot=copyInput(input,directory,cancelled)
            try {
                check(hash(snapshot.file,cancelled)==snapshot.sourceHash) {"Bundle snapshot failed disk verification"}
                return snapshot
            } catch(error:Throwable) {snapshot.close();throw error}
        }
        fun hash(file: AssetFile, cancelled: () -> Boolean = { false }): String = KFiles.system.read(file.assetPath()) {
            val digest = Sha256(); val buffer = ByteArray(65536)
            while (true) {
                check(!cancelled()) { "Migration cancelled" }
                val count = read(buffer); if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.hex()
        }

        private fun copyInput(input:Source,directory:AssetFile?,cancelled:()->Boolean):BundleSnapshot {
            var file:Path?=null
            try {
                input.buffer().use {source->
                    check(!cancelled()) {"Bundle snapshot cancelled"}
                    val owned=(directory?.assetPath() ?: KFiles.tempDirectory()) / "bundle-source-${Uuids.random()}.zip"
                    val digest=Sha256();val buffer=ByteArray(65536)
                    openWritable(owned,exclusive=true).use {handle->
                        file=owned
                        handle.sink().buffer().use {out->
                            while(true) {
                                check(!cancelled()) {"Bundle snapshot cancelled"};val n=source.read(buffer);if(n<0) break
                                out.write(buffer,0,n);digest.update(buffer,0,n)
                            }
                        }
                        handle.flush()
                    }
                    return BundleSnapshot(owned.asAssetFile(),digest.hex())
                }
            } catch(error:Throwable) {file?.let {try {KFiles.system.delete(it)} catch(cleanup:Throwable) {error.addSuppressed(cleanup)}};throw error}
        }
    }
}
