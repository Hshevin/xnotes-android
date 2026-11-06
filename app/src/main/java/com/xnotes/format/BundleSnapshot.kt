package com.xnotes.format

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Owns a private immutable container snapshot, never the external source or its managed PDF resources. */
internal class BundleSnapshot private constructor(val file:File,val sourceHash:String):Closeable {
    override fun close() {file.delete()}
    companion object {
        fun take(source:File,directory:File?=null,cancelled:()->Boolean={false}):BundleSnapshot {
            val expected=BundleMigration.hash(source,cancelled)
            val snapshot=copyInput(source.inputStream(),directory,cancelled)
            try {
                check(snapshot.sourceHash==expected && BundleMigration.hash(source,cancelled)==expected && BundleMigration.hash(snapshot.file,cancelled)==expected) {"Source changed while taking bundle snapshot"}
                return snapshot
            } catch(error:Throwable) {snapshot.close();throw error}
        }
        /** Takes ownership of input; one copy for stream-based export, with no second PDF-sized staging copy. */
        fun fromInput(input:InputStream,directory:File?=null,cancelled:()->Boolean={false}):BundleSnapshot {
            val snapshot=copyInput(input,directory,cancelled)
            try {
                check(BundleMigration.hash(snapshot.file,cancelled)==snapshot.sourceHash) {"Bundle snapshot failed disk verification"}
                return snapshot
            } catch(error:Throwable) {snapshot.close();throw error}
        }
        private fun copyInput(input:InputStream,directory:File?,cancelled:()->Boolean):BundleSnapshot {
            var file:File?=null
            try {
                input.use {source->
                    check(!cancelled()) {"Bundle snapshot cancelled"}
                    val owned=File.createTempFile("bundle-source-",".zip",directory);file=owned
                    val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
                    FileOutputStream(owned).use {out->
                        while(true) {
                            check(!cancelled()) {"Bundle snapshot cancelled"};val n=source.read(buffer);if(n<0) break
                            out.write(buffer,0,n);digest.update(buffer,0,n)
                        }
                        out.fd.sync()
                    }
                    return BundleSnapshot(owned,digest.digest().joinToString("") {"%02x".format(it)})
                }
            } catch(error:Throwable) {file?.delete();throw error}
        }
    }
}
