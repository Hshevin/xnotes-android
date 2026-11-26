package com.xnotes.format

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.kio.Crc32
import com.xnotes.kio.KFiles
import okio.buffer
import okio.use

object TemplatePackage {
    const val JSON_ENTRY = "template.json"
    const val MAX_BYTES = 16L shl 20

    fun isPackage(head: ByteArray): Boolean = head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()

    fun isPackage(file: AssetFile): Boolean = runCatching {
        file.input().buffer().use { it.request(2) && isPackage(it.readByteArray(2)) }
    }.getOrDefault(false)

    fun readJson(file: AssetFile): String {
        val size = KFiles.system.metadataOrNull(file.assetPath())?.size ?: 0L
        if (size > MAX_BYTES) throw TemplateFormatException("template package is larger than 16 MiB")
        try {
            return bundleDirectory(file).use { archive ->
                val entry = archive.entry(JSON_ENTRY) ?: throw TemplateFormatException("package has no $JSON_ENTRY")
                val bytes = entry.open().buffer().use { readAtMost(it, TemplateReader.MAX_BYTES) }
                    ?: throw TemplateFormatException("template is larger than 1 MiB")
                bytes.decodeToString()
            }
        } catch (error: Exception) {
            if (isBundleZipError(error)) throw TemplateFormatException("not a readable template package")
            throw error
        }
    }

    fun validSrc(src: String): Boolean = validTemplateImagePath(src)

    fun extract(file: AssetFile, src: String, dest: AssetFile): Boolean {
        if (!validSrc(src)) return false
        return runCatching {
            bundleDirectory(file).use { archive ->
                val entry = archive.entry(src)?.takeUnless { it.name.endsWith('/') } ?: return false
                entry.open().buffer().use { source -> dest.receive(source) }
                true
            }
        }.getOrDefault(false)
    }

    fun write(out: AssetFile, json: String, pictures: List<Pair<String, AssetFile>>) {
        KFiles.system.sink(out.assetPath()).use { sink ->
            bundleWriter(sink, level = -1).use { archive ->
                archive.putDeflated(JSON_ENTRY, json.encodeToByteArray())
                for ((name, file) in pictures) {
                    val crc = Crc32()
                    file.input().buffer().use { source ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            crc.update(buffer, 0, count)
                        }
                    }
                    archive.entry(name, null, requireNotNull(KFiles.system.metadata(file.assetPath()).size), crc.value) { output ->
                        file.input().buffer().use { it.readAll(output) }
                    }
                }
            }
        }
    }
}
