package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.kio.Sha256
import okio.*

/** All metadata decisions address one owned source snapshot; only the optional manifest is ours. */
class BundleCopyPlan private constructor(
    val identity:BundleIdentity,
    val header:BundleHeader,
    val before:Map<String,String>,
    val manifest:AssetFile?,
    val marker:ByteArray?,
    private val pageIds:Set<String>?,
) : Closeable {
    override fun close() {manifest?.remove()}

    fun verify(candidate:AssetFile,cancelled:()->Boolean={false},pdfDigest:String?=before[BundlePdf.ENTRY]) {
        val actual=BundleHeader.read(candidate,cancelled)
        require(actual.identity==identity) {"Candidate document identity changed"}
        val after=BundleEntries.digests(candidate,cancelled,boundedManifest=marker!=null)
        val expected=before.toMutableMap()
        // Identity is checked semantically above; an unchanged bundle may use different JSON
        // whitespace/key order, whereas a newly written entry uses the current canonical writer.
        expected.remove(BundleIdentity.ENTRY)
        manifest?.let {expected[MANIFEST]=BundleMigration.hash(it,cancelled)}
        marker?.let {expected[AnchorMetadata.ENTRY]=digest(it)}
        if(pdfDigest==null) expected.remove(BundlePdf.ENTRY) else expected[BundlePdf.ENTRY]=pdfDigest
        require(after.filterKeys {it!=BundleIdentity.ENTRY}==expected) {"Bundle copy changed original payload"}
        if(pageIds!=null) {
            require(actual.anchors?.documentId==identity.documentId)
            bundleDirectory(candidate).use {z->
                val checked=AnchorManifest.inspectSource({z.open(MANIFEST)},true,cancelled)
                require(checked.identities==pageIds && checked.insertions.isEmpty()) {"Candidate page identities changed"}
            }
        } else require(actual.anchors==null) {"Candidate acquired unexpected page metadata"}
    }

    companion object {
        const val MANIFEST="manifest.json"
        fun prepare(snapshot:BundleSnapshot,identity:BundleIdentity,directory:AssetFile,cancelled:()->Boolean={false}):BundleCopyPlan {
            val header=BundleHeader.read(snapshot.file,cancelled)
            val before=BundleEntries.digests(snapshot.file,cancelled,boundedManifest=header.anchors!=null)
            require(MANIFEST in before) {"Missing note manifest"}
            require(header.anchors==null || header.identity?.externalPdf!=true || BundlePdf.ENTRY !in before) {"Reference-only PDF identity cannot be overridden by embedded data"}
            val recorded=header.identity?.pdfResource
            val embedded=before[BundlePdf.ENTRY]
            if(recorded!=null) require(identity.pdfResource==recorded) {"Copy cannot replace the recorded PDF original"}
            if(recorded==null && embedded!=null && identity.pdfResource!=null) require(identity.pdfResource==embedded) {"PDF identity does not match the original payload"}
            if(header.anchors!=null && recorded!=null && embedded!=null) require(embedded==recorded) {"Embedded PDF does not match the recorded original"}
            var manifest:AssetFile?=null
            try {
                var ids:Set<String>?=null
                bundleDirectory(snapshot.file).use {z->
                    val open={z.open(MANIFEST)}
                    if(header.anchors!=null) {
                        // Even unchanged identities must validate the complete page contract.
                        val copying=header.identity!!.documentId!=identity.documentId
                        val plan=if(copying) AnchorManifest.inspectCopySource(open,cancelled) else AnchorManifest.inspectSource(open,true,cancelled)
                        ids=plan.identities
                        if(copying) {
                            val replacement=temporaryAsset("copy-manifest-",".json",directory);manifest=replacement
                            replacement.durable {out->AnchorManifest.write(open,out,plan,cancelled)}
                        }
                    } else open().use {input->
                        val format = validateLegacyFormat(input,cancelled)
                        if (CanvasCodec.isCanvasFormat(format)) {
                            MapJson.checkEntry(format, MapJson.ENTRY in before)
                            if (format == CanvasCodec.FORMAT_WITH_MAP) MapJson.validateForCopy(z, cancelled)
                        }
                    }
                }
                return BundleCopyPlan(identity,header,before,manifest,header.anchorBytesFor(identity),ids)
            } catch(error:Throwable) {manifest?.remove();throw error}
        }
        /** A legacy format prefix may be large; it has no anchor token/byte limits. Borrows input. */
        fun validateLegacyFormat(input:Source,cancelled:()->Boolean={false}): String {
            val p=JsonPull(jsonChars(input.cancellable(cancelled)));p.beginObject()
            var format:String?=null
            while(p.hasNext()) {
                check(!cancelled())
                if(p.nextName()=="format") {format=p.nextString();break} else p.skipValue()
            }
            require(format=="xnote" || CanvasCodec.isCanvasFormat(format)) {"Not a supported note bundle"}
            return requireNotNull(format)
        }
        private fun digest(bytes:ByteArray)=Sha256.hex(bytes)
    }
}
