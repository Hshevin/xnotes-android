package com.xnotes.format

import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import okio.buffer
import okio.source
import okio.sink

internal fun AnchorMetadata.write(zip: ZipOutputStream) { zip.putNextEntry(ZipEntry(AnchorMetadata.ENTRY)); zip.write(bytes()); zip.closeEntry() }
internal fun AnchorMetadata.Companion.read(input: InputStream): AnchorMetadata {
    val source = input.source().buffer()
    require(!source.request(MAX_BYTES+1)) { "Anchor marker too large" }
    return decode(source.readByteArray())
}
fun DocumentCodec.peek(channel: FileChannel): NotePeek? = peek(channel.borrow())
fun CanvasCodec.peek(channel: FileChannel): CanvasPeek? = peek(channel.borrow())

internal fun AnchorManifest.inspect(input: () -> InputStream, anchored: Boolean, cancelled: () -> Boolean = { false }) =
    inspectSource({ input().source() }, anchored, cancelled)
internal fun AnchorManifest.inspectActivation(input: () -> InputStream, orderedPageIds: List<String>, cancelled: () -> Boolean = { false }) =
    inspectActivationSource({ input().source() }, orderedPageIds, cancelled)
internal fun AnchorManifest.inspectCopy(input: () -> InputStream, cancelled: () -> Boolean = { false }) = inspectCopySource({ input().source() }, cancelled)
internal fun AnchorManifest.write(input: () -> InputStream, out: OutputStream, plan: AnchorManifest.Plan, cancelled: () -> Boolean = { false }) =
    write({ input().source() }, out.sink(), plan, cancelled)

internal fun BundleEntries.cancellable(input:InputStream,cancelled:()->Boolean):InputStream = object:java.io.FilterInputStream(input) {
        override fun read():Int {check(!cancelled());return super.read()}
        override fun read(b:ByteArray,off:Int,len:Int):Int {check(!cancelled());return super.read(b,off,len)}
        override fun skip(n:Long):Long {check(!cancelled());return super.skip(n)}
    }
internal fun BundleEntries.copy(input: InputStream, out: OutputStream, cancelled: () -> Boolean = { false }) = copy(input.source(), out.sink(), cancelled)

internal fun BundleCopyPlan.Companion.validateLegacyFormat(input: InputStream, cancelled: () -> Boolean = { false }) = validateLegacyFormat(input.source(),cancelled)
