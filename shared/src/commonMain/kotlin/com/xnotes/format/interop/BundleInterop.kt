package com.xnotes.format.interop

import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.snapshot
import com.xnotes.core.pal.*
import com.xnotes.format.*
import com.xnotes.kio.*
import okio.Buffer
import okio.Path.Companion.toPath
import okio.use

data class BundleSummary(val kind: String, val documentId: String, val pageIds: List<String>, val inkDigest: String,
                         val entryNames: List<String>, val entryContentCrc: Map<String, Long>) {
    fun canonical(): String = buildList {
        add("kind=$kind"); add("documentId=$documentId"); add("pageIds=${pageIds.joinToString(",")}")
        add("inkDigest=$inkDigest"); add("entries=${entryNames.size}")
        for (name in entryNames) add("crc:$name=${entryContentCrc.getValue(name)}")
    }.sorted().joinToString("\n", postfix = "\n")
}

object BundleInterop {
    val VOLATILE_ENTRIES: Set<String> = emptySet()

    private val image = object : ImageCodec {
        override fun probeFile(path: String): ImageSize? = KFiles.system.read(path.toPath()) {
            if (!request(24) || readLong() != 0x89504e470d0a1a0auL.toLong()) return@read null
            if (readInt() != 13 || readUtf8(4) != "IHDR") return@read null
            ImageSize(readInt(), readInt()).takeIf { it.width > 0 && it.height > 0 }
        }
    }
    private val text = object : TextMeasurer {
        override fun measure(text: String, font: FontSpec, wrapWidth: Double, flags: TextFlags) = Rect(0.0,0.0,wrapWidth,(1 + text.count { it == '\n' }) * lineHeight(font))
        override fun lineHeight(font: FontSpec) = font.pointSize * 1.3
        override fun metrics(font: FontSpec) = LineMetrics(font.pointSize,font.pointSize * 0.3)
        override fun advances(text: String, font: FontSpec) = DoubleArray(text.length) { font.pointSize * 0.6 }
    }
    private val notes = DocumentCodec(image,text)
    private val canvases = CanvasCodec(image,text)
    private inline fun <T> withAssets(action: (AssetFile) -> T): T {
        val path = KFiles.tempDirectory() / Uuids.random()
        KFiles.system.createDirectory(path,mustCreate = true)
        try { return action(path.asAssetFile()) } finally { KFiles.system.deleteRecursively(path) }
    }

    fun reencode(name: String, bytes: ByteArray): ByteArray = withAssets { directory ->
        val input = Buffer().write(bytes); val output = Buffer()
        when (name.substringAfterLast('.')) {
            "xnote" -> notes.write(notes.read(input,directory,directory).snapshot(),output)
            "xcanvas" -> canvases.write(canvases.read(input,directory).snapshotForWrite(),output)
            else -> throw IllegalArgumentException("Unrecognized bundle: $name")
        }
        output.readByteArray()
    }

    fun summarize(name: String, bytes: ByteArray): BundleSummary = withAssets { directory ->
        val input = Buffer().write(bytes)
        val (kind,id,pages) = when (name.substringAfterLast('.')) {
            "xnote" -> notes.read(input,directory,directory).let { doc -> Triple("note",doc.identity.documentId,doc.pages.mapNotNull { it.referenceIdentity.id }) }
            "xcanvas" -> Triple("canvas",canvases.read(input,directory).identity.documentId,emptyList())
            else -> throw IllegalArgumentException("Unrecognized bundle: $name")
        }
        val digest = Sha256(); val crc = linkedMapOf<String,Long>()
        bundleReader(Buffer().write(bytes)).use { archive ->
            while (true) {
                val entry = archive.next() ?: break
                val data = entry.data.readByteArray()
                require(crc.put(entry.name,Crc32.of(data)) == null) { "Duplicate ZIP entry: ${entry.name}" }
                if (entry.name == "manifest.json" || entry.name == MapJson.ENTRY) digestSamples(data,digest)
            }
        }
        BundleSummary(kind,id,pages,digest.hex(),crc.keys.toList(),crc)
    }

    private fun digestSamples(bytes: ByteArray, digest: Sha256) {
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        val parser = JsonPull(text,strict = true)
        fun visit() {
            when (parser.peek()) {
                JsonPull.Token.BEGIN_OBJECT -> {
                    parser.beginObject()
                    while (parser.hasNext()) {
                        if (parser.nextName() != "samples") { visit(); continue }
                        require(parser.peek() == JsonPull.Token.BEGIN_ARRAY)
                        val start = parser.position.toInt(); parser.skipValue()
                        val raw = text.encodeToByteArray(start,parser.position.toInt())
                        digest.update(raw,0,raw.size)
                    }
                    parser.endObject()
                }
                JsonPull.Token.BEGIN_ARRAY -> { parser.beginArray(); while (parser.hasNext()) visit(); parser.endArray() }
                else -> parser.skipValue()
            }
        }
        visit(); require(parser.peek() == JsonPull.Token.END_DOCUMENT)
    }
}
