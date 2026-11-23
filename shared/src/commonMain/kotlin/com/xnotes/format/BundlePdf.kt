package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.core.pal.asAssetFile
import com.xnotes.kio.Crc32
import com.xnotes.kio.KFiles
import okio.*

/** Byte-preserving PDF packaging; an independent copy also receives fresh document/page IDs. */
expect abstract class PdfStreamAdapters()

object BundlePdf : PdfStreamAdapters() {
    const val ENTRY = "assets/source.pdf"

    fun write(source:AssetFile,out:Sink,identity:BundleIdentity,pdf:AssetFile?,external:Boolean,cancelled:()->Boolean={false}) {
        BundleSnapshot.take(source,source.assetPath().parent?.asAssetFile(),cancelled).use {snapshot->
            write(snapshot,out,identity,pdf,external,cancelled)
            check(BundleMigration.hash(source,cancelled)==snapshot.sourceHash) {"Source changed during portable export"}
        }
    }

    /** Reuse an already owned stream snapshot rather than copying a large PDF bundle a second time. */
    fun write(snapshot:BundleSnapshot,out:Sink,identity:BundleIdentity,pdf:AssetFile?,external:Boolean,cancelled:()->Boolean={false}) {
        val target=BundleIdentity(identity.documentId,identity.pdfResource,external)
        BundleCopyPlan.prepare(snapshot,target,requireNotNull(snapshot.file.assetPath().parent).asAssetFile(),cancelled).use {plan->
            if(identity.externalPdf) requireNotNull(pdf)
            write(snapshot,plan,out,pdf,external,cancelled)
        }
    }

    private fun write(snapshot:BundleSnapshot,plan:BundleCopyPlan,out:Sink,pdf:AssetFile?,external:Boolean,cancelled:()->Boolean) {
        if(external || plan.header.identity?.externalPdf==true) requireNotNull(pdf)
        pdf?.let {require(BundleMigration.hash(it,cancelled)==plan.identity.pdfResource) {"PDF original changed"}}
        val expected=plan.before.toMutableMap()
        plan.manifest?.let {expected[BundleCopyPlan.MANIFEST]=BundleMigration.hash(it,cancelled)}
        bundleDirectory(snapshot.file).use {zip->bundleWriter(out,comment=zip.comment).use {dest->
            for(entry in zip.entries) {
                check(!cancelled())
                if(entry.name==BundleIdentity.ENTRY || entry.name==ENTRY) continue
                val replacement=entry.name==BundleCopyPlan.MANIFEST && plan.manifest!=null || entry.name==AnchorMetadata.ENTRY
                dest.putOriginal(entry,replacement) { sink ->
                    if(entry.name==AnchorMetadata.ENTRY) sink.putBytes(requireNotNull(plan.marker)) else {
                        val input=if(entry.name==BundleCopyPlan.MANIFEST) plan.manifest?.input() ?: entry.open() else entry.open()
                        require(input.use {BundleEntries.copy(it,sink,cancelled)}==expected[entry.name]) {"Snapshot payload changed during export"}
                    }
                }
            }
            if(!external) {
                if(pdf!=null) {
                    val crc=Crc32()
                    pdf.input().buffer().use {input->
                        val buffer=ByteArray(65536)
                        while(true) {check(!cancelled());val n=input.read(buffer);if(n<0) break;crc.update(buffer,0,n)}
                    }
                    dest.entry(ENTRY,null,requireNotNull(KFiles.system.metadata(pdf.assetPath()).size),crc.value) { sink ->
                        val written=pdf.input().use {BundleEntries.copy(it,sink,cancelled)}
                        require(written==plan.identity.pdfResource) {"PDF changed during export"}
                    }
                } else zip.entry(ENTRY)?.let {entry->
                    val expectedPdf=requireNotNull(expected[ENTRY])
                    plan.identity.pdfResource?.let {require(it==expectedPdf) {"PDF identity does not match embedded original"}}
                    dest.putOriginal(entry) { sink -> require(entry.open().use {BundleEntries.copy(it,sink,cancelled)}==expectedPdf) }
                }
            }
            dest.putDeflated(BundleIdentity.ENTRY,encodeIdentity(plan.identity))
        }}
    }

    fun prepare(source:AssetFile,directory:AssetFile,identity:BundleIdentity,pdf:AssetFile,cancelled:()->Boolean={false}):BundleMigration.Prepared =
        BundleSnapshot.take(source,directory,cancelled).use {snapshot->
            require(BundleHeader.read(snapshot.file,cancelled).identity?.documentId==identity.documentId) {"PDF migration source identity changed"}
            val target=BundleIdentity(identity.documentId,identity.pdfResource,true)
            BundleCopyPlan.prepare(snapshot,target,directory,cancelled).use {plan->
                require(plan.before[ENTRY]==identity.pdfResource) {com.xnotes.storage.StorageText.t(com.xnotes.storage.StorageMsg.PDF_RESOURCE_VERIFY_FAILED)}
                val candidate=temporaryAsset("pdf-reference-",".zip",directory)
                try {
                    candidate.durable {write(snapshot,plan,it,pdf,true,cancelled)}
                    plan.verify(candidate,cancelled,pdfDigest=null)
                    check(BundleMigration.hash(source,cancelled)==snapshot.sourceHash) {"Source changed during PDF migration"}
                    BundleMigration.Prepared(candidate,target,snapshot.sourceHash)
                } catch(error:Throwable) {candidate.remove();throw error}
            }
        }
}
