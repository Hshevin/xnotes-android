package com.xnotes.format

import java.io.InputStream
import java.nio.channels.FileChannel
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okio.buffer
import okio.source

internal fun AnchorMetadata.write(zip: ZipOutputStream) { zip.putNextEntry(ZipEntry(AnchorMetadata.ENTRY)); zip.write(bytes()); zip.closeEntry() }
internal fun AnchorMetadata.Companion.read(input: InputStream): AnchorMetadata {
    val source = input.source().buffer()
    require(!source.request(MAX_BYTES+1)) { "Anchor marker too large" }
    return decode(source.readByteArray())
}
fun DocumentCodec.peek(channel: FileChannel): NotePeek? = runCatching { ZipTail.readEntry(channel, "manifest.json") { peekManifest(it) } }.getOrNull()
fun CanvasCodec.peek(channel: FileChannel): CanvasPeek? = runCatching { ZipTail.readEntry(channel, "manifest.json") { peekManifest(it) } }.getOrNull()
