package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.KFiles
import com.xnotes.kio.Sha256
import okio.*

/** Payload checks shared by byte-preserving upgrades, copies and PDF packaging. */
object BundleEntries {
    /** Validate the same local-entry view consumed by DocumentCodec, not just ZipFile's directory. */
    fun digests(file:AssetFile,cancelled:()->Boolean={false},boundedManifest:Boolean=false):Map<String,String> {
        val central=linkedMapOf<String,String>()
        bundleDirectory(file).use {zip->for(entry in zip.entries) {
            check(!cancelled());require(entry.name !in central) {"Duplicate directory ZIP entry"}
            val limit=limitFor(entry.name,boundedManifest)
            require(entry.size<0 || entry.size<=limit) {"ZIP declared payload limit exceeded"}
            central[entry.name]=entry.open().buffer().use {digest(it,cancelled,limit)}
        }}
        val sequential=linkedMapOf<String,String>()
        bundleReader(KFiles.system.source(file.assetPath())).use {zip->
            var entry=zip.next()
            while(entry!=null) {
                check(!cancelled());require(entry.name !in sequential) {"Duplicate local ZIP entry"}
                val limit=limitFor(entry.name,boundedManifest)
                require(entry.size<0 || entry.size<=limit) {"ZIP declared payload limit exceeded"}
                sequential[entry.name]=digest(entry.data,cancelled,limit);entry=zip.next()
            }
        }
        require(central.keys.toList()==sequential.keys.toList() && central==sequential) {"ZIP local entries disagree with the directory"}
        return central
    }
    private fun limitFor(name:String,boundedManifest:Boolean)=when(name) {"manifest.json"->if(boundedManifest) AnchorManifest.MAX_BYTES else Long.MAX_VALUE;AnchorMetadata.ENTRY,BundleIdentity.ENTRY->AnchorMetadata.MAX_BYTES;else->Long.MAX_VALUE}

    fun copy(input:Source,out:Sink,cancelled:()->Boolean={false}):String {
        val source=input.buffer(); val buffer=Buffer(); val digest=HashingSink.sha256(out)
        while(true) {
            check(!cancelled()) {"Bundle copy cancelled"}
            val count=source.read(buffer,65536);if(count<0) break
            digest.write(buffer,count)
        }
        return digest.hash.hex()
    }
    private fun digest(input:BufferedSource,cancelled:()->Boolean,limit:Long):String {
        val digest=Sha256();val buffer=ByteArray(65536)
        var bytes=0L
        while(true) {check(!cancelled());val n=input.read(buffer);if(n<0) break;bytes+=n;require(bytes<=limit) {"ZIP actual payload limit exceeded"};digest.update(buffer,0,n)}
        return digest.hex()
    }
}
