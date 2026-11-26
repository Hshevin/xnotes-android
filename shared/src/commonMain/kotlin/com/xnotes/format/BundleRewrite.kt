package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.KFiles
import okio.*

internal fun bundleFormat(input:Source,cancelled:()->Boolean={false}): String {
    val p=JsonPull(jsonChars(input.cancellable(cancelled)));p.beginObject()
    var format:String?=null
    while(p.hasNext()) {
        check(!cancelled())
        if(p.nextName()=="format") {format=p.nextString();break} else p.skipValue()
    }
    require(format=="xnote" || CanvasCodec.isCanvasFormat(format)) {"Not a supported note bundle"}
    return requireNotNull(format)
}

/** Rewrites metadata without decoding ink. Asset streams go straight from source to destination. */
internal fun rewriteBundle(
    source: AssetFile, output: Sink, identity: BundleIdentity, cancelled: () -> Boolean,
    activate: Boolean = false, portablePdf: AssetFile? = null, removePdf: Boolean = false, validateCopy: Boolean = false,
): Int {
    val header = BundleHeader.read(source, cancelled)
    val old = header.identity
    old?.pdfResource?.let { require(it == identity.pdfResource) { "PDF original changed" } }
    bundleDirectory(source).use { zip ->
        val manifest = requireNotNull(zip.entry("manifest.json")) { "Missing manifest" }
        val open = { manifest.open() }
        val format = open().use { bundleFormat(it, cancelled) }
        if (CanvasCodec.isCanvasFormat(format)) {
            MapJson.checkEntry(format, zip.entry(MapJson.ENTRY) != null)
            if (format == CanvasCodec.FORMAT_WITH_MAP) MapJson.validateForCopy(zip, cancelled)
        }
        val anchored = header.anchors != null
        val copy = anchored && old?.documentId != identity.documentId
        val plan = when {
            copy -> AnchorManifest.inspectCopySource(open, cancelled)
            activate || anchored -> AnchorManifest.inspectSource(open, anchored, cancelled)
            else -> null
        }
        val embedded = zip.entry(BundlePdf.ENTRY)
        require(old?.externalPdf != true || embedded == null)
        if (validateCopy) {
            open().use { source ->
                val reader = JsonPull(jsonChars(source.cancellable(cancelled)), strict = true, tokenLimit = Int.MAX_VALUE)
                reader.beginObject()
                var version = false
                var hasPdf = false
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "version" -> version = reader.nextInt() == 1
                        "has_pdf" -> hasPdf = reader.nextBoolean()
                        "pages" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    require(reader.nextName() != "reference_id" || anchored)
                                    reader.skipValue()
                                }
                                reader.endObject()
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                require(reader.peek() == JsonPull.Token.END_DOCUMENT && version)
                require(hasPdf == (embedded != null || old?.externalPdf == true))
            }
            if (embedded != null && old?.pdfResource != null) require(embedded.open().use { BundleMigration.digest(it, cancelled) } == old.pdfResource)
        }
        bundleWriter(output, comment = zip.comment).use { dest ->
            for (entry in zip.entries) {
                check(!cancelled())
                if (entry.name == BundleIdentity.ENTRY && old != identity) continue
                if (entry.name == BundlePdf.ENTRY && (removePdf || portablePdf != null)) continue
                val editManifest = entry.name == manifest.name && (copy || activate && !anchored)
                val editMarker = entry.name == AnchorMetadata.ENTRY && copy
                dest.putOriginal(entry, editManifest || editMarker) { out ->
                    when {
                        editManifest -> AnchorManifest.write(open, out, requireNotNull(plan), cancelled)
                        editMarker -> out.putBytes(AnchorMetadata(identity.documentId).bytes())
                        else -> entry.open().use { it.cancellable(cancelled).buffer().readAll(out) }
                    }
                }
            }
            if (activate && !anchored) dest.putDeflated(AnchorMetadata.ENTRY, AnchorMetadata(identity.documentId).bytes())
            portablePdf?.let { pdf -> dest.deflated(BundlePdf.ENTRY) { out -> pdf.input().use { it.cancellable(cancelled).buffer().readAll(out) } } }
            if (old != identity) dest.putDeflated(BundleIdentity.ENTRY, encodeIdentity(identity))
        }
        return plan?.identities?.size ?: 0
    }
}
