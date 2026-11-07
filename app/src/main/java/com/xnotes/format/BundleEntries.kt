package com.xnotes.format

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Payload checks shared by byte-preserving upgrades, copies and PDF packaging. */
internal object BundleEntries {
    fun cancellable(input:InputStream,cancelled:()->Boolean):InputStream = object:java.io.FilterInputStream(input) {
        override fun read():Int {check(!cancelled());return super.read()}
        override fun read(b:ByteArray,off:Int,len:Int):Int {check(!cancelled());return super.read(b,off,len)}
        override fun skip(n:Long):Long {check(!cancelled());return super.skip(n)}
    }
    /** Validate the same local-entry view consumed by DocumentCodec, not just ZipFile's directory. */
    fun digests(file:File,cancelled:()->Boolean={false},boundedManifest:Boolean=false):Map<String,String> {
        val central=linkedMapOf<String,String>()
        ZipFile(file).use {zip->for(entry in zip.entries().asSequence()) {
            check(!cancelled());require(entry.name !in central) {"Duplicate directory ZIP entry"}
            val limit=limitFor(entry.name,boundedManifest)
            require(entry.size<0 || entry.size<=limit) {"ZIP declared payload limit exceeded"}
            central[entry.name]=zip.getInputStream(entry).use {digest(it,cancelled,limit)}
        }}
        val sequential=linkedMapOf<String,String>()
        ZipInputStream(file.inputStream()).use {zip->
            var entry=zip.nextEntry
            while(entry!=null) {
                check(!cancelled());require(entry.name !in sequential) {"Duplicate local ZIP entry"}
                val limit=limitFor(entry.name,boundedManifest)
                require(entry.size<0 || entry.size<=limit) {"ZIP declared payload limit exceeded"}
                sequential[entry.name]=digest(zip,cancelled,limit);zip.closeEntry();entry=zip.nextEntry
            }
        }
        require(central.keys.toList()==sequential.keys.toList() && central==sequential) {"ZIP local entries disagree with the directory"}
        return central
    }
    private fun limitFor(name:String,boundedManifest:Boolean)=when(name) {"manifest.json"->if(boundedManifest) AnchorManifest.MAX_BYTES else Long.MAX_VALUE;AnchorMetadata.ENTRY,BundleIdentity.ENTRY->AnchorMetadata.MAX_BYTES;else->Long.MAX_VALUE}

    fun copy(input:InputStream,out:OutputStream,cancelled:()->Boolean={false}):String {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        while(true) {
            check(!cancelled()) {"Bundle copy cancelled"}
            val n=input.read(buffer);if(n<0) break
            out.write(buffer,0,n);digest.update(buffer,0,n)
        }
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
    private fun digest(input:InputStream,cancelled:()->Boolean,limit:Long):String {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        var bytes=0L
        while(true) {check(!cancelled());val n=input.read(buffer);if(n<0) break;bytes+=n;require(bytes<=limit) {"ZIP actual payload limit exceeded"};digest.update(buffer,0,n)}
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
}
