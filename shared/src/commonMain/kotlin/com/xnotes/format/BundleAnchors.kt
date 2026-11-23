package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.kio.Sha256
import okio.*

/** Explicit staging only. Storage/editor integration is a later step; no SAF or source writes here. */
object BundleAnchors {
    const val ENTRY=AnchorMetadata.ENTRY
    private const val MANIFEST="manifest.json"
    data class Prepared(val file:AssetFile,val identity:BundleIdentity,val sourceHash:String,val anchorCount:Int)

    /**
     * Proves only the raw missing-page-ID insertion, including for a retained candidate after restart.
     * Caller owns immutable snapshots and separately binds their versions to a publication request.
     * No source/model write capability is created here, and neither input is changed or removed.
     */
    fun verifyActivation(original:AssetFile,candidate:AssetFile,cancelled:()->Boolean={false}) {
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
        val expectedMarker=Sha256.hex(markerBytes(identity))
        require(after[ENTRY]==expectedMarker) {"Activation marker changed"}
        bundleDirectory(original).use {source->bundleDirectory(candidate).use {target->
            val actual=AnchorManifest.inspectSource({target.open(MANIFEST)},true,cancelled)
            val open={source.open(MANIFEST)}
            val projection=AnchorManifest.inspectActivationSource(open,actual.orderedPageIds,cancelled)
            val digest=HashingSink.sha256(blackholeSink())
            val output=object:Sink by digest {
                override fun write(source:Buffer,byteCount:Long) {check(!cancelled());digest.write(source,byteCount)}
            }
            // Strict UTF-8 decoding/encoding preserves every original byte, including escape and
            // number spellings. Only the validated candidate's direct page IDs are inserted.
            AnchorManifest.write(open,output,projection,cancelled)
            require(after[MANIFEST]==digest.hash.hex()) {"Activation changed original manifest content"}
        }}
        check(!cancelled()) {"Anchor upgrade cancelled"}
    }

    fun prepare(source:AssetFile,directory:AssetFile,expectedAnchored:Boolean=false,cancelled:()->Boolean={false}):Prepared {
        val sourceHash=BundleMigration.hash(source,cancelled)
        val temporary=ArrayList<AssetFile>()
        fun temp(prefix:String,suffix:String)=temporaryAsset(prefix,suffix,directory).also {temporary+=it}
        try {
            val snapshot=temp("anchor-source-",".zip")
            snapshot.durable {out->source.input().use {copy(it,out,cancelled)}}
            check(BundleMigration.hash(snapshot,cancelled)==sourceHash && BundleMigration.hash(source,cancelled)==sourceHash) {"Source changed while taking anchor snapshot"}
            val before=BundleEntries.digests(snapshot,cancelled,boundedManifest=true)
            require(MANIFEST in before) {"Missing anchor manifest"}
            val identity=requireNotNull(readBundleIdentity(snapshot)) {"Document identity required before anchor upgrade"}
            val manifest=temp("anchor-manifest-",".json")
            val candidate=temp("anchor-candidate-",".zip")
            val plan=bundleDirectory(snapshot).use {zip->
                val marker=zip.entry(ENTRY)
                require(!expectedAnchored || marker!=null) {"Known upgraded document lost its anchor metadata"}
                marker?.let {it.open().use {input->readMarker(input,identity)}}
                val open={zip.open(MANIFEST)}
                val edits=AnchorManifest.inspectSource(open,marker!=null,cancelled)
                manifest.durable {out->AnchorManifest.write(open,out,edits,cancelled)}
                candidate.durable {out->
                    bundleWriter(out,comment=zip.comment).use {dest->
                        for(entry in zip.entries) {
                            check(!cancelled())
                            // Rebuild local entries as well as the directory. Never leave an earlier manifest for ZipInputStream.
                            val replacement=entry.name==MANIFEST
                            dest.putOriginal(entry,replacement) { sink ->
                                (if(replacement) manifest.input() else entry.open()).use {copy(it,sink,cancelled)}
                            }
                        }
                        if(marker==null) {
                            dest.putDeflated(ENTRY,markerBytes(identity))
                        }
                    }
                }
                edits
            }
            val after=BundleEntries.digests(candidate,cancelled,boundedManifest=true)
            require(after.keys==before.keys+ENTRY) {"Anchor upgrade changed bundle entries"}
            require(after.filterKeys {it!=MANIFEST && it!=ENTRY}==before.filterKeys {it!=MANIFEST && it!=ENTRY}) {"Anchor upgrade changed original payload"}
            before[ENTRY]?.let {require(after[ENTRY]==it) {"Anchor marker changed"}}
            require(after[MANIFEST]==BundleMigration.hash(manifest,cancelled)) {"Anchor manifest changed during packaging"}
            require(readBundleIdentity(candidate)==identity)
            bundleDirectory(candidate).use {zip->
                zip.open(ENTRY).use {readMarker(it,identity)}
                val verified=AnchorManifest.inspectSource({zip.open(MANIFEST)},true,cancelled)
                require(verified.insertions.isEmpty() && verified.identities==plan.identities) {"Candidate anchor identities changed"}
            }
            check(BundleMigration.hash(source,cancelled)==sourceHash) {"Source changed during anchor upgrade"}
            temporary.remove(candidate)
            return Prepared(candidate,identity,sourceHash,plan.identities.size)
        } finally {temporary.forEach {it.remove()}}
    }
    /** Local atomic replacement only, with the same source conflict protection as identity migration. */
    fun publish(prepared:Prepared,destination:AssetFile) = BundleMigration.publish(BundleMigration.Prepared(prepared.file,prepared.identity,prepared.sourceHash),destination)

    private fun markerBytes(identity:BundleIdentity)=AnchorMetadata(identity.documentId).bytes()
    private fun readMarker(input:Source,identity:BundleIdentity) {
        val bytes=requireNotNull(readAtMost(input.buffer(),AnchorMetadata.MAX_BYTES.toInt())) {"Anchor marker too large"}
        require(AnchorMetadata.decode(bytes).documentId==identity.documentId) {"Anchor metadata belongs to another document"}
    }
    private fun copy(input:Source,out:Sink,cancelled:()->Boolean) {input.cancellable(cancelled).buffer().readAll(out)}
}
