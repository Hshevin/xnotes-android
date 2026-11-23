package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.asAssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.KFiles
import com.xnotes.kio.Uuids
import okio.*
import kotlin.test.AfterTest

abstract class BundleTestFiles {
    private val root = KFiles.tempDirectory() / Uuids.random()

    protected fun testDirectory(prefix: String): AssetFile {
        val path = root / (prefix + Uuids.random())
        KFiles.system.createDirectories(path)
        return path.asAssetFile()
    }

    protected fun testFile(prefix: String, suffix: String?): AssetFile {
        KFiles.system.createDirectories(root)
        return temporaryAsset(prefix, suffix, root.asAssetFile())
    }

    @AfterTest fun cleanBundleTestFiles() { KFiles.system.deleteRecursively(root) }
}

internal fun AssetFile.child(name: String) = (assetPath() / name).asAssetFile()
internal fun AssetFile.fileBytes() = KFiles.system.read(assetPath()) { readByteArray() }
internal fun AssetFile.setBytes(bytes: ByteArray) = KFiles.system.write(assetPath()) { write(bytes); Unit }
internal fun AssetFile.setText(text: String) = setBytes(text.encodeToByteArray())
internal fun AssetFile.fileText() = fileBytes().decodeToString()
internal fun AssetFile.fileSink() = KFiles.system.sink(assetPath())
internal fun AssetFile.deleteTree() = KFiles.system.deleteRecursively(assetPath())
internal fun AssetFile.fileNames() = KFiles.system.list(assetPath()).map { it.name }
internal fun ByteArray.asSource() = Buffer().write(this)
internal fun Buffer.bytes() = snapshot().toByteArray()
internal fun AssetFile.appendUtf8(text: String) = KFiles.system.appendingSink(assetPath()).buffer().use { it.writeUtf8(text); Unit }
internal fun oneByteSource(bytes: ByteArray): Source = object : ForwardingSource(bytes.asSource()) {
    override fun read(sink: Buffer, byteCount: Long) = super.read(sink, minOf(1, byteCount))
}
internal fun BundleWriter.addIdentity(identity: BundleIdentity) = putDeflated(BundleIdentity.ENTRY, encodeIdentity(identity))
internal fun BundleWriter.addMarker(marker: AnchorMetadata) = putDeflated(AnchorMetadata.ENTRY, marker.bytes())
