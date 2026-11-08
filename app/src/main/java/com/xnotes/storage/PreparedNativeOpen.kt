package com.xnotes.storage

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.Document
import com.xnotes.core.util.DocumentKind
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Actual codec output from one verified source. Owns all temporary decoded assets until adoption.
 * Closing an adopted preparation releases only the handoff lease; its model keeps its assets.
 * This is neither a writer nor permission to rebind a cached model to a source.
 */
internal class PreparedNativeOpen private constructor(
    val version:NativeSourceVersion,val byteSize:Long,
    val note:Document?,val canvas:InfiniteDocument?,
    private val assets:File,private val resources:PdfResources,
    private val pdfIds:Set<String>,private val lease:AutoCloseable,
) : AutoCloseable {
    private var closed=false
    private var adopted=false
    private var protected=false

    /** IO-only, before the final non-suspending UI adoption check. No source file is written. */
    @Synchronized fun protectForAdoption() {
        check(!closed && !adopted) {"Prepared open is no longer awaiting adoption"}
        resources.protect("note:${version.documentId}",pdfIds)
        protected=true
    }
    private fun same(document:Document)=note===document && version.kind==DocumentKind.NOTE &&
        document.identity.documentId==version.documentId && document.path?.let {resources.registry.locationKey(it)}==version.locationKey
    private fun same(document:InfiniteDocument)=canvas===document && version.kind==DocumentKind.CANVAS &&
        document.identity.documentId==version.documentId && document.path?.let {resources.registry.locationKey(it)}==version.locationKey
    @Synchronized fun adopt(document:Document) {checkReady();if(!same(document)) reject();adopted=true}
    @Synchronized fun adopt(document:InfiniteDocument) {checkReady();if(!same(document)) reject();adopted=true}
    private fun checkReady() {check(!closed && protected) {"Prepared open is not ready for adoption"}}
    @Synchronized fun sourceVersion(document:Document):NativeSourceVersion {
        check(adopted) {"Source version requires actual adoption"};if(!same(document)) reject();return version
    }
    @Synchronized fun sourceVersion(document:InfiniteDocument):NativeSourceVersion {
        check(adopted) {"Source version requires actual adoption"};if(!same(document)) reject();return version
    }
    private fun reject():Nothing=throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
    override fun close() {
        val discard=synchronized(this) {if(closed) return;closed=true;!adopted}
        var failure:Throwable?=null
        if(discard) try {
            if(!assets.deleteRecursively() && assets.exists()) throw java.io.IOException("Cannot remove prepared document assets")
        } catch(error:Throwable) {failure=error}
        try {lease.close()} catch(error:Throwable) {if(failure==null) failure=error else failure.addSuppressed(error)}
        failure?.let {throw it}
    }
    companion object {
        private val ASSET_NAME=Regex("native-open-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        /** Only these owned directories are recursively purged at launch; other temp entries retain prior behavior. */
        internal fun isAssetDirectory(file:File)=ASSET_NAME.matches(file.name) && Files.isDirectory(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)
        /** Borrows source; only this private decode path can issue a model/source pairing. */
        internal fun read(source:NativeSourceSnapshot,resources:PdfResources,noteCodec:DocumentCodec,canvasCodec:CanvasCodec,
                          pdfDir:File,assetRoot:File,timing:DocumentCodec.ReadTiming?,checkActive:()->Unit):PreparedNativeOpen {
            checkActive()
            check(assetRoot.isDirectory || assetRoot.mkdirs()) {"Cannot create decoded asset directory"}
            val assets=File(assetRoot,"native-open-${UUID.randomUUID()}")
            check(assets.mkdir()) {"Cannot create prepared open directory"}
            var lease:AutoCloseable?=null
            try {
                val bytes=source.file.length();val version=source.version
                val note:Document?;val canvas:InfiniteDocument?
                fun input()=object:java.io.FilterInputStream(source.file.inputStream()) {
                    override fun read():Int {checkActive();return super.read()}
                    override fun read(bytes:ByteArray,offset:Int,length:Int):Int {checkActive();return `in`.read(bytes,offset,length)}
                }
                when(version.kind) {
                    DocumentKind.NOTE -> {
                        note=input().use {noteCodec.read(it,pdfDir,assets,timing,pdfStore={source.admitOriginalPdf(it,"PDF")})}
                        canvas=null;source.verify(note)
                    }
                    DocumentKind.CANVAS -> {
                        canvas=input().use {canvasCodec.read(it,assets)};note=null;source.verify(canvas)
                    }
                    else -> throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
                }
                checkActive()
                val ids=note?.pdfFile?.let {file->
                    if(!resources.isOwned(file)) throw ReferenceContractViolation(StorageMsg.PDF_RESOURCE_MISMATCH)
                    setOf(file.nameWithoutExtension)
                } ?: emptySet()
                lease=source.retainPdfForAdoption()
                checkActive()
                return PreparedNativeOpen(version,bytes,note,canvas,assets,resources,ids,lease)
            } catch(error:Throwable) {
                try {lease?.close()} catch(close:Throwable) {error.addSuppressed(close)}
                try {if(!assets.deleteRecursively() && assets.exists()) error.addSuppressed(java.io.IOException("Cannot remove prepared document assets"))}
                catch(close:Throwable) {error.addSuppressed(close)}
                throw error
            }
        }
    }
}
