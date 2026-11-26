package com.xnotes.format

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.xnotes.core.model.Document
import com.xnotes.core.infinite.InfiniteDocument
import okio.*

actual abstract class PdfStreamAdapters actual constructor() {
    fun write(source:File,out:OutputStream,identity:BundleIdentity,pdf:File?,external:Boolean,cancelled:()->Boolean={false}) =
        BundlePdf.write(source,out.sink(),identity,pdf,external,cancelled)
}


internal actual fun encodeIdentity(identity: BundleIdentity) = identity.bytes()
internal actual fun decodeIdentity(data: BufferedSource) = BundleIdentity.read(data.inputStream())

internal actual fun bundleReader(source: Source): BundleReader = object : BundleReader {
    val zip = ZipInputStream(source.buffer().inputStream())
    override fun next(): BundleEntry? = zip.nextEntry?.let { entry ->
        val data = object : ForwardingSource(zip.source()) { override fun close() = Unit }.buffer()
        BundleEntry(entry.name, data, entry.method, entry.size) { zip.closeEntry(); EntrySizes(entry.size, entry.compressedSize, entry.crc) }
    }
    override fun close() = zip.close()
}

internal class AndroidBundleWriter(private val zip: ZipOutputStream) : BundleWriter {
    override fun entry(name: String, time: Long?, storedSize: Long?, crc: Long, write: (Sink) -> Unit) {
        zip.putNextEntry(ZipEntry(name).apply {
            time?.let { this.time = it }
            if (storedSize != null) { method = ZipEntry.STORED; size = storedSize; this.crc = crc }
        })
        write(zip.sink()); zip.closeEntry()
    }
    override fun deflated(name: String, write: (Sink) -> Unit) {
        zip.putNextEntry(ZipEntry(name))
        write(zip.sink())
        zip.closeEntry()
    }
    override fun stored(name: String, file: File, checkActive: () -> Unit) {
        zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = file.length(); compressedSize = size; crc = AssetCrc.of(file) })
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                checkActive()
                val size = input.read(bytes)
                if (size < 0) break
                zip.write(bytes, 0, size)
            }
        }
        zip.closeEntry()
    }
    override fun close() = zip.close()
}

internal actual fun bundleWriter(sink: Sink, level: Int, comment: String?): BundleWriter = AndroidBundleWriter(ZipOutputStream(sink.buffer().outputStream()).apply { setLevel(level); comment?.let(::setComment) })
internal actual fun jsonChars(source: Source, strict: Boolean): JsonChars {
    val stream = source.buffer().inputStream()
    val reader = if (strict) java.io.InputStreamReader(stream, Charsets.UTF_8.newDecoder()) else stream.reader()
    return JsonChars(reader::read)
}
internal actual fun writeJson(sink: Sink, write: (JsonWrite) -> Unit) {
    val writer = java.io.BufferedWriter(sink.buffer().outputStream().writer(), 32 * 1024)
    write(JsonWrite(writer)); writer.flush()
}

actual abstract class DocumentStreamAdapters actual constructor() {
    private val codec get() = this as DocumentCodec
    fun write(doc: Document, out: OutputStream, timing: DocumentCodec.WriteTiming? = null, embedPdf: Boolean = true, isCancelled: () -> Boolean = { false }) = codec.write(doc, out.sink(), timing, embedPdf, isCancelled)
    fun writeTail(zos: ZipOutputStream, doc: Document, assets: List<Pair<String, File>>, timing: DocumentCodec.WriteTiming? = null, audio: List<Pair<String, File>> = codec.audioAssets(doc), externalPdf: Boolean = false) = codec.writeTail(AndroidBundleWriter(zos), doc, assets, timing, audio, externalPdf)
    private fun pdfAdapter(store: ((InputStream) -> File)?): ((Source) -> File)? = store?.let { { source -> it(source.buffer().inputStream()) } }
    fun read(input: InputStream, pdfDir: File? = null, imageDir: File? = null, timing: DocumentCodec.ReadTiming? = null, pdfStore: ((InputStream) -> File)? = null) = codec.read(input.source(), pdfDir, imageDir, timing, pdfAdapter(pdfStore))
    fun readObserved(input: InputStream, pdfDir: File, imageDir: File, timing: DocumentCodec.ReadTiming? = null, pdfStore: ((InputStream) -> File)? = null) = codec.readObserved(input.source(), pdfDir, imageDir, timing, pdfAdapter(pdfStore))
    fun readComplete(input: InputStream, pdfDir: File, imageDir: File, timing: DocumentCodec.ReadTiming? = null, pdfStore: ((InputStream) -> File)? = null, preserveInkSamples: Boolean = false) = codec.readComplete(input.source(), pdfDir, imageDir, timing, pdfAdapter(pdfStore), preserveInkSamples)
    fun readSession(input: InputStream, pdfDir: File, imageDir: File) = codec.readComplete(input.source(), pdfDir, imageDir, preserveInkSamples = true).document
    fun readInkCopy(input: InputStream, imageDir: File? = null) = codec.readInkCopy(input.source(), imageDir)
    fun peek(input: InputStream) = codec.peek(input.source())
    fun peekManifest(json: InputStream) = codec.peekManifest(json.source())
}

actual abstract class CanvasStreamAdapters actual constructor() {
    private val codec get() = this as CanvasCodec
    fun write(doc: InfiniteDocument, out: OutputStream, isCancelled: () -> Boolean = { false }) = codec.write(doc, out.sink(), isCancelled)
    fun read(input: InputStream, imageDir: File? = null) = codec.read(input.source(), imageDir)
    fun readObserved(input: InputStream, imageDir: File) = codec.readObserved(input.source(), imageDir)
    fun readComplete(input: InputStream, imageDir: File) = codec.readComplete(input.source(), imageDir)
    fun peek(input: InputStream) = codec.peek(input.source())
    fun peekManifest(json: InputStream) = codec.peekManifest(json.source())
}
