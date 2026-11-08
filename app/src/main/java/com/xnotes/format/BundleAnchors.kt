package com.xnotes.format

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Explicit staging only. Storage/editor integration is a later step; no SAF or source writes here. */
internal object BundleAnchors {
    const val ENTRY=AnchorMetadata.ENTRY
    private const val MANIFEST="manifest.json"
    data class Prepared(val file:File,val identity:BundleIdentity,val sourceHash:String,val anchorCount:Int)

    /**
     * Proves only the raw missing-page-ID insertion, including for a retained candidate after restart.
     * Caller owns immutable snapshots and separately binds their versions to a publication request.
     * No source/model write capability is created here, and neither input is changed or removed.
     */
    fun verifyActivation(original:File,candidate:File,cancelled:()->Boolean={false}) {
        val before=BundleEntries.digests(original,cancelled,boundedManifest=true)
        require(MANIFEST in before && ENTRY !in before) {"Only an unmarked note can activate page identities"}
        val after=BundleEntries.digests(candidate,cancelled,boundedManifest=true)
        require(after.keys==before.keys+ENTRY) {"Activation changed bundle entries"}
        require(after.filterKeys {it!=MANIFEST && it!=ENTRY}==before.filterKeys {it!=MANIFEST}) {
            "Activation changed original payload"
        }
        // A byte-preserving edit cannot repair an invalid native/PDF container. In particular,
        // absence of a marker alone does not make an undeclared or missing PDF activatable.
        val originalHeader=BundleReferenceInfo.inspectStrict(original,cancelled).header
        val identity=requireNotNull(originalHeader.identity) {"Document identity required before anchor upgrade"}
        require(originalHeader.anchors==null) {"Page identities already activated"}
        val candidateHeader=BundleReferenceInfo.inspectStrict(candidate,cancelled).header
        require(candidateHeader.identity==identity && candidateHeader.anchors?.documentId==identity.documentId) {
            "Activation changed document identity"
        }
        val expectedMarker=MessageDigest.getInstance("SHA-256").digest(markerBytes(identity))
        require(after[ENTRY]==hex(expectedMarker)) {"Activation marker changed"}
        ZipFile(original).use {source->ZipFile(candidate).use {target->
            val actual=AnchorManifest.inspect({target.getInputStream(target.getEntry(MANIFEST))},true,cancelled)
            val open={source.getInputStream(source.getEntry(MANIFEST))}
            val projection=AnchorManifest.inspectActivation(open,actual.orderedPageIds,cancelled)
            val digest=MessageDigest.getInstance("SHA-256")
            val output=object:OutputStream() {
                override fun write(b:Int) {check(!cancelled());digest.update(b.toByte())}
                override fun write(bytes:ByteArray,offset:Int,length:Int) {
                    check(!cancelled());digest.update(bytes,offset,length)
                }
            }
            // Strict UTF-8 decoding/encoding preserves every original byte, including escape and
            // number spellings. Only the validated candidate's direct page IDs are inserted.
            AnchorManifest.write(open,output,projection,cancelled)
            require(after[MANIFEST]==hex(digest.digest())) {"Activation changed original manifest content"}
        }}
        check(!cancelled()) {"Anchor upgrade cancelled"}
    }

