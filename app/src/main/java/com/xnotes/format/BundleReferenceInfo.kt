package com.xnotes.format

import java.io.File
import java.util.zip.ZipFile

/** Caller owns an immutable file. This inspects reference metadata without decoding ink or PDF. */
internal class BundleReferenceInfo private constructor(val header:BundleHeader,val pageIds:Set<String>) {
    companion object {
        fun legacy(header:BundleHeader):BundleReferenceInfo {
            require(header.anchors==null)
            return BundleReferenceInfo(header,emptySet())
        }
        fun inspect(file:File,validatePayload:Boolean,cancelled:()->Boolean={false}):BundleReferenceInfo {
            val header=BundleHeader.read(file,cancelled)
            if(validatePayload || header.anchors!=null) BundleEntries.digests(file,cancelled,boundedManifest=header.anchors!=null)
            if(header.anchors==null) return legacy(header)
            val pages=ZipFile(file).use {zip->
                val entry=requireNotNull(zip.getEntry("manifest.json")) {"Missing reference manifest"}
                val plan=AnchorManifest.inspect({zip.getInputStream(entry)},anchored=true,cancelled=cancelled)
                require(plan.insertions.isEmpty()) {"Reference manifest needs explicit upgrade"}
                plan.identities
            }
            return BundleReferenceInfo(header,pages)
        }
    }
}
