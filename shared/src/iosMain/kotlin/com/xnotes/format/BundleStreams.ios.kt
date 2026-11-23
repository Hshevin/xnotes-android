package com.xnotes.format

import okio.*

actual abstract class PdfStreamAdapters actual constructor()

actual abstract class SnapshotInputAdapters actual constructor()

actual abstract class CanvasStreamAdapters actual constructor()
actual abstract class DocumentStreamAdapters actual constructor()

internal actual fun encodeIdentity(identity: BundleIdentity): ByteArray = buildString {
    val writer = JsonWrite(this)
    writer.beginObject().name("pdfResource")
    if (identity.pdfResource == null) writer.nullValue() else writer.value(identity.pdfResource)
    writer.name("documentId").value(identity.documentId).name("version").value(if (identity.externalPdf) 2 else 1).endObject()
}.encodeToByteArray()

internal actual fun decodeIdentity(data: BufferedSource): BundleIdentity {
    val bytes = requireNotNull(readAtMost(data, 4096)) { "Document identity metadata is too large" }
    val parser = JsonPull(bytes.decodeToString())
    var version = 0
    var documentId: String? = null
    var pdf: String? = null
    parser.beginObject()
    while (parser.hasNext()) when (parser.nextName()) {
        "version" -> version = if (parser.peek() == JsonPull.Token.STRING) parser.nextString().toInt() else parser.nextInt()
        "documentId" -> documentId = parser.nextString()
        "pdfResource" -> pdf = if (parser.peek() == JsonPull.Token.STRING) parser.nextString() else { parser.skipValue(); null }
        else -> parser.skipValue()
    }
    parser.endObject()
    require(version in 1..2) { "Unsupported document identity version" }
    return BundleIdentity(requireNotNull(documentId), pdf, version == 2)
}

internal actual fun jsonChars(source: Source, strict: Boolean): JsonChars {
    val input = source.buffer()
    return object : JsonChars {
        private val bytes = ByteArray(8192 + 3)
        private var text = ""
        private var position = 0
        override fun read(buffer: CharArray): Int {
            if (buffer.isEmpty()) return 0
            if (position == text.length) {
                var size = input.read(bytes, 0, bytes.size - 3)
                if (size < 0) return -1
                var lead = size - 1
                while (lead >= 0 && (bytes[lead].toInt() and 0xc0) == 0x80) lead--
                if (lead >= 0) {
                    val width = when (bytes[lead].toInt() and 255) { in 0xc2..0xdf -> 2; in 0xe0..0xef -> 3; in 0xf0..0xf4 -> 4; else -> 1 }
                    while (size - lead < width && input.request(1) && (input.buffer[0].toInt() and 0xc0) == 0x80) bytes[size++] = input.readByte()
                }
                text = bytes.decodeToString(0, size, throwOnInvalidSequence = strict)
                position = 0
            }
            val count = minOf(buffer.size, text.length - position)
            text.toCharArray(buffer, 0, position, position + count)
            position += count
            return count
        }
    }
}

internal actual fun writeJson(sink: Sink, write: (JsonWrite) -> Unit) {
    val output = sink.buffer()
    val text = StringBuilder(32768)
    fun drain(force: Boolean = false) {
        if (force || (text.length >= 32768 && !text.last().isHighSurrogate())) {
            output.writeUtf8(text.toString()); text.clear()
        }
    }
    write(JsonWrite(object : JsonOutput {
        override fun write(value: String) { text.append(value); drain() }
        override fun write(codeUnit: Int) { text.append(codeUnit.toChar()); drain() }
        override fun write(chars: CharArray, offset: Int, length: Int) { text.appendRange(chars, offset, offset + length); drain() }
    }))
    drain(true); output.emit()
}
