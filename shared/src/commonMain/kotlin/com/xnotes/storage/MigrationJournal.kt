package com.xnotes.storage

import com.xnotes.core.pal.AssetFile
import com.xnotes.core.pal.asAssetFile
import com.xnotes.core.pal.assetPath
import com.xnotes.format.BundleMigration
import com.xnotes.format.durable
import com.xnotes.format.putBytes
import com.xnotes.kio.KFiles
import com.xnotes.kio.Sha256
import com.xnotes.kio.Uuids
import com.xnotes.kio.atomicReplace
import okio.*

/** Retained evidence only. A journal never acquires a target or authorizes publication. */
class MigrationJournal(directory: AssetFile) {
    private val root = directory.assetPath()
    private val fs = KFiles.system
    private val marker = root / "pending"

    fun stage(source: AssetFile, replacement: AssetFile, purpose: PublicationPurpose?, expectedCandidateHash: String?,
              expectedOriginalHash: String?, handoffId: String?, checkActive: () -> Unit): String {
        require(handoffId == null || (canonicalUuid(handoffId) && purpose?.ordinarySave == true &&
            expectedOriginalHash?.matches(SHA256) == true && expectedCandidateHash?.matches(SHA256) == true))
        checkActive()
        check(!fs.exists(marker)) { StorageText.t(StorageMsg.MIGRATION_RECOVERY_PENDING) }
        fs.createDirectories(root)
        fun retain(file: AssetFile, name: String): Path {
            val path = root / name
            replace(path, checkActive) { output ->
                val buffer = Buffer()
                fs.source(file.assetPath()).use { input ->
                    while (true) {
                        checkActive()
                        val count = input.read(buffer, 65536)
                        if (count < 0) break
                        output.write(buffer, count)
                    }
                }
                checkActive()
            }
            return path
        }
        fun hash(file: AssetFile) = BundleMigration.hash(file) { checkActive(); false }
        val original = retain(source, "original.zip")
        val candidate = retain(replacement, "candidate.zip")
        val oldHash = hash(original.asAssetFile())
        val newHash = hash(candidate.asAssetFile())
        if (expectedCandidateHash != null && expectedCandidateHash != newHash || expectedOriginalHash != null && expectedOriginalHash != oldHash)
            throw ReferenceContractViolation(StorageMsg.REFERENCE_WRITE_CONTEXT_INVALID)
        check(hash(source) == oldHash && hash(replacement) == newHash) { StorageText.t(StorageMsg.MIGRATION_VERIFY_FAILED) }
        val bytes = when {
            handoffId != null -> "4\n$oldHash\n$newHash\n${requireNotNull(purpose).key}\n$handoffId"
            purpose != null -> "3\n$oldHash\n$newHash\n${purpose.key}"
            else -> "2\n$oldHash\n$newHash"
        }.encodeToByteArray()
        replace(marker, checkActive) { it.putBytes(bytes) }
        return Sha256.hex(bytes)
    }

    private fun replace(target: Path, checkActive: () -> Unit, write: (Sink) -> Unit) {
        val temp = root / (target.name + ".tmp")
        try {
            temp.asAssetFile().durable(write)
            checkActive()
            atomicReplace(temp, target)
        } finally { try { fs.delete(temp) } catch (_: IOException) {} }
    }

    class Marker internal constructor(val bytes: ByteArray, val digest: String, val size: Long) {
        val typed: Boolean get() {
            val prefix = bytes.decodeToString()
            if ('\n' !in prefix && size >= 257 && prefix.all { it in '0'..'9' }) return true
            val version = prefix.substringBefore('\n').trim().trimStart('0')
            return version.isNotEmpty() && version.all { it in '0'..'9' } && (version.length > 1 || version[0] > '2')
        }
    }

    data class Record(val original: String?, val candidate: String?, val digest: String, val purpose: PublicationPurpose?, val handoffId: String? = null)

    companion object {
        private val SHA256 = Regex("[a-f0-9]{64}")
        private fun canonicalUuid(value: String) = Uuids.isValid(value) && value == value.lowercase()

        fun readMarker(source: Source, forKeep: Boolean = false): Marker = source.use { input ->
            val buffer = Buffer()
            while (buffer.size < 257 && input.read(buffer, 257 - buffer.size) != -1L) { }
            val bytes = buffer.readByteArray()
            var size = bytes.size.toLong()
            val digest = Sha256()
            digest.update(bytes, 0, bytes.size)
            if (size > 256) {
                require(forKeep && !Marker(bytes, "", size).typed)
                while (true) {
                    val count = input.read(buffer, 65536)
                    if (count < 0) break
                    val chunk = buffer.readByteArray()
                    digest.update(chunk, 0, chunk.size)
                    size += count
                }
            }
            Marker(bytes, digest.hex(), size)
        }

        fun parse(raw: Marker): Record {
            require(raw.size <= 256)
            val body = raw.bytes.decodeToString()
            if (body == "1") return Record(null, null, raw.digest, null)
            val parts = body.split('\n')
            val purpose = when {
                parts.size == 3 && parts[0] == "2" -> null
                parts.size == 4 && parts[0] == "3" -> PublicationPurpose.entries.single { it.key == parts[3] }
                parts.size == 5 && parts[0] == "4" && canonicalUuid(parts[4]) -> PublicationPurpose.entries.single { it.key == parts[3] && it.ordinarySave }
                else -> throw IllegalArgumentException("Invalid publication record")
            }
            require(parts[1].matches(SHA256) && parts[2].matches(SHA256))
            return Record(parts[1], parts[2], raw.digest, purpose, parts.getOrNull(4))
        }
    }
}
