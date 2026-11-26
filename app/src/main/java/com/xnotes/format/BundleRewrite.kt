package com.xnotes.format

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal fun cancellableInput(input: InputStream, cancelled: () -> Boolean): InputStream =
    object : FilterInputStream(input) {
        override fun read(): Int { check(!cancelled()); return super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            check(!cancelled())
            return super.read(b, off, len)
        }
    }

internal fun bundleFormat(input: InputStream, cancelled: () -> Boolean = { false }): String {
    val reader = JsonPull(InputStreamReader(cancellableInput(input, cancelled), Charsets.UTF_8))
    reader.beginObject()
    while (reader.hasNext()) {
        if (reader.nextName() == "format") return reader.nextString().also {
            require(it == "xnote" || CanvasCodec.isCanvasFormat(it)) { "Unsupported document format" }
        }
        reader.skipValue()
    }
    error("Missing document format")
}

/** Rewrites metadata without decoding ink. Asset streams go straight from source to destination. */
internal fun rewriteBundle(
    source: File, output: OutputStream, identity: BundleIdentity, cancelled: () -> Boolean,
    activate: Boolean = false, portablePdf: File? = null, removePdf: Boolean = false, validateCopy: Boolean = false,
): Int {
    val header = BundleHeader.read(source, cancelled)
    val old = header.identity
    old?.pdfResource?.let { require(it == identity.pdfResource) { "PDF original changed" } }
    ZipFile(source).use { zip ->
        val manifest = requireNotNull(zip.getEntry("manifest.json")) { "Missing manifest" }
        val open = { zip.getInputStream(manifest) }
        val format = open().use { bundleFormat(it, cancelled) }
        if (CanvasCodec.isCanvasFormat(format)) {
            MapJson.checkEntry(format, zip.getEntry(MapJson.ENTRY) != null)
            if (format == CanvasCodec.FORMAT_WITH_MAP) MapJson.validateForCopy(zip, cancelled)
        }
        val anchored = header.anchors != null
        val copy = anchored && old?.documentId != identity.documentId
        val plan = when {
            copy -> AnchorManifest.inspectCopy(open, cancelled)
            activate || anchored -> AnchorManifest.inspect(open, anchored, cancelled)
            else -> null
        }
        val embedded = zip.getEntry(BundlePdf.ENTRY)
        require(old?.externalPdf != true || embedded == null)
        if (validateCopy) {
            val reader = JsonPull(InputStreamReader(cancellableInput(open(), cancelled), Charsets.UTF_8), strict = true, tokenLimit = Int.MAX_VALUE)
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
            if (embedded != null && old?.pdfResource != null) require(zip.getInputStream(embedded).use { BundleMigration.digest(it, cancelled) } == old.pdfResource)
        }
        ZipOutputStream(output).use { dest ->
            dest.setLevel(1)
            zip.comment?.let(dest::setComment)
            for (entry in zip.entries().asSequence()) {
                check(!cancelled())
                if (entry.name == BundleIdentity.ENTRY && old != identity) continue
                if (entry.name == BundlePdf.ENTRY && (removePdf || portablePdf != null)) continue
                val editManifest = entry.name == manifest.name && (copy || activate && !anchored)
                val editMarker = entry.name == AnchorMetadata.ENTRY && copy
                dest.putNextEntry(ZipEntry(entry.name).apply {
                    time = entry.time
                    if (!editManifest && !editMarker && entry.method == ZipEntry.STORED) {
                        method = ZipEntry.STORED
                        size = entry.size
                        crc = entry.crc
                    }
                })
                when {
                    editManifest -> AnchorManifest.write(open, dest, requireNotNull(plan), cancelled)
                    editMarker -> dest.write(AnchorMetadata(identity.documentId).bytes())
                    else -> zip.getInputStream(entry).use { cancellableInput(it, cancelled).copyTo(dest) }
                }
                dest.closeEntry()
            }
            if (activate && !anchored) {
                dest.putNextEntry(ZipEntry(AnchorMetadata.ENTRY))
                dest.write(AnchorMetadata(identity.documentId).bytes())
                dest.closeEntry()
            }
            portablePdf?.let { pdf ->
                dest.putNextEntry(ZipEntry(BundlePdf.ENTRY))
                pdf.inputStream().use { cancellableInput(it, cancelled).copyTo(dest) }
                dest.closeEntry()
            }
            if (old != identity) identity.write(dest)
        }
        return plan?.identities?.size ?: 0
    }
}
