package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.core.pal.asAssetFile
import com.xnotes.kio.Crc32
import com.xnotes.kio.KFiles
import com.xnotes.kio.Sha256
import com.xnotes.kio.Uuids
import com.xnotes.kio.openRead
import com.xnotes.kio.openReadWrite
import okio.*

/** Prepare and verify on a separate file. Publishing is a distinct, atomic operation for local files. */
object BundleMigration {
    data class Prepared(val file: AssetFile, val identity: BundleIdentity, val sourceHash: String)
    fun prepare(source: AssetFile, directory: AssetFile, copy: Boolean = false, cancelled: () -> Boolean = { false }): Prepared =
        prepareBundle(source,directory,copy,null,cancelled)

    /** Prepares an independent copy for a fixed creation UUID; grants no permission to publish it. */
    fun prepareCopy(source:AssetFile,directory:AssetFile,targetDocumentId:String,cancelled:()->Boolean={false}):Prepared {
        require(Uuids.isValid(targetDocumentId) && targetDocumentId.lowercase()==targetDocumentId) {"Invalid document UUID"}
        return prepareBundle(source,directory,true,targetDocumentId,cancelled)
    }

    private fun prepareBundle(source:AssetFile,directory:AssetFile,copy:Boolean,targetDocumentId:String?,cancelled:()->Boolean):Prepared =
        BundleSnapshot.take(source,directory,cancelled).use { snapshot ->
            val existing=BundleHeader.read(snapshot.file,cancelled).identity
            if(targetDocumentId!=null) {
                require(existing?.documentId!=targetDocumentId) {"Copy requires a new document UUID"}
                BundleReferenceInfo.inspectStrict(snapshot.file,cancelled)
            }
            val identity=if(copy) BundleIdentity(documentId=targetDocumentId ?: Uuids.random(),
                pdfResource=existing?.pdfResource,externalPdf=existing?.externalPdf ?: false) else existing ?: BundleIdentity()
            BundleCopyPlan.prepare(snapshot,identity,directory,cancelled).use { plan ->
                val candidate=temporaryAsset("identity-",".zip",directory)
                val tail=temporaryAsset("identity-tail-",".zip",directory)
                try {
                    candidate.durable {out->snapshot.file.input().use {copyStream(it,out,cancelled)}}
                    if(existing==null || copy) {
                        tail.durable {out->bundleWriter(out,-1).use {it.putDeflated(BundleIdentity.ENTRY,encodeIdentity(identity))}}
                        val raw=openReadWrite(candidate.assetPath()).use {dest->openRead(tail.assetPath()).use {extra->
                            val dir=ZipTail.read(dest);val addition=ZipTail.read(extra)
                            if(existing!=null || dir==null || addition==null) false else {
                                check(!cancelled())
                                check(ZipTail.splice(dest,dir.offset,dir.entries,extra,addition)>=0) {"Cannot append identity metadata"}
                                dest.sync();true
                            }
                        }}
                        if(!raw) {
                            bundleDirectory(snapshot.file).use {zip->candidate.durable {out->
                                bundleWriter(out,comment=zip.comment).use {dest->
                                    for(entry in zip.entries) {
                                        check(!cancelled())
                                        if(entry.name==BundleIdentity.ENTRY) continue
                                        val replacement=entry.name==BundleCopyPlan.MANIFEST && plan.manifest!=null || entry.name==AnchorMetadata.ENTRY
                                        dest.putOriginal(entry,replacement) { sink ->
                                            when(entry.name) {
                                                AnchorMetadata.ENTRY -> sink.putBytes(requireNotNull(plan.marker))
                                                BundleCopyPlan.MANIFEST -> (plan.manifest?.input() ?: entry.open()).use {copyStream(it,sink,cancelled)}
                                                else -> entry.open().use {copyStream(it,sink,cancelled)}
                                            }
                                        }
                                    }
                                    dest.putDeflated(BundleIdentity.ENTRY,encodeIdentity(identity))
                                }
                            }}
                        }
                    }
                    plan.verify(candidate,cancelled)
                    check(hash(source,cancelled)==snapshot.sourceHash) {"Source changed during migration"}
                    Prepared(candidate,identity,snapshot.sourceHash)
                } catch(error:Throwable) {candidate.remove();throw error}
                finally {tail.remove()}
            }
        }
    fun publish(prepared: Prepared, destination: AssetFile) {
        check(hash(destination) == prepared.sourceHash) { "Source changed before migration commit" }
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
    fun hash(file: AssetFile, cancelled: () -> Boolean = { false }): String = BundleSnapshot.hash(file, cancelled)
    private fun digest(input: Source, cancelled: () -> Boolean): String {
        return BundleEntries.copy(input,blackholeSink(),cancelled)
    }
    private fun copyStream(input: Source, output: Sink, cancelled: () -> Boolean) {
        input.cancellable(cancelled).buffer().readAll(output)
    }
}