    fun prepare(source:File,directory:File,expectedAnchored:Boolean=false,cancelled:()->Boolean={false}):Prepared {
        val sourceHash=BundleMigration.hash(source,cancelled)
        val temporary=ArrayList<File>()
        fun temp(prefix:String,suffix:String)=File.createTempFile(prefix,suffix,directory).also {temporary+=it}
        try {
            val snapshot=temp("anchor-source-",".zip")
            FileOutputStream(snapshot).use {out->source.inputStream().use {copy(it,out,cancelled)};out.fd.sync()}
            check(BundleMigration.hash(snapshot,cancelled)==sourceHash && BundleMigration.hash(source,cancelled)==sourceHash) {"Source changed while taking anchor snapshot"}
            val before=BundleEntries.digests(snapshot,cancelled,boundedManifest=true)
            require(MANIFEST in before) {"Missing anchor manifest"}
            val identity=requireNotNull(BundleIdentity.read(snapshot)) {"Document identity required before anchor upgrade"}
            val manifest=temp("anchor-manifest-",".json")
            val candidate=temp("anchor-candidate-",".zip")
            val plan:AnchorManifest.Plan
            ZipFile(snapshot).use {zip->
                val marker=zip.getEntry(ENTRY)
                require(!expectedAnchored || marker!=null) {"Known upgraded document lost its anchor metadata"}
                marker?.let {zip.getInputStream(it).use {input->readMarker(input,identity)}}
                val open={zip.getInputStream(zip.getEntry(MANIFEST))}
                plan=AnchorManifest.inspect(open,marker!=null,cancelled)
                FileOutputStream(manifest).use {out->AnchorManifest.write(open,out,plan,cancelled);out.fd.sync()}
                FileOutputStream(candidate).use {out->
                    ZipOutputStream(out).use {dest->
                        dest.setLevel(1);zip.comment?.let(dest::setComment)
                        for(entry in zip.entries().asSequence()) {
                            check(!cancelled())
                            // Rebuild local entries as well as the directory. Never leave an earlier manifest for ZipInputStream.
                            val replacement=entry.name==MANIFEST
                            val next=ZipEntry(entry.name).apply {
                                time=entry.time
                                if(!replacement && entry.method==ZipEntry.STORED) {method=ZipEntry.STORED;size=entry.size;crc=entry.crc}
                            }
                            dest.putNextEntry(next)
                            if(replacement) manifest.inputStream().use {copy(it,dest,cancelled)}
                            else zip.getInputStream(entry).use {copy(it,dest,cancelled)}
                            dest.closeEntry()
                        }
                        if(marker==null) {
                            dest.putNextEntry(ZipEntry(ENTRY));dest.write(markerBytes(identity));dest.closeEntry()
                        }
                        dest.finish();dest.flush();out.fd.sync()
                    }
                }
            }
            val after=BundleEntries.digests(candidate,cancelled,boundedManifest=true)
            require(after.keys==before.keys+ENTRY) {"Anchor upgrade changed bundle entries"}
            require(after.filterKeys {it!=MANIFEST && it!=ENTRY}==before.filterKeys {it!=MANIFEST && it!=ENTRY}) {"Anchor upgrade changed original payload"}
            before[ENTRY]?.let {require(after[ENTRY]==it) {"Anchor marker changed"}}
            require(after[MANIFEST]==BundleMigration.hash(manifest,cancelled)) {"Anchor manifest changed during packaging"}
            require(BundleIdentity.read(candidate)==identity)
            ZipFile(candidate).use {zip->
                zip.getInputStream(zip.getEntry(ENTRY)).use {readMarker(it,identity)}
                val verified=AnchorManifest.inspect({zip.getInputStream(zip.getEntry(MANIFEST))},true,cancelled)
                require(verified.insertions.isEmpty() && verified.identities==plan.identities) {"Candidate anchor identities changed"}
            }
            check(BundleMigration.hash(source,cancelled)==sourceHash) {"Source changed during anchor upgrade"}
            temporary.remove(candidate)
            return Prepared(candidate,identity,sourceHash,plan.identities.size)
        } finally {temporary.forEach {it.delete()}}
    }
    /** Local atomic replacement only, with the same source conflict protection as identity migration. */
    fun publish(prepared:Prepared,destination:File) = BundleMigration.publish(BundleMigration.Prepared(prepared.file,prepared.identity,prepared.sourceHash),destination)

    private fun markerBytes(identity:BundleIdentity)=AnchorMetadata(identity.documentId).bytes()
    private fun hex(bytes:ByteArray)=bytes.joinToString("") {"%02x".format(it)}
    private fun readMarker(input:InputStream,identity:BundleIdentity) {
        require(AnchorMetadata.read(input).documentId==identity.documentId) {"Anchor metadata belongs to another document"}
    }
    private fun copy(input:InputStream,out:OutputStream,cancelled:()->Boolean) {
        val buffer=ByteArray(65536)
        while(true) {check(!cancelled()) {"Anchor upgrade cancelled"};val n=input.read(buffer);if(n<0) break;out.write(buffer,0,n)}
    }
}
